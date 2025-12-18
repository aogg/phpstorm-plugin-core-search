package com.aogg.core.search.helper

import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.jetbrains.php.lang.psi.stubs.indexes.PhpClassIndex
import java.util.regex.Pattern

/**
 * 核心注解帮助类
 * 用于解析 PHPDoc 中的 @core 关键词注解
 */
object CoreAnnotationHelper {
    
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
        // 检查当前类
        if (hasCoreAnnotationInClass(phpClass)) {
            ProjectLogHelper.log(phpClass.project, "hasCoreAnnotation: class=${phpClass.fqn} self=true")
            return true
        }
        
        // 检查父类
        val superClasses = phpClass.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass && hasCoreAnnotationInClass(superClass)) {
                ProjectLogHelper.log(phpClass.project, "hasCoreAnnotation: class=${phpClass.fqn} inherit=${superClass.fqn}")
                return true
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

        // 收集当前类的方法
        collectCoreMethods(phpClass, result)

        // 收集父类的方法
        val superClasses = phpClass.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass) {
                collectCoreMethods(superClass, result)
            }
        }

        return result
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
        val coreMethods = getAllCoreMethods(phpClass)
        return coreMethods.keys
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

