package com.aogg.core.search.helper

import com.aogg.core.search.settings.AutoDiscoverSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.intellij.psi.search.searches.ReferencesSearch
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
        // 将通配符 * 转换为 .*，然后手动转义其他正则特殊字符，但保留 .* (通配符生成的)
        val converted = pattern.replace("*", ".*")
        // 只转义需要转义的特殊字符，但保留 .* (因为这是通配符转换的结果)
        val escaped = converted
            .replace("\\", "\\\\")  // 先转义反斜杠
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|")
            .replace("?", "\\?")
            .replace("+", "\\+")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")

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
        // 记录当前配置，便于排查规则是否被正确读取
        com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 当前配置 rules=${settings.rules} caseInsensitive=${settings.caseInsensitive}")
        com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 开始收集匹配规则 class=${phpClass.fqn}")
        val psiFile = phpClass.containingFile
        val vFile: VirtualFile? = psiFile?.virtualFile
        val key = vFile?.path ?: (psiFile?.name ?: phpClass.fqn ?: "unknown")
        val currentStamp = vFile?.modificationStamp ?: psiFile?.modificationStamp ?: -1L
        val cached = cache[key]
        if (cached != null && cached.modStamp == currentStamp) {
            com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 缓存命中 key=$key patterns=${cached.matchedRules}")
            return cached.matchedRules
        }

        val rules = settings.rules
        val ignoreCase = settings.caseInsensitive
        val matched = mutableListOf<String>()

        for (pattern in rules) {
            val regex = patternToRegex(pattern, ignoreCase)
            
            for (method in phpClass.methods) {
                // 自动发现不再限定仅 public 方法，支持任意方法名用于匹配规则
                val name = method.name ?: continue
                // 打印正在检查的方法名，便于排查匹配过程
                // com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 检查方法 name=$name pattern=$pattern")
                // 先用正则匹配
                if (regex.matches(name)) {
                    com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 方法匹配 name=$name pattern=$pattern")
                    matched.add(pattern)
                    break
                }
                // 如果规则是以 '*' 结尾且 regex 未匹配，做 startsWith 快速回退匹配（兼容简单通配符场景）
                if (pattern.endsWith("*")) {
                    val prefix = pattern.removeSuffix("*")
                    if (prefix.isNotEmpty() && name.startsWith(prefix, ignoreCase = ignoreCase)) {
                        com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 方法匹配（prefix） name=$name pattern=$pattern")
                        matched.add(pattern)
                        break
                    }
                }
            }
        }

        val entry = CacheEntry(currentStamp, matched)
        cache[key] = entry
        com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: 收集完成 key=$key matched=${matched}")
        return matched
    }

    /**
     * 让外部可以主动清理缓存（例如配置变更时）
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 判断给定模式是否存在至少一个调用，其调用方为目标类或目标类的子类
     * 用于在自动发现中剔除明显与当前类无关的规则项
     */
    fun patternHasRelatedUsages(project: Project, phpClass: PhpClass, pattern: String): Boolean {
        try {
            val ignoreCase = AutoDiscoverSettings.getInstance(project).caseInsensitive
            val regex = patternToRegex(pattern, ignoreCase)
            // 检查 phpClass 的方法中哪些与 pattern 匹配
            val methods = phpClass.methods.filter { method ->
                val name = method.name ?: return@filter false
                try {
                    regex.matches(name)
                } catch (_: Throwable) {
                    false
                }
            }
            if (methods.isEmpty()) return false

            for (method in methods) {
                val refs = RefSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
                for (ref in refs) {
                    val element = ref.element ?: continue
                    val methodReference = PsiTreeUtil.getParentOfType(
                        element,
                        com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                        false
                    ) ?: continue

                    val classRef = methodReference.classReference
                    if (classRef != null && !classRef.text.startsWith("$")) {
                        val className = classRef.text
                        val phpIndex = PhpIndex.getInstance(project)
                        val resolved = phpIndex.getAnyByFQN(className)
                        for (r in resolved) {
                            if (r is PhpClass) {
                                if (isClassRelated(r, phpClass)) return true
                                if (r.name == phpClass.name) return true
                            }
                        }
                        if (className == phpClass.name) return true
                    } else {
                        val firstPsiChild = methodReference.firstPsiChild
                        if (firstPsiChild is com.jetbrains.php.lang.psi.elements.PhpTypedElement) {
                            val phpType = firstPsiChild.type
                            val globalType = phpType.global(project)
                            for (type in globalType.types) {
                                val cleanFqn = type.toString().removePrefix("\\").removePrefix("#")
                                val phpIndex = PhpIndex.getInstance(project)
                                val classes = phpIndex.getClassesByFQN(cleanFqn)
                                for (c in classes) {
                                    if (isClassRelated(c, phpClass)) return true
                                }
                            }
                        }
                    }
                }
            }
        } catch (ex: Throwable) {
            com.aogg.core.search.helper.ProjectLogHelper.log(project, "自动发现: patternHasRelatedUsages 异常 pattern=$pattern ex=${ex.message}")
        }
        return false
    }

    private fun isClassRelated(class1: PhpClass, class2: PhpClass): Boolean {
        if (class1 == class2) return true
        val visited = mutableSetOf<String>()
        return checkInheritance(class1, class2, visited)
    }

    private fun checkInheritance(child: PhpClass, parent: PhpClass, visited: MutableSet<String>): Boolean {
        val childFqn = child.fqn ?: return false
        if (visited.contains(childFqn)) return false
        visited.add(childFqn)
        val superClasses = child.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass) {
                if (superClass == parent) return true
                if (checkInheritance(superClass, parent, visited)) return true
            }
        }
        return false
    }
}


