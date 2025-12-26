package com.aogg.core.search.helper

import java.io.File

/**
 * 自动发现显示相关的工具函数
 */
object AutoDiscoverDisplayHelper {

    /**
     * 根据方法名返回固定分组标识，用于对 usages 进行分组显示
     * 规则（优先级）：以 get/set/is/has 开头分别归类，否则归类到 "其他"
     */
    @JvmStatic
    fun getMethodGroupFromName(methodName: String): String {
        val name = methodName.lowercase()
        return when {
            name.startsWith("get") -> "get"
            name.startsWith("set") -> "set"
            name.startsWith("is") -> "is"
            name.startsWith("has") -> "has"
            else -> "其他"
        }
    }

    /**
     * 在指定项目根目录下查找 docs 目录中的相关文档文件，返回相对于项目根的 docs 路径（例如 "docs/核心逻辑/核心搜索/搜索逻辑.md"）。
     * 如果未找到返回 null。
     */
    @JvmStatic
    fun findDocsLabelFromBase(projectBasePath: String?, className: String, title: String): String? {
        val basePath = projectBasePath ?: return null
        try {
            val baseDir = File(basePath)
            if (!baseDir.exists()) return null
            val candidates = baseDir.walkTopDown().filter { file ->
                file.isFile && file.path.contains("${File.separator}docs${File.separator}")
            }
            val match = candidates.firstOrNull { it.name.contains(className, ignoreCase = true) }
                ?: candidates.firstOrNull { it.path.contains(title, ignoreCase = true) || it.name.contains(title, ignoreCase = true) }
            if (match != null) {
                val rel = match.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                return "docs/$rel"
            }
        } catch (_: Throwable) {
            // 忽略错误，返回 null
        }
        return null
    }
}


