package com.cyc.mobileagent.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 通用 Android UI 能力探针。
 *
 * 目标不是“为每个 App 写死坐标”，而是：
 *
 * 1. 先看语义：editable / ACTION_SET_TEXT / clickable / label / viewId。
 * 2. 语义不足时看结构：位置、尺寸、与已知输入区域的相对关系。
 * 3. 仍然无法确定时输出诊断摘要，让 Driver 再决定是否进入人工校准。
 *
 * 这个类不执行点击，不产生外部副作用。
 * 它只负责观察和给候选节点打分。
 */
object AdaptiveUiProbe {

    data class Candidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val label: String,
        val viewId: String,
        val score: Int,
        val reasons: List<String>
    )

    private data class Snapshot(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val label: String,
        val viewId: String
    )

    fun findInputCandidate(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        keywords: List<String>
    ): Candidate? {
        if (
            screenWidth <= 0 ||
            screenHeight <= 0
        ) {
            return null
        }

        return collect(
            root
        )
            .mapNotNull {
                snapshot ->

                val node =
                    snapshot.node

                if (
                    !node.isVisibleToUser ||
                    !node.isEnabled ||
                    snapshot.bounds.width() <= 0 ||
                    snapshot.bounds.height() <= 0
                ) {
                    return@mapNotNull null
                }

                val normalized =
                    normalize(
                        snapshot.label +
                            " " +
                            snapshot.viewId
                    )

                var score =
                    0

                val reasons =
                    mutableListOf<String>()

                if (node.isEditable) {
                    score +=
                        1600

                    reasons.add(
                        "editable"
                    )
                }

                if (
                    hasAction(
                        node,
                        AccessibilityNodeInfo
                            .ACTION_SET_TEXT
                    )
                ) {
                    score +=
                        1400

                    reasons.add(
                        "setText"
                    )
                }

                if (
                    node.className
                        ?.toString()
                        ?.contains(
                            "EditText",
                            ignoreCase =
                                true
                        ) ==
                    true
                ) {
                    score +=
                        900

                    reasons.add(
                        "editClass"
                    )
                }

                if (
                    keywords.any {
                        keyword ->

                        normalized.contains(
                            normalize(
                                keyword
                            )
                        )
                    }
                ) {
                    score +=
                        1100

                    reasons.add(
                        "inputLabel"
                    )
                }

                if (node.isFocusable) {
                    score +=
                        220

                    reasons.add(
                        "focusable"
                    )
                }

                if (
                    node.isClickable ||
                    hasAction(
                        node,
                        AccessibilityNodeInfo
                            .ACTION_CLICK
                    )
                ) {
                    score +=
                        130

                    reasons.add(
                        "clickable"
                    )
                }

                val centerY =
                    snapshot.bounds
                        .centerY()

                if (
                    centerY >
                    screenHeight *
                        0.48f
                ) {
                    score +=
                        240

                    reasons.add(
                        "lowerHalf"
                    )
                }

                val widthRatio =
                    snapshot.bounds
                        .width()
                        .toFloat() /
                        screenWidth

                if (
                    widthRatio in
                    0.35f..0.98f
                ) {
                    score +=
                        180

                    reasons.add(
                        "composerWidth"
                    )
                }

                val heightRatio =
                    snapshot.bounds
                        .height()
                        .toFloat() /
                        screenHeight

                if (
                    heightRatio in
                    0.025f..0.30f
                ) {
                    score +=
                        120

                    reasons.add(
                        "composerHeight"
                    )
                }

                /*
                 * 没有任何输入语义时，不允许仅凭“在下半屏”就把巨大容器
                 * 当作输入框。结构兜底至少需要 focus/click 或合理标签。
                 */
                val hasInteractionHint =
                    node.isEditable ||
                        node.isFocusable ||
                        node.isClickable ||
                        hasAction(
                            node,
                            AccessibilityNodeInfo
                                .ACTION_SET_TEXT
                        ) ||
                        hasAction(
                            node,
                            AccessibilityNodeInfo
                                .ACTION_CLICK
                        ) ||
                        reasons.contains(
                            "inputLabel"
                        )

                if (!hasInteractionHint) {
                    return@mapNotNull null
                }

                Candidate(
                    node =
                        node,
                    bounds =
                        Rect(
                            snapshot.bounds
                        ),
                    label =
                        snapshot.label,
                    viewId =
                        snapshot.viewId,
                    score =
                        score,
                    reasons =
                        reasons
                )
            }
            .filter {
                it.score >=
                    350
            }
            .maxByOrNull {
                it.score
            }
    }

    fun findSendCandidate(
        root: AccessibilityNodeInfo,
        anchor: Rect?,
        screenWidth: Int,
        screenHeight: Int,
        keywords: List<String>
    ): Candidate? {
        if (
            screenWidth <= 0 ||
            screenHeight <= 0
        ) {
            return null
        }

        return collect(
            root
        )
            .mapNotNull {
                snapshot ->

                val node =
                    snapshot.node

                if (
                    !node.isVisibleToUser ||
                    !node.isEnabled ||
                    snapshot.bounds.width() <= 0 ||
                    snapshot.bounds.height() <= 0
                ) {
                    return@mapNotNull null
                }

                val clickable =
                    node.isClickable ||
                        hasAction(
                            node,
                            AccessibilityNodeInfo
                                .ACTION_CLICK
                        )

                if (!clickable) {
                    return@mapNotNull null
                }

                val normalized =
                    normalize(
                        snapshot.label +
                            " " +
                            snapshot.viewId
                    )

                var score =
                    0

                val reasons =
                    mutableListOf<String>()

                if (
                    keywords.any {
                        keyword ->

                        normalized.contains(
                            normalize(
                                keyword
                            )
                        )
                    }
                ) {
                    score +=
                        2000

                    reasons.add(
                        "sendLabel"
                    )
                }

                if (anchor != null) {
                    val bounds =
                        snapshot.bounds

                    val verticalDistance =
                        abs(
                            bounds.centerY() -
                                anchor.centerY()
                        )

                    val tolerance =
                        maxOf(
                            anchor.height() * 2,
                            220
                        )

                    if (
                        verticalDistance <=
                        tolerance
                    ) {
                        score +=
                            620

                        reasons.add(
                            "nearInputY"
                        )
                    }

                    if (
                        bounds.centerX() >
                        anchor.centerX()
                    ) {
                        score +=
                            520

                        reasons.add(
                            "rightOfInput"
                        )
                    }

                    if (
                        bounds.left >=
                        anchor.right -
                            maxOf(
                                anchor.height(),
                                80
                            )
                    ) {
                        score +=
                            300

                        reasons.add(
                            "nearInputRight"
                        )
                    }
                }

                val widthRatio =
                    snapshot.bounds
                        .width()
                        .toFloat() /
                        screenWidth

                val heightRatio =
                    snapshot.bounds
                        .height()
                        .toFloat() /
                        screenHeight

                /*
                 * 发送按钮通常是小型方形/圆形控件。
                 * 巨大容器即便 clickable，也不应该作为发送候选。
                 */
                if (
                    widthRatio in
                    0.035f..0.22f &&
                    heightRatio in
                    0.025f..0.16f
                ) {
                    score +=
                        340

                    reasons.add(
                        "buttonSize"
                    )
                } else if (
                    widthRatio > 0.50f ||
                    heightRatio > 0.30f
                ) {
                    score -=
                        900

                    reasons.add(
                        "tooLarge"
                    )
                }

                if (
                    snapshot.bounds.centerX() >
                    screenWidth *
                        0.65f
                ) {
                    score +=
                        220

                    reasons.add(
                        "rightSide"
                    )
                }

                if (
                    snapshot.bounds.centerY() >
                    screenHeight *
                        0.45f
                ) {
                    score +=
                        120

                    reasons.add(
                        "lowerArea"
                    )
                }

                /*
                 * 常见非发送控件降权。
                 * 这里只处理非常泛化的 UI 词，不绑定具体 App。
                 */
                val negativeKeywords =
                    listOf(
                        "menu",
                        "navigation",
                        "close",
                        "back",
                        "add",
                        "attach",
                        "camera",
                        "microphone",
                        "voice",
                        "history",
                        "设置",
                        "菜单",
                        "返回",
                        "关闭",
                        "添加",
                        "附件",
                        "相机",
                        "语音",
                        "麦克风"
                    )

                if (
                    negativeKeywords.any {
                        normalized.contains(
                            normalize(
                                it
                            )
                        )
                    }
                ) {
                    score -=
                        900

                    reasons.add(
                        "negativeLabel"
                    )
                }

                Candidate(
                    node =
                        node,
                    bounds =
                        Rect(
                            snapshot.bounds
                        ),
                    label =
                        snapshot.label,
                    viewId =
                        snapshot.viewId,
                    score =
                        score,
                    reasons =
                        reasons
                )
            }
            .filter {
                it.score >=
                    500
            }
            .maxByOrNull {
                it.score
            }
    }

    /**
     * 给运行记录输出一个紧凑诊断。
     *
     * 只保留最高价值的候选，避免把整棵 Accessibility Tree 塞进 UI。
     */
    fun buildCompactReport(
        root: AccessibilityNodeInfo?,
        anchor: Rect?,
        screenWidth: Int,
        screenHeight: Int,
        inputKeywords: List<String>,
        sendKeywords: List<String>
    ): String {
        if (root == null) {
            return "UI诊断：当前没有可读取的 Accessibility 根节点"
        }

        val snapshots =
            collect(
                root
            )

        val visible =
            snapshots.filter {
                it.node.isVisibleToUser
            }

        val editableCount =
            visible.count {
                it.node.isEditable ||
                    hasAction(
                        it.node,
                        AccessibilityNodeInfo
                            .ACTION_SET_TEXT
                    )
            }

        val clickableCount =
            visible.count {
                it.node.isClickable ||
                    hasAction(
                        it.node,
                        AccessibilityNodeInfo
                            .ACTION_CLICK
                    )
            }

        val input =
            findInputCandidate(
                root =
                    root,
                screenWidth =
                    screenWidth,
                screenHeight =
                    screenHeight,
                keywords =
                    inputKeywords
            )

        val send =
            findSendCandidate(
                root =
                    root,
                anchor =
                    anchor,
                screenWidth =
                    screenWidth,
                screenHeight =
                    screenHeight,
                keywords =
                    sendKeywords
            )

        fun describe(
            title: String,
            candidate: Candidate?
        ): String {
            if (candidate == null) {
                return "$title=none"
            }

            val label =
                candidate.label
                    .replace(
                        '\n',
                        ' '
                    )
                    .trim()
                    .take(
                        80
                    )
                    .ifBlank {
                        "<no-label>"
                    }

            return "$title=" +
                "score:${candidate.score}," +
                "label:$label," +
                "bounds:${candidate.bounds}," +
                "reasons:${candidate.reasons.joinToString("/")}"
        }

        return buildString {
            append(
                "UI诊断：visible="
            )
            append(
                visible.size
            )
            append(
                ", editable="
            )
            append(
                editableCount
            )
            append(
                ", clickable="
            )
            append(
                clickableCount
            )
            append(
                "；"
            )
            append(
                describe(
                    "input",
                    input
                )
            )
            append(
                "；"
            )
            append(
                describe(
                    "send",
                    send
                )
            )
        }
    }

    private fun collect(
        root: AccessibilityNodeInfo
    ): List<Snapshot> {
        val result =
            mutableListOf<Snapshot>()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 900
        ) {
            val node =
                queue.removeFirst()

            visited +=
                1

            val bounds =
                Rect()

            try {
                node.getBoundsInScreen(
                    bounds
                )
            } catch (_: Exception) {
                // 保留空 bounds。
            }

            val label =
                buildString {
                    node.text
                        ?.toString()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            append(
                                it
                            )
                        }

                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            if (isNotEmpty()) {
                                append(
                                    " | "
                                )
                            }

                            append(
                                it
                            )
                        }
                }

            val viewId =
                try {
                    node.viewIdResourceName
                        .orEmpty()
                        .trim()
                } catch (_: Exception) {
                    ""
                }

            result.add(
                Snapshot(
                    node =
                        node,
                    bounds =
                        bounds,
                    label =
                        label,
                    viewId =
                        viewId
                )
            )

            for (
                index in
                0 until node.childCount
            ) {
                try {
                    node.getChild(
                        index
                    )
                        ?.let {
                            queue.add(
                                it
                            )
                        }
                } catch (_: Exception) {
                    // 页面刷新时节点可能瞬间失效。
                }
            }
        }

        return result
    }

    private fun hasAction(
        node: AccessibilityNodeInfo,
        actionId: Int
    ): Boolean {
        return try {
            node.actionList.any {
                it.id ==
                    actionId
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun normalize(
        value: String
    ): String {
        return value
            .trim()
            .lowercase()
            .filterNot {
                it.isWhitespace()
            }
    }
}
