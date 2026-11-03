package com.cyc.mobileagent.wechat

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.cyc.mobileagent.MainActivity
import com.cyc.mobileagent.agent.CalibrationPoint
import com.cyc.mobileagent.agent.CalibrationStore
import com.cyc.mobileagent.agent.CalibrationTarget
import com.cyc.mobileagent.agent.ExecutionEventType
import com.cyc.mobileagent.agent.WechatTask
import com.cyc.mobileagent.service.AgentAccessibilityService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.ArrayDeque

/**
 * 微信在无障碍服务中的完整 UI 执行器。
 *
 * 多步骤队列、坐标执行、输入、安全熔断、
 * taskId 回传和设备校准全部封装在这里。
 */
class WechatUiExecutor(
    private val service: AgentAccessibilityService,
    private val sendEvent: (
        taskId: String,
        type: String,
        message: String,
        contact: String?,
        messageIndex: Int?,
        totalMessages: Int?
    ) -> Unit
) {

    companion object {
        private const val TAG =
            "WechatUiExecutor"

        private const val WECHAT_PACKAGE =
            "com.tencent.mm"

        private const val CALIBRATION_DUMMY_TEXT =
            "校准测试"

        private const val WINDOW_CHECK_DELAY_MILLIS =
            250L

        private const val TASK_START_EVENT_GRACE_MILLIS =
            1800L
    }

    private enum class Stage {
        WAITING,
        OPENING_SEARCH,
        FOCUSING_SEARCH_FIELD,
        TYPING_SEARCH_TEXT,
        VERIFYING_SEARCH_RESULT,
        OPENING_RESULT,
        VERIFYING_CHAT_PAGE,
        FOCUSING_MESSAGE_FIELD,
        TYPING_MESSAGE,
        SENDING_MESSAGE,
        OPENING_NEXT_CONTACT,
        FINISHED
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val gson =
        Gson()

    // Android 系统截图 + 设备本地 OCR；不访问微信节点树。
    private val visualObserver by lazy { WechatVisualObserver(service) }
    private var observationSerial = 0L

    private val taskQueue =
        ArrayDeque<WechatTask>()

    private val planTotalMessages =
        mutableMapOf<String, Int>()

    private val planExecutedMessages =
        mutableMapOf<String, Int>()

    private val announcedPlanIds =
        mutableSetOf<String>()

    private val calibrationStore by lazy {
        CalibrationStore(
            service.applicationContext
        )
    }

    private val overlayWindowManager by lazy {
        service.getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager
    }

    private var stage =
        Stage.WAITING

    private var currentTask: WechatTask? =
        null

    private var currentMessageIndex =
        0

    private var calibrationActive =
        false

    private var calibrationContact =
        ""

    private var calibrationOverlay: CalibrationTouchOverlay? =
        null

    private val pendingCalibrationPoints =
        mutableMapOf<CalibrationTarget, CalibrationPoint>()

    private var ignoreWindowExitEventsUntil =
        0L

    private var lastNonImeWindowPackage: String? =
        null

    private val verifyWechatVisibilityRunnable =
        Runnable {
            if (
                isWechatExecutionActive() &&
                !isWechatWindowVisible()
            ) {
                finishTaskWithError(
                    "检测到微信已不在前台，已立即停止任务，后续点击不会继续执行"
                )
            }
        }

    fun isExecutionActive(): Boolean {
        return isWechatExecutionActive()
    }

    fun isCalibrationActive(): Boolean {
        return calibrationActive
    }

    fun isBusy(): Boolean {
        return isWechatExecutionActive() ||
            calibrationActive
    }

    fun handlePlan(
        intent: Intent
    ) {
        handleWechatPlan(
            intent
        )
    }

    fun startCalibration(
        contact: String
    ) {
        startGuidedCalibration(
            contact
        )
    }

    fun cancelCalibrationByUser() {
        cancelCalibration(
            "设备校准已取消",
            returnToApp =
                true
        )
    }

    fun cancelTask(
        taskId: String
    ) {
        cancelWechatExecution(
            requestedTaskId =
                taskId,
            reason =
                "用户手动取消了当前任务"
        )
    }

    fun onWindowEvent(
        eventPackage: String?
    ) {
        if (
            !eventPackage.isNullOrBlank() &&
            eventPackage !=
            getCurrentImePackage()
        ) {
            lastNonImeWindowPackage =
                eventPackage
        }

        if (!isWechatExecutionActive()) {
            return
        }

        if (
            SystemClock.uptimeMillis() <
            ignoreWindowExitEventsUntil
        ) {
            return
        }

        mainHandler.removeCallbacks(
            verifyWechatVisibilityRunnable
        )

        mainHandler.postDelayed(
            verifyWechatVisibilityRunnable,
            WINDOW_CHECK_DELAY_MILLIS
        )
    }

    fun interrupt(
        reason: String
    ) {
        observationSerial++
        val affectedTaskIds =
            collectActiveTaskIds()

        mainHandler.removeCallbacksAndMessages(
            null
        )

        removeCalibrationOverlay()

        calibrationActive =
            false

        pendingCalibrationPoints.clear()
        taskQueue.clear()

        currentTask =
            null

        currentMessageIndex =
            0

        clearPlanTracking()

        stage =
            Stage.WAITING

        affectedTaskIds.forEach {
            taskId ->

            sendExecutionEvent(
                taskId =
                    taskId,
                type =
                    ExecutionEventType.TASK_CANCELLED,
                message =
                    reason
            )
        }
    }

    fun shutdown() {
        observationSerial++
        mainHandler.removeCallbacksAndMessages(
            null
        )

        removeCalibrationOverlay()

        calibrationActive =
            false

        pendingCalibrationPoints.clear()
        taskQueue.clear()

        currentTask =
            null

        currentMessageIndex =
            0

        clearPlanTracking()

        stage =
            Stage.WAITING
    }

    // ---------------------------------------------------------------------
    // 引导式设备校准
    // ---------------------------------------------------------------------

    private fun startGuidedCalibration(
        contact: String
    ) {
        mainHandler.removeCallbacksAndMessages(null)
        removeCalibrationOverlay()

        taskQueue.clear()
        currentTask = null
        currentMessageIndex = 0
        clearPlanTracking()
        stage = Stage.WAITING

        calibrationActive = true
        calibrationContact = contact
        pendingCalibrationPoints.clear()

        calibrationStore.setLastStatus(
            "校准进行中：请按照微信屏幕上的提示点击"
        )

        Log.d(
            TAG,
            "开始引导式设备校准，测试联系人：$calibrationContact"
        )

        Toast.makeText(
            service,
            "微信打开后，请按照浮层提示点击",
            Toast.LENGTH_LONG
        ).show()

        mainHandler.postDelayed(
            {
                showCalibrationOverlay(
                    CalibrationTarget.SEARCH_ICON
                )
            },
            1800L
        )
    }

    private fun showCalibrationOverlay(
        target: CalibrationTarget
    ) {
        if (!calibrationActive) {
            return
        }

        removeCalibrationOverlay()

        val overlay =
            CalibrationTouchOverlay(
                context = service,
                target = target,
                stepNumber = target.ordinal + 1,
                totalSteps = CalibrationTarget.values().size,
                onTargetTapped = {
                        rawX,
                        rawY ->

                    handleCalibrationTap(
                        target = target,
                        rawX = rawX,
                        rawY = rawY
                    )
                },
                onCancel = {
                    cancelCalibration(
                        "设备校准已取消",
                        returnToApp = true
                    )
                }
            )

        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or Gravity.START

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

        try {
            overlayWindowManager.addView(
                overlay,
                layoutParams
            )

            calibrationOverlay =
                overlay

            Log.d(
                TAG,
                "显示校准步骤 ${target.ordinal + 1}：${target.displayName}"
            )
        } catch (e: Exception) {
            cancelCalibration(
                "无法显示校准浮层：${e.message ?: e.javaClass.simpleName}",
                returnToApp = true
            )
        }
    }

    private fun handleCalibrationTap(
        target: CalibrationTarget,
        rawX: Float,
        rawY: Float
    ) {
        if (!calibrationActive) {
            return
        }

        removeCalibrationOverlay()

        val (screenWidth, screenHeight) =
            getFullScreenSize()

        if (
            screenWidth <= 0 ||
            screenHeight <= 0
        ) {
            cancelCalibration(
                "无法取得屏幕尺寸",
                returnToApp = true
            )
            return
        }

        val x =
            rawX.coerceIn(
                0f,
                screenWidth.toFloat()
            )

        val y =
            rawY.coerceIn(
                0f,
                screenHeight.toFloat()
            )

        val point =
            CalibrationPoint(
                xRatio =
                    (x / screenWidth)
                        .coerceIn(0f, 1f),
                yRatio =
                    (y / screenHeight)
                        .coerceIn(0f, 1f),
                calibrated =
                    true
            )

        pendingCalibrationPoints[target] =
            point

        Log.d(
            TAG,
            "已记录 ${target.displayName}：" +
                    "xRatio=${point.xRatio}，yRatio=${point.yRatio}"
        )

        when (target) {
            CalibrationTarget.SEARCH_ICON -> {
                replayCalibrationTap(
                    x = x,
                    y = y,
                    actionName = target.displayName,
                    afterTapDelayMillis = 1100L
                ) {
                    showCalibrationOverlay(
                        CalibrationTarget.SEARCH_FIELD
                    )
                }
            }

            CalibrationTarget.SEARCH_FIELD -> {
                replayCalibrationTap(
                    x = x,
                    y = y,
                    actionName = target.displayName,
                    afterTapDelayMillis = 650L
                ) {
                    typeCalibrationTextWithRetry(
                        text = calibrationContact,
                        actionName = "校准联系人",
                        attempt = 0,
                        onCompleted = {
                            mainHandler.postDelayed(
                                {
                                    showCalibrationOverlay(
                                        CalibrationTarget.FIRST_RESULT
                                    )
                                },
                                1300L
                            )
                        }
                    )
                }
            }

            CalibrationTarget.FIRST_RESULT -> {
                replayCalibrationTap(
                    x = x,
                    y = y,
                    actionName = target.displayName,
                    afterTapDelayMillis = 1500L
                ) {
                    showCalibrationOverlay(
                        CalibrationTarget.MESSAGE_FIELD
                    )
                }
            }

            CalibrationTarget.MESSAGE_FIELD -> {
                replayCalibrationTap(
                    x = x,
                    y = y,
                    actionName = target.displayName,
                    afterTapDelayMillis = 750L
                ) {
                    typeCalibrationTextWithRetry(
                        text = CALIBRATION_DUMMY_TEXT,
                        actionName = "校准测试文字",
                        attempt = 0,
                        onCompleted = {
                            mainHandler.postDelayed(
                                {
                                    showCalibrationOverlay(
                                        CalibrationTarget.SEND_BUTTON
                                    )
                                },
                                800L
                            )
                        }
                    )
                }
            }

            CalibrationTarget.SEND_BUTTON -> {
                /*
                 * 最后一步只记录发送按钮位置，不重放点击，
                 * 因此不会把“校准测试”真正发送出去。
                 */
                mainHandler.postDelayed(
                    {
                        clearCurrentInputText()
                        finishCalibrationSuccessfully()
                    },
                    250L
                )
            }
        }
    }

    private fun replayCalibrationTap(
        x: Float,
        y: Float,
        actionName: String,
        afterTapDelayMillis: Long,
        onCompleted: () -> Unit
    ) {
        dispatchTapAbsolute(
            x = x,
            y = y,
            actionName = "校准重放：$actionName",
            onCompleted = {
                mainHandler.postDelayed(
                    {
                        onCompleted()
                    },
                    afterTapDelayMillis
                )
            },
            onFailed = {
                    errorMessage ->

                cancelCalibration(
                    errorMessage,
                    returnToApp = true
                )
            }
        )
    }

    private fun typeCalibrationTextWithRetry(
        text: String,
        actionName: String,
        attempt: Int,
        onCompleted: () -> Unit
    ) {
        commitTextWithRetry(
            text = text,
            actionName = actionName,
            attempt = attempt,
            onCompleted = onCompleted,
            onFailed = {
                    errorMessage ->

                cancelCalibration(
                    errorMessage,
                    returnToApp = true
                )
            }
        )
    }

    private fun clearCurrentInputText() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        try {
            val connection =
                service.inputMethod
                    ?.currentInputConnection
                    ?: return

            connection.performContextMenuAction(
                android.R.id.selectAll
            )

            connection.commitText(
                "",
                1,
                null
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "清除校准测试文字失败",
                e
            )
        }
    }

    private fun finishCalibrationSuccessfully() {
        if (!calibrationActive) {
            return
        }

        val saved =
            calibrationStore.saveAll(
                points = pendingCalibrationPoints,
                status = "设备校准完成：已记录 5 个微信控件"
            )

        calibrationActive =
            false

        pendingCalibrationPoints.clear()
        removeCalibrationOverlay()

        if (!saved) {
            calibrationStore.setLastStatus(
                "校准保存失败：记录不完整"
            )

            Toast.makeText(
            service,
                "校准保存失败，请重试",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
            service,
                "设备校准完成",
                Toast.LENGTH_SHORT
            ).show()

            Log.d(
                TAG,
                "设备校准完成，新的坐标已生效"
            )
        }

        mainHandler.postDelayed(
            {
                returnToMobileAgent()
            },
            600L
        )
    }

    private fun cancelCalibration(
        reason: String,
        returnToApp: Boolean
    ) {
        calibrationActive =
            false

        pendingCalibrationPoints.clear()
        removeCalibrationOverlay()

        stage =
            Stage.WAITING

        calibrationStore.setLastStatus(
            reason
        )

        Log.e(
            TAG,
            reason
        )

        Toast.makeText(
            service,
            reason,
            Toast.LENGTH_LONG
        ).show()

        if (returnToApp) {
            mainHandler.postDelayed(
                {
                    returnToMobileAgent()
                },
                500L
            )
        }
    }

    private fun removeCalibrationOverlay() {
        val overlay =
            calibrationOverlay
                ?: return

        try {
            overlayWindowManager.removeView(
                overlay
            )
        } catch (_: Exception) {
            // 浮层可能已经被系统移除。
        }

        calibrationOverlay =
            null
    }

    private fun returnToMobileAgent() {
        val intent =
            Intent(
                service,
                MainActivity::class.java
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    WechatContract.EXTRA_OPEN_CALIBRATION_PAGE,
                    true
                )
            }

        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "返回 MobileAgent 失败",
                e
            )
        }
    }

    // ---------------------------------------------------------------------
    // 多步骤微信任务队列
    // ---------------------------------------------------------------------

    private fun handleWechatPlan(
        intent: Intent
    ) {
        val broadcastTaskId =
            intent.getStringExtra(
                WechatContract.EXTRA_TASK_ID
            )
                .orEmpty()
                .trim()
                .ifBlank {
                    "wechat-${System.currentTimeMillis()}"
                }

        if (calibrationActive) {
            val message =
                "设备正在校准，暂不接收微信任务"

            Log.e(
                TAG,
                message
            )

            sendExecutionEvent(
                taskId = broadcastTaskId,
                type = ExecutionEventType.TASK_FAILED,
                message = message
            )
            return
        }

        val tasksJson =
            intent.getStringExtra(
                WechatContract.EXTRA_WECHAT_TASKS_JSON
            )
                .orEmpty()
                .trim()

        if (tasksJson.isBlank()) {
            val message =
                "微信计划为空"

            Log.e(
                TAG,
                message
            )

            sendExecutionEvent(
                taskId = broadcastTaskId,
                type = ExecutionEventType.TASK_FAILED,
                message = message
            )
            return
        }

        val tasks =
            parseWechatTasks(tasksJson)
                .filter { task ->
                    task.contact.isNotBlank() &&
                        task.messages.any {
                            it.isNotBlank()
                        }
                }
                .map { task ->
                    task.copy(
                        taskId =
                            task.taskId
                                .trim()
                                .ifBlank {
                                    broadcastTaskId
                                },
                        contact =
                            task.contact.trim(),
                        messages =
                            task.messages
                                .map {
                                    it.trim()
                                }
                                .filter {
                                    it.isNotBlank()
                                }
                    )
                }

        if (tasks.isEmpty()) {
            val message =
                "没有可执行的微信任务"

            Log.e(
                TAG,
                message
            )

            sendExecutionEvent(
                taskId = broadcastTaskId,
                type = ExecutionEventType.TASK_FAILED,
                message = message
            )
            return
        }

        ignoreWindowExitEventsUntil =
            SystemClock.uptimeMillis() +
                TASK_START_EVENT_GRACE_MILLIS

        val groupedMessageTotals =
            tasks
                .groupBy {
                    it.taskId
                }
                .mapValues {
                    (_, planTasks) ->
                    planTasks.sumOf {
                        it.messages.size
                    }
                }

        groupedMessageTotals.forEach {
                taskId,
                newMessageCount ->

            planTotalMessages[taskId] =
                (planTotalMessages[taskId] ?: 0) +
                    newMessageCount

            planExecutedMessages.putIfAbsent(
                taskId,
                0
            )

            if (announcedPlanIds.add(taskId)) {
                sendExecutionEvent(
                    taskId = taskId,
                    type = ExecutionEventType.TASK_STARTED,
                    message =
                        "已接收 ${planTotalMessages[taskId] ?: newMessageCount} 条微信消息"
                )
            }
        }

        tasks.forEach { task ->
            taskQueue.addLast(task)
        }

        Log.d(
            TAG,
            "收到微信执行计划：${tasks.size} 个联系人任务"
        )

        tasks.forEachIndexed {
                index,
                task ->

            Log.d(
                TAG,
                "任务 ${index + 1}：${task.contact}，" +
                    "${task.messages.size} 条消息，taskId=${task.taskId}"
            )
        }

        if (
            stage == Stage.WAITING ||
            stage == Stage.FINISHED
        ) {
            beginNextTask(
                initialDelayMillis =
                    2500L
            )
        } else {
            Log.d(
                TAG,
                "当前任务执行中，新任务已加入队列"
            )
        }
    }

    private fun parseWechatTasks(
        tasksJson: String
    ): List<WechatTask> {
        return try {
            val type =
                object :
                    TypeToken<List<WechatTask>>() {}
                    .type

            gson.fromJson<List<WechatTask>>(
                tasksJson,
                type
            ) ?: emptyList()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "解析微信任务计划失败",
                e
            )

            emptyList()
        }
    }

    private fun beginNextTask(
        initialDelayMillis: Long
    ) {
        observationSerial++
        val nextTask =
            taskQueue.pollFirst()

        if (nextTask == null) {
            stage =
                Stage.FINISHED

            resetWorkflowLater()
            return
        }

        currentTask =
            nextTask

        currentMessageIndex =
            0

        stage =
            Stage.OPENING_SEARCH

        Log.d(
            TAG,
            "开始联系人任务：${nextTask.contact}"
        )

        sendExecutionEvent(
            taskId = nextTask.taskId,
            type = ExecutionEventType.CONTACT_STARTED,
            message =
                "正在处理联系人 ${nextTask.contact}",
            contact =
                nextTask.contact
        )

        mainHandler.postDelayed(
            {
                clickSearchIcon()
            },
            initialDelayMillis
        )
    }

    private fun clickSearchIcon() {
        Log.d(TAG, "第一步：点击搜索图标")

        tapTarget(
            target =
                CalibrationTarget.SEARCH_ICON,
            actionName =
                "搜索图标",
            onCompleted = {
                stage =
                    Stage.FOCUSING_SEARCH_FIELD

                mainHandler.postDelayed(
                    {
                        focusSearchField()
                    },
                    900L
                )
            }
        )
    }

    private fun focusSearchField() {
        Log.d(TAG, "第二步：点击搜索输入框")

        tapTarget(
            target =
                CalibrationTarget.SEARCH_FIELD,
            actionName =
                "搜索输入框",
            onCompleted = {
                stage =
                    Stage.TYPING_SEARCH_TEXT

                mainHandler.postDelayed(
                    {
                        typeTaskTextWithRetry(
                            text =
                                requireCurrentTask()
                                    .contact,
                            actionName =
                                "联系人名称",
                            attempt =
                                0,
                            onCompleted = {
                                stage =
                                    Stage.VERIFYING_SEARCH_RESULT

                                Log.d(
                                    TAG,
                                    "联系人搜索词输入指令已完成，等待验证搜索结果"
                                )

                                mainHandler.postDelayed(
                                    {
                                        verifyContactSearchResult(attempt = 0)
                                    },
                                    1800L
                                )
                            }
                        )
                    },
                    800L
                )
            }
        )
    }

    /** 搜索后拍摄 Android 屏幕并运行本地 OCR。未确认联系人时绝不点击。 */
    private fun verifyContactSearchResult(attempt: Int) {
        if (stage != Stage.VERIFYING_SEARCH_RESULT || !isWechatExecutionActive()) return
        val task = requireCurrentTask()
        val contact = task.contact
        val taskId = task.taskId
        val requestId = ++observationSerial
        Log.d(TAG, "开始截图验证搜索结果：attempt=$attempt")

        // OCR 是异步任务：超时必须熔断，旧截图回调不能影响取消后的新任务。
        mainHandler.postDelayed({
            if (stage == Stage.VERIFYING_SEARCH_RESULT &&
                observationSerial == requestId && currentTask?.taskId == taskId
            ) {
                observationSerial++
                finishTaskWithError("微信搜索截图/OCR超时，已停止任务，未点击联系人")
            }
        }, 12000L)

        visualObserver.verifySearch(contact) { observation ->
            if (stage != Stage.VERIFYING_SEARCH_RESULT ||
                observationSerial != requestId || currentTask?.taskId != taskId ||
                currentTask?.contact != contact
            ) return@verifySearch

            Log.d(TAG, "搜索OCR：state=${observation.state}，${observation.diagnostic}")
            when (observation.state) {
                WechatVisualObserver.SearchState.FOUND -> {
                    val xRatio = observation.xRatio
                    val yRatio = observation.yRatio
                    if (xRatio == null || yRatio == null) {
                        finishTaskWithError("视觉验证没有提供可信联系人坐标，已停止任务")
                        return@verifySearch
                    }
                    stage = Stage.OPENING_RESULT
                    // 手势执行依旧使用原有坐标模块。坐标来自实际截图中确认的目标姓名。
                    tapByRatio(
                        xRatio = xRatio,
                        yRatio = yRatio,
                        actionName = "OCR确认的微信联系人结果",
                        onCompleted = { scheduleChatPageVerification() }
                    )
                }
                WechatVisualObserver.SearchState.NOT_FOUND -> {
                    if (attempt < 1) {
                        mainHandler.postDelayed({ verifyContactSearchResult(attempt + 1) }, 650L)
                    } else {
                        finishTaskWithError("未在微信搜索结果中找到联系人：$contact，任务已停止；未点击、输入或发送消息")
                    }
                }
                WechatVisualObserver.SearchState.UNKNOWN -> {
                    if (attempt < 2) {
                        mainHandler.postDelayed({ verifyContactSearchResult(attempt + 1) }, 650L)
                    } else {
                        finishTaskWithError("微信搜索结果无法可靠识别：$contact，任务已停止；${observation.diagnostic}")
                    }
                }
            }
        }
    }

    private fun scheduleChatPageVerification() {
        if (stage != Stage.OPENING_RESULT || !isWechatExecutionActive()) return
        stage = Stage.VERIFYING_CHAT_PAGE
        mainHandler.postDelayed({ verifyChatPage(attempt = 0) }, 900L)
    }

    /** 进入聊天页后再次截图；仅手势完成绝不等价于进入了正确聊天。 */
    private fun verifyChatPage(attempt: Int) {
        if (stage != Stage.VERIFYING_CHAT_PAGE || !isWechatExecutionActive()) return
        val task = requireCurrentTask()
        val contact = task.contact
        val taskId = task.taskId
        val requestId = ++observationSerial

        mainHandler.postDelayed({
            if (stage == Stage.VERIFYING_CHAT_PAGE &&
                observationSerial == requestId && currentTask?.taskId == taskId
            ) {
                observationSerial++
                finishTaskWithError("微信聊天页视觉验证超时，任务已停止；未输入或发送消息")
            }
        }, 12000L)

        visualObserver.verifyChat(contact) { observation ->
            if (stage != Stage.VERIFYING_CHAT_PAGE ||
                observationSerial != requestId || currentTask?.taskId != taskId ||
                currentTask?.contact != contact
            ) return@verifyChat

            Log.d(TAG, "聊天页OCR：${observation.diagnostic}")
            if (observation.confirmed) {
                stage = Stage.FOCUSING_MESSAGE_FIELD
                mainHandler.postDelayed({ focusMessageField() }, 250L)
            } else if (attempt < 2) {
                mainHandler.postDelayed({ verifyChatPage(attempt + 1) }, 600L)
            } else {
                finishTaskWithError(
                    "无法通过截图确认进入 $contact 的聊天页，任务已停止；未输入或发送消息"
                )
            }
        }
    }

    private fun focusMessageField() {
        Log.d(TAG, "第四步：点击消息输入框")

        tapTarget(
            target =
                CalibrationTarget.MESSAGE_FIELD,
            actionName =
                "消息输入框",
            onCompleted = {
                stage =
                    Stage.TYPING_MESSAGE

                mainHandler.postDelayed(
                    {
                        typeCurrentMessage()
                    },
                    900L
                )
            }
        )
    }

    private fun typeCurrentMessage() {
        val task =
            requireCurrentTask()

        val message =
            task.messages
                .getOrNull(
                    currentMessageIndex
                )

        if (message == null) {
            finishCurrentContactTask()
            return
        }

        Log.d(
            TAG,
            "准备输入第 ${currentMessageIndex + 1}/" +
                    "${task.messages.size} 条消息"
        )

        val nextGlobalMessageIndex =
            (planExecutedMessages[task.taskId] ?: 0) +
                1

        sendExecutionEvent(
            taskId = task.taskId,
            type = ExecutionEventType.MESSAGE_STARTED,
            message =
                "正在执行第 $nextGlobalMessageIndex/" +
                    "${planTotalMessages[task.taskId] ?: task.messages.size} 条消息",
            contact =
                task.contact,
            messageIndex =
                nextGlobalMessageIndex,
            totalMessages =
                planTotalMessages[task.taskId]
                    ?: task.messages.size
        )

        typeTaskTextWithRetry(
            text = message,
            actionName = "微信消息",
            attempt = 0,
            onCompleted = {
                Log.d(
                    TAG,
                    "消息已写入输入框：$message"
                )

                scheduleClickSend()
            }
        )
    }

    private fun scheduleClickSend() {
        stage =
            Stage.SENDING_MESSAGE

        Log.d(
            TAG,
            "第五步：准备点击微信发送按钮"
        )

        mainHandler.postDelayed(
            {
                tapTarget(
                    target =
                        CalibrationTarget.SEND_BUTTON,
                    actionName =
                        "微信发送按钮",
                    onCompleted = {
                        Log.d(
                            TAG,
                            "发送按钮点击已完成（未验证实际发送或送达）"
                        )

                        onCurrentMessageSent()
                    }
                )
            },
            900L
        )
    }

    private fun onCurrentMessageSent() {
        val task =
            requireCurrentTask()

        val executedMessageCount =
            (planExecutedMessages[task.taskId] ?: 0) +
                1

        planExecutedMessages[task.taskId] =
            executedMessageCount

        sendExecutionEvent(
            taskId = task.taskId,
            type = ExecutionEventType.MESSAGE_EXECUTED,
            message =
                "已执行第 $executedMessageCount/" +
                    "${planTotalMessages[task.taskId] ?: task.messages.size} 条消息",
            contact =
                task.contact,
            messageIndex =
                executedMessageCount,
            totalMessages =
                planTotalMessages[task.taskId]
                    ?: task.messages.size
        )

        currentMessageIndex +=
            1

        if (
            currentMessageIndex <
            task.messages.size
        ) {
            stage =
                Stage.TYPING_MESSAGE

            Log.d(
                TAG,
                "同一联系人还有下一条消息，继续发送"
            )

            mainHandler.postDelayed(
                {
                    typeCurrentMessage()
                },
                650L
            )
        } else {
            finishCurrentContactTask()
        }
    }

    private fun finishCurrentContactTask() {
        val finishedTask =
            currentTask

        val finishedContact =
            finishedTask
                ?.contact
                .orEmpty()

        val finishedTaskId =
            finishedTask
                ?.taskId
                .orEmpty()

        Log.d(
            TAG,
            "联系人任务完成：$finishedContact"
        )

        currentTask =
            null

        currentMessageIndex =
            0

        val nextTask =
            taskQueue.peekFirst()

        if (
            finishedTaskId.isNotBlank() &&
            (
                nextTask == null ||
                    nextTask.taskId != finishedTaskId
            )
        ) {
            completePlan(
                taskId =
                    finishedTaskId
            )
        }

        if (nextTask == null) {
            stage =
                Stage.FINISHED

            resetWorkflowLater()
            return
        }

        stage =
            Stage.OPENING_NEXT_CONTACT

        Log.d(
            TAG,
            "准备执行下一个联系人任务"
        )

        openWechatHomeForNextTask()
    }

    private fun openWechatHomeForNextTask() {
        val launchIntent =
            service.packageManager
                .getLaunchIntentForPackage(
                    WECHAT_PACKAGE
                )

        if (launchIntent == null) {
            finishTaskWithError(
                "无法重新打开微信首页"
            )
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        try {
            ignoreWindowExitEventsUntil =
                SystemClock.uptimeMillis() + 1200L

            service.startActivity(
                launchIntent
            )
        } catch (e: Exception) {
            finishTaskWithError(
                "重新打开微信首页失败：" +
                        (e.message
                            ?: e.javaClass.simpleName)
            )
            return
        }

        mainHandler.postDelayed(
            {
                beginNextTask(
                    initialDelayMillis =
                        700L
                )
            },
            1200L
        )
    }

    private fun typeTaskTextWithRetry(
        text: String,
        actionName: String,
        attempt: Int,
        onCompleted: () -> Unit
    ) {
        commitTextWithRetry(
            text = text,
            actionName = actionName,
            attempt = attempt,
            onCompleted = onCompleted,
            onFailed = {
                    errorMessage ->

                finishTaskWithError(
                    errorMessage
                )
            }
        )
    }

    private fun commitTextWithRetry(
        text: String,
        actionName: String,
        attempt: Int,
        onCompleted: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (
            !ensureWechatVisibleForAction(
                actionName = "$actionName 输入",
                onFailed = onFailed
            )
        ) {
            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            onFailed(
                "$actionName 输入失败：系统低于 Android 13"
            )
            return
        }

        val inputConnection =
            service.inputMethod
                ?.currentInputConnection

        if (inputConnection == null) {
            if (attempt < 10) {
                Log.d(
                    TAG,
                    "$actionName 输入连接未建立，" +
                            "重试 ${attempt + 1}/10"
                )

                mainHandler.postDelayed(
                    {
                        commitTextWithRetry(
                            text = text,
                            actionName = actionName,
                            attempt = attempt + 1,
                            onCompleted = onCompleted,
                            onFailed = onFailed
                        )
                    },
                    300L
                )
            } else {
                onFailed(
                    "$actionName 输入失败：无法取得 InputConnection"
                )
            }

            return
        }

        Log.d(
            TAG,
            "开始输入 $actionName：$text"
        )

        inputConnection.commitText(
            text,
            1,
            null
        )

        Log.d(
            TAG,
            "$actionName 输入命令已发送"
        )

        onCompleted()
    }

    private fun tapTarget(
        target: CalibrationTarget,
        actionName: String,
        onCompleted: () -> Unit
    ) {
        val point =
            calibrationStore.getPoint(
                target
            )

        Log.d(
            TAG,
            "$actionName 使用" +
                    if (point.calibrated) {
                        "设备校准坐标"
                    } else {
                        "默认坐标"
                    }
        )

        tapByRatio(
            xRatio = point.xRatio,
            yRatio = point.yRatio,
            actionName = actionName,
            onCompleted = onCompleted
        )
    }

    private fun tapByRatio(
        xRatio: Float,
        yRatio: Float,
        actionName: String,
        onCompleted: () -> Unit
    ) {
        val (screenWidth, screenHeight) =
            getFullScreenSize()

        val targetX =
            screenWidth * xRatio

        val targetY =
            screenHeight * yRatio

        dispatchTapAbsolute(
            x = targetX,
            y = targetY,
            actionName = actionName,
            onCompleted = onCompleted,
            onFailed = {
                    errorMessage ->

                finishTaskWithError(
                    errorMessage
                )
            }
        )
    }

    private fun dispatchTapAbsolute(
        x: Float,
        y: Float,
        actionName: String,
        onCompleted: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (
            !ensureWechatVisibleForAction(
                actionName = actionName,
                onFailed = onFailed
            )
        ) {
            return
        }

        val path =
            Path().apply {
                moveTo(x, y)
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0L,
                80L
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        Log.d(
            TAG,
            "$actionName 坐标：x=$x，y=$y"
        )

        val accepted =
            service.dispatchGesture(
                gesture,
                object : GestureResultCallback() {

                    override fun onCompleted(
                        gestureDescription: GestureDescription?
                    ) {
                        super.onCompleted(
                            gestureDescription
                        )

                        Log.d(
                            TAG,
                            "$actionName 点击完成"
                        )

                        if (
                            !calibrationActive &&
                            !isWechatExecutionActive()
                        ) {
                            Log.d(
                                TAG,
                                "$actionName 的迟到回调已忽略"
                            )
                            return
                        }

                        onCompleted()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        super.onCancelled(
                            gestureDescription
                        )

                        if (
                            !calibrationActive &&
                            !isWechatExecutionActive()
                        ) {
                            Log.d(
                                TAG,
                                "$actionName 的迟到取消回调已忽略"
                            )
                            return
                        }

                        onFailed(
                            "$actionName 点击被系统取消"
                        )
                    }
                },
                null
            )

        if (!accepted) {
            if (
                calibrationActive ||
                isWechatExecutionActive()
            ) {
                onFailed(
                    "系统没有接受 $actionName 点击手势"
                )
            }
        }
    }

    /**
     * 当前是否存在正在执行的微信任务。
     * 校准流程不走这里，校准有自己的取消和错误处理。
     */
    private fun isWechatExecutionActive(): Boolean {
        return !calibrationActive &&
                currentTask != null &&
                stage != Stage.WAITING &&
                stage != Stage.FINISHED
    }

    /**
     * 微信即使不暴露可点击节点，AccessibilityWindowInfo 通常仍然能提供窗口所属包名。
     * 输入法弹出时，当前活跃窗口可能是输入法，因此不能只看 rootInActiveWindow；
     * 只要可见窗口列表中仍有微信窗口，就认为微信仍在屏幕上。
     */
    private fun isWechatWindowVisible(): Boolean {
        val imePackage =
            getCurrentImePackage()

        val activeRootPackage =
            try {
                service.rootInActiveWindow
                    ?.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        // 当前活跃窗口明确是微信。
        if (activeRootPackage == WECHAT_PACKAGE) {
            return true
        }

        // 当前活跃窗口明确属于另一个普通 App，直接判定已离开微信。
        // 输入法窗口除外，因为键盘弹出时微信仍然在后面可见。
        if (
            !activeRootPackage.isNullOrBlank() &&
            activeRootPackage != imePackage &&
            activeRootPackage != service.packageName
        ) {
            return false
        }

        val wechatExistsInVisibleWindows =
            try {
                service.windows.any { window ->
                    window.root
                        ?.packageName
                        ?.toString() == WECHAT_PACKAGE
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "检查微信窗口失败",
                    e
                )
                false
            }

        if (wechatExistsInVisibleWindows) {
            return true
        }

        // 某些微信版本不给窗口 root，但窗口切换事件仍会提供包名。
        // 因此最后使用最近一次非输入法窗口作为兜底。
        return lastNonImeWindowPackage == WECHAT_PACKAGE
    }

    private fun getCurrentImePackage(): String? {
        return try {
            Settings.Secure.getString(
                service.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "读取当前输入法包名失败",
                e
            )
            null
        }
    }

    /**
     * 所有坐标点击和文字输入的总安全闸。
     * 一旦微信已经不可见，本次操作不会下发，并通过原有失败通道清空队列。
     */
    private fun ensureWechatVisibleForAction(
        actionName: String,
        onFailed: (String) -> Unit
    ): Boolean {
        if (isWechatWindowVisible()) {
            return true
        }

        val message =
            "已阻止 $actionName：微信不在前台，任务已停止"

        Log.e(
            TAG,
            message
        )

        onFailed(message)
        return false
    }

    private fun requireCurrentTask():
            WechatTask {

        return currentTask
            ?: throw IllegalStateException(
                "当前没有微信任务"
            )
    }

    private fun completePlan(
        taskId: String
    ) {
        val totalMessages =
            planTotalMessages[taskId]
                ?: planExecutedMessages[taskId]
                ?: 0

        val executedMessages =
            planExecutedMessages[taskId]
                ?: 0

        Log.d(
            TAG,
            "微信任务执行完成：taskId=$taskId，" +
                "$executedMessages/$totalMessages"
        )

        sendExecutionEvent(
            taskId = taskId,
            type = ExecutionEventType.TASK_COMPLETED,
            message =
                "微信操作流程已执行完毕：$executedMessages/$totalMessages 条消息已执行（当前未验证微信实际送达）",
            messageIndex =
                executedMessages,
            totalMessages =
                totalMessages
        )

        removePlanTracking(
            taskId
        )
    }

    private fun finishTaskWithError(
        errorMessage: String
    ) {
        val affectedTaskIds =
            collectActiveTaskIds()

        val cancelled =
            errorMessage.contains(
                "微信不在前台"
            ) ||
                errorMessage.contains(
                    "检测到微信已不在前台"
                )

        stopAllWechatExecutionState()

        Log.e(
            TAG,
            errorMessage
        )

        affectedTaskIds.forEach { taskId ->
            sendExecutionEvent(
                taskId = taskId,
                type =
                    if (cancelled) {
                        ExecutionEventType.TASK_CANCELLED
                    } else {
                        ExecutionEventType.TASK_FAILED
                    },
                message =
                    errorMessage
            )
        }

        Toast.makeText(
            service,
            errorMessage,
            Toast.LENGTH_LONG
        ).show()

        resetWorkflowLater()
    }

    private fun cancelWechatExecution(
        requestedTaskId: String,
        reason: String
    ) {
        val activeTaskIds =
            collectActiveTaskIds()

        val affectedTaskIds =
            if (activeTaskIds.isNotEmpty()) {
                activeTaskIds
            } else if (requestedTaskId.isNotBlank()) {
                listOf(
                    requestedTaskId
                )
            } else {
                emptyList()
            }

        stopAllWechatExecutionState()

        affectedTaskIds.forEach { taskId ->
            sendExecutionEvent(
                taskId = taskId,
                type = ExecutionEventType.TASK_CANCELLED,
                message = reason
            )
        }

        Log.d(
            TAG,
            reason
        )

        Toast.makeText(
            service,
            reason,
            Toast.LENGTH_SHORT
        ).show()

        resetWorkflowLater()
    }

    private fun stopAllWechatExecutionState() {
        observationSerial++
        // 清除所有尚未执行的 postDelayed，防止任务终止后继续按旧坐标点击。
        mainHandler.removeCallbacksAndMessages(null)

        stage =
            Stage.FINISHED

        taskQueue.clear()
        currentTask = null
        currentMessageIndex = 0
        clearPlanTracking()
    }

    private fun collectActiveTaskIds(): List<String> {
        val ids =
            linkedSetOf<String>()

        currentTask
            ?.taskId
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                ids.add(it)
            }

        taskQueue.forEach { task ->
            if (task.taskId.isNotBlank()) {
                ids.add(
                    task.taskId
                )
            }
        }

        ids.addAll(
            announcedPlanIds
        )

        return ids.toList()
    }

    private fun removePlanTracking(
        taskId: String
    ) {
        planTotalMessages.remove(
            taskId
        )

        planExecutedMessages.remove(
            taskId
        )

        announcedPlanIds.remove(
            taskId
        )
    }

    private fun clearPlanTracking() {
        planTotalMessages.clear()
        planExecutedMessages.clear()
        announcedPlanIds.clear()
    }

    private fun sendExecutionEvent(
        taskId: String,
        type: String,
        message: String,
        contact: String? = null,
        messageIndex: Int? = null,
        totalMessages: Int? = null
    ) {
        sendEvent(
            taskId,
            type,
            message,
            contact,
            messageIndex,
            totalMessages
        )
    }

    private fun resetWorkflowLater() {
        mainHandler.postDelayed(
            {
                stage =
                    Stage.WAITING

                Log.d(
                    TAG,
                    "已恢复等待下一条 AI 命令"
                )
            },
            1500L
        )
    }

    private fun getFullScreenSize():
            Pair<Int, Int> {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            val windowManager =
                service.getSystemService(
                    WindowManager::class.java
                )

            val bounds =
                windowManager
                    .maximumWindowMetrics
                    .bounds

            Pair(
                bounds.width(),
                bounds.height()
            )
        } else {
            val metrics =
                service.resources.displayMetrics

            Pair(
                metrics.widthPixels,
                metrics.heightPixels
            )
        }
    }



    /**
     * 全屏透明校准浮层。
     *
     * 用户看到微信原页面，只需点击提示中的目标控件。
     * 浮层会截获这一次点击，记录屏幕坐标，再由服务决定是否重放。
     */
    private class CalibrationTouchOverlay(
        context: Context,
        private val target: CalibrationTarget,
        private val stepNumber: Int,
        private val totalSteps: Int,
        private val onTargetTapped: (
            rawX: Float,
            rawY: Float
        ) -> Unit,
        private val onCancel: () -> Unit
    ) : View(context) {

        private val density =
            resources.displayMetrics.density

        private val bannerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    Color.argb(
                        235,
                        20,
                        20,
                        24
                    )
            }

        private val titlePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f * density
                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
            }

        private val bodyPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    Color.rgb(
                        225,
                        225,
                        235
                    )
                textSize = 15f * density
            }

        private val cancelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    Color.rgb(
                        255,
                        120,
                        120
                    )
                textSize = 14f * density
                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
            }

        private val bannerRect =
            RectF()

        private val cancelRect =
            RectF()

        init {
            setBackgroundColor(
                Color.TRANSPARENT
            )

            isClickable = true
        }

        override fun onDraw(
            canvas: Canvas
        ) {
            super.onDraw(canvas)

            canvas.drawColor(
                Color.argb(
                    20,
                    0,
                    0,
                    0
                )
            )

            val margin =
                14f * density

            val bannerHeight =
                128f * density

            val placeAtTop =
                target == CalibrationTarget.MESSAGE_FIELD ||
                        target == CalibrationTarget.SEND_BUTTON

            val top =
                if (placeAtTop) {
                    margin + 24f * density
                } else {
                    height - bannerHeight -
                            margin - 22f * density
                }

            bannerRect.set(
                margin,
                top,
                width - margin,
                top + bannerHeight
            )

            canvas.drawRoundRect(
                bannerRect,
                20f * density,
                20f * density,
                bannerPaint
            )

            val textLeft =
                bannerRect.left +
                        18f * density

            canvas.drawText(
                "设备校准 $stepNumber/$totalSteps：${target.displayName}",
                textLeft,
                bannerRect.top +
                        34f * density,
                titlePaint
            )

            val instructionLines =
                splitInstruction(
                    target.instruction
                )

            instructionLines.forEachIndexed {
                    index,
                    line ->

                canvas.drawText(
                    line,
                    textLeft,
                    bannerRect.top +
                            (66f + index * 23f) * density,
                    bodyPaint
                )
            }

            val cancelWidth =
                58f * density

            val cancelHeight =
                34f * density

            cancelRect.set(
                bannerRect.right -
                        cancelWidth -
                        12f * density,
                bannerRect.top +
                        10f * density,
                bannerRect.right -
                        12f * density,
                bannerRect.top +
                        10f * density +
                        cancelHeight
            )

            canvas.drawText(
                "取消",
                cancelRect.left +
                        10f * density,
                cancelRect.bottom -
                        9f * density,
                cancelPaint
            )
        }

        override fun onTouchEvent(
            event: MotionEvent
        ): Boolean {
            if (
                event.action !=
                MotionEvent.ACTION_UP
            ) {
                return true
            }

            if (
                cancelRect.contains(
                    event.x,
                    event.y
                )
            ) {
                onCancel()
                return true
            }

            if (
                bannerRect.contains(
                    event.x,
                    event.y
                )
            ) {
                return true
            }

            onTargetTapped(
                event.rawX,
                event.rawY
            )

            return true
        }

        private fun splitInstruction(
            text: String
        ): List<String> {
            if (text.length <= 20) {
                return listOf(text)
            }

            val breakIndex =
                (text.length / 2)
                    .coerceIn(
                        12,
                        22
                    )

            return listOf(
                text.substring(
                    0,
                    breakIndex
                ),
                text.substring(
                    breakIndex
                )
            )
        }
    }

}
