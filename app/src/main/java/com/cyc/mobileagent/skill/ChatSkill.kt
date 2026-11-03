package com.cyc.mobileagent.skill

import com.cyc.mobileagent.agent.AgentAction

/**
 * 普通文本回复 Skill。
 *
 * chat 不再由任何具体 App Skill 兜底处理。
 */
class ChatSkill : AgentSkill {

    override val id: String =
        "chat"

    override fun canHandle(
        actions: List<AgentAction>
    ): Boolean {
        return actions.size == 1 &&
            actions.first()
                .tool
                .orEmpty()
                .trim() ==
            "chat"
    }

    override fun execute(
        request: SkillExecutionRequest
    ): SkillExecutionResult {
        val action =
            request.actions
                .singleOrNull()
                ?: throw IllegalArgumentException(
                    "chat 当前一次只能处理一个动作"
                )

        val reply =
            action.reply
                .orEmpty()
                .trim()
                .ifBlank {
                    request.rawResult
                        .trim()
                }

        return SkillExecutionResult(
            handedOffToService =
                false,
            statusMessage =
                "AI 已回复",
            logs =
                listOf(
                    reply
                )
        )
    }
}
