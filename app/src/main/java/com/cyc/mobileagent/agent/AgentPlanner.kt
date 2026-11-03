package com.cyc.mobileagent.agent

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * 大模型规划结果。
 */
data class AgentPlanningResult(
    val actions: List<AgentAction>,
    val rawResult: String,
    val jsonText: String
)

/**
 * 可以直接展示给用户的规划阶段错误。
 */
class AgentPlanningException(
    val userStatus: String,
    val detail: String
) : Exception(detail)

/**
 * 只负责：
 * 1. 请求 DeepSeek
 * 2. 清洗模型返回
 * 3. 解析 AgentPlan
 *
 * 它不负责联系人解析、微信启动和无障碍执行。
 */
class AgentPlanner {

    suspend fun createPlan(
        command: String
    ): AgentPlanningResult {
        val response =
            RetrofitClient.api.chat(
                authorization =
                    "Bearer ${Config.API_KEY}",
                request =
                    ChatRequest(
                        model =
                            "deepseek-v4-flash",
                        messages =
                            listOf(
                                Message(
                                    role =
                                        "system",
                                    content =
                                        SYSTEM_PROMPT
                                ),
                                Message(
                                    role =
                                        "user",
                                    content =
                                        command
                                )
                            )
                    )
            )

        if (!response.isSuccessful) {
            val detail =
                response
                    .errorBody()
                    ?.string()
                    ?: "没有错误信息"

            throw AgentPlanningException(
                userStatus =
                    "请求失败：HTTP ${response.code()}",
                detail =
                    detail
            )
        }

        val rawResult =
            response
                .body()
                ?.choices
                ?.firstOrNull()
                ?.message
                ?.content
                .orEmpty()

        if (rawResult.isBlank()) {
            throw AgentPlanningException(
                userStatus =
                    "AI 没有返回内容",
                detail =
                    "模型返回为空"
            )
        }

        val jsonText =
            cleanJsonResponse(
                rawResult
            )

        val parsedActions =
            parseAgentActions(
                jsonText
            )

        val actions =
            normalizePlan(
                command =
                    command,
                actions =
                    parsedActions
            )

        validatePlanSafety(
            command =
                command,
            actions =
                actions
        )

        if (actions.isEmpty()) {
            throw AgentPlanningException(
                userStatus =
                    "AI 返回格式错误",
                detail =
                    jsonText
            )
        }

        return AgentPlanningResult(
            actions =
                actions,
            rawResult =
                rawResult,
            jsonText =
                jsonText
        )
    }

    /**
     * 对 Planner 的结果做最小化确定性归一化。
     *
     * 当前只处理一个高风险回归：
     * 当模型在用户只要求发送一条消息时，偶尔会返回两条完全相同的
     * send_wechat action，导致真实 App 重复发送。
     *
     * 如果用户明确要求重复、连续多次或多条相同内容，则不去重。
     */
    private fun normalizePlan(
        command: String,
        actions: List<AgentAction>
    ): List<AgentAction> {
        if (
            actions.size <= 1 ||
            hasExplicitRepeatIntent(
                command
            )
        ) {
            return actions
        }

        val seenWechatSends =
            mutableSetOf<String>()

        return actions.filter {
            action ->

            if (
                action.tool
                    .orEmpty()
                    .trim() !=
                "send_wechat"
            ) {
                true
            } else {
                val contact =
                    action.contact
                        .orEmpty()
                        .trim()
                        .lowercase()

                val text =
                    action.text
                        .orEmpty()
                        .trim()

                val key =
                    contact +
                        "\u0000" +
                        text

                seenWechatSends.add(
                    key
                )
            }
        }
    }

    private fun hasExplicitRepeatIntent(
        command: String
    ): Boolean {
        val normalized =
            command
                .trim()
                .lowercase()

        if (normalized.isBlank()) {
            return false
        }

        val markers =
            listOf(
                "重复",
                "再发一遍",
                "再发一次",
                "再发两遍",
                "再发两次",
                "发两遍",
                "发两次",
                "发三遍",
                "发三次",
                "连续发",
                "连续发送",
                "两条",
                "三条",
                "2条",
                "3条"
            )

        return markers.any {
            normalized.contains(
                it
            )
        }
    }

    /**
     * 对真实微信发送做本地安全校验。
     *
     * 即使 LLM 错误返回 send_wechat，只要用户原始命令
     * 没有明确表达发送 / 转告 / 询问 / 回复意图，就禁止执行。
     */
    private fun validatePlanSafety(
        command: String,
        actions: List<AgentAction>
    ) {
        val containsWechatSend =
            actions.any {
                it.tool
                    .orEmpty()
                    .trim() ==
                    "send_wechat"
            }

        if (
            containsWechatSend &&
            !hasExplicitWechatSendIntent(
                command
            )
        ) {
            throw AgentPlanningException(
                userStatus =
                    "未执行微信发送",
                detail =
                    "Planner 返回了 send_wechat，" +
                        "但原始指令没有明确发送、转告、询问或回复意图。" +
                        "为避免误发，本次任务已被本地安全闸拦截。"
            )
        }
    }

