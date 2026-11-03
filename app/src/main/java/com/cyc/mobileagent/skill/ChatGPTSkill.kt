package com.cyc.mobileagent.skill

import com.cyc.mobileagent.agent.AgentAction
import com.cyc.mobileagent.chatgpt.ChatGPTTaskController

/**
 * ChatGPT App Skill v1。
 *
 * 当前边界：
 * - 一次只处理一个 ask_chatgpt action
 * - 总是尝试进入一个新对话
 * - 只负责把 prompt 发出去
 * - 不读取 ChatGPT 回复
 * - 不做历史对话选择
 *
 * 历史会话解析留给 ConversationResolver v2。
 */
class ChatGPTSkill(
    private val controller: ChatGPTTaskController
) : AgentSkill {

    override val id: String =
        "chatgpt"

    override fun canHandle(
        actions: List<AgentAction>
    ): Boolean {
        return actions.size == 1 &&
            actions.first()
                .tool
                .orEmpty()
                .trim() ==
            "ask_chatgpt"
    }

    override fun execute(
        request: SkillExecutionRequest
    ): SkillExecutionResult {
        val action =
            request.actions
                .singleOrNull()
                ?: throw IllegalArgumentException(
                    "ChatGPT Skill 当前一次只能处理一个动作"
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
                "AI 返回的 ChatGPT 问题为空"
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
                        "ChatGPT 任务没有启动。请先开启 MobileAgent 无障碍服务。"
                    )
            )
        }

        if (
            !controller
                .openChatGPT()
        ) {
            return SkillExecutionResult(
                handedOffToService =
                    false,
                statusMessage =
                    "没有找到 ChatGPT",
                logs =
                    listOf(
                        "请确认手机已经安装官方 ChatGPT Android App。"
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
                "正在交给 ChatGPT",
            logs =
                listOf(
                    "准备发送给 ChatGPT：$prompt"
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
