package com.cyc.mobileagent.skill

import com.cyc.mobileagent.agent.AgentAction

/**
 * Runtime 对可执行能力的统一抽象。
 */
interface AgentSkill {

    val id: String

    fun canHandle(
        actions: List<AgentAction>
    ): Boolean

    fun execute(
        request: SkillExecutionRequest
    ): SkillExecutionResult

    fun cancel(
        taskId: String
    ) = Unit
}

data class SkillExecutionRequest(
    val actions: List<AgentAction>,
    val taskId: String,
    val rawResult: String
)

data class SkillExecutionResult(
    val handedOffToService: Boolean,
    val statusMessage: String,
    val logs: List<String> = emptyList(),
    val dataChanged: Boolean = false
)
