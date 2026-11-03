package com.cyc.mobileagent.agent

/**
 * 把执行器的结构化事件转换成首页短状态和运行记录文本。
 */
object ExecutionEventFormatter {

    fun compactStatus(
        event: AgentExecutionEvent
    ): String {
        return when (event.type) {
            ExecutionEventType.TASK_STARTED ->
                "任务执行中"

            ExecutionEventType.CONTACT_STARTED ->
                "正在处理联系人"

            ExecutionEventType.MESSAGE_STARTED ->
                "正在发送消息"

            ExecutionEventType.MESSAGE_EXECUTED ->
                "消息已执行"

            ExecutionEventType.TASK_COMPLETED ->
                "任务完成"

            ExecutionEventType.TASK_FAILED ->
                "任务失败"

            ExecutionEventType.TASK_CANCELLED ->
                "任务已取消"

            else ->
                event.message
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty()
                    .ifBlank {
                        "状态已更新"
                    }
        }
    }

    fun logLine(
        event: AgentExecutionEvent
    ): String {
        return when (event.type) {
            ExecutionEventType.TASK_STARTED ->
                "任务开始：${event.message}"

            ExecutionEventType.CONTACT_STARTED ->
                "联系人：${event.contact.orEmpty()}"

            ExecutionEventType.MESSAGE_STARTED ->
                "正在执行第 ${event.messageIndex ?: 0}/${event.totalMessages ?: 0} 条消息"

            ExecutionEventType.MESSAGE_EXECUTED ->
                "已执行第 ${event.messageIndex ?: 0}/${event.totalMessages ?: 0} 条消息"

            ExecutionEventType.TASK_COMPLETED ->
                "任务完成：${event.message}"

            ExecutionEventType.TASK_FAILED ->
                "任务失败：${event.message}"

            ExecutionEventType.TASK_CANCELLED ->
                "任务取消：${event.message}"

            else ->
                event.message
        }
    }
}