    private fun hasExplicitWechatSendIntent(
        command: String
    ): Boolean {
        val normalized =
            command
                .trim()
                .lowercase()

        if (normalized.isBlank()) {
            return false
        }

        val directMarkers =
            listOf(
                "发送",
                "发给",
                "发一条",
                "发一句",
                "发消息",
                "告诉",
                "转告",
                "通知",
                "跟他说",
                "跟她说",
                "跟ta说",
                "跟他讲",
                "跟她讲",
                "对他说",
                "对她说",
                "问问",
                "问一下",
                "问下",
                "帮我问",
                "替我问",
                "回复",
                "回他",
                "回她",
                "回ta"
            )

        if (
            directMarkers.any {
                normalized.contains(it)
            }
        ) {
            return true
        }

        return normalized.contains("给") &&
            (
                normalized.contains("发") ||
                    normalized.contains("说") ||
                    normalized.contains("问") ||
                    normalized.contains("回")
            )
    }

    private fun parseAgentActions(
        jsonText: String
    ): List<AgentAction> {
        val root =
            JsonParser.parseString(
                jsonText
            )

        return when {
            root.isJsonObject &&
                root.asJsonObject.has(
                    "actions"
                ) -> {
                Gson().fromJson(
                    root,
                    AgentPlan::class.java
                ).actions
            }

            root.isJsonArray -> {
                root
                    .asJsonArray
                    .mapNotNull {
                        element ->

                        if (!element.isJsonObject) {
                            null
                        } else {
                            Gson().fromJson(
                                element,
                                AgentAction::class.java
                            )
                        }
                    }
            }

            root.isJsonObject -> {
                listOf(
                    Gson().fromJson(
                        root,
                        AgentAction::class.java
                    )
                )
            }

            else ->
                emptyList()
        }
    }

    private fun cleanJsonResponse(
        rawResult: String
    ): String {
        return rawResult
            .trim()
            .removePrefix(
                "```json"
            )
            .removePrefix(
                "```JSON"
            )
            .removePrefix(
                "```"
            )
            .removeSuffix(
                "```"
            )
            .trim()
    }

    companion object {
        private val SYSTEM_PROMPT =
            """
你是一个 Android 手机智能体的计划器。

你的任务是把用户的一次自然语言命令转换成可顺序执行的 JSON 计划。

只返回 JSON。
不要解释。
不要使用 Markdown。
不要在 JSON 前后添加其他文字。

统一返回格式：

{
  "actions": [
    {
      "tool": "工具名称"
    }
  ]
}

支持以下工具。

一、发送微信消息

只有当用户明确表达发送、转告、通知、询问或回复某个联系人的意图时，
才允许使用 send_wechat。

{
  "tool": "send_wechat",
  "contact": "用户原本使用的联系人称呼",
  "text": "需要独立发送的一条消息"
}

contact 必须保留用户原本使用的称呼。

如果用户只要求发送一条消息，
只能生成一个 send_wechat action。

只有当用户明确要求多条独立消息时，
才生成多个 send_wechat action。

不要因为一句话里出现“发”或“发送”等词两次，
就重复生成相同 action。

每一条需要独立发送的消息都必须是单独的 action。
不要把多条不同消息合并为一个 text。

如果用户只是在说明联系人映射关系，
绝对不能使用 send_wechat。

二、保存联系人别名

只有当用户明确表达联系人称呼与微信真实显示名称之间的绑定关系时，
才使用 bind_contact_alias。

{
  "tool": "bind_contact_alias",
  "alias": "用户习惯使用的称呼",
  "wechatName": "微信中真实显示的联系人名称"
}

一句话里出现多个明确的联系人映射时，
每个映射生成一个独立 action。

三、普通聊天

如果用户没有要求操作手机，也没有要求保存联系人映射，使用 chat。

{
  "tool": "chat",
  "reply": "回答内容"
}

四、向 ChatGPT 提问

只有当用户明确要求把问题交给 ChatGPT 时，才使用 ask_chatgpt。

{
  "tool": "ask_chatgpt",
  "prompt": "需要发送给 ChatGPT 的问题或指令"
}

prompt 只保留真正需要发送给 ChatGPT 的内容。
不要替目标 App 回答这个问题。

五、向 Gemini 提问

只有当用户明确要求把问题交给 Gemini 时，才使用 ask_gemini。

{
  "tool": "ask_gemini",
  "prompt": "需要发送给 Gemini 的问题或指令"
}

prompt 只保留真正需要发送给 Gemini 的内容。
不要替目标 App 回答这个问题。

六、向 DeepSeek App 提问

只有当用户明确要求把问题交给 DeepSeek App 时，才使用 ask_deepseek。

例如，用户明确表达“问 DeepSeek”“用 DeepSeek 问”“让 DeepSeek 回答”等含义时，
才属于这个工具。

如果用户只是在讨论 DeepSeek 本身，例如询问 DeepSeek 是什么，
不要启动 DeepSeek App，应该使用普通 chat。

{
  "tool": "ask_deepseek",
  "prompt": "需要发送给 DeepSeek App 的问题或指令"
}

prompt 只保留真正需要发送给 DeepSeek App 的内容。
不要替目标 App 回答这个问题。

当前版本限制：

ask_chatgpt、ask_gemini 和 ask_deepseek 都只能单独构成一次执行计划，
不能和 send_wechat、bind_contact_alias、chat 或另一个 AI App 动作混用。

如果用户一次命令同时要求多个 App 动作，
返回一个 chat action，说明当前版本需要拆分为多个命令。

必须保持用户要求的动作顺序。
            """.trimIndent()
    }
}
