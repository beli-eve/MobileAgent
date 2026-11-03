package com.cyc.mobileagent.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.cyc.mobileagent.service.AgentAccessibilityService

/**
 * Runtime 级系统能力检查。
 *
 * 首页只需要知道 MobileAgent 的无障碍服务是否可用，
 * 不应通过某个具体 Skill 的 Controller 间接判断。
 */
class AccessibilityController(
    context: Context
) {

    private val appContext =
        context.applicationContext

    fun isEnabled(): Boolean {
        val manager =
            appContext.getSystemService(
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
                    appContext.packageName &&
                    serviceInfo
                        .resolveInfo
                        .serviceInfo
                        .name ==
                    AgentAccessibilityService::class.java.name
            }
    }
}
