package com.cyc.mobileagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.cyc.mobileagent.agent.AgentExecutionEvent
import com.cyc.mobileagent.agent.ExecutionEventType
import com.cyc.mobileagent.chatgpt.ChatGPTContract
import com.cyc.mobileagent.chatgpt.ChatGPTUiExecutor
import com.cyc.mobileagent.gemini.GeminiContract
import com.cyc.mobileagent.gemini.GeminiUiExecutor
import com.cyc.mobileagent.deepseek.DeepSeekContract
import com.cyc.mobileagent.deepseek.DeepSeekUiExecutor
import com.cyc.mobileagent.runtime.RuntimeEventContract
import com.cyc.mobileagent.wechat.WechatContract
import com.cyc.mobileagent.wechat.WechatUiExecutor
import com.google.gson.Gson

/**
 * 通用 Accessibility Runtime Host。
 *
 * 这里只负责：
 * - 开启 Accessibility Input Method 能力
 * - 接收内部任务广播
 * - 在不同 Executor 之间做互斥
 * - 转发窗口变化
 * - 发送统一 Runtime 执行事件
 *
 * 具体 App 的 UI 自动化逻辑全部放在各自 Executor。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG =
            "AgentAccessibilityService"
    }

    private val gson =
        Gson()

    private var receiverRegistered =
        false

    private val chatGPTExecutor by lazy {
        ChatGPTUiExecutor(
            service =
                this,
            sendEvent = {
                taskId,
                type,
                message,
                messageIndex,
                totalMessages ->

                sendExecutionEvent(
                    taskId =
                        taskId,
                    type =
                        type,
                    message =
                        message,
                    messageIndex =
                        messageIndex,
                    totalMessages =
                        totalMessages
                )
            }
        )
    }

    private val geminiExecutor by lazy {
        GeminiUiExecutor(
            service =
                this,
            sendEvent = {
                taskId,
                type,
                message,
                messageIndex,
                totalMessages ->

                sendExecutionEvent(
                    taskId =
                        taskId,
                    type =
                        type,
                    message =
                        message,
                    messageIndex =
                        messageIndex,
                    totalMessages =
                        totalMessages
                )
            }
        )
    }

    private val deepSeekExecutor by lazy {
        DeepSeekUiExecutor(
            service =
                this,
            sendEvent = {
                taskId,
                type,
                message,
                messageIndex,
                totalMessages ->

                sendExecutionEvent(
                    taskId =
                        taskId,
                    type =
                        type,
                    message =
                        message,
                    messageIndex =
                        messageIndex,
                    totalMessages =
                        totalMessages
                )
            }
        )
    }

    private val wechatExecutor by lazy {
        WechatUiExecutor(
            service =
                this,
            sendEvent = {
                taskId,
                type,
                message,
                contact,
                messageIndex,
                totalMessages ->

                sendExecutionEvent(
                    taskId =
                        taskId,
                    type =
                        type,
                    message =
                        message,
                    contact =
                        contact,
                    messageIndex =
                        messageIndex,
                    totalMessages =
                        totalMessages
                )
            }
        )
    }

    private val commandReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                when (intent?.action) {
                    WechatContract.ACTION_EXECUTE_WECHAT_PLAN -> {
                        if (
                            chatGPTExecutor
                                .isActive() ||
                            geminiExecutor
                                .isActive() ||
                            deepSeekExecutor
                                .isActive()
                        ) {
                            failWechatStart(
                                intent =
                                    intent,
                                message =
                                    "当前有其他 AI App 任务正在执行"
                            )
                            return
                        }

                        wechatExecutor
                            .handlePlan(
                                intent
                            )
                    }

                    WechatContract.ACTION_CANCEL_WECHAT_TASK -> {
                        wechatExecutor
                            .cancelTask(
                                intent.getStringExtra(
                                    WechatContract
                                        .EXTRA_TASK_ID
                                )
                                    .orEmpty()
                                    .trim()
                            )
                    }

                    WechatContract.ACTION_START_WECHAT_CALIBRATION -> {
                        if (
                            chatGPTExecutor
                                .isActive() ||
                            geminiExecutor
                                .isActive() ||
                            deepSeekExecutor
                                .isActive() ||
                            wechatExecutor
                                .isExecutionActive()
                        ) {
                            Toast.makeText(
                                this@AgentAccessibilityService,
                                "当前有任务正在执行，暂不能开始设备校准",
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }

                        val contact =
                            intent.getStringExtra(
                                WechatContract
                                    .EXTRA_CALIBRATION_CONTACT
                            )
                                .orEmpty()
                                .trim()

                        if (contact.isBlank()) {
                            Toast.makeText(
                                this@AgentAccessibilityService,
                                "校准联系人为空",
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }

                        wechatExecutor
                            .startCalibration(
                                contact
                            )
                    }

                    WechatContract.ACTION_CANCEL_WECHAT_CALIBRATION -> {
                        wechatExecutor
                            .cancelCalibrationByUser()
                    }

                    ChatGPTContract.ACTION_EXECUTE_CHATGPT -> {
                        handleChatGPTTask(
                            intent
                        )
                    }

                    ChatGPTContract.ACTION_CANCEL_CHATGPT_TASK -> {
                        chatGPTExecutor
                            .cancel(
                                requestedTaskId =
                                    intent.getStringExtra(
                                        ChatGPTContract
                                            .EXTRA_TASK_ID
                                    )
                                        .orEmpty()
                                        .trim()
                            )
                    }

                    GeminiContract.ACTION_EXECUTE_GEMINI -> {
                        handleGeminiTask(
                            intent
                        )
                    }

                    GeminiContract.ACTION_CANCEL_GEMINI_TASK -> {
                        geminiExecutor
                            .cancel(
                                requestedTaskId =
                                    intent.getStringExtra(
                                        GeminiContract
                                            .EXTRA_TASK_ID
                                    )
                                        .orEmpty()
                                        .trim()
                            )
                    }

                    DeepSeekContract.ACTION_EXECUTE_DEEPSEEK -> {
                        handleDeepSeekTask(
                            intent
                        )
                    }

                    DeepSeekContract.ACTION_CANCEL_DEEPSEEK_TASK -> {
                        deepSeekExecutor
                            .cancel(
                                requestedTaskId =
                                    intent.getStringExtra(
                                        DeepSeekContract
                                            .EXTRA_TASK_ID
                                    )
                                        .orEmpty()
                                        .trim()
                            )
                    }
                }
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        enableInputMethodEditorFlag()
        registerCommandReceiver()

        Log.d(
            TAG,
            "Accessibility Runtime connected"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        val eventType =
            event?.eventType
                ?: return

        if (
            eventType !=
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType !=
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val eventPackage =
            event.packageName
                ?.toString()

        wechatExecutor
            .onWindowEvent(
                eventPackage
            )

        chatGPTExecutor
            .onWindowEvent(
                eventPackage
            )

        geminiExecutor
            .onWindowEvent(
                eventPackage
            )

        deepSeekExecutor
            .onWindowEvent(
                eventPackage
            )
    }

    private fun enableInputMethodEditorFlag() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val updatedInfo =
            serviceInfo

        updatedInfo.flags =
            updatedInfo.flags or
                AccessibilityServiceInfo
                    .FLAG_INPUT_METHOD_EDITOR

        serviceInfo =
            updatedInfo
    }

    private fun registerCommandReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter =
            IntentFilter().apply {
                addAction(
                    WechatContract
                        .ACTION_EXECUTE_WECHAT_PLAN
                )

                addAction(
                    WechatContract
                        .ACTION_CANCEL_WECHAT_TASK
                )

                addAction(
                    WechatContract
                        .ACTION_START_WECHAT_CALIBRATION
                )

                addAction(
                    WechatContract
                        .ACTION_CANCEL_WECHAT_CALIBRATION
                )

                addAction(
                    ChatGPTContract
                        .ACTION_EXECUTE_CHATGPT
                )

                addAction(
                    ChatGPTContract
                        .ACTION_CANCEL_CHATGPT_TASK
                )

                addAction(
                    GeminiContract
                        .ACTION_EXECUTE_GEMINI
                )

                addAction(
                    GeminiContract
                        .ACTION_CANCEL_GEMINI_TASK
                )

                addAction(
                    DeepSeekContract
                        .ACTION_EXECUTE_DEEPSEEK
                )

                addAction(
                    DeepSeekContract
                        .ACTION_CANCEL_DEEPSEEK_TASK
                )
            }

        ContextCompat.registerReceiver(
            this,
            commandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered =
            true
    }

    private fun handleChatGPTTask(
        intent: Intent
    ) {
        val taskId =
            intent.getStringExtra(
                ChatGPTContract
                    .EXTRA_TASK_ID
            )
                .orEmpty()
                .trim()
                .ifBlank {
                    "chatgpt-${System.currentTimeMillis()}"
                }

        val prompt =
            intent.getStringExtra(
                ChatGPTContract
                    .EXTRA_PROMPT
            )
                .orEmpty()
                .trim()

        if (
            wechatExecutor
                .isBusy() ||
            geminiExecutor
                .isActive() ||
            deepSeekExecutor
                .isActive()
        ) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "当前有其他任务或设备校准正在执行，ChatGPT 任务未启动"
            )
            return
        }

        if (prompt.isBlank()) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "ChatGPT 问题为空"
            )
            return
        }

        chatGPTExecutor
            .start(
                requestedTaskId =
                    taskId,
                requestedPrompt =
                    prompt
            )
    }

    private fun handleGeminiTask(
        intent: Intent
    ) {
        val taskId =
            intent.getStringExtra(
                GeminiContract
                    .EXTRA_TASK_ID
            )
                .orEmpty()
                .trim()
                .ifBlank {
                    "gemini-${System.currentTimeMillis()}"
                }

        val prompt =
            intent.getStringExtra(
                GeminiContract
                    .EXTRA_PROMPT
            )
                .orEmpty()
                .trim()

        if (
            wechatExecutor
                .isBusy() ||
            chatGPTExecutor
                .isActive() ||
            deepSeekExecutor
                .isActive()
        ) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "当前有其他任务或设备校准正在执行，Gemini 任务未启动"
            )
            return
        }

        if (prompt.isBlank()) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "Gemini 问题为空"
            )
            return
        }

        geminiExecutor
            .start(
                requestedTaskId =
                    taskId,
                requestedPrompt =
                    prompt
            )
    }

    private fun handleDeepSeekTask(
        intent: Intent
    ) {
        val taskId =
            intent.getStringExtra(
                DeepSeekContract
                    .EXTRA_TASK_ID
            )
                .orEmpty()
                .trim()
                .ifBlank {
                    "deepseek-${System.currentTimeMillis()}"
                }

        val prompt =
            intent.getStringExtra(
                DeepSeekContract
                    .EXTRA_PROMPT
            )
                .orEmpty()
                .trim()

        if (
            wechatExecutor
                .isBusy() ||
            chatGPTExecutor
                .isActive() ||
            geminiExecutor
                .isActive()
        ) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "当前有其他任务或设备校准正在执行，DeepSeek 任务未启动"
            )
            return
        }

        if (prompt.isBlank()) {
            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_FAILED,
                message =
                    "DeepSeek 问题为空"
            )
            return
        }

        deepSeekExecutor
            .start(
                requestedTaskId =
                    taskId,
                requestedPrompt =
                    prompt
            )
    }

    private fun failWechatStart(
        intent: Intent,
        message: String
    ) {
        val taskId =
            intent.getStringExtra(
                WechatContract
                    .EXTRA_TASK_ID
            )
                .orEmpty()
                .trim()
                .ifBlank {
                    "wechat-${System.currentTimeMillis()}"
                }

        sendExecutionEvent(
            taskId =
                taskId,
            type =
                ExecutionEventType.TASK_FAILED,
            message =
                message
        )
    }

    private fun sendExecutionEvent(
        taskId: String,
        type: String,
        message: String,
        contact: String? = null,
        messageIndex: Int? = null,
        totalMessages: Int? = null
    ) {
        if (taskId.isBlank()) {
            return
        }

        val event =
            AgentExecutionEvent(
                taskId =
                    taskId,
                type =
                    type,
                message =
                    message,
                contact =
                    contact,
                messageIndex =
                    messageIndex,
                totalMessages =
                    totalMessages
            )

        sendBroadcast(
            Intent(
                RuntimeEventContract
                    .ACTION_EXECUTION_EVENT
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    RuntimeEventContract
                        .EXTRA_EXECUTION_EVENT_JSON,
                    gson.toJson(
                        event
                    )
                )
            }
        )
    }

    override fun onInterrupt() {
        chatGPTExecutor
            .interrupt(
                "无障碍服务被系统中断，ChatGPT 任务已停止"
            )

        geminiExecutor
            .interrupt(
                "无障碍服务被系统中断，Gemini 任务已停止"
            )

        deepSeekExecutor
            .interrupt(
                "无障碍服务被系统中断，DeepSeek 任务已停止"
            )

        wechatExecutor
            .interrupt(
                "无障碍服务被系统中断，任务已停止"
            )

        Log.d(
            TAG,
            "Accessibility Runtime interrupted"
        )
    }

    override fun onDestroy() {
        chatGPTExecutor
            .shutdown()

        geminiExecutor
            .shutdown()

        deepSeekExecutor
            .shutdown()

        wechatExecutor
            .shutdown()

        if (receiverRegistered) {
            try {
                unregisterReceiver(
                    commandReceiver
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "注销广播接收器失败",
                    e
                )
            }

            receiverRegistered =
                false
        }

        super.onDestroy()
    }
}
