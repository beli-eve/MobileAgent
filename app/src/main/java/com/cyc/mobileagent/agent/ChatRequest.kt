package com.cyc.mobileagent.agent

import com.google.gson.annotations.SerializedName

/**
 * 发送给 DeepSeek 的单条消息。
 */
data class Message(
    val role: String,
    val content: String
)

/**
 * DeepSeek 思考模式配置。
 */
data class ThinkingConfig(
    val type: String
)

/**
 * DeepSeek 对话请求。
 */
data class ChatRequest(

    /**
     * 模型名称。
     */
    val model: String,

    /**
     * system 与 user 消息。
     */
    val messages: List<Message>,

    /**
     * MobileAgent 只需要提取短 JSON，
     * 因此关闭深度思考。
     */
    val thinking: ThinkingConfig =
        ThinkingConfig(
            type = "disabled"
        ),

    /**
     * 限制输出长度，避免生成大量无关内容。
     */
    @SerializedName("max_tokens")
    val maxTokens: Int = 300,

    /**
     * 暂时使用非流式响应。
     */
    val stream: Boolean = false
)