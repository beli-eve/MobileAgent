package com.cyc.mobileagent.gemini

/**
 * Gemini App Skill 的内部协议。
 *
 * Google Play 上官方 Gemini Android App 的包名：
 * com.google.android.apps.bard
 */
object GeminiContract {

    const val GEMINI_PACKAGE =
        "com.google.android.apps.bard"

    const val ACTION_EXECUTE_GEMINI =
        "com.cyc.mobileagent.action.EXECUTE_GEMINI"

    const val ACTION_CANCEL_GEMINI_TASK =
        "com.cyc.mobileagent.action.CANCEL_GEMINI_TASK"

    const val EXTRA_TASK_ID =
        "gemini_task_id"

    const val EXTRA_PROMPT =
        "gemini_prompt"
}
