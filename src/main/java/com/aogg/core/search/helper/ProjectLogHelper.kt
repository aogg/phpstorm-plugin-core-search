package com.aogg.core.search.helper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 项目级日志工具，按天输出到 .idea/plugin/core-search/logs/debug-YYYY-MM-DD.log
 * 为避免在 UI 线程发生磁盘 IO 导致卡顿，改为在线程池中异步写入。
 */
object ProjectLogHelper {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 写调试日志，自动创建目录和文件，按天分隔日志文件
     * 将实际文件写入放到线程池中执行，避免在 EDT 上直接 IO。
     */
    fun log(project: Project?, message: String) {
        val basePath = project?.basePath ?: return
        val now = LocalDateTime.now()
        val dateStr = dateFormatter.format(now)
        val logFile = Path.of(basePath, ".idea", "plugin", "core-search", "logs", "debug-$dateStr.log")
        val threadName = Thread.currentThread().name
        val line = "[${timeFormatter.format(now)}][$threadName] $message${System.lineSeparator()}"

        // 在后台线程写文件，防止在 EDT 上进行磁盘 IO 导致 IDE 卡顿
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureFile(logFile)
                Files.writeString(
                    logFile,
                    line,
                    StandardOpenOption.APPEND
                )
            } catch (_: IOException) {
                // 忽略日志异常，避免影响功能
            }
        }
    }

    private fun ensureFile(logFile: Path) {
        Files.createDirectories(logFile.parent)
        if (!Files.exists(logFile)) {
            Files.createFile(logFile)
        }
    }
}

