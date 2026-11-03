package com.cyc.mobileagent.deepseek

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.cyc.mobileagent.service.AgentAccessibilityService

/**
 * DeepSeek Skill 与 Android 系统之间的轻量入口。
 *
 * 只负责：
 * - 检查 MobileAgent 无障碍服务
 * - 打开 DeepSeek Android App
 * - 向 AgentAccessibilityService 发送内部执行 / 取消广播
 */
class DeepSeekTaskController(
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
     * Android 13+ 使用 getLaunchIntentSenderForPackage()，
     * 避免 package visibility 导致“已安装但无法启动”的问题。
     */
    fun openDeepSeek(): Boolean {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                val sender =
                    context
                        .packageManager
                        .getLaunchIntentSenderForPackage(
                            DeepSeekContract
                                .DEEPSEEK_PACKAGE
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
                        DeepSeekContract
                            .DEEPSEEK_PACKAGE
                    )
                    ?: return false

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            try {
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
                DeepSeekContract
                    .ACTION_EXECUTE_DEEPSEEK
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    DeepSeekContract
                        .EXTRA_TASK_ID,
                    taskId
                )

                putExtra(
                    DeepSeekContract
                        .EXTRA_PROMPT,
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
                DeepSeekContract
                    .ACTION_CANCEL_DEEPSEEK_TASK
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    DeepSeekContract
                        .EXTRA_TASK_ID,
                    taskId
                )
            }
        )
    }
}
