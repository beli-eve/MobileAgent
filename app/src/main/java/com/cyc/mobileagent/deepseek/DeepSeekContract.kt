package com.cyc.mobileagent.deepseek

/**
 * DeepSeek App Skill 与 Accessibility Runtime 之间的内部协议。
 */
object DeepSeekContract {

    const val DEEPSEEK_PACKAGE =
        "com.deepseek.chat"

    const val ACTION_EXECUTE_DEEPSEEK =
        "com.cyc.mobileagent.action.EXECUTE_DEEPSEEK"

    const val ACTION_CANCEL_DEEPSEEK_TASK =
        "com.cyc.mobileagent.action.CANCEL_DEEPSEEK_TASK"

    const val EXTRA_TASK_ID =
        "deepseek_task_id"

    const val EXTRA_PROMPT =
        "deepseek_prompt"
}
