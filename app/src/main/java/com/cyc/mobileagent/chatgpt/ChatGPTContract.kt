package com.cyc.mobileagent.chatgpt

/**
 * ChatGPT Skill 与无障碍执行器之间的内部协议。
 */
object ChatGPTContract {

    const val CHATGPT_PACKAGE =
        "com.openai.chatgpt"

    const val ACTION_EXECUTE_CHATGPT =
        "com.cyc.mobileagent.action.EXECUTE_CHATGPT"

    const val ACTION_CANCEL_CHATGPT_TASK =
        "com.cyc.mobileagent.action.CANCEL_CHATGPT_TASK"

    const val EXTRA_TASK_ID =
        "chatgpt_task_id"

    const val EXTRA_PROMPT =
        "chatgpt_prompt"
}
