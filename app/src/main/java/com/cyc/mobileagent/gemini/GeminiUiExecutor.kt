package com.cyc.mobileagent.gemini

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import com.cyc.mobileagent.agent.ExecutionEventType
import com.cyc.mobileagent.automation.AdaptiveUiProbe
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Gemini Android App 的 UI 执行器。
 *
 * v1 只做：
 * 1. 尽量进入“新对话”
 * 2. 找到输入框
 * 3. 写入 prompt
 * 4. 找到发送按钮并点击
 *
 * 与微信不同，这里优先使用 AccessibilityNodeInfo 的语义节点，
 * 不猜固定屏幕坐标。如果当前 Gemini 版本没有暴露足够节点，
 * 任务会安全失败，而不是盲点屏幕。
 */
class GeminiUiExecutor(
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
            "GeminiExecutor"

        private const val WINDOW_CHECK_DELAY_MILLIS =
            250L

        private const val TASK_START_EVENT_GRACE_MILLIS =
            2200L

        private const val INITIAL_UI_DELAY_MILLIS =
            1800L

        /*
         * 某些 Android/Gemini 版本会由独立 Gemini 包启动，
         * 但实际可访问窗口可能由 Google App 承载。
         * 对 Google App 包不会直接放行，后面还会检查页面里是否真的包含 Gemini 语义。
         */
        private const val GOOGLE_APP_PACKAGE =
            "com.google.android.googlequicksearchbox"

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
                "start chat",
                "新聊天",
                "新对话",
                "新建聊天",
                "新建对话",
                "开始新对话",
                "发起新对话"
            )

        private val MENU_KEYWORDS =
            listOf(
                "open navigation",
                "open navigation drawer",
                "navigation drawer",
                "navigation menu",
                "open sidebar",
                "sidebar",
                "menu",
                "打开导航",
                "打开导航栏",
                "打开侧边栏",
                "导航菜单",
                "侧边栏",
                "菜单"
            )

        private val FRESH_CHAT_KEYWORDS =
            listOf(
                "ask gemini",
                "how can i help",
                "what can i help",
                "how can gemini help",
                "chat with gemini",
                "问问 gemini",
                "向 gemini 提问",
                "有什么可以帮",
                "我能帮你做什么",
                "今天我可以帮你做什么"
            )

        private val INPUT_KEYWORDS =
            listOf(
                "ask gemini",
                "message gemini",
                "enter a prompt",
                "type a message",
                "write a prompt",
                "prompt",
                "向 gemini 提问",
                "问问 gemini",
                "输入提示",
                "输入问题",
                "输入消息",
                "发消息"
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
                !isGeminiWindowVisible()
            ) {
                finishWithError(
                    "检测到 Gemini 已不在前台，任务已立即停止",
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
                "Gemini 问题为空",
                null,
                null
            )
            return
        }

        if (isActive()) {
            sendEvent(
                cleanTaskId,
                ExecutionEventType.TASK_FAILED,
                "已有 Gemini 任务正在执行",
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
            "Gemini 已接收问题，正在准备新对话",
            null,
            1
        )

        Log.d(
            TAG,
            "开始 Gemini 任务：taskId=$taskId"
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
            "用户手动取消了当前 Gemini 任务"
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
            !isGeminiWindowVisible() &&
            SystemClock.uptimeMillis() -
                taskStartedAt >=
            TASK_START_EVENT_GRACE_MILLIS
        ) {
            finishWithError(
                "Gemini 没有保持在前台，任务已停止",
                cancelled =
                    true
            )
            return
        }

        val root =
            getGeminiRoot()

        if (root == null) {
            retryNewChat(
                attempt =
                    attempt,
                reason =
                    "暂时还没有取得 Gemini 页面节点" +
                        "（当前窗口包名：${getActiveWindowPackage().orEmpty().ifBlank { "未知" }}）"
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
                    "Gemini 新建对话"
                )
            ) {
                Log.d(
                    TAG,
                    "已点击 Gemini 新建对话"
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

        /*
         * Gemini 的 Compose 语义在不同版本上差异很大：
         * 有些版本不会把顶部“新对话”图标暴露成可搜索文字，
         * 但底部输入区已经可以直接使用。
         *
         * 因此：能明确找到“新对话”就先点；
         * 找不到时，只要当前页面能可靠识别到 Gemini 输入区，就直接继续。
         * 这比一直卡在“寻找新对话”更稳，也不会使用固定坐标。
         */
        val directPromptNode =
            findBestPromptNode(
                root
            )

        if (directPromptNode != null) {
            inputBounds =
                Rect(
                    directPromptNode.bounds
                )

            Log.d(
                TAG,
                "当前 Gemini 页面已存在可输入区域，直接准备输入"
            )

            sendEvent(
                taskId,
                ExecutionEventType.STATUS,
                "已识别 Gemini 输入区，准备输入问题",
                null,
                1
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
                        "Gemini 导航菜单"
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
                    "没有识别到 Gemini 的“新对话”入口"
                } else {
                    "没有识别到 Gemini 的“新对话”或导航入口"
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
                "$reason。请确认 Gemini 已登录；如果 App 界面已更新，请把当前页面截图发给我适配。"
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
            getGeminiRoot()

        if (root == null) {
            retryInput(
                attempt =
                    attempt
            )
            return
        }

        val inputNode =
            findBestPromptNode(
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
                "没有识别到 Gemini 输入框。请确认 Gemini 已登录并停留在可输入的新对话页面。"
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
            !ensureGeminiVisible(
                "输入 Gemini 问题"
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
                "正在向 Gemini 输入问题",
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
                "聚焦 Gemini 输入框失败：${e.message}"
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
                "Gemini prompt 已通过 ACTION_SET_TEXT 写入"
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
            /*
             * 对“只有可点击容器、没有 editable 语义”的 Compose 输入框，
             * 点击后 InputConnection 往往要过几十到几百毫秒才建立。
             * 这里不立即判失败，而是短暂等待后再尝试 InputMethod。
             */
            handler.postDelayed(
                {
                    if (!isActive()) {
                        return@postDelayed
                    }

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
                                "Gemini prompt 已通过 Accessibility InputMethod 写入"
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

                            return@postDelayed
                        } catch (e: Exception) {
                            Log.d(
                                TAG,
                                "InputMethod 输入失败：${e.message}"
                            )
                        }
                    }

                    retryPromptTyping(
                        previousAttempt =
                            attempt
                    )
                },
                280L
            )

            return
        }

        if (
            attempt >=
            MAX_INPUT_ATTEMPTS - 1
        ) {
            finishWithError(
                "找到了 Gemini 输入框，但无法写入文字。"
            )
            return
        }

        retryPromptTyping(
            previousAttempt =
                attempt
        )
    }

    private fun retryPromptTyping(
        previousAttempt: Int
    ) {
        if (!isActive()) {
            return
        }

        if (
            previousAttempt >=
            MAX_INPUT_ATTEMPTS - 1
        ) {
            finishWithError(
                "找到了 Gemini 输入区域，但无法写入文字。"
            )
            return
        }

        handler.postDelayed(
            {
                val freshRoot =
                    getGeminiRoot()

                val freshInput =
                    freshRoot
                        ?.let {
                            findBestPromptNode(
                                it
                            )
                        }

                if (freshInput == null) {
                    retryInput(
                        attempt =
                            previousAttempt + 1
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
                            previousAttempt + 1
                    )
                }
            },
            320L
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
            !ensureGeminiVisible(
                "寻找 Gemini 发送按钮"
            )
        ) {
            return
        }

        val root =
            getGeminiRoot()

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
                            viewId =
                                candidate.viewId,
                            bounds =
                                Rect(
                                    candidate.bounds
                                )
                        )
                    }

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
                "Gemini 发送按钮"
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
            /*
             * 当前 Gemini Android 版本的发送按钮是输入卡片右下角的圆形上箭头。
             *
             * AccessibilityInputMethod.performEditorAction() 在这里没有可靠的
             * “是否真的发送”返回值，因此不能把“调用没有抛异常”当成发送成功。
             *
             * 优先使用已经识别出的输入卡片边界，点击右下角发送区域。
             */
            if (
                tryTapSendByComposerBounds()
            ) {
                return
            }

            /*
             * 手势无法派发时才尝试 IME action。
             * 该层只作为最后兜底，不再用于阻止真实发送按钮手势。
             */
            if (
                trySubmitWithImeAction()
            ) {
                finishAfterFallbackSubmit(
                    "已尝试通过输入法提交 Gemini 问题"
                )
                return
            }

            val diagnostic =
                AdaptiveUiProbe
                    .buildCompactReport(
                        root =
                            getGeminiRoot(),
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
                "问题已经写入 Gemini，但自动策略仍未找到可靠发送方式。$diagnostic"
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

    /**
     * 某些 Gemini 版本在文字写入后不会把发送按钮暴露成可点击节点，
     * 但输入框仍保持真实 InputConnection。
     *
     * 这是优先级最高的兜底：直接请求输入法执行 SEND action，
     * 不使用固定屏幕坐标。
     */
    private fun trySubmitWithImeAction(): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return false
        }

        if (
            !ensureGeminiVisible(
                "提交 Gemini 问题"
            )
        ) {
            return false
        }

        val inputConnection =
            try {
                service
                    .inputMethod
                    ?.currentInputConnection
            } catch (_: Exception) {
                null
            }
                ?: return false

        return try {
            /*
             * AccessibilityService.InputMethod.currentInputConnection
             * 暴露的是 AccessibilityInputMethod.InputConnection。
             *
             * 这里的 performEditorAction(...) 返回 Unit，而不是
             * android.view.inputmethod.InputConnection 的 Boolean。
             * 因此只要调用过程没有抛异常，就视为本层提交动作已成功发出。
             */
            inputConnection
                .performEditorAction(
                    EditorInfo
                        .IME_ACTION_SEND
                )

            Log.d(
                TAG,
                "Gemini 已触发 IME_ACTION_SEND"
            )

            true
        } catch (e: Exception) {
            Log.d(
                TAG,
                "Gemini 输入法提交失败：${e.message}"
            )

            false
        }
    }

    /**
     * 最后一层兜底。
     *
     * 只有在：
     * 1. 已经成功识别并写入输入区域；
     * 2. Gemini 仍然在前台；
     * 3. 语义节点和输入法 SEND 都失败；
     *
     * 才允许根据“已识别输入区域”的边界，在输入区最右侧执行一次手势。
     * 这里不是全屏固定坐标，目标点由当前输入区实时计算。
     */
    private fun tryTapSendByComposerBounds(): Boolean {
        if (
            !ensureGeminiVisible(
                "Gemini 发送兜底"
            )
        ) {
            return false
        }

        val bounds =
            inputBounds
                ?: return false

        if (
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return false
        }

        val screenWidth =
            getScreenWidth()

        val screenHeight =
            getScreenHeight()

        if (
            screenWidth <= 0 ||
            screenHeight <= 0
        ) {
            return false
        }

        val composerWidth =
            maxOf(
                bounds.width(),
                1
            )

        val composerHeight =
            maxOf(
                bounds.height(),
                1
            )

        val primaryX =
            (
                bounds.right -
                    composerWidth *
                    0.10f
            ).coerceIn(
                0f,
                screenWidth.toFloat()
            )

        val primaryY =
            (
                bounds.bottom -
                    composerHeight *
                    0.18f
            ).coerceIn(
                0f,
                screenHeight.toFloat()
            )

        /*
         * 第一候选点来自已经识别出的 composer。
         * 后面几个候选点来自当前 Gemini 手机界面中发送按钮的稳定区域。
         *
         * 所有点都仍受 ensureGeminiVisible() 前台安全闸约束；
         * 并且每次点击后都会检查 prompt 是否仍留在输入区，
         * 只有确认还没发出去才继续尝试下一个点。
         */
        val candidates =
            listOf(
                primaryX to primaryY,
                screenWidth * 0.835f to
                    screenHeight * 0.525f,
                screenWidth * 0.875f to
                    screenHeight * 0.525f,
                screenWidth * 0.835f to
                    screenHeight * 0.545f
            )

        stage =
            Stage.SENDING

        return dispatchSendCandidate(
            candidates =
                candidates,
            index =
                0
        )
    }

    private fun dispatchSendCandidate(
        candidates: List<Pair<Float, Float>>,
        index: Int
    ): Boolean {
        if (!isActive()) {
            return false
        }

        if (
            !ensureGeminiVisible(
                "Gemini 发送候选点击"
            )
        ) {
            return false
        }

        if (
            index !in
            candidates.indices
        ) {
            val diagnostic =
                AdaptiveUiProbe
                    .buildCompactReport(
                        root =
                            getGeminiRoot(),
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
                "Gemini 问题已经写入，但发送控件仍未成功触发。$diagnostic"
            )
            return false
        }

        val (
            targetX,
            targetY
        ) =
            candidates[index]

        val path =
            Path().apply {
                moveTo(
                    targetX,
                    targetY
                )
            }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0L,
                            70L
                        )
                )
                .build()

        val accepted =
            try {
                service.dispatchGesture(
                    gesture,
                    object :
                        AccessibilityService
                            .GestureResultCallback() {

                        override fun onCompleted(
                            gestureDescription:
                                GestureDescription?
                        ) {
                            super.onCompleted(
                                gestureDescription
                            )

                            Log.d(
                                TAG,
                                "Gemini 发送候选点击完成：" +
                                    "index=$index, " +
                                    "x=$targetX, y=$targetY"
                            )

                            handler.postDelayed(
                                {
                                    if (!isActive()) {
                                        return@postDelayed
                                    }

                                    if (
                                        !ensureGeminiVisible(
                                            "确认 Gemini 是否已发送"
                                        )
                                    ) {
                                        return@postDelayed
                                    }

                                    if (
                                        isPromptStillInComposer()
                                    ) {
                                        Log.d(
                                            TAG,
                                            "Gemini prompt 仍在输入区，继续尝试下一个发送候选点"
                                        )

                                        dispatchSendCandidate(
                                            candidates =
                                                candidates,
                                            index =
                                                index + 1
                                        )
                                    } else {
                                        finishAfterFallbackSubmit(
                                            "已确认 Gemini prompt 离开输入区，发送完成"
                                        )
                                    }
                                },
                                500L
                            )
                        }

                        override fun onCancelled(
                            gestureDescription:
                                GestureDescription?
                        ) {
                            super.onCancelled(
                                gestureDescription
                            )

                            handler.postDelayed(
                                {
                                    dispatchSendCandidate(
                                        candidates =
                                            candidates,
                                        index =
                                            index + 1
                                    )
                                },
                                120L
                            )
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Log.d(
                    TAG,
                    "Gemini 发送候选手势失败：" +
                        "${e.message}"
                )

                false
            }

        if (!accepted) {
            handler.postDelayed(
                {
                    dispatchSendCandidate(
                        candidates =
                            candidates,
                        index =
                            index + 1
                    )
                },
                120L
            )
        }

        return true
    }

    private fun isPromptStillInComposer(): Boolean {
        val expectedPrompt =
            prompt
                .trim()

        if (expectedPrompt.isBlank()) {
            return false
        }

        val root =
            getGeminiRoot()
                ?: return true

        val knownInputBounds =
            inputBounds

        val normalizedExpected =
            expectedPrompt
                .lowercase()
                .filterNot {
                    it.isWhitespace()
                }

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

                val canContainPrompt =
                    node.isEditable ||
                        node.actionList.any {
                            action ->

                            action.id ==
                                AccessibilityNodeInfo
                                    .ACTION_SET_TEXT
                        }

                if (!canContainPrompt) {
                    return@any false
                }

                if (
                    knownInputBounds != null
                ) {
                    val bounds =
                        snapshot.bounds

                    val horizontallyRelated =
                        bounds.right >=
                            knownInputBounds.left &&
                            bounds.left <=
                            knownInputBounds.right

                    val verticalTolerance =
                        maxOf(
                            knownInputBounds.height(),
                            bounds.height(),
                            80
                        )

                    val verticallyRelated =
                        abs(
                            bounds.centerY() -
                                knownInputBounds.centerY()
                        ) <=
                            verticalTolerance

                    if (
                        !horizontallyRelated ||
                        !verticallyRelated
                    ) {
                        return@any false
                    }
                }

                val normalizedNodeText =
                    snapshot.label
                        .lowercase()
                        .filterNot {
                            it.isWhitespace()
                        }

                normalizedNodeText.isNotBlank() &&
                    (
                        normalizedNodeText ==
                            normalizedExpected ||
                            normalizedNodeText.contains(
                                normalizedExpected
                            )
                    )
            }
    }

    private fun finishAfterFallbackSubmit(
        detail: String
    ) {
        if (!isActive()) {
            return
        }

        stage =
            Stage.SENDING

        Log.d(
            TAG,
            detail
        )

        handler.postDelayed(
            {
                finishSuccessfully()
            },
            450L
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

    private fun findBestPromptNode(
        root: AccessibilityNodeInfo
    ): NodeSnapshot? {
        /*
         * 第一优先级仍然是真正 editable / ACTION_SET_TEXT 节点。
         */
        val editable =
            findBestEditableNode(
                root
            )

        if (editable != null) {
            return editable
        }

        /*
         * Gemini 某些 Compose 版本只把输入区暴露成带语义标签的可点击容器，
         * 不直接标记 editable。此时先找到输入区容器，点击后再走
         * Accessibility InputMethod 的 InputConnection 写入。
         */
        val labeled =
            findBestLabeledNode(
                root =
                    root,
                keywords =
                    INPUT_KEYWORDS,
                preferTop =
                    false,
                preferLeft =
                    false,
                preferBottom =
                    true
            )

        if (labeled != null) {
            return labeled
        }

        /*
         * 最后一层自动推断：
         * 不依赖具体 App 文案，而是综合 editable / focus / click /
         * 位置 / 尺寸推断最像输入区域的节点。
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
                    viewId =
                        candidate.viewId,
                    bounds =
                        Rect(
                            candidate.bounds
                        )
                )
            }
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
            !ensureGeminiVisible(
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

    private fun ensureGeminiVisible(
        actionName: String
    ): Boolean {
        if (
            isGeminiWindowVisible()
        ) {
            return true
        }

        finishWithError(
            "已阻止 $actionName：Gemini 不在前台，任务已停止",
            cancelled =
                true
        )

        return false
    }

    private fun isGeminiWindowVisible(): Boolean {
        val imePackage =
            getCurrentImePackage()

        val activeRoot =
            try {
                service
                    .rootInActiveWindow
            } catch (_: Exception) {
                null
            }

        if (
            activeRoot != null &&
            isGeminiUiRoot(
                activeRoot
            )
        ) {
            return true
        }

        val activeRootPackage =
            try {
                activeRoot
                    ?.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        /*
         * 如果明确切到了另一个普通 App，立即判定离开 Gemini。
         * Google App 需要继续往下检查页面语义，因为部分 Gemini 版本可能由它承载。
         */
        if (
            !activeRootPackage.isNullOrBlank() &&
            activeRootPackage != imePackage &&
            activeRootPackage != service.packageName &&
            activeRootPackage !=
                GeminiContract.GEMINI_PACKAGE &&
            activeRootPackage !=
                GOOGLE_APP_PACKAGE
        ) {
            return false
        }

        val existsInVisibleWindows =
            try {
                service
                    .windows
                    .any {
                        window ->

                        val root =
                            window.root

                        root != null &&
                            isGeminiUiRoot(
                                root
                            )
                    }
            } catch (e: Exception) {
                Log.d(
                    TAG,
                    "检查 Gemini 窗口失败：${e.message}"
                )

                false
            }

        if (existsInVisibleWindows) {
            return true
        }

        /*
         * 独立 Gemini 包可以安全使用最近窗口包名兜底。
         * Google App 不做这个兜底，避免误把普通 Google 搜索页当 Gemini。
         */
        return lastNonImeWindowPackage ==
            GeminiContract.GEMINI_PACKAGE
    }

    private fun getGeminiRoot():
            AccessibilityNodeInfo? {
        val activeRoot =
            try {
                service
                    .rootInActiveWindow
            } catch (_: Exception) {
                null
            }

        if (
            activeRoot != null &&
            isGeminiUiRoot(
                activeRoot
            )
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

                    isGeminiUiRoot(
                        root
                    )
                }
        } catch (e: Exception) {
            Log.d(
                TAG,
                "取得 Gemini root 失败：${e.message}"
            )

            null
        }
    }

    private fun isGeminiUiRoot(
        root: AccessibilityNodeInfo
    ): Boolean {
        val packageName =
            try {
                root.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        if (
            packageName ==
            GeminiContract.GEMINI_PACKAGE
        ) {
            return true
        }

        if (
            packageName !=
            GOOGLE_APP_PACKAGE
        ) {
            return false
        }

        /*
         * Google App 只有在当前可访问树里确实出现 Gemini 语义时才接受。
         * 这样不会因为用户切到普通 Google 搜索页而继续自动操作。
         */
        return collectNodeSnapshots(
            root
        )
            .any {
                snapshot ->

                (
                    snapshot.label +
                        " " +
                        snapshot.viewId
                )
                    .contains(
                        "gemini",
                        ignoreCase =
                            true
                    )
            }
    }

    private fun getActiveWindowPackage(): String? {
        return try {
            service
                .rootInActiveWindow
                ?.packageName
                ?.toString()
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
            "已点击 Gemini 发送按钮",
            1,
            1
        )

        sendEvent(
            completedTaskId,
            ExecutionEventType.TASK_COMPLETED,
            "Gemini 问题已提交",
            1,
            1
        )

        Log.d(
            TAG,
            "Gemini 任务完成：taskId=$completedTaskId"
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
