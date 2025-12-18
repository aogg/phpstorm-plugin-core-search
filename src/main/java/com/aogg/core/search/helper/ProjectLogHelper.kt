package com.aogg.core.search.helper

import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 项目级日志工具，按天输出到 .idea/plugin/core-search/logs/debug-YYYY-MM-DD.log
 */
object ProjectLogHelper {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 写调试日志，自动创建目录和文件，按天分隔日志文件
     */
    fun log(project: Project?, message: String) {
        val basePath = project?.basePath ?: return
        val now = LocalDateTime.now()
        val dateStr = dateFormatter.format(now)
        val logFile = Path.of(basePath, ".idea", "plugin", "core-search", "logs", "debug-$dateStr.log")
        try {
            ensureFile(logFile)
            val line = "[${timeFormatter.format(now)}][${Thread.currentThread().name}] $message${System.lineSeparator()}"
            Files.writeString(
                logFile,
                line,
                StandardOpenOption.APPEND
            )
        } catch (_: IOException) {
            // 忽略日志异常，避免影响功能
        }
    }

    private fun ensureFile(logFile: Path) {
        Files.createDirectories(logFile.parent)
        if (!Files.exists(logFile)) {
            Files.createFile(logFile)
        }
    }
}

