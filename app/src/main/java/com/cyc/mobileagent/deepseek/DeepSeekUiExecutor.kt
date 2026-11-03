package com.cyc.mobileagent.deepseek

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.cyc.mobileagent.agent.ExecutionEventType
import com.cyc.mobileagent.automation.AdaptiveUiProbe
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * DeepSeek Android App 的文字提问执行器。
 *
 * 控制策略：
 * 1. 语义节点优先：新对话 / 输入框 / 发送按钮。
 * 2. 输入支持 ACTION_SET_TEXT，并保留 Accessibility InputMethod 兜底。
 * 3. 发送节点不可见时只尝试 IME_ACTION_SEND；不再使用坐标点击兜底。
 * 4. 无论节点点击还是 IME 提交，都必须验证 prompt 是否真的离开输入框。
 * 5. 任意动作前都检查 DeepSeek 是否仍在前台。
 *
 * 这里不读取 DeepSeek 回复，也不选择历史会话。
 */
class DeepSeekUiExecutor(
    private val service: AccessibilityService,
    private val sendEvent: (
        taskId: String,
        type: String,
        message: String,
        messageIndex: Int?,
        totalMessages: Int?
    ) -> Unit
) {

    companion object {
        private const val TAG =
            "DeepSeekExecutor"

        private const val WINDOW_CHECK_DELAY_MILLIS =
            250L

        private const val TASK_START_EVENT_GRACE_MILLIS =
            2200L

        private const val INITIAL_UI_DELAY_MILLIS =
            1700L

        private const val MAX_PREPARE_ATTEMPTS =
            6

        private const val MAX_INPUT_ATTEMPTS =
            12

        private const val MAX_SEND_ATTEMPTS =
            8

        private const val MAX_SEND_VERIFY_ATTEMPTS =
            5

        private const val SEND_VERIFY_DELAY_MILLIS =
            350L

        private val NEW_CHAT_KEYWORDS =
            listOf(
                "new chat",
                "new conversation",
                "start new chat",
                "start a new chat",
                "新对话",
                "新聊天",
                "新建对话",
                "新建聊天",
                "开始新对话"
            )

        private val INPUT_KEYWORDS =
            listOf(
                "message deepseek",
                "ask deepseek",
                "send a message",
                "message",
                "输入消息",
                "输入内容",
                "发送消息",
                "向 deepseek 提问"
            )

        private val SEND_KEYWORDS =
            listOf(
                "send",
                "send message",
                "submit",
                "发送",
                "发送消息",
                "提交"
            )
    }

    private enum class Stage {
        IDLE,
        PREPARING_CHAT,
        FINDING_INPUT,
        TYPING_PROMPT,
        FINDING_SEND,
        SENDING,
        FINISHED
    }

    private data class NodeSnapshot(
        val node: AccessibilityNodeInfo,
        val label: String,
        val bounds: Rect
    )

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var stage =
        Stage.IDLE

    private var taskId =
        ""

    private var prompt =
        ""

    private var taskStartedAt =
        0L

    private var lastNonImeWindowPackage: String? =
        null

    private var inputBounds: Rect? =
        null

    private var newChatAttempted =
        false

    private var messageStartedEventSent =
        false

    private var semanticSendFailed =
        false

    private val verifyVisibilityRunnable =
        Runnable {
            if (
                isActive() &&
                !isDeepSeekWindowVisible()
            ) {
                finishWithError(
                    "检测到 DeepSeek 已不在前台，任务已立即停止",
                    cancelled =
                        true
                )
            }
        }

    fun isActive(): Boolean {
        return taskId.isNotBlank() &&
            stage != Stage.IDLE &&
            stage != Stage.FINISHED
    }

    fun start(
        requestedTaskId: String,
        requestedPrompt: String
    ) {
        val cleanTaskId =
            requestedTaskId
                .trim()

        val cleanPrompt =
            requestedPrompt
                .trim()

        if (cleanTaskId.isBlank()) {
            return
        }

        if (cleanPrompt.isBlank()) {
            sendEvent(
                cleanTaskId,
                ExecutionEventType.TASK_FAILED,
                "DeepSeek 问题为空",
                null,
                null
            )
            return
        }

        if (isActive()) {
            sendEvent(
                cleanTaskId,
                ExecutionEventType.TASK_FAILED,
                "已有 DeepSeek 任务正在执行",
                null,
                null
            )
            return
        }

        handler.removeCallbacksAndMessages(
            null
        )

        taskId =
            cleanTaskId

        prompt =
            cleanPrompt

        stage =
            Stage.PREPARING_CHAT

        taskStartedAt =
            SystemClock.uptimeMillis()

        inputBounds =
            null

        newChatAttempted =
            false

        messageStartedEventSent =
            false

        semanticSendFailed =
            false

        sendEvent(
            taskId,
            ExecutionEventType.TASK_STARTED,
            "DeepSeek 已接收问题，正在准备输入",
            null,
            1
        )

        handler.postDelayed(
            {
                prepareConversation(
                    attempt =
                        0
                )
            },
            INITIAL_UI_DELAY_MILLIS
        )
    }

    fun onWindowEvent(
        eventPackage: String?
    ) {
        val imePackage =
            getCurrentImePackage()

        if (
            !eventPackage.isNullOrBlank() &&
            eventPackage != imePackage
        ) {
            lastNonImeWindowPackage =
                eventPackage
        }

        if (!isActive()) {
            return
        }

        if (
            SystemClock.uptimeMillis() -
                taskStartedAt <
            TASK_START_EVENT_GRACE_MILLIS
        ) {
            return
        }

        handler.removeCallbacks(
            verifyVisibilityRunnable
        )

        handler.postDelayed(
            verifyVisibilityRunnable,
            WINDOW_CHECK_DELAY_MILLIS
        )
    }

    fun cancel(
        requestedTaskId: String,
        reason: String =
            "用户手动取消了当前 DeepSeek 任务"
    ) {
        val cleanTaskId =
            requestedTaskId
                .trim()

        if (!isActive()) {
            if (cleanTaskId.isNotBlank()) {
                sendEvent(
                    cleanTaskId,
                    ExecutionEventType.TASK_CANCELLED,
                    reason,
                    null,
                    null
                )
            }
            return
        }

        if (
            cleanTaskId.isNotBlank() &&
            cleanTaskId != taskId
        ) {
            return
        }

        finishWithError(
            reason,
            cancelled =
                true
        )
    }

    fun interrupt(
        reason: String
    ) {
        if (!isActive()) {
            return
        }

        finishWithError(
            reason,
            cancelled =
                true
        )
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(
            null
        )

        clearState()
    }

    // ------------------------------------------------------------------
    // Step 1: 尽量进入新对话
    // ------------------------------------------------------------------

    private fun prepareConversation(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !ensureDeepSeekVisible(
                "准备 DeepSeek 对话"
            )
        ) {
            return
        }

        val root =
            getDeepSeekRoot()

        if (root == null) {
            if (
                attempt >=
                MAX_PREPARE_ATTEMPTS - 1
            ) {
                finishWithError(
                    "没有取得 DeepSeek 页面节点"
                )
                return
            }

            handler.postDelayed(
                {
                    prepareConversation(
                        attempt =
                            attempt + 1
                    )
                },
                350L
            )
            return
        }

        if (!newChatAttempted) {
            newChatAttempted =
                true

            val newChatNode =
                findBestLabeledNode(
                    root =
                        root,
                    keywords =
                        NEW_CHAT_KEYWORDS,
                    preferTop =
                        true,
                    preferRight =
                        true
                )

            if (
                newChatNode != null &&
                performClick(
                    newChatNode.node,
                    "DeepSeek 新对话"
                )
            ) {
                stage =
                    Stage.FINDING_INPUT

                handler.postDelayed(
                    {
                        findPromptInput(
                            attempt =
                                0
                        )
                    },
                    550L
                )
                return
            }
        }

        /*
         * 某些版本的新对话按钮没有语义节点。
         * V1 不为了“必须新对话”盲点屏幕；找不到时使用当前会话。
         */
        stage =
            Stage.FINDING_INPUT

        findPromptInput(
            attempt =
                0
        )
    }

    // ------------------------------------------------------------------
    // Step 2: 输入 prompt
    // ------------------------------------------------------------------

    private fun findPromptInput(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !ensureDeepSeekVisible(
                "寻找 DeepSeek 输入框"
            )
        ) {
            return
        }

        val root =
            getDeepSeekRoot()

        val input =
            root
                ?.let {
                    findBestEditableNode(
                        it
                    )
                }

        if (input == null) {
            if (
                attempt >=
                MAX_INPUT_ATTEMPTS - 1
            ) {
                val diagnostic =
                    AdaptiveUiProbe
                        .buildCompactReport(
                            root =
                                getDeepSeekRoot(),
                            anchor =
                                inputBounds,
                            screenWidth =
                                getScreenWidth(),
                            screenHeight =
                                getScreenHeight(),
                            inputKeywords =
                                INPUT_KEYWORDS,
                            sendKeywords =
                                SEND_KEYWORDS
                        )

                finishWithError(
                    "没有识别到 DeepSeek 输入框。$diagnostic"
                )
                return
            }

            handler.postDelayed(
                {
                    findPromptInput(
                        attempt =
                            attempt + 1
                    )
                },
                300L
            )
            return
        }

        inputBounds =
            Rect(
                input.bounds
            )

        stage =
            Stage.TYPING_PROMPT

        typePrompt(
            node =
                input.node,
            attempt =
                attempt
        )
    }

    private fun typePrompt(
        node: AccessibilityNodeInfo,
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !ensureDeepSeekVisible(
                "输入 DeepSeek 问题"
            )
        ) {
            return
        }

        if (!messageStartedEventSent) {
            messageStartedEventSent =
                true

            sendEvent(
                taskId,
                ExecutionEventType.MESSAGE_STARTED,
                "正在向 DeepSeek 输入问题",
                1,
                1
            )
        }

        try {
            node.performAction(
                AccessibilityNodeInfo
                    .ACTION_FOCUS
            )

            clickNodeOrAncestor(
                node
            )
        } catch (e: Exception) {
            Log.d(
                TAG,
                "聚焦 DeepSeek 输入框失败：${e.message}"
            )
        }

        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    prompt
                )
            }

        val setTextAccepted =
            try {
                node.performAction(
                    AccessibilityNodeInfo
                        .ACTION_SET_TEXT,
                    arguments
                )
            } catch (_: Exception) {
                false
            }

        if (setTextAccepted) {
            stage =
                Stage.FINDING_SEND

            handler.postDelayed(
                {
                    findSendButton(
                        attempt =
                            0
                    )
                },
                500L
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            val inputConnection =
                try {
                    service
                        .inputMethod
                        ?.currentInputConnection
                } catch (_: Exception) {
                    null
                }

            if (inputConnection != null) {
                try {
                    inputConnection
                        .performContextMenuAction(
                            android.R.id.selectAll
                        )

                    inputConnection.commitText(
                        prompt,
                        1,
                        null
                    )

                    stage =
                        Stage.FINDING_SEND

                    handler.postDelayed(
                        {
                            findSendButton(
                                attempt =
                                    0
                            )
                        },
                        500L
                    )
                    return
                } catch (e: Exception) {
                    Log.d(
                        TAG,
                        "DeepSeek InputMethod 输入失败：${e.message}"
                    )
                }
            }
        }

        if (
            attempt >=
            MAX_INPUT_ATTEMPTS - 1
        ) {
            finishWithError(
                "找到了 DeepSeek 输入框，但无法写入文字"
            )
            return
        }

        handler.postDelayed(
            {
                findPromptInput(
                    attempt =
                        attempt + 1
                )
            },
            300L
        )
    }

    // ------------------------------------------------------------------
    // Step 3: 发送 + 结果验证
    // ------------------------------------------------------------------

    private fun findSendButton(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !ensureDeepSeekVisible(
                "寻找 DeepSeek 发送按钮"
            )
        ) {
            return
        }

        if (!semanticSendFailed) {
            val root =
                getDeepSeekRoot()

            val sendNode =
                root
                    ?.let {
                        findSemanticSendNode(
                            it
                        )
                    }

            if (sendNode != null) {
                stage =
                    Stage.SENDING

                if (
                    performClick(
                        sendNode.node,
                        "DeepSeek 发送按钮"
                    )
                ) {
                    handler.postDelayed(
                        {
                            verifySendAfterSemanticClick()
                        },
                        500L
                    )
                    return
                }
            }
        }

        if (
            attempt <
            MAX_SEND_ATTEMPTS - 1
        ) {
            handler.postDelayed(
                {
                    findSendButton(
                        attempt =
                            attempt + 1
                    )
                },
                300L
            )
            return
        }

        tryImeSubmitThenVerify()
    }

    private fun verifySendAfterSemanticClick() {
        verifySendResult(
            successMessage =
                "DeepSeek 问题已通过发送控件提交",
            attempt =
                0,
            onFailure = {
                semanticSendFailed =
                    true

                tryImeSubmitThenVerify()
            }
        )
    }

    private fun tryImeSubmitThenVerify() {
        if (!isActive()) {
            return
        }

        if (
            !ensureDeepSeekVisible(
                "DeepSeek 输入法提交"
            )
        ) {
            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            finishSendUnavailable(
                "没有识别到 DeepSeek 可点击发送控件，且当前 Android 版本不支持本项目使用的 Accessibility InputMethod 提交方式"
            )
            return
        }

        val inputConnection =
            try {
                service
                    .inputMethod
                    ?.currentInputConnection
            } catch (_: Exception) {
                null
            }

        if (inputConnection == null) {
            finishSendUnavailable(
                "没有识别到 DeepSeek 可点击发送控件，也没有取得可用的输入法连接"
            )
            return
        }

        try {
            inputConnection
                .performEditorAction(
                    android.view.inputmethod.EditorInfo
                        .IME_ACTION_SEND
                )
        } catch (e: Exception) {
            Log.d(
                TAG,
                "DeepSeek IME_ACTION_SEND 失败：${e.message}"
            )

            finishSendUnavailable(
                "DeepSeek 输入法提交动作执行异常，已停止任务以避免坐标误点"
            )
            return
        }

        verifySendResult(
            successMessage =
                "DeepSeek 问题已通过输入法提交",
            attempt =
                0,
            onFailure = {
                finishSendUnavailable(
                    "DeepSeek 接受了输入法提交动作，但问题仍停留在输入框中"
                )
            }
        )
    }

    private fun verifySendResult(
        successMessage: String,
        attempt: Int,
        onFailure: () -> Unit
    ) {
        if (!isActive()) {
            return
        }

        handler.postDelayed(
            {
                if (!isActive()) {
                    return@postDelayed
                }

                if (
                    !ensureDeepSeekVisible(
                        "确认 DeepSeek 是否已提交"
                    )
                ) {
                    return@postDelayed
                }

                if (!isPromptStillInComposer()) {
                    finishSuccessfully(
                        successMessage
                    )
                    return@postDelayed
                }

                if (
                    attempt >=
                    MAX_SEND_VERIFY_ATTEMPTS - 1
                ) {
                    onFailure()
                    return@postDelayed
                }

                verifySendResult(
                    successMessage =
                        successMessage,
                    attempt =
                        attempt + 1,
                    onFailure =
                        onFailure
                )
            },
            SEND_VERIFY_DELAY_MILLIS
        )
    }

    private fun finishSendUnavailable(
        prefix: String
    ) {
        val diagnostic =
            AdaptiveUiProbe
                .buildCompactReport(
                    root =
                        getDeepSeekRoot(),
                    anchor =
                        inputBounds,
                    screenWidth =
                        getScreenWidth(),
                    screenHeight =
                        getScreenHeight(),
                    inputKeywords =
                        INPUT_KEYWORDS,
                    sendKeywords =
                        SEND_KEYWORDS
                )

        finishWithError(
            "$prefix。当前版本不会使用屏幕坐标强行点击发送按钮。$diagnostic"
        )
    }

    private fun isPromptStillInComposer(): Boolean {
        val expectedPrompt =
            prompt
                .trim()

        if (expectedPrompt.isBlank()) {
            return false
        }

        val root =
            getDeepSeekRoot()
                ?: return true

        val expected =
            normalize(
                expectedPrompt
            )

        return collectNodeSnapshots(
            root
        )
            .any {
                snapshot ->

                val node =
                    snapshot.node

                if (
                    !node.isVisibleToUser ||
                    !node.isEnabled
                ) {
                    return@any false
                }

                val isInputNode =
                    node.isEditable ||
                        node.actionList.any {
                            action ->

                            action.id ==
                                AccessibilityNodeInfo
                                    .ACTION_SET_TEXT
                        }

                if (!isInputNode) {
                    return@any false
                }

                val nodeText =
                    normalize(
                        node.text
                            ?.toString()
                            .orEmpty()
                    )

                nodeText.isNotBlank() &&
                    (
                        nodeText ==
                            expected ||
                            nodeText.contains(
                                expected
                            )
                    )
            }
    }

    // ------------------------------------------------------------------
    // Node discovery
    // ------------------------------------------------------------------

    private fun findBestEditableNode(
        root: AccessibilityNodeInfo
    ): NodeSnapshot? {
        val screenHeight =
            getScreenHeight()

        val semantic =
            collectNodeSnapshots(
                root
            )
                .filter {
                    snapshot ->

                    val node =
                        snapshot.node

                    node.isVisibleToUser &&
                        node.isEnabled &&
                        (
                            node.isEditable ||
                                node.actionList.any {
                                    action ->

                                    action.id ==
                                        AccessibilityNodeInfo
                                            .ACTION_SET_TEXT
                                }
                        )
                }
                .maxByOrNull {
                    snapshot ->

                    val bounds =
                        snapshot.bounds

                    var score =
                        0

                    if (
                        INPUT_KEYWORDS.any {
                            keyword ->

                            snapshot.label
                                .contains(
                                    keyword,
                                    ignoreCase =
                                        true
                                )
                        }
                    ) {
                        score +=
                            1000
                    }

                    if (
                        bounds.centerY() >
                        screenHeight *
                            0.45f
                    ) {
                        score +=
                            400
                    }

                    score +=
                        bounds.centerY()

                    score +=
                        bounds.width() / 3

                    score
                }

        if (semantic != null) {
            return semantic
        }

        /*
         * DeepSeek 某些版本可能不暴露 editable。
         * 这里不直接进入人工坐标，而是先做结构推断：
         * 找最像输入区域的 focus/click 容器，点击后再让
         * Accessibility InputMethod 建立 InputConnection。
         */
        return AdaptiveUiProbe
            .findInputCandidate(
                root =
                    root,
                screenWidth =
                    getScreenWidth(),
                screenHeight =
                    getScreenHeight(),
                keywords =
                    INPUT_KEYWORDS
            )
            ?.let {
                candidate ->

                NodeSnapshot(
                    node =
                        candidate.node,
                    label =
                        candidate.label,
                    bounds =
                        Rect(
                            candidate.bounds
                        )
                )
            }
    }

    private fun findSemanticSendNode(
        root: AccessibilityNodeInfo
    ): NodeSnapshot? {
        val knownInput =
            inputBounds

        return collectNodeSnapshots(
            root
        )
            .filter {
                snapshot ->

                val node =
                    snapshot.node

                node.isVisibleToUser &&
                    node.isEnabled &&
                    (
                        node.isClickable ||
                            node.actionList.any {
                                action ->

                                action.id ==
                                    AccessibilityNodeInfo
                                        .ACTION_CLICK
                            }
                    )
            }
            .map {
                snapshot ->

                var score =
                    0

                if (
                    SEND_KEYWORDS.any {
                        keyword ->

                        snapshot.label
                            .contains(
                                keyword,
                                ignoreCase =
                                    true
                            )
                    }
                ) {
                    score +=
                        1800
                }

                if (knownInput != null) {
                    val bounds =
                        snapshot.bounds

                    val verticalDistance =
                        abs(
                            bounds.centerY() -
                                knownInput.centerY()
                        )

                    val tolerance =
                        max(
                            knownInput.height() * 2,
                            160
                        )

                    if (
                        verticalDistance <=
                        tolerance
                    ) {
                        score +=
                            500
                    }

                    if (
                        bounds.centerX() >
                        knownInput.centerX()
                    ) {
                        score +=
                            450
                    }

                    if (
                        bounds.left >=
                        knownInput.right - 20
                    ) {
                        score +=
                            350
                    }
                }

                snapshot to
                    score
            }
            .filter {
                it.second >=
                    900
            }
            .maxByOrNull {
                it.second
            }
            ?.first
            ?: AdaptiveUiProbe
                .findSendCandidate(
                    root =
                        root,
                    anchor =
                        inputBounds,
                    screenWidth =
                        getScreenWidth(),
                    screenHeight =
                        getScreenHeight(),
                    keywords =
                        SEND_KEYWORDS
                )
                ?.let {
                    candidate ->

                    NodeSnapshot(
                        node =
                            candidate.node,
                        label =
                            candidate.label,
                        bounds =
                            Rect(
                                candidate.bounds
                            )
                    )
                }
    }

    private fun findBestLabeledNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        preferTop: Boolean,
        preferRight: Boolean
    ): NodeSnapshot? {
        val screenWidth =
            getScreenWidth()

        val screenHeight =
            getScreenHeight()

        return collectNodeSnapshots(
            root
        )
            .filter {
                snapshot ->

                val node =
                    snapshot.node

                node.isVisibleToUser &&
                    node.isEnabled &&
                    keywords.any {
                        keyword ->

                        snapshot.label
                            .contains(
                                keyword,
                                ignoreCase =
                                    true
                            )
                    }
            }
            .maxByOrNull {
                snapshot ->

                var score =
                    1000

                if (
                    snapshot.node.isClickable ||
                    hasClickableAncestor(
                        snapshot.node
                    )
                ) {
                    score +=
                        500
                }

                if (
                    preferTop &&
                    snapshot.bounds.centerY() <
                    screenHeight * 0.35f
                ) {
                    score +=
                        250
                }

                if (
                    preferRight &&
                    snapshot.bounds.centerX() >
                    screenWidth * 0.55f
                ) {
                    score +=
                        250
                }

                score
            }
    }

    private fun collectNodeSnapshots(
        root: AccessibilityNodeInfo
    ): List<NodeSnapshot> {
        val result =
            mutableListOf<NodeSnapshot>()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 900
        ) {
            val node =
                queue.removeFirst()

            visited +=
                1

            val bounds =
                Rect()

            try {
                node.getBoundsInScreen(
                    bounds
                )
            } catch (_: Exception) {
                // 保留空 bounds。
            }

            result.add(
                NodeSnapshot(
                    node =
                        node,
                    label =
                        buildLabel(
                            node
                        ),
                    bounds =
                        bounds
                )
            )

            for (
                index in
                0 until node.childCount
            ) {
                try {
                    node.getChild(
                        index
                    )
                        ?.let {
                            queue.add(
                                it
                            )
                        }
                } catch (_: Exception) {
                    // 节点树可能在遍历中刷新。
                }
            }
        }

        return result
    }

    private fun buildLabel(
        node: AccessibilityNodeInfo
    ): String {
        val parts =
            mutableListOf<String>()

        node.text
            ?.toString()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    it
                )
            }

        node.contentDescription
            ?.toString()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    it
                )
            }

        try {
            node.viewIdResourceName
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    parts.add(
                        it
                    )
                }
        } catch (_: Exception) {
            // 某些节点不允许读取 view id。
        }

        return parts
            .joinToString(
                separator =
                    " | "
            )
    }

    // ------------------------------------------------------------------
    // Click + safety fuse
    // ------------------------------------------------------------------

    private fun performClick(
        node: AccessibilityNodeInfo,
        actionName: String
    ): Boolean {
        if (
            !ensureDeepSeekVisible(
                actionName
            )
        ) {
            return false
        }

        return try {
            clickNodeOrAncestor(
                node
            )
        } catch (e: Exception) {
            Log.d(
                TAG,
                "$actionName 点击失败：${e.message}"
            )

            false
        }
    }

    private fun clickNodeOrAncestor(
        startNode: AccessibilityNodeInfo
    ): Boolean {
        var current:
            AccessibilityNodeInfo? =
            startNode

        var depth =
            0

        while (
            current != null &&
            depth < 7
        ) {
            val canClick =
                current.isClickable ||
                    current.actionList.any {
                        action ->

                        action.id ==
                            AccessibilityNodeInfo
                                .ACTION_CLICK
                    }

            if (
                canClick &&
                current.performAction(
                    AccessibilityNodeInfo
                        .ACTION_CLICK
                )
            ) {
                return true
            }

            current =
                try {
                    current.parent
                } catch (_: Exception) {
                    null
                }

            depth +=
                1
        }

        return false
    }

    private fun hasClickableAncestor(
        startNode: AccessibilityNodeInfo
    ): Boolean {
        var current:
            AccessibilityNodeInfo? =
            startNode

        var depth =
            0

        while (
            current != null &&
            depth < 6
        ) {
            if (
                current.isClickable ||
                current.actionList.any {
                    it.id ==
                        AccessibilityNodeInfo
                            .ACTION_CLICK
                }
            ) {
                return true
            }

            current =
                try {
                    current.parent
                } catch (_: Exception) {
                    null
                }

            depth +=
                1
        }

        return false
    }

    private fun ensureDeepSeekVisible(
        actionName: String
    ): Boolean {
        if (
            isDeepSeekWindowVisible()
        ) {
            return true
        }

        finishWithError(
            "执行“$actionName”前检测到 DeepSeek 已不在前台，任务已停止",
            cancelled =
                true
        )

        return false
    }

    private fun isDeepSeekWindowVisible(): Boolean {
        val rootPackage =
            try {
                service
                    .rootInActiveWindow
                    ?.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        if (
            rootPackage ==
            DeepSeekContract.DEEPSEEK_PACKAGE
        ) {
            return true
        }

        val hasDeepSeekWindow =
            try {
                service.windows.any {
                    window ->

                    window.root
                        ?.packageName
                        ?.toString() ==
                        DeepSeekContract
                            .DEEPSEEK_PACKAGE
                }
            } catch (_: Exception) {
                false
            }

        if (hasDeepSeekWindow) {
            return true
        }

        return lastNonImeWindowPackage ==
            DeepSeekContract
                .DEEPSEEK_PACKAGE
    }

    private fun getDeepSeekRoot():
            AccessibilityNodeInfo? {
        val activeRoot =
            try {
                service
                    .rootInActiveWindow
            } catch (_: Exception) {
                null
            }

        if (
            activeRoot
                ?.packageName
                ?.toString() ==
            DeepSeekContract
                .DEEPSEEK_PACKAGE
        ) {
            return activeRoot
        }

        return try {
            service
                .windows
                .asSequence()
                .mapNotNull {
                    it.root
                }
                .firstOrNull {
                    it.packageName
                        ?.toString() ==
                        DeepSeekContract
                            .DEEPSEEK_PACKAGE
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCurrentImePackage(): String? {
        return try {
            Settings.Secure.getString(
                service.contentResolver,
                Settings.Secure
                    .DEFAULT_INPUT_METHOD
            )
                ?.substringBefore(
                    '/'
                )
                ?.takeIf {
                    it.isNotBlank()
                }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    private fun finishSuccessfully(
        detail: String
    ) {
        if (!isActive()) {
            return
        }

        val completedTaskId =
            taskId

        handler.removeCallbacksAndMessages(
            null
        )

        stage =
            Stage.FINISHED

        sendEvent(
            completedTaskId,
            ExecutionEventType.MESSAGE_EXECUTED,
            detail,
            1,
            1
        )

        sendEvent(
            completedTaskId,
            ExecutionEventType.TASK_COMPLETED,
            "DeepSeek 问题已提交",
            1,
            1
        )

        handler.postDelayed(
            {
                clearState()
            },
            800L
        )
    }

    private fun finishWithError(
        message: String,
        cancelled: Boolean =
            false
    ) {
        if (!isActive()) {
            return
        }

        val failedTaskId =
            taskId

        handler.removeCallbacksAndMessages(
            null
        )

        stage =
            Stage.FINISHED

        sendEvent(
            failedTaskId,
            if (cancelled) {
                ExecutionEventType
                    .TASK_CANCELLED
            } else {
                ExecutionEventType
                    .TASK_FAILED
            },
            message,
            null,
            null
        )

        Log.e(
            TAG,
            message
        )

        handler.postDelayed(
            {
                clearState()
            },
            800L
        )
    }

    private fun clearState() {
        stage =
            Stage.IDLE

        taskId =
            ""

        prompt =
            ""

        taskStartedAt =
            0L

        inputBounds =
            null

        newChatAttempted =
            false

        messageStartedEventSent =
            false

        semanticSendFailed =
            false

        lastNonImeWindowPackage =
            null
    }

    private fun normalize(
        value: String
    ): String {
        return value
            .trim()
            .lowercase()
            .filterNot {
                it.isWhitespace()
            }
    }

    private fun getScreenWidth(): Int {
        return service
            .resources
            .displayMetrics
            .widthPixels
    }

    private fun getScreenHeight(): Int {
        return service
            .resources
            .displayMetrics
            .heightPixels
    }
}
