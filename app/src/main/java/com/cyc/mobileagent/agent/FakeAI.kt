package com.cyc.mobileagent.agent

object FakeAI {

    fun think(command: String): String {

        return when (command.lowercase()) {

            "打开微信" -> "open_wechat"

            else -> "unknown"

        }

    }

}