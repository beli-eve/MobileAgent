package com.cyc.mobileagent.agent

import android.content.Context

/**
 * 微信自动化需要定位的五个控件。
 *
 * defaultXRatio / defaultYRatio 是当前已验证设备的兜底坐标。
 * 用户完成引导式校准后，会优先使用该设备自己记录的坐标。
 */
enum class CalibrationTarget(
    val storageKey: String,
    val displayName: String,
    val instruction: String,
    val defaultXRatio: Float,
    val defaultYRatio: Float
) {
    SEARCH_ICON(
        storageKey = "search_icon",
        displayName = "微信搜索按钮",
        instruction = "请点击微信右上角的搜索按钮",
        defaultXRatio = 0.837f,
        defaultYRatio = 0.076f
    ),

    SEARCH_FIELD(
        storageKey = "search_field",
        displayName = "搜索输入框",
        instruction = "请点击微信顶部的搜索输入框",
        defaultXRatio = 0.50f,
        defaultYRatio = 0.087f
    ),

    FIRST_RESULT(
        storageKey = "first_result",
        displayName = "第一条搜索结果",
        instruction = "请点击第一条联系人搜索结果",
        defaultXRatio = 0.35f,
        defaultYRatio = 0.202f
    ),

    MESSAGE_FIELD(
        storageKey = "message_field",
        displayName = "消息输入框",
        instruction = "请点击聊天页面底部的消息输入框",
        defaultXRatio = 0.43f,
        defaultYRatio = 0.945f
    ),

    SEND_BUTTON(
        storageKey = "send_button",
        displayName = "发送按钮",
        instruction = "请点击绿色发送按钮；这次点击只记录位置，不会发送测试消息",
        defaultXRatio = 0.917f,
        defaultYRatio = 0.557f
    )
}

data class CalibrationPoint(
    val xRatio: Float,
    val yRatio: Float,
    val calibrated: Boolean
)

data class CalibrationSnapshot(
    val points: Map<CalibrationTarget, CalibrationPoint>,
    val lastStatus: String
) {
    val completedCount: Int
        get() = points.values.count {
            it.calibrated
        }

    val totalCount: Int
        get() = CalibrationTarget.values().size

    val isComplete: Boolean
        get() = completedCount == totalCount
}

/**
 * 每台手机独立保存自己的微信坐标。
 *
 * 用户不需要输入任何数字。无障碍服务捕获用户点击后，
 * 自动计算 xRatio = x / 屏幕宽度、yRatio = y / 屏幕高度。
 */
class CalibrationStore(
    context: Context
) {

    companion object {
        private const val PREFS_NAME =
            "mobile_agent_device_calibration"

        private const val KEY_LAST_STATUS =
            "last_status"
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun getPoint(
        target: CalibrationTarget
    ): CalibrationPoint {
        val xKey =
            "${target.storageKey}_x"

        val yKey =
            "${target.storageKey}_y"

        val calibrated =
            preferences.contains(xKey) &&
                    preferences.contains(yKey)

        return CalibrationPoint(
            xRatio =
                preferences.getFloat(
                    xKey,
                    target.defaultXRatio
                ),
            yRatio =
                preferences.getFloat(
                    yKey,
                    target.defaultYRatio
                ),
            calibrated =
                calibrated
        )
    }

    @Synchronized
    fun getSnapshot(): CalibrationSnapshot {
        val points =
            CalibrationTarget.values()
                .associateWith {
                    getPoint(it)
                }

        return CalibrationSnapshot(
            points = points,
            lastStatus =
                preferences.getString(
                    KEY_LAST_STATUS,
                    "尚未进行设备校准"
                ).orEmpty()
        )
    }

    /**
     * 一次性提交完整校准，避免校准中途取消后留下半套坐标。
     */
    @Synchronized
    fun saveAll(
        points: Map<CalibrationTarget, CalibrationPoint>,
        status: String = "设备校准完成"
    ): Boolean {
        val editor =
            preferences.edit()

        CalibrationTarget.values().forEach {
                target ->

            val point =
                points[target]
                    ?: return false

            editor.putFloat(
                "${target.storageKey}_x",
                point.xRatio.coerceIn(
                    0f,
                    1f
                )
            )

            editor.putFloat(
                "${target.storageKey}_y",
                point.yRatio.coerceIn(
                    0f,
                    1f
                )
            )
        }

        editor.putString(
            KEY_LAST_STATUS,
            status
        )

        return editor.commit()
    }

    @Synchronized
    fun setLastStatus(
        status: String
    ) {
        preferences
            .edit()
            .putString(
                KEY_LAST_STATUS,
                status
            )
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences
            .edit()
            .clear()
            .putString(
                KEY_LAST_STATUS,
                "校准数据已清除"
            )
            .apply()
    }
}
