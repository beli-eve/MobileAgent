package com.cyc.mobileagent.agent

import com.cyc.mobileagent.BuildConfig

object Config {

    val API_KEY: String
        get() = BuildConfig.DEEPSEEK_API_KEY
}