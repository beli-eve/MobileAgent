package com.cyc.mobileagent.skill

import com.cyc.mobileagent.agent.AgentAction

/**
 * Runtime 的 Skill 路由表。
 *
 * Runtime 只根据 action 选择 Skill，
 * 不知道任何具体 App 的执行细节。
 */
class SkillRegistry(
    skills: List<AgentSkill>
) {

    private val registeredSkills =
        skills.toList()

    init {
        require(
            registeredSkills.isNotEmpty()
        ) {
            "SkillRegistry 至少需要注册一个 Skill"
        }

        val duplicateIds =
            registeredSkills
                .groupBy {
                    it.id
                }
                .filterValues {
                    it.size > 1
                }
                .keys

        require(
            duplicateIds.isEmpty()
        ) {
            "存在重复 Skill id：${duplicateIds.joinToString()}"
        }
    }

    fun resolve(
        actions: List<AgentAction>
    ): AgentSkill {
        return registeredSkills
            .firstOrNull {
                skill ->

                skill.canHandle(
                    actions
                )
            }
            ?: throw IllegalArgumentException(
                buildUnsupportedToolMessage(
                    actions
                )
            )
    }

    private fun buildUnsupportedToolMessage(
        actions: List<AgentAction>
    ): String {
        val tools =
            actions
                .map {
                    it.tool
                        .orEmpty()
                        .trim()
                        .ifBlank {
                            "<empty>"
                        }
                }
                .distinct()

        return if (tools.isEmpty()) {
            "当前没有 Skill 可以处理这个执行计划"
        } else {
            "当前没有 Skill 可以处理工具：" +
                tools.joinToString(
                    separator = ", "
                )
        }
    }
}
