package com.cyc.mobileagent.runtime

import com.cyc.mobileagent.agent.AgentExecutionEvent
import com.cyc.mobileagent.agent.AgentPlanner
import com.cyc.mobileagent.agent.AgentPlanningException
import com.cyc.mobileagent.agent.ExecutionEventFormatter
import com.cyc.mobileagent.agent.ExecutionEventType
import com.cyc.mobileagent.agent.ExecutionLogStore
import com.cyc.mobileagent.skill.AgentSkill
import com.cyc.mobileagent.skill.SkillExecutionRequest
import com.cyc.mobileagent.skill.SkillRegistry
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeState(
    val status: String = "等待命令",
    val isRunning: Boolean = false,
    val canCancel: Boolean = false,
    val logVersion: Int = 0,
    val dataVersion: Int = 0
)

/**
 * MobileAgent 的通用执行 Runtime。
 *
 * Runtime 不依赖 Activity 或 Compose。
 * UI 只观察 state，并提交 execute / cancel。
 */
class AgentRuntime(
    private val planner: AgentPlanner,
    private val skillRegistry: SkillRegistry,
    private val executionLogStore: ExecutionLogStore,
    private val localCommandHandlers: List<LocalCommandHandler>
) {

    private val _state =
        MutableStateFlow(
            RuntimeState()
        )

    val state: StateFlow<RuntimeState> =
        _state.asStateFlow()

    private var currentTaskId: String? =
        null

    private var currentSkill: AgentSkill? =
        null

    suspend fun execute(
        rawCommand: String
    ) {
        val command =
            rawCommand.trim()

        if (command.isBlank()) {
            updateStatus(
                "请输入命令"
            )
            return
        }

        if (_state.value.isRunning) {
            updateStatus(
                "当前任务仍在执行"
            )
            return
        }

        updateState {
            it.copy(
                isRunning =
                    true
            )
        }

        var handedOffToService =
            false

        try {
            appendLog(
                "用户命令：$command"
            )

            val localResult =
                localCommandHandlers
                    .asSequence()
                    .mapNotNull {
                        handler ->

                        handler.tryHandle(
                            command
                        )
                    }
                    .firstOrNull()

            if (localResult != null) {
                updateStatus(
                    localResult
                        .statusMessage
                )

                appendLogs(
                    localResult.logs
                )

                if (
                    localResult
                        .dataChanged
                ) {
                    bumpDataVersion()
                }

                return
            }

            updateStatus(
                "AI 正在生成执行计划..."
            )

            val planningResult =
                planner
                    .createPlan(
                        command
                    )

            val taskId =
                UUID.randomUUID()
                    .toString()

            val skill =
                skillRegistry
                    .resolve(
                        planningResult.actions
                    )

            currentTaskId =
                taskId

            currentSkill =
                skill

            updateState {
                it.copy(
                    canCancel =
                        true
                )
            }

            val result =
                skill.execute(
                    SkillExecutionRequest(
                        actions =
                            planningResult.actions,
                        taskId =
                            taskId,
                        rawResult =
                            planningResult.rawResult
                    )
                )

            appendLogs(
                result.logs
            )

            if (
                result.dataChanged
            ) {
                bumpDataVersion()
            }

            /*
             * 内部广播可能在 Skill.execute() 返回前就回传终态。
             * 如果 taskId 已经被清理，不再用“正在执行”覆盖真实终态。
             */
            if (
                currentTaskId ==
                taskId
            ) {
                updateStatus(
                    result.statusMessage
                )

                handedOffToService =
                    result
                        .handedOffToService
            } else {
                handedOffToService =
                    true
            }
        } catch (
            e: AgentPlanningException
        ) {
            updateStatus(
                e.userStatus
            )

            appendLog(
                "${e.userStatus}：${e.detail}"
            )
        } catch (
            _: SocketTimeoutException
        ) {
            updateStatus(
                "请求超时"
            )

            appendLog(
                "模型响应时间过长，本次任务没有执行。"
            )
        } catch (
            e: Exception
        ) {
            updateStatus(
                "执行失败"
            )

            val detail =
                e.message
                    ?: e.javaClass
                        .simpleName

            appendLog(
                "执行失败：$detail"
            )
        } finally {
            if (!handedOffToService) {
                finishCurrentExecution()
            }
        }
    }

    fun cancelCurrentTask() {
        val taskId =
            currentTaskId

        val skill =
            currentSkill

        if (
            taskId.isNullOrBlank() ||
            skill == null
        ) {
            updateStatus(
                "当前没有可取消的任务"
            )
            return
        }

        updateStatus(
            "正在取消当前任务..."
        )

        skill.cancel(
            taskId
        )
    }

    fun handleExecutionEvent(
        event: AgentExecutionEvent
    ) {
        val expectedTaskId =
            currentTaskId

        if (
            expectedTaskId.isNullOrBlank() ||
            event.taskId !=
            expectedTaskId
        ) {
            return
        }

        updateStatus(
            ExecutionEventFormatter
                .compactStatus(
                    event
                )
        )

        appendLog(
            ExecutionEventFormatter
                .logLine(
                    event
                )
        )

        if (
            ExecutionEventType
                .isTerminal(
                    event.type
                )
        ) {
            finishCurrentExecution()
        }
    }

    fun readLogs():
            List<String> {
        return executionLogStore
            .readAllNewestFirst()
    }

    fun clearLogs() {
        executionLogStore
            .clear()

        updateState {
            it.copy(
                logVersion =
                    it.logVersion +
                        1
            )
        }
    }

    private inline fun updateState(
        transform: (RuntimeState) -> RuntimeState
    ) {
        _state.value =
            transform(
                _state.value
            )
    }

    private fun updateStatus(
        status: String
    ) {
        updateState {
            it.copy(
                status =
                    status
            )
        }
    }

    private fun bumpDataVersion() {
        updateState {
            it.copy(
                dataVersion =
                    it.dataVersion +
                        1
            )
        }
    }

    private fun appendLogs(
        logs: List<String>
    ) {
        logs.forEach {
            appendLog(
                it
            )
        }
    }

    private fun appendLog(
        message: String
    ) {
        if (message.isBlank()) {
            return
        }

        executionLogStore
            .append(
                message
            )

        updateState {
            it.copy(
                logVersion =
                    it.logVersion +
                        1
            )
        }
    }

    private fun finishCurrentExecution() {
        currentTaskId =
            null

        currentSkill =
            null

        updateState {
            it.copy(
                isRunning =
                    false,
                canCancel =
                    false
            )
        }
    }
}
