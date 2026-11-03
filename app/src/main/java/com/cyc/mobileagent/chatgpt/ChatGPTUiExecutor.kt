package com.cyc.mobileagent.chatgpt

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
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * ChatGPT Android App 的 UI 执行器。
 *
 * v1 只做：
 * 1. 尽量进入“新对话”
 * 2. 找到输入框
 * 3. 写入 prompt
 * 4. 找到发送按钮并点击
 *
 * 与微信不同，这里优先使用 AccessibilityNodeInfo 的语义节点，
 * 不猜固定屏幕坐标。如果当前 ChatGPT 版本没有暴露足够节点，
 * 任务会安全失败，而不是盲点屏幕。
 */
class ChatGPTUiExecutor(
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
            "ChatGPTExecutor"

        private const val WINDOW_CHECK_DELAY_MILLIS =
            250L

        private const val TASK_START_EVENT_GRACE_MILLIS =
            2200L

        private const val INITIAL_UI_DELAY_MILLIS =
            1500L

        private const val MAX_NEW_CHAT_ATTEMPTS =
            10

        private const val MAX_INPUT_ATTEMPTS =
            12

        private const val MAX_SEND_ATTEMPTS =
            12

        private val NEW_CHAT_KEYWORDS =
            listOf(
                "new chat",
                "start new chat",
                "new conversation",
                "start a new chat",
                "新聊天",
                "新对话",
                "新建聊天",
                "新建对话",
                "开始新对话"
            )

        private val MENU_KEYWORDS =
            listOf(
                "open navigation",
                "open sidebar",
                "navigation menu",
                "sidebar",
                "menu",
                "打开导航",
                "打开侧边栏",
                "导航菜单",
                "侧边栏",
                "菜单"
            )

        private val FRESH_CHAT_KEYWORDS =
            listOf(
                "what can i help with",
                "how can i help",
                "where should we begin",
                "what can i help you with",
                "有什么可以帮",
                "我能帮你做什么",
                "今天我可以帮你做什么",
                "我们从哪里开始"
            )

        private val SEND_KEYWORDS =
            listOf(
                "send message",
                "send prompt",
                "send",
                "submit",
                "发送消息",
                "发送",
                "提交"
            )
    }

    private enum class Stage {
        IDLE,
        FINDING_NEW_CHAT,
        OPENING_MENU,
        FINDING_INPUT,
        TYPING_PROMPT,
        FINDING_SEND,
        SENDING,
        FINISHED
    }

    private data class NodeSnapshot(
        val node: AccessibilityNodeInfo,
        val label: String,
        val viewId: String,
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

    private var menuOpened =
        false

    private var messageStartedEventSent =
        false

    private var inputBounds: Rect? =
        null

    private val verifyVisibilityRunnable =
        Runnable {
            if (
                isActive() &&
                !isChatGPTWindowVisible()
            ) {
                finishWithError(
                    "检测到 ChatGPT 已不在前台，任务已立即停止",
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
                "ChatGPT 问题为空",
                null,
                null
            )
            return
        }

        if (isActive()) {
            sendEvent(
                cleanTaskId,
                ExecutionEventType.TASK_FAILED,
                "已有 ChatGPT 任务正在执行",
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
            Stage.FINDING_NEW_CHAT

        taskStartedAt =
            SystemClock.uptimeMillis()

        menuOpened =
            false

        messageStartedEventSent =
            false

        inputBounds =
            null

        sendEvent(
            taskId,
            ExecutionEventType.TASK_STARTED,
            "ChatGPT 已接收问题，正在准备新对话",
            null,
            1
        )

        Log.d(
            TAG,
            "开始 ChatGPT 任务：taskId=$taskId"
        )

        handler.postDelayed(
            {
                findOrCreateNewChat(
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
            "用户手动取消了当前 ChatGPT 任务"
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

    // ---------------------------------------------------------------------
    // Step 1: 新对话
    // ---------------------------------------------------------------------

    private fun findOrCreateNewChat(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !isChatGPTWindowVisible() &&
            SystemClock.uptimeMillis() -
                taskStartedAt >=
            TASK_START_EVENT_GRACE_MILLIS
        ) {
            finishWithError(
                "ChatGPT 没有保持在前台，任务已停止",
                cancelled =
                    true
            )
            return
        }

        val root =
            getChatGPTRoot()

        if (root == null) {
            retryNewChat(
                attempt =
                    attempt,
                reason =
                    "暂时还没有取得 ChatGPT 页面节点"
            )
            return
        }

        if (
            isLikelyFreshChat(
                root
            )
        ) {
            Log.d(
                TAG,
                "当前已经是 ChatGPT 新对话页面"
            )

            stage =
                Stage.FINDING_INPUT

            handler.postDelayed(
                {
                    findPromptInput(
                        attempt =
                            0
                    )
                },
                250L
            )
            return
        }

        val newChatNode =
            findBestLabeledNode(
                root =
                    root,
                keywords =
                    NEW_CHAT_KEYWORDS,
                preferTop =
                    true,
                preferLeft =
                    false
            )

        if (newChatNode != null) {
            stage =
                Stage.FINDING_NEW_CHAT

            if (
                performClick(
                    newChatNode.node,
                    "ChatGPT 新建对话"
                )
            ) {
                Log.d(
                    TAG,
                    "已点击 ChatGPT 新建对话"
                )

                stage =
                    Stage.FINDING_INPUT

                handler.postDelayed(
                    {
                        findPromptInput(
                            attempt =
                                0
                        )
                    },
                    900L
                )
            }
            return
        }

        if (!menuOpened) {
            val menuNode =
                findBestLabeledNode(
                    root =
                        root,
                    keywords =
                        MENU_KEYWORDS,
                    preferTop =
                        true,
                    preferLeft =
                        true
                )

            if (menuNode != null) {
                stage =
                    Stage.OPENING_MENU

                if (
                    performClick(
                        menuNode.node,
                        "ChatGPT 导航菜单"
                    )
                ) {
                    menuOpened =
                        true

                    handler.postDelayed(
                        {
                            stage =
                                Stage.FINDING_NEW_CHAT

                            findOrCreateNewChat(
                                attempt =
                                    attempt + 1
                            )
                        },
                        650L
                    )
                }
                return
            }
        }

        retryNewChat(
            attempt =
                attempt,
            reason =
                if (menuOpened) {
                    "没有识别到 ChatGPT 的“新对话”入口"
                } else {
                    "没有识别到 ChatGPT 的“新对话”或导航入口"
                }
        )
    }

    private fun retryNewChat(
        attempt: Int,
        reason: String
    ) {
        if (!isActive()) {
            return
        }

        if (
            attempt >=
            MAX_NEW_CHAT_ATTEMPTS - 1
        ) {
            finishWithError(
                "$reason。请确认 ChatGPT 已登录；如果 App 界面已更新，请把当前页面截图发给我适配。"
            )
            return
        }

        handler.postDelayed(
            {
                findOrCreateNewChat(
                    attempt =
                        attempt + 1
                )
            },
            400L
        )
    }

    // ---------------------------------------------------------------------
    // Step 2: 输入 prompt
    // ---------------------------------------------------------------------

    private fun findPromptInput(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        val root =
            getChatGPTRoot()

        if (root == null) {
            retryInput(
                attempt =
                    attempt
            )
            return
        }

        val inputNode =
            findBestEditableNode(
                root
            )

        if (inputNode == null) {
            retryInput(
                attempt =
                    attempt
            )
            return
        }

        inputBounds =
            Rect(
                inputNode.bounds
            )

        stage =
            Stage.TYPING_PROMPT

        typePrompt(
            node =
                inputNode.node,
            attempt =
                0
        )
    }

    private fun retryInput(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            attempt >=
            MAX_INPUT_ATTEMPTS - 1
        ) {
            finishWithError(
                "没有识别到 ChatGPT 输入框。请确认 ChatGPT 已登录并停留在可输入的新对话页面。"
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
            350L
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
            !ensureChatGPTVisible(
                "输入 ChatGPT 问题"
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
                "正在向 ChatGPT 输入问题",
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
                "聚焦 ChatGPT 输入框失败：${e.message}"
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
            Log.d(
                TAG,
                "ChatGPT prompt 已通过 ACTION_SET_TEXT 写入"
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
                550L
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

                    Log.d(
                        TAG,
                        "ChatGPT prompt 已通过 Accessibility InputMethod 写入"
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
                        550L
                    )
                    return
                } catch (e: Exception) {
                    Log.d(
                        TAG,
                        "InputMethod 输入失败：${e.message}"
                    )
                }
            }
        }

        if (
            attempt >=
            MAX_INPUT_ATTEMPTS - 1
        ) {
            finishWithError(
                "找到了 ChatGPT 输入框，但无法写入文字。"
            )
            return
        }

        handler.postDelayed(
            {
                val freshRoot =
                    getChatGPTRoot()

                val freshInput =
                    freshRoot
                        ?.let {
                            findBestEditableNode(
                                it
                            )
                        }

                if (freshInput == null) {
                    retryInput(
                        attempt =
                            attempt + 1
                    )
                } else {
                    inputBounds =
                        Rect(
                            freshInput.bounds
                        )

                    typePrompt(
                        node =
                            freshInput.node,
                        attempt =
                            attempt + 1
                    )
                }
            },
            300L
        )
    }

    // ---------------------------------------------------------------------
    // Step 3: 点击发送
    // ---------------------------------------------------------------------

    private fun findSendButton(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            !ensureChatGPTVisible(
                "寻找 ChatGPT 发送按钮"
            )
        ) {
            return
        }

        val root =
            getChatGPTRoot()

        if (root == null) {
            retrySend(
                attempt =
                    attempt
            )
            return
        }

        val sendNode =
            findBestLabeledNode(
                root =
                    root,
                keywords =
                    SEND_KEYWORDS,
                preferTop =
                    false,
                preferLeft =
                    false,
                preferBottom =
                    true
            )
                ?: findSendNodeNearInput(
                    root
                )

        if (sendNode == null) {
            retrySend(
                attempt =
                    attempt
            )
            return
        }

        stage =
            Stage.SENDING

        if (
            performClick(
                sendNode.node,
                "ChatGPT 发送按钮"
            )
        ) {
            handler.postDelayed(
                {
                    finishSuccessfully()
                },
                350L
            )
        }
    }

    private fun retrySend(
        attempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            attempt >=
            MAX_SEND_ATTEMPTS - 1
        ) {
            finishWithError(
                "问题已经写入 ChatGPT，但没有识别到发送按钮。为避免误点，任务已停止。"
            )
            return
        }

        handler.postDelayed(
            {
                findSendButton(
                    attempt =
                        attempt + 1
                )
            },
            350L
        )
    }

    // ---------------------------------------------------------------------
    // 节点识别
    // ---------------------------------------------------------------------

    private fun isLikelyFreshChat(
        root: AccessibilityNodeInfo
    ): Boolean {
        val editable =
            findBestEditableNode(
                root
            )
                ?: return false

        val labels =
            collectNodeSnapshots(
                root
            )
                .map {
                    it.label
                }

        val hasFreshGreeting =
            labels.any {
                label ->

                FRESH_CHAT_KEYWORDS.any {
                    keyword ->

                    label.contains(
                        keyword,
                        ignoreCase =
                            true
                    )
                }
            }

        if (hasFreshGreeting) {
            inputBounds =
                Rect(
                    editable.bounds
                )
        }

        return hasFreshGreeting
    }

    private fun findBestEditableNode(
        root: AccessibilityNodeInfo
    ): NodeSnapshot? {
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

                val node =
                    snapshot.node

                var score =
                    0

                if (node.isEditable) {
                    score +=
                        100
                }

                if (
                    node.className
                        ?.toString()
                        ?.contains(
                            "EditText",
                            ignoreCase =
                                true
                        ) ==
                    true
                ) {
                    score +=
                        40
                }

                if (
                    snapshot.bounds
                        .centerY() >
                    screenHeight * 0.45f
                ) {
                    score +=
                        35
                }

                if (node.isFocusable) {
                    score +=
                        10
                }

                score
            }
    }

    private fun findBestLabeledNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        preferTop: Boolean,
        preferLeft: Boolean,
        preferBottom: Boolean = false
    ): NodeSnapshot? {
        val screenWidth =
            getScreenWidth()

        val screenHeight =
            getScreenHeight()

        return collectNodeSnapshots(
            root
        )
            .mapNotNull {
                snapshot ->

                if (
                    !snapshot.node.isVisibleToUser ||
                    !snapshot.node.isEnabled
                ) {
                    return@mapNotNull null
                }

                val normalized =
                    (
                        snapshot.label +
                            " " +
                            snapshot.viewId
                    )
                        .trim()
                        .lowercase()

                if (normalized.isBlank()) {
                    return@mapNotNull null
                }

                val matchingKeyword =
                    keywords
                        .firstOrNull {
                            keyword ->

                            normalized.contains(
                                keyword.lowercase()
                            )
                        }
                        ?: return@mapNotNull null

                var score =
                    if (
                        normalized ==
                        matchingKeyword.lowercase()
                    ) {
                        120
                    } else {
                        90
                    }

                if (
                    snapshot.node.isClickable
                ) {
                    score +=
                        25
                }

                if (
                    snapshot.node.isVisibleToUser
                ) {
                    score +=
                        20
                }

                if (
                    preferTop &&
                    snapshot.bounds.centerY() <
                    screenHeight * 0.35f
                ) {
                    score +=
                        20
                }

                if (
                    preferLeft &&
                    snapshot.bounds.centerX() <
                    screenWidth * 0.45f
                ) {
                    score +=
                        20
                }

                if (
                    preferBottom &&
                    snapshot.bounds.centerY() >
                    screenHeight * 0.55f
                ) {
                    score +=
                        25
                }

                Pair(
                    snapshot,
                    score
                )
            }
            .maxByOrNull {
                it.second
            }
            ?.first
    }

    /**
     * 某些 Compose 版本可能不给发送按钮明确文字，
     * 但仍会暴露一个可点击节点。
     *
     * 此处只在“已知输入框附近”寻找节点，不使用固定坐标。
     */
    private fun findSendNodeNearInput(
        root: AccessibilityNodeInfo
    ): NodeSnapshot? {
        val knownInputBounds =
            inputBounds
                ?: return null

        val screenWidth =
            getScreenWidth()

        val verticalTolerance =
            maxOf(
                knownInputBounds.height() * 2,
                220
            )

        return collectNodeSnapshots(
            root
        )
            .filter {
                snapshot ->

                val bounds =
                    snapshot.bounds

                snapshot.node.isVisibleToUser &&
                    snapshot.node.isEnabled &&
                    (
                        snapshot.node.isClickable ||
                            hasClickableAncestor(
                                snapshot.node
                            )
                    ) &&
                    bounds.width() > 0 &&
                    bounds.height() > 0 &&
                    bounds.centerX() >
                    screenWidth * 0.65f &&
                    abs(
                        bounds.centerY() -
                            knownInputBounds.centerY()
                    ) <=
                    verticalTolerance
            }
            .maxByOrNull {
                snapshot ->

                val bounds =
                    snapshot.bounds

                bounds.centerX() * 4 -
                    abs(
                        bounds.centerY() -
                            knownInputBounds.centerY()
                    )
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
            visited < 600
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
                // 保留空 Rect。
            }

            val label =
                buildString {
                    node.text
                        ?.toString()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            append(it)
                            append(' ')
                        }

                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            append(it)
                        }
                }
                    .trim()

            val viewId =
                try {
                    node
                        .viewIdResourceName
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }

            result.add(
                NodeSnapshot(
                    node =
                        node,
                    label =
                        label,
                    viewId =
                        viewId,
                    bounds =
                        bounds
                )
            )

            val childCount =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (
                index in
                0 until childCount
            ) {
                val child =
                    try {
                        node.getChild(
                            index
                        )
                    } catch (_: Exception) {
                        null
                    }

                if (child != null) {
                    queue.addLast(
                        child
                    )
                }
            }
        }

        return result
    }

    // ---------------------------------------------------------------------
    // 点击与安全
    // ---------------------------------------------------------------------

    private fun performClick(
        node: AccessibilityNodeInfo,
        actionName: String
    ): Boolean {
        if (
            !ensureChatGPTVisible(
                actionName
            )
        ) {
            return false
        }

        val clicked =
            clickNodeOrAncestor(
                node
            )

        if (!clicked) {
            finishWithError(
                "$actionName 无法点击。为避免误操作，任务已停止。"
            )
        }

        return clicked
    }

    private fun clickNodeOrAncestor(
        startNode: AccessibilityNodeInfo
    ): Boolean {
        var node: AccessibilityNodeInfo? =
            startNode

        var depth =
            0

        while (
            node != null &&
            depth < 5
        ) {
            if (
                node.isEnabled &&
                node.isClickable
            ) {
                val accepted =
                    try {
                        node.performAction(
                            AccessibilityNodeInfo
                                .ACTION_CLICK
                        )
                    } catch (_: Exception) {
                        false
                    }

                if (accepted) {
                    return true
                }
            }

            node =
                try {
                    node.parent
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
        var node: AccessibilityNodeInfo? =
            startNode

        var depth =
            0

        while (
            node != null &&
            depth < 5
        ) {
            if (
                node.isEnabled &&
                node.isClickable
            ) {
                return true
            }

            node =
                try {
                    node.parent
                } catch (_: Exception) {
                    null
                }

            depth +=
                1
        }

        return false
    }

    private fun ensureChatGPTVisible(
        actionName: String
    ): Boolean {
        if (
            isChatGPTWindowVisible()
        ) {
            return true
        }

        finishWithError(
            "已阻止 $actionName：ChatGPT 不在前台，任务已停止",
            cancelled =
                true
        )

        return false
    }

    private fun isChatGPTWindowVisible(): Boolean {
        val imePackage =
            getCurrentImePackage()

        val activeRootPackage =
            try {
                service
                    .rootInActiveWindow
                    ?.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        if (
            activeRootPackage ==
            ChatGPTContract.CHATGPT_PACKAGE
        ) {
            return true
        }

        if (
            !activeRootPackage.isNullOrBlank() &&
            activeRootPackage != imePackage &&
            activeRootPackage != service.packageName
        ) {
            return false
        }

        val existsInVisibleWindows =
            try {
                service
                    .windows
                    .any {
                        window ->

                        window.root
                            ?.packageName
                            ?.toString() ==
                            ChatGPTContract
                                .CHATGPT_PACKAGE
                    }
            } catch (e: Exception) {
                Log.d(
                    TAG,
                    "检查 ChatGPT 窗口失败：${e.message}"
                )

                false
            }

        if (existsInVisibleWindows) {
            return true
        }

        return lastNonImeWindowPackage ==
            ChatGPTContract.CHATGPT_PACKAGE
    }

    private fun getChatGPTRoot():
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
            ChatGPTContract.CHATGPT_PACKAGE
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
                    root ->

                    root.packageName
                        ?.toString() ==
                        ChatGPTContract
                            .CHATGPT_PACKAGE
                }
        } catch (e: Exception) {
            Log.d(
                TAG,
                "取得 ChatGPT root 失败：${e.message}"
            )

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

    // ---------------------------------------------------------------------
    // 完成/失败
    // ---------------------------------------------------------------------

    private fun finishSuccessfully() {
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
            "已点击 ChatGPT 发送按钮",
            1,
            1
        )

        sendEvent(
            completedTaskId,
            ExecutionEventType.TASK_COMPLETED,
            "ChatGPT 问题已提交",
            1,
            1
        )

        Log.d(
            TAG,
            "ChatGPT 任务完成：taskId=$completedTaskId"
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

        menuOpened =
            false

        messageStartedEventSent =
            false

        inputBounds =
            null
    }

    private fun getScreenWidth(): Int {
        return service
            .resources
            .displayMetrics
            .widthPixels
            .coerceAtLeast(
                1
            )
    }

    private fun getScreenHeight(): Int {
        return service
            .resources
            .displayMetrics
            .heightPixels
            .coerceAtLeast(
                1
            )
    }
}
