package com.cyc.mobileagent.runtime

/**
 * 不需要经过 LLM 的确定性本地命令入口。
 *
 * 例如某个 Skill 可以自己处理本地配置、映射或设备设置，
 * Runtime 只关心有没有被处理，以及本地数据是否发生变化。
 */
interface LocalCommandHandler {

    fun tryHandle(
        command: String
    ): LocalCommandResult?
}

data class LocalCommandResult(
    val statusMessage: String,
    val logs: List<String> = emptyList(),
    val dataChanged: Boolean = false
)
