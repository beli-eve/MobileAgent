package com.cyc.mobileagent.wechat

import com.cyc.mobileagent.agent.AgentAction
import com.cyc.mobileagent.agent.BindAliasStatus
import com.cyc.mobileagent.agent.MobileAgentProfileStore
import com.cyc.mobileagent.agent.WechatTask

/**
 * AI 动作转换成微信执行任务后的结果。
 */
data class WechatPlanBuildResult(
    val tasks: List<WechatTask>,
    val logs: List<String>,
    val statusMessage: String?,
    val hasChatReply: Boolean,
    val contactsChanged: Boolean
)

/**
 * 负责微信语义层的任务构建：
 * - 联系人别名解析
 * - 同联系人连续消息合并
 * - 联系人别名保存
 * - 普通聊天回复收集
 *
 * 它不负责打开微信，也不负责执行坐标点击。
 */
class WechatTaskBuilder(
    private val profileStore: MobileAgentProfileStore
) {

    fun build(
        actions: List<AgentAction>,
        taskId: String,
        rawResult: String
    ): WechatPlanBuildResult {
        val tasks =
            mutableListOf<WechatTask>()

        val logs =
            mutableListOf<String>()

        var statusMessage: String? =
            null

        var hasChatReply =
            false

        var contactsChanged =
            false

        actions.forEach {
            action ->

            when (action.tool) {
                "bind_contact_alias" -> {
                    val result =
                        bindContactAlias(
                            action
                        )

                    statusMessage =
                        result.status

                    logs.add(
                        result.log
                    )

                    contactsChanged =
                        true
                }

                "send_wechat" -> {
                    val requestedContact =
                        action.contact
                            .orEmpty()
                            .trim()

                    val message =
                        action.text
                            .orEmpty()
                            .trim()

                    if (requestedContact.isBlank()) {
                        throw IllegalArgumentException(
                            "AI 返回的联系人为空"
                        )
                    }

                    if (message.isBlank()) {
                        throw IllegalArgumentException(
                            "AI 返回的消息内容为空"
                        )
                    }

                    val resolveResult =
                        profileStore
                            .resolveWechatContact(
                                requestedContact
                            )

                    appendWechatTask(
                        tasks =
                            tasks,
                        taskId =
                            taskId,
                        contact =
                            resolveResult
                                .resolvedDisplayName,
                        message =
                            message
                    )

                    logs.add(
                        if (resolveResult.matchedByAlias) {
                            "${resolveResult.requestedName} → " +
                                "${resolveResult.resolvedDisplayName}：$message"
                        } else {
                            "${resolveResult.resolvedDisplayName}：$message"
                        }
                    )
                }

                "chat" -> {
                    hasChatReply =
                        true

                    statusMessage =
                        "AI 已回复"

                    logs.add(
                        action.reply
                            .orEmpty()
                            .ifBlank {
                                rawResult
                            }
                    )
                }

                else -> {
                    throw IllegalArgumentException(
                        "无法识别 AI 工具：${action.tool}"
                    )
                }
            }
        }

        return WechatPlanBuildResult(
            tasks =
                tasks,
            logs =
                logs,
            statusMessage =
                statusMessage,
            hasChatReply =
                hasChatReply,
            contactsChanged =
                contactsChanged
        )
    }

    private fun bindContactAlias(
        action: AgentAction
    ): AliasBindResult {
        val alias =
            action.alias
                .orEmpty()
                .trim()

        val wechatName =
            action.wechatName
                .orEmpty()
                .trim()

        if (alias.isBlank()) {
            throw IllegalArgumentException(
                "AI 返回的联系人别名为空"
            )
        }

        if (wechatName.isBlank()) {
            throw IllegalArgumentException(
                "AI 返回的微信联系人名称为空"
            )
        }

        val result =
            profileStore.bindWechatAlias(
                alias =
                    alias,
                displayName =
                    wechatName
            )

        return when (result.status) {
            BindAliasStatus.CREATED_NEW_CONTACT ->
                AliasBindResult(
                    status =
                        "联系人档案已创建",
                    log =
                        "已保存：${result.alias} → ${result.displayName}"
                )

            BindAliasStatus.ADDED_TO_EXISTING_CONTACT ->
                AliasBindResult(
                    status =
                        "联系人别名已添加",
                    log =
                        "已添加：${result.alias} → ${result.displayName}"
                )

            BindAliasStatus.ALREADY_BOUND ->
                AliasBindResult(
                    status =
                        "这个别名已经存在",
                    log =
                        "当前映射：${result.alias} → ${result.displayName}"
                )

            BindAliasStatus.CONFLICT ->
                AliasBindResult(
                    status =
                        "联系人别名发生冲突",
                    log =
                        "别名 ${result.alias} 当前属于 " +
                            "${result.conflictingDisplayName}，未修改"
                )
        }
    }

    private fun appendWechatTask(
        tasks: MutableList<WechatTask>,
        taskId: String,
        contact: String,
        message: String
    ) {
        val lastTask =
            tasks.lastOrNull()

        if (
            lastTask != null &&
            lastTask.contact == contact
        ) {
            tasks[tasks.lastIndex] =
                lastTask.copy(
                    messages =
                        lastTask.messages +
                            message
                )
        } else {
            tasks.add(
                WechatTask(
                    taskId =
                        taskId,
                    contact =
                        contact,
                    messages =
                        listOf(
                            message
                        )
                )
            )
        }
    }

    private data class AliasBindResult(
        val status: String,
        val log: String
    )
}
