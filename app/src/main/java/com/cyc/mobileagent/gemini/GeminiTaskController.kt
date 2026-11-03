package com.cyc.mobileagent.gemini

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.cyc.mobileagent.service.AgentAccessibilityService

/**
 * Gemini Skill 与 Android 系统之间的轻量入口。
 *
 * 只负责：
 * - 检查 MobileAgent 无障碍服务
 * - 打开官方 Gemini Android App
 * - 向 AgentAccessibilityService 发送内部执行/取消广播
 *
 * 具体 Gemini UI 识别与点击由 GeminiUiExecutor 完成。
 */
class GeminiTaskController(
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
     * 避免 Package Visibility 导致“已经安装却查不到”的误判。
     */
    fun openGemini(): Boolean {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                val sender =
                    context
                        .packageManager
                        .getLaunchIntentSenderForPackage(
                            GeminiContract.GEMINI_PACKAGE
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
                        GeminiContract.GEMINI_PACKAGE
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
                GeminiContract
                    .ACTION_EXECUTE_GEMINI
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    GeminiContract.EXTRA_TASK_ID,
                    taskId
                )

                putExtra(
                    GeminiContract.EXTRA_PROMPT,
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
                GeminiContract
                    .ACTION_CANCEL_GEMINI_TASK
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    GeminiContract.EXTRA_TASK_ID,
                    taskId
                )
            }
        )
    }
}
