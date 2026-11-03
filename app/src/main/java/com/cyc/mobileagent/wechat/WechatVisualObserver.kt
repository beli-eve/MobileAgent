package com.cyc.mobileagent.wechat

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import com.cyc.mobileagent.service.AgentAccessibilityService
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs

/**
 * v0.1 微信视觉观察器：只使用 Android 系统截图和设备本地 ML Kit OCR。
 * 不读取微信 Accessibility Tree，不上传图片，不保存截图，不执行点击。
 */
internal class WechatVisualObserver(
    private val service: AgentAccessibilityService
) {
    private companion object {
        const val TAG = "WechatVisualObserver"
    }

    enum class SearchState { FOUND, NOT_FOUND, UNKNOWN }

    data class SearchObservation(
        val state: SearchState,
        val xRatio: Float? = null,
        val yRatio: Float? = null,
        val diagnostic: String = ""
    )

    data class ChatObservation(
        val confirmed: Boolean,
        val diagnostic: String
    )

    private data class OcrItem(val text: String, val rect: Rect)
    private data class Snapshot(
        val width: Int,
        val height: Int,
        val items: List<OcrItem>,
        val searchBarLikely: Boolean
    )

    // 使用 bundled 模型，首次执行不需要下载，避免部分设备上依赖 Play Services 联网。
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun verifySearch(contact: String, callback: (SearchObservation) -> Unit) {
        readSnapshot { snapshot, error ->
            if (snapshot == null) {
                callback(SearchObservation(SearchState.UNKNOWN, diagnostic = "截图/OCR失败: $error"))
                return@readSnapshot
            }
            callback(classifySearch(snapshot, contact))
        }
    }

    fun verifyChat(contact: String, callback: (ChatObservation) -> Unit) {
        readSnapshot { snapshot, error ->
            if (snapshot == null) {
                callback(ChatObservation(false, "聊天页截图/OCR失败: $error"))
                return@readSnapshot
            }
            callback(classifyChat(snapshot, contact))
        }
    }

    private fun readSnapshot(callback: (Snapshot?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null, "系统版本低于 Android 11，无法使用无障碍截图")
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        callback(null, "系统截图失败 code=$errorCode；请检查 accessibility_config.xml 的 canTakeScreenshot")
                    }

                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = try {
                            val buffer = result.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                try {
                                    hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hardwareBitmap?.recycle()
                                }
                            } finally {
                                buffer.close()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "无法解码系统截图", e)
                            null
                        }
                        if (bitmap == null) {
                            callback(null, "截图 Bitmap 解码失败")
                            return
                        }
                        recognize(bitmap, callback)
                    }
                }
            )
        } catch (e: Exception) {
            callback(null, "调用 Android 系统截图接口失败: ${e.javaClass.simpleName}")
        }
    }

    private fun recognize(bitmap: Bitmap, callback: (Snapshot?, String?) -> Unit) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val searchBarLikely = looksLikeWechatSearchBar(bitmap)
            val chinese = chineseRecognizer.process(image)
            val latin = latinRecognizer.process(image)
            // 两个识别器共同覆盖中文昵称、英文备注、中文分组标题。
            Tasks.whenAllComplete(chinese, latin)
                .addOnCompleteListener(service.mainExecutor) {
                    try {
                        val found = mutableListOf<OcrItem>()
                        if (chinese.isSuccessful) appendText(found, chinese.result)
                        if (latin.isSuccessful) appendText(found, latin.result)
                        if (found.isEmpty()) {
                            callback(null, "OCR 没有返回任何文本（中文/拉丁识别均不可用或识别为空）")
                        } else {
                            callback(Snapshot(bitmap.width, bitmap.height, found, searchBarLikely), null)
                        }
                    } catch (e: Exception) {
                        callback(null, "OCR 结果处理失败: ${e.javaClass.simpleName}")
                    } finally {
                        bitmap.recycle()
                    }
                }
        } catch (e: Exception) {
            bitmap.recycle()
            callback(null, "启动本地 OCR 失败: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 当前微信深色搜索框横跨屏幕顶部，框底色与其下方背景有稳定明度差。
     * 只作为聊天页的额外否定证据，不作为联系人存在的充分证据。
     */
    private fun looksLikeWechatSearchBar(bitmap: Bitmap): Boolean {
        if (bitmap.width < 200 || bitmap.height < 400) return false
        fun brightness(x: Float, y: Float): Int {
            val px = bitmap.getPixel(
                (bitmap.width * x).toInt().coerceIn(0, bitmap.width - 1),
                (bitmap.height * y).toInt().coerceIn(0, bitmap.height - 1)
            )
            return (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
        }
        // 在同一高度比较搜索框内部与屏幕最右侧边缘，避免将聊天页顶部导航栏
        // 与消息正文的背景色差误判成搜索框。
        return listOf(0.077f, 0.090f, 0.103f).count { y ->
            abs(brightness(0.72f, y) - brightness(0.985f, y)) >= 15 &&
                abs(brightness(0.58f, y) - brightness(0.985f, y)) >= 15
        } >= 2
    }

    private fun appendText(items: MutableList<OcrItem>, result: Text) {
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.boundingBox?.let { items.add(OcrItem(line.text, Rect(it))) }
                line.elements.forEach { element ->
                    element.boundingBox?.let { items.add(OcrItem(element.text, Rect(it))) }
                }
            }
        }
    }

    private fun norm(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .filterNot {
            it.isWhitespace() || it == '\u200b' || it == '\u200c' ||
                it == '\u200d' || it == '\ufeff'
        }
        .trim('：', ':', '。', '.', '，', ',', '·')

    private val sectionNames = setOf(
        "最常使用", "联系人", "群聊", "聊天记录", "公众号", "小程序",
        "朋友圈", "文章", "视频号", "搜一搜", "文件", "表情",
        "音乐", "服务", "搜索网络结果", "查找账号"
    )
    private val validSections = setOf("最常使用", "联系人")

    private fun classifySearch(snapshot: Snapshot, contact: String): SearchObservation {
        val (width, height, items) = snapshot
        val target = norm(contact)
        if (target.isEmpty()) return SearchObservation(SearchState.UNKNOWN, diagnostic = "目标姓名为空")

        val sectionCandidates = items.mapNotNull { item ->
            val label = norm(item.text)
            // 微信分组标题在屏幕左侧；“最常使用”中的联系人副标题位于姓名下方，不能混淆。
            if (label !in sectionNames || item.rect.left > width * 0.165f ||
                item.rect.centerY() !in (height * 0.11f).toInt()..(height * 0.75f).toInt()
            ) null else item.copy(text = label)
        }.distinctBy { "${it.text}:${it.rect.centerY() / 18}" }
            .sortedBy { it.rect.centerY() }

        // 必须确认顶部搜索框仍然对应当前查询，防止异步/旧结果误点击。
        val currentQuerySeen = items.any {
            norm(it.text) == target &&
                it.rect.centerY() < height * 0.145f &&
                it.rect.left > width * 0.12f && it.rect.left < width * 0.83f
        }
        if (!currentQuerySeen) {
            return SearchObservation(SearchState.UNKNOWN, diagnostic =
                "顶部搜索词未被OCR可靠识别；sections=${sectionCandidates.map { it.text }.distinct()}")
        }

        val resultNames = items.filter { item ->
            norm(item.text) == target &&
                item.rect.centerY() > height * 0.15f &&
                item.rect.centerY() < height * 0.65f &&
                item.rect.left > width * 0.12f &&
                item.rect.left < width * 0.81f &&
                item.rect.width() > 3 && item.rect.height() > 7
        }.distinctBy { it.rect.centerY() / (height * 0.025f).toInt().coerceAtLeast(20) }

        val validMatches = resultNames.filter { item ->
            val preceding = sectionCandidates.lastOrNull {
                it.rect.centerY() < item.rect.centerY() - 8
            } ?: return@filter false
            if (preceding.text !in validSections) return@filter false
            if (preceding.text == "联系人") return@filter true

            // 「最常使用」必须在姓名下方出现“联系人”身份副标题，不能把群聊当好友。
            items.any { subtitle ->
                norm(subtitle.text) == "联系人" &&
                    subtitle.rect.centerY() > item.rect.centerY() + 10 &&
                    subtitle.rect.centerY() < item.rect.centerY() + height * 0.060f &&
                    abs(subtitle.rect.left - item.rect.left) < width * 0.10f
            }
        }
        val distinctMatches = validMatches.distinctBy {
            it.rect.centerY() / (height * 0.025f).toInt().coerceAtLeast(20)
        }
        val info = "ocr=${items.size},sections=${sectionCandidates.map { it.text }.distinct()}," +
            "exactRows=${resultNames.size},validRows=${distinctMatches.size}"
        Log.d(TAG, "搜索视觉验证: $info")

        return when {
            distinctMatches.size == 1 -> {
                val row = distinctMatches.single().rect
                SearchObservation(
                    SearchState.FOUND,
                    xRatio = (row.centerX().toFloat() / width).coerceIn(0.02f, 0.98f),
                    yRatio = (row.centerY().toFloat() / height).coerceIn(0.02f, 0.98f),
                    diagnostic = info
                )
            }
            distinctMatches.size > 1 -> SearchObservation(SearchState.UNKNOWN,
                diagnostic = "有多个可信同名联系人，拒绝猜测；$info")
            sectionCandidates.any { it.text == "搜索网络结果" || it.text == "查找账号" } ->
                SearchObservation(SearchState.NOT_FOUND,
                    diagnostic = "没有可信联系人，仅有查找账号/网络结果；$info")
            else -> SearchObservation(SearchState.UNKNOWN,
                diagnostic = "当前截图未能确认目标联系人；$info")
        }
    }

    private fun classifyChat(snapshot: Snapshot, contact: String): ChatObservation {
        val (width, height, items) = snapshot
        val target = norm(contact)
        val headerMatch = items.any {
            norm(it.text) == target &&
                it.rect.centerY() > height * 0.045f &&
                it.rect.centerY() < height * 0.17f &&
                it.rect.left > width * 0.12f &&
                it.rect.left < width * 0.85f
        }
        // 若点击后仍停在搜索页，目标昵称也会出现在顶部。必须排除微信搜索分组。
        val searchMarkers = items.filter {
            norm(it.text) in sectionNames &&
                it.rect.centerY() > height * 0.11f &&
                it.rect.centerY() < height * 0.72f &&
                it.rect.left < width * 0.165f
        }.map { norm(it.text) }.distinct()
        val confirmed = headerMatch && searchMarkers.isEmpty() && !snapshot.searchBarLikely
        val info = "顶部目标名=$headerMatch,搜索页分组=$searchMarkers," +
            "搜索框视觉特征=${snapshot.searchBarLikely},ocr=${items.size}"
        Log.d(TAG, "聊天页视觉验证: $info")
        return ChatObservation(confirmed, info)
    }
}
