package com.aogg.core.search.helper

import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.jetbrains.php.lang.psi.stubs.indexes.PhpClassIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 核心注解帮助类
 * 用于解析 PHPDoc 中的 @core 关键词注解
 */
object CoreAnnotationHelper {

    // 缓存类信息，避免重复解析
    private data class CacheEntry(
        val hasCoreAnnotation: Boolean,
        val uniqueKeywords: Set<String>,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    init {
        // 监听文件变更，清除相关缓存
        val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    for (event in events) {
                        val file = event.file ?: continue
                        if (file.extension?.lowercase() == "php") {
                            // 清除所有缓存，因为很难确定具体哪些类受到了影响
                            cache.clear()
                            break
                        }
                    }
                }
            })
    }

    /**
     * 获取缓存键
     */
    private fun getCacheKey(phpClass: PhpClass): String {
        return phpClass.fqn ?: "unknown_${System.identityHashCode(phpClass)}"
    }

    /**
     * 检查缓存是否有效
     */
    private fun isCacheValid(phpClass: PhpClass, entry: CacheEntry): Boolean {
        val file = phpClass.containingFile?.virtualFile ?: return false
        val currentTimestamp = file.modificationStamp
        return entry.timestamp == currentTimestamp
    }

    /**
     * 从缓存获取或计算结果
     */
    private fun getOrComputeCache(phpClass: PhpClass): CacheEntry {
        val key = getCacheKey(phpClass)
        val existing = cache[key]
        if (existing != null && isCacheValid(phpClass, existing)) {
            return existing
        }

        // 计算新结果
        val hasCore = computeHasCoreAnnotation(phpClass)
        val keywords = computeAllUniqueKeywords(phpClass)
        val timestamp = phpClass.containingFile?.virtualFile?.modificationStamp ?: 0L

        val entry = CacheEntry(hasCore, keywords, timestamp)
        cache[key] = entry
        return entry
    }

    /**
     * 计算是否有核心注解（实际计算逻辑）
     */
    private fun computeHasCoreAnnotation(phpClass: PhpClass): Boolean {
        // 检查当前类
        if (hasCoreAnnotationInClass(phpClass)) {
            return true
        }

        // 递归检查所有祖先类
        val visited = mutableSetOf<String>()
        return hasCoreAnnotationInAncestors(phpClass, visited)
    }

    /**
     * 计算所有唯一关键词（实际计算逻辑）
     */
    private fun computeAllUniqueKeywords(phpClass: PhpClass): Set<String> {
        val coreMethods = getAllCoreMethods(phpClass)
        return coreMethods.keys
    }
    
    // 缓存编译后的正则表达式
    private val CORE_PATTERN: Pattern = Pattern.compile("@core\\s+(.+)", Pattern.CASE_INSENSITIVE)
    
    /**
     * 从方法的 PHPDoc 中提取 @core 关键词列表
     * 
     * @param method 方法元素
     * @return 关键词列表，如果没有找到则返回空列表
     */
    fun extractCoreKeywords(method: Method): List<String> {
        val docComment = method.docComment ?: return emptyList()
        val keywords = extractCoreKeywordsFromDocComment(docComment)
        if (keywords.isNotEmpty()) {
            ProjectLogHelper.log(method.project, "extractCoreKeywords: method=${method.name}, keywords=$keywords")
        }
        return keywords
    }
    
    /**
     * 从 PHPDoc 注释中提取 @core 关键词
     * 
     * @param docComment PHPDoc 注释
     * @return 关键词列表
     */
    fun extractCoreKeywordsFromDocComment(docComment: PhpDocComment): List<String> {
        val keywords = mutableListOf<String>()
        val text = docComment.text
        
        val matcher = CORE_PATTERN.matcher(text)
        while (matcher.find()) {
            val keyword = matcher.group(1)?.trim()
            if (!keyword.isNullOrBlank()) {
                keywords.add(keyword)
            }
        }
        if (keywords.isNotEmpty()) {
            ProjectLogHelper.log(docComment.project, "extractCoreKeywordsFromDocComment: keywords=$keywords")
        }
        
        return keywords
    }
    
    /**
     * 检查类或其父类是否有 @core 注解的方法
     *
     * @param phpClass PHP 类元素
     * @return 如果类或其父类有 @core 注解的方法则返回 true
     */
    fun hasCoreAnnotation(phpClass: PhpClass): Boolean {
        val entry = getOrComputeCache(phpClass)
        if (entry.hasCoreAnnotation) {
            ProjectLogHelper.log(phpClass.project, "hasCoreAnnotation: class=${phpClass.fqn} cached=true")
        }
        return entry.hasCoreAnnotation
    }
    
    /**
     * 递归检查所有祖先类是否有 @core 注解的方法
     * 
     * @param phpClass PHP 类元素
     * @param visited 已访问的类集合，用于避免循环引用
     * @return 如果祖先类有 @core 注解的方法则返回 true
     */
    private fun hasCoreAnnotationInAncestors(phpClass: PhpClass, visited: MutableSet<String>): Boolean {
        val fqn = phpClass.fqn ?: return false
        if (visited.contains(fqn)) {
            return false // 避免循环引用
        }
        visited.add(fqn)
        
        val superClasses = phpClass.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass) {
                if (hasCoreAnnotationInClass(superClass)) {
                    ProjectLogHelper.log(phpClass.project, "hasCoreAnnotation: class=${phpClass.fqn} inherit=${superClass.fqn}")
                    return true
                }
                // 递归检查父类的父类
                if (hasCoreAnnotationInAncestors(superClass, visited)) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 检查类中是否有 @core 注解的方法
     * 
     * @param phpClass PHP 类元素
     * @return 如果类中有 @core 注解的方法则返回 true
     */
    private fun hasCoreAnnotationInClass(phpClass: PhpClass): Boolean {
        val methods = phpClass.methods
        for (method in methods) {
            if (!method.access.isPublic) {
                continue
            }
            val keywords = extractCoreKeywords(method)
            if (keywords.isNotEmpty()) {
                return true
            }
        }
        return false
    }
    
    /**
     * 获取类及其父类中所有带有 @core 注解的方法
     *
     * @param phpClass PHP 类元素
     * @return 关键词到方法列表的映射
     */
    fun getAllCoreMethods(phpClass: PhpClass): Map<String, List<Method>> {
        val result = mutableMapOf<String, MutableList<Method>>()
        val visited = mutableSetOf<String>()

        // 收集当前类的方法
        collectCoreMethods(phpClass, result)

        // 递归收集所有祖先类的方法
        collectCoreMethodsFromAncestors(phpClass, result, visited)

        return result
    }
    
    /**
     * 递归收集所有祖先类中带有 @core 注解的方法
     *
     * @param phpClass PHP 类元素
     * @param result 结果映射（关键词 -> 方法列表）
     * @param visited 已访问的类集合，用于避免循环引用
     */
    private fun collectCoreMethodsFromAncestors(
        phpClass: PhpClass,
        result: MutableMap<String, MutableList<Method>>,
        visited: MutableSet<String>
    ) {
        val fqn = phpClass.fqn ?: return
        if (visited.contains(fqn)) {
            return // 避免循环引用
        }
        visited.add(fqn)
        
        val superClasses = phpClass.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass) {
                collectCoreMethods(superClass, result)
                // 递归收集父类的父类
                collectCoreMethodsFromAncestors(superClass, result, visited)
            }
        }
    }

    /**
     * 收集类中所有带有 @core 注解的方法
     *
     * @param phpClass PHP 类元素
     * @param result 结果映射（关键词 -> 方法列表）
     */
    private fun collectCoreMethods(phpClass: PhpClass, result: MutableMap<String, MutableList<Method>>) {
        val methods = phpClass.methods
        for (method in methods) {
            if (!method.access.isPublic) {
                continue
            }
            val keywords = extractCoreKeywords(method)
            if (keywords.isNotEmpty()) {
                for (keyword in keywords) {
                    val list = result.getOrPut(keyword) { mutableListOf() }
                    list.add(method)
                }
                ProjectLogHelper.log(phpClass.project, "collectCoreMethods: class=${phpClass.fqn}, method=${method.name}, keywords=$keywords")
            }
        }
    }

    /**
     * 获取类及其父类中所有唯一的 @core 关键词
     *
     * @param phpClass PHP 类元素
     * @return 唯一关键词集合
     */
    fun getAllUniqueKeywords(phpClass: PhpClass): Set<String> {
        val entry = getOrComputeCache(phpClass)
        ProjectLogHelper.log(phpClass.project, "getAllUniqueKeywords: class=${phpClass.fqn} keywords=${entry.uniqueKeywords}")
        return entry.uniqueKeywords
    }

    /**
     * 在全项目范围内查找带指定 @core 关键词的方法
     */
    fun findMethodsByKeyword(project: com.intellij.openapi.project.Project, keyword: String): List<Method> {
        if (keyword.isBlank()) {
            return emptyList()
        }
        val result = mutableListOf<Method>()
        val scope = GlobalSearchScope.projectScope(project)
        val max = 300
        StubIndex.getInstance().processAllKeys(PhpClassIndex.KEY, project) { name ->
            if (result.size >= max) return@processAllKeys false
            val classes = StubIndex.getElements(
                PhpClassIndex.KEY,
                name,
                project,
                scope,
                PhpClass::class.java
            )
            for (phpClass in classes) {
                if (result.size >= max) break
                for (method in phpClass.methods) {
                    if (!method.access.isPublic) continue
                    val keywords = extractCoreKeywords(method)
                    if (keywords.contains(keyword)) {
                        result.add(method)
                        if (result.size >= max) break
                    }
                }
            }
            true
        }
        if (result.isNotEmpty()) {
            val info = result.map { ((it.containingClass as? PhpClass)?.fqn ?: "<no-class>") + "::" + it.name }
            ProjectLogHelper.log(project, "findMethodsByKeyword: keyword=$keyword, count=${result.size}, methods=${info.joinToString("; ")}")
        }
        return result
    }
    
    /**
     * 检查方法是否有指定的 @core 关键词
     * 
     * @param method 方法元素
     * @param keyword 关键词
     * @return 如果方法有该关键词则返回 true
     */
    fun hasKeyword(method: Method, keyword: String): Boolean {
        if (!method.access.isPublic) {
            return false
        }
        val keywords = extractCoreKeywords(method)
        return keywords.contains(keyword)
    }
}

