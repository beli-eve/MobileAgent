package com.cyc.mobileagent.wechat

/**
 * 微信模块与无障碍执行器之间使用的固定协议。
 *
 * 把包名、广播 Action 和 Extra 统一放在这里，避免 MainActivity
 * 成为微信协议的“总仓库”。
 */
object WechatContract {
    const val WECHAT_PACKAGE =
        "com.tencent.mm"

    const val ACTION_EXECUTE_WECHAT_PLAN =
        "com.cyc.mobileagent.action.EXECUTE_WECHAT_PLAN"

    const val EXTRA_WECHAT_TASKS_JSON =
        "wechat_tasks_json"

    const val EXTRA_TASK_ID =
        "task_id"

    const val ACTION_CANCEL_WECHAT_TASK =
        "com.cyc.mobileagent.action.CANCEL_WECHAT_TASK"

    const val ACTION_WECHAT_EXECUTION_EVENT =
        "com.cyc.mobileagent.action.WECHAT_EXECUTION_EVENT"

    const val EXTRA_EXECUTION_EVENT_JSON =
        "execution_event_json"

    const val ACTION_START_WECHAT_CALIBRATION =
        "com.cyc.mobileagent.action.START_WECHAT_CALIBRATION"

    const val ACTION_CANCEL_WECHAT_CALIBRATION =
        "com.cyc.mobileagent.action.CANCEL_WECHAT_CALIBRATION"

    const val EXTRA_CALIBRATION_CONTACT =
        "calibration_contact"

    const val EXTRA_OPEN_CALIBRATION_PAGE =
        "open_calibration_page"
}
