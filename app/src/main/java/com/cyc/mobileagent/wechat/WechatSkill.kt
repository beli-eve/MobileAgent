package com.cyc.mobileagent.wechat

import com.cyc.mobileagent.agent.AgentAction
import com.cyc.mobileagent.agent.WechatTask
import com.cyc.mobileagent.skill.AgentSkill
import com.cyc.mobileagent.skill.SkillExecutionRequest
import com.cyc.mobileagent.skill.SkillExecutionResult

/**
 * 微信执行 Skill。
 *
 * 只处理微信相关 action。
 * 普通 chat、其他 AI App 和 Runtime 状态都不属于这里。
 */
class WechatSkill(
    private val taskBuilder: WechatTaskBuilder,
    private val taskController: WechatTaskController
) : AgentSkill {

    override val id: String =
        "wechat"

    private val supportedTools =
        setOf(
            "send_wechat",
            "bind_contact_alias"
        )

    override fun canHandle(
        actions: List<AgentAction>
    ): Boolean {
        return actions.isNotEmpty() &&
            actions.all {
                action ->

                action.tool
                    .orEmpty()
                    .trim() in supportedTools
            }
    }

    override fun execute(
        request: SkillExecutionRequest
    ): SkillExecutionResult {
        val buildResult =
            taskBuilder.build(
                actions =
                    request.actions,
                taskId =
                    request.taskId,
                rawResult =
                    request.rawResult
            )

        if (buildResult.tasks.isEmpty()) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    buildResult
                        .statusMessage
                        ?: "执行计划完成",
                logs =
                    buildResult.logs,
                dataChanged =
                    buildResult.contactsChanged
            )
        }

        if (
            !taskController
                .isAccessibilityEnabled()
        ) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    "请先开启 MobileAgent 无障碍服务",
                logs =
                    listOf(
                        "任务没有启动。请先开启 MobileAgent 无障碍服务。"
                    ),
                dataChanged =
                    buildResult.contactsChanged
            )
        }

        if (
            !taskController
                .openWeChat()
        ) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    "没有找到微信",
                logs =
                    listOf(
                        "目标 App 没有成功启动。"
                    ),
                dataChanged =
                    buildResult.contactsChanged
            )
        }

        val messageCount =
            buildResult.tasks
                .sumOf {
                    it.messages.size
                }

        taskController
            .executePlan(
                taskId =
                    request.taskId,
                tasks =
                    buildResult.tasks
            )

        return SkillExecutionResult(
            handedOffToService =
                true,
            statusMessage =
                "正在执行 $messageCount 条微信消息",
            logs =
                listOf(
                    buildPlanLog(
                        buildResult.tasks
                    )
                ),
            dataChanged =
                buildResult.contactsChanged
        )
    }

    override fun cancel(
        taskId: String
    ) {
        taskController
            .cancelTask(
                taskId
            )
    }

    private fun buildPlanLog(
        tasks: List<WechatTask>
    ): String {
        return buildString {
            appendLine(
                "执行计划："
            )

            tasks.forEachIndexed {
                taskIndex,
                task ->

                appendLine()
                appendLine(
                    "${taskIndex + 1}. 联系人：${task.contact}"
                )

                task.messages.forEachIndexed {
                    messageIndex,
                    text ->

                    appendLine(
                        "   ${messageIndex + 1}) $text"
                    )
                }
            }
        }.trim()
    }
}
