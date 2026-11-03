package com.cyc.mobileagent.skill

import com.cyc.mobileagent.agent.AgentAction
import com.cyc.mobileagent.deepseek.DeepSeekTaskController

/**
 * DeepSeek Android App Skill v1。
 *
 * 当前边界：
 * - 一次只处理一个 ask_deepseek action
 * - 尽量进入新对话；无法识别新对话控件时使用当前会话
 * - 只负责把 prompt 发出去
 * - 不读取回复
 * - 不选择历史对话
 */
class DeepSeekSkill(
    private val controller: DeepSeekTaskController
) : AgentSkill {

    override val id: String =
        "deepseek"

    override fun canHandle(
        actions: List<AgentAction>
    ): Boolean {
        return actions.size == 1 &&
            actions
                .first()
                .tool
                .orEmpty()
                .trim() ==
            "ask_deepseek"
    }

    override fun execute(
        request: SkillExecutionRequest
    ): SkillExecutionResult {
        val action =
            request.actions
                .singleOrNull()
                ?: throw IllegalArgumentException(
                    "DeepSeek Skill 当前一次只能处理一个动作"
                )

        val prompt =
            action.prompt
                .orEmpty()
                .trim()
                .ifBlank {
                    action.text
                        .orEmpty()
                        .trim()
                }

        if (prompt.isBlank()) {
            throw IllegalArgumentException(
                "AI 返回的 DeepSeek 问题为空"
            )
        }

        if (
            !controller
                .isAccessibilityEnabled()
        ) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    "请先开启 MobileAgent 无障碍服务",
                logs =
                    listOf(
                        "DeepSeek 任务没有启动。请先开启 MobileAgent 无障碍服务。"
                    )
            )
        }

        if (
            !controller
                .openDeepSeek()
        ) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    "没有找到 DeepSeek",
                logs =
                    listOf(
                        "请确认手机已经安装 DeepSeek Android App。"
                    )
            )
        }

        controller.execute(
            taskId =
                request.taskId,
            prompt =
                prompt
        )

        return SkillExecutionResult(
            handedOffToService =
                true,
            statusMessage =
                "正在交给 DeepSeek",
            logs =
                listOf(
                    "DeepSeek 已接收待发送问题。"
                )
        )
    }

    override fun cancel(
        taskId: String
    ) {
        controller
            .cancelTask(
                taskId
            )
    }
}
