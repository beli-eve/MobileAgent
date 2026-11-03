package com.cyc.mobileagent.wechat

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import com.cyc.mobileagent.agent.WechatTask
import com.cyc.mobileagent.service.AgentAccessibilityService
import com.google.gson.Gson

/**
 * 微信模块与 Android 系统之间的入口。
 *
 * MainActivity 不再自己拼微信广播，也不再自己保存微信包名。
 */
class WechatTaskController(
    private val context: Context
) {

    private val gson =
        Gson()

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

    fun openWeChat(): Boolean {
        val launchIntent =
            context
                .packageManager
                .getLaunchIntentForPackage(
                    WechatContract.WECHAT_PACKAGE
                )
                ?: return false

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        context.startActivity(
            launchIntent
        )

        return true
    }

    fun executePlan(
        taskId: String,
        tasks: List<WechatTask>
    ) {
        val tasksJson =
            gson.toJson(
                tasks
            )

        context.sendBroadcast(
            Intent(
                WechatContract.ACTION_EXECUTE_WECHAT_PLAN
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    WechatContract.EXTRA_TASK_ID,
                    taskId
                )

                putExtra(
                    WechatContract.EXTRA_WECHAT_TASKS_JSON,
                    tasksJson
                )
            }
        )
    }

    fun cancelTask(
        taskId: String
    ) {
        context.sendBroadcast(
            Intent(
                WechatContract.ACTION_CANCEL_WECHAT_TASK
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    WechatContract.EXTRA_TASK_ID,
                    taskId
                )
            }
        )
    }

    fun startCalibration(
        contact: String
    ) {
        context.sendBroadcast(
            Intent(
                WechatContract.ACTION_START_WECHAT_CALIBRATION
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    WechatContract.EXTRA_CALIBRATION_CONTACT,
                    contact
                )
            }
        )
    }

    fun cancelCalibration() {
        context.sendBroadcast(
            Intent(
                WechatContract.ACTION_CANCEL_WECHAT_CALIBRATION
            ).apply {
                setPackage(
                    context.packageName
                )
            }
        )
    }
}
