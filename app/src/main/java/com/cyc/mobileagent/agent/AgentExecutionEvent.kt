package com.cyc.mobileagent.agent

/**
 * 无障碍执行器回传给 MobileAgent 主界面的结构化事件。
 *
 * 这里使用“已执行”而不是“发送成功”，因为当前只能确认：
 * Android 已完成发送按钮点击手势，尚未验证微信服务器最终送达结果。
 */
data class AgentExecutionEvent(
    val taskId: String = "",
    val type: String = ExecutionEventType.STATUS,
    val message: String = "",
    val contact: String? = null,
    val messageIndex: Int? = null,
    val totalMessages: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object ExecutionEventType {
    const val TASK_STARTED = "task_started"
    const val CONTACT_STARTED = "contact_started"
    const val MESSAGE_STARTED = "message_started"
    const val MESSAGE_EXECUTED = "message_executed"
    const val TASK_COMPLETED = "task_completed"
    const val TASK_FAILED = "task_failed"
    const val TASK_CANCELLED = "task_cancelled"
    const val STATUS = "status"

    fun isTerminal(type: String): Boolean {
        return type == TASK_COMPLETED ||
            type == TASK_FAILED ||
            type == TASK_CANCELLED
    }
}
