package com.cyc.mobileagent.chatgpt

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.cyc.mobileagent.service.AgentAccessibilityService

/**
 * ChatGPT Skill 与 Android 系统之间的轻量入口。
 *
 * 这里只负责：
 * - 检查 MobileAgent 无障碍服务
 * - 打开官方 ChatGPT Android App
 * - 向 AgentAccessibilityService 发送内部执行/取消广播
 *
 * 具体 UI 识别和点击由 ChatGPTUiExecutor 完成。
 */
class ChatGPTTaskController(
    private val context: Context
) {

    fun isAccessibilityEnabled(): Boolean {
        val manager =
            context.getSystemService(
                AccessibilityManager::class.java
            )

        return manager
            .getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo
                    .FEEDBACK_ALL_MASK
            )
            .any {
                serviceInfo ->

                serviceInfo
                    .resolveInfo
                    .serviceInfo
                    .packageName ==
                    context.packageName &&
                    serviceInfo
                        .resolveInfo
                        .serviceInfo
                        .name ==
                    AgentAccessibilityService::class.java.name
            }
    }

    /**
     * 打开官方 ChatGPT App。
     *
     * Android 13+ 优先使用 getLaunchIntentSenderForPackage()：
     * 这个 API 不受 package visibility 限制，比自己拼 ACTION_MAIN/CATEGORY_LAUNCHER 更稳。
     *
     * Android 12 及以下退回 getLaunchIntentForPackage()。
     * Manifest 的 <queries> 中同时声明 com.openai.chatgpt，
     * 保证旧系统分支也能正常拿到 launcher Intent。
     */
    fun openChatGPT(): Boolean {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                val sender =
                    context
                        .packageManager
                        .getLaunchIntentSenderForPackage(
                            ChatGPTContract.CHATGPT_PACKAGE
                        )

                sender.sendIntent(
                    context,
                    0,
                    null,
                    null,
                    null
                )

                true
            } catch (_: Exception) {
                false
            }
        } else {
            val launchIntent =
                context
                    .packageManager
                    .getLaunchIntentForPackage(
                        ChatGPTContract.CHATGPT_PACKAGE
                    )
                    ?: return false

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            return try {
                context.startActivity(
                    launchIntent
                )

                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun execute(
        taskId: String,
        prompt: String
    ) {
        context.sendBroadcast(
            Intent(
                ChatGPTContract
                    .ACTION_EXECUTE_CHATGPT
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    ChatGPTContract.EXTRA_TASK_ID,
                    taskId
                )

                putExtra(
                    ChatGPTContract.EXTRA_PROMPT,
                    prompt
                )
            }
        )
    }

    fun cancelTask(
        taskId: String
    ) {
        context.sendBroadcast(
            Intent(
                ChatGPTContract
                    .ACTION_CANCEL_CHATGPT_TASK
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    ChatGPTContract.EXTRA_TASK_ID,
                    taskId
                )
            }
        )
    }
}
