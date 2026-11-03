package com.cyc.mobileagent.runtime

/**
 * 所有异步 Skill 共用的执行结果协议。
 *
 * 无障碍服务只向 Runtime 回传统一事件，
 * 不再把执行结果挂在某个具体 App 的 Contract 上。
 */
object RuntimeEventContract {

    const val ACTION_EXECUTION_EVENT =
        "com.cyc.mobileagent.action.RUNTIME_EXECUTION_EVENT"

    const val EXTRA_EXECUTION_EVENT_JSON =
        "runtime_execution_event_json"
}
