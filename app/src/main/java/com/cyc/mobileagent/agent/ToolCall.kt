package com.cyc.mobileagent.agent

/**
 * AI 返回的通用执行计划。
 */
data class AgentPlan(
    val actions: List<AgentAction> = emptyList()
)

/**
 * 执行计划中的单个动作。
 *
 * 当前支持：
 * - send_wechat
 * - bind_contact_alias
 * - chat
 * - ask_chatgpt
 */
data class AgentAction(
    val tool: String? = null,
    val contact: String? = null,
    val text: String? = null,
    val reply: String? = null,
    val alias: String? = null,
    val wechatName: String? = null,
    val prompt: String? = null
)

/**
 * MainActivity 交给无障碍服务执行的微信联系人任务。
 *
 * 同一个联系人可以连续发送多条消息，因此 messages 是列表。
 * taskId 用于把服务端执行结果准确回传给发起这次命令的界面，
 * 防止旧任务的迟到广播覆盖新任务状态。
 */
data class WechatTask(
    val taskId: String = "",
    val contact: String = "",
    val messages: List<String> = emptyList()
)