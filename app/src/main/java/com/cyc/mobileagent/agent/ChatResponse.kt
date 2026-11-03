package com.cyc.mobileagent.agent

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)