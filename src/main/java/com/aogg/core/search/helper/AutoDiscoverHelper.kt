package com.aogg.core.search.helper

import com.aogg.core.search.settings.AutoDiscoverSettings
import com.intellij.openapi.project.Project
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.psi.PsiFile
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动发现帮助类：通配符转正则、方法匹配、简单缓存
 */
object AutoDiscoverHelper {

    private data class CacheEntry(val modStamp: Long, val matchedRules: List<String>)

    // key: virtualFile.path
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 将通配符模式转换为 Regex
     */
    fun patternToRegex(pattern: String, ignoreCase: Boolean): Regex {
        // 转义正则特殊字符，保留 * 作为通配符
        val escaped = Regex.escape(pattern).replace("\\*".toRegex(), ".*")
        return if (ignoreCase) {
            Regex("^$escaped$", RegexOption.IGNORE_CASE)
        } else {
            Regex("^$escaped$")
        }
    }

    /**
     * 检查方法名是否匹配给定模式
     */
    fun methodMatches(pattern: String, methodName: String, ignoreCase: Boolean): Boolean {
        val regex = patternToRegex(pattern, ignoreCase)
        return regex.matches(methodName)
    }

    /**
     * 为当前类收集存在匹配方法的规则列表
     * 会使用简单缓存：以 psiFile.virtualFile.path 为 key，modificationStamp 作为版本判断
     */
    fun collectMatchingRulesForClass(phpClass: PhpClass): List<String> {
        val project = phpClass.project
        val settings = AutoDiscoverSettings.getInstance(project)
        val psiFile = phpClass.containingFile
        val vFile: VirtualFile? = psiFile?.virtualFile
        val key = vFile?.path ?: (psiFile?.name ?: phpClass.fqn ?: "unknown")
        val currentStamp = vFile?.modificationStamp ?: psiFile?.modificationStamp ?: -1L

        val cached = cache[key]
        if (cached != null && cached.modStamp == currentStamp) {
            return cached.matchedRules
        }

        val rules = settings.rules
        val ignoreCase = settings.caseInsensitive
        val matched = mutableListOf<String>()

        for (pattern in rules) {
            val regex = patternToRegex(pattern, ignoreCase)
            for (method in phpClass.methods) {
                if (!method.access.isPublic) continue
                val name = method.name ?: continue
                if (regex.matches(name)) {
                    matched.add(pattern)
                    break
                }
            }
        }

        val entry = CacheEntry(currentStamp, matched)
        cache[key] = entry
        return matched
    }

    /**
     * 让外部可以主动清理缓存（例如配置变更时）
     */
    fun clearCache() {
        cache.clear()
    }
}


