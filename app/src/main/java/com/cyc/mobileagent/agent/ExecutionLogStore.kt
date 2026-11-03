package com.cyc.mobileagent.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MobileAgent 的本地运行记录。
 *
 * 日志保存在 App 私有目录：
 * /data/user/0/com.cyc.mobileagent/files/mobile_agent_execution.log
 *
 * 不需要申请外部存储权限，普通文件管理器也无法直接读取。
 */
class ExecutionLogStore(
    context: Context
) {

    companion object {
        private const val FILE_NAME =
            "mobile_agent_execution.log"

        private const val MAX_FILE_BYTES =
            512L * 1024L

        private const val MAX_VISIBLE_LINES =
            200

        private const val LINES_TO_KEEP_AFTER_TRIM =
            300
    }

    private val logFile =
        File(
            context.filesDir,
            FILE_NAME
        )

    private val timeFormatter =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        )

    @Synchronized
    fun append(
        message: String
    ) {
        val cleanMessage =
            message
                .lineSequence()
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .joinToString(
                    separator = " ｜ "
                )
                .trim()

        if (cleanMessage.isBlank()) {
            return
        }

        val timestamp =
            timeFormatter.format(
                Date()
            )

        logFile.parentFile
            ?.mkdirs()

        logFile.appendText(
            "$timestamp  $cleanMessage${System.lineSeparator()}"
        )

        trimFileIfNeeded()
    }

    @Synchronized
    fun readAllNewestFirst(): List<String> {
        if (!logFile.exists()) {
            return emptyList()
        }

        return try {
            logFile
                .readLines()
                .asSequence()
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .toList()
                .takeLast(
                    MAX_VISIBLE_LINES
                )
                .asReversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (_: Exception) {
            // 清空失败不会影响主任务执行。
        }
    }

    private fun trimFileIfNeeded() {
        if (
            !logFile.exists() ||
            logFile.length() <= MAX_FILE_BYTES
        ) {
            return
        }

        try {
            val linesToKeep =
                logFile
                    .readLines()
                    .takeLast(
                        LINES_TO_KEEP_AFTER_TRIM
                    )

            logFile.writeText(
                linesToKeep.joinToString(
                    separator =
                        System.lineSeparator(),
                    postfix =
                        if (linesToKeep.isEmpty()) {
                            ""
                        } else {
                            System.lineSeparator()
                        }
                )
            )
        } catch (_: Exception) {
            // 裁剪失败不阻断日志写入和任务执行。
        }
    }
}
