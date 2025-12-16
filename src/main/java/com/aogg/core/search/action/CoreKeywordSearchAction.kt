package com.aogg.core.search.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.usageView.UsageInfo
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.PsiReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.aogg.core.search.helper.CoreAnnotationHelper

/**
 * 关键词搜索动作
 * 搜索调用当前类方法的位置，且调用处的方法有对应的 @core 关键词
 */
class CoreKeywordSearchAction(
    private val keyword: String,
    private val phpClass: PhpClass
) : AnAction(keyword) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 获取当前类中所有带有该关键词的方法
        val targetMethods = getMethodsWithKeyword(phpClass, keyword)
        
        if (targetMethods.isEmpty()) {
            return
        }
        
        // 收集所有符合条件的用法
        val usages = mutableListOf<Usage>()
        
        for (method in targetMethods) {
            val methodUsages = findMethodUsages(project, method)
            
            // 过滤：只保留调用处的方法有对应 @core 关键词的用法
            val filteredUsages = filterUsagesByKeyword(methodUsages, keyword)
            usages.addAll(filteredUsages)
        }
        
        // 显示搜索结果
        if (usages.isNotEmpty()) {
            showUsages(project, usages, keyword)
        }
    }
    
    /**
     * 获取类中所有带有指定关键词的方法
     */
    private fun getMethodsWithKeyword(phpClass: PhpClass, keyword: String): List<Method> {
        val result = mutableListOf<Method>()
        val coreMethods = CoreAnnotationHelper.getAllCoreMethods(phpClass)
        
        for ((method, keywords) in coreMethods) {
            if (keywords.contains(keyword)) {
                result.add(method)
            }
        }
        
        return result
    }
    
    /**
     * 查找方法的调用位置
     */
    private fun findMethodUsages(project: Project, method: Method): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        val searchScope = GlobalSearchScope.projectScope(project)
        
        // 使用 ReferencesSearch 查找方法引用
        val references = ReferencesSearch.search(method, searchScope, false)
        
        for (reference in references) {
            val element = reference.element
            val range = reference.rangeInElement
            usages.add(UsageInfo(element, range.startOffset, range.endOffset, true))
        }
        
        return usages
    }
    
    /**
     * 过滤用法：只保留调用处的方法有对应 @core 关键词的用法
     */
    private fun filterUsagesByKeyword(
        usages: List<UsageInfo>,
        keyword: String
    ): List<Usage> {
        val result = mutableListOf<Usage>()
        
        for (usageInfo in usages) {
            val element = usageInfo.element ?: continue
            
            // 查找包含该元素的 PHP 方法
            val callingMethod = findContainingMethod(element) ?: continue
            
            // 检查调用方法是否有对应的 @core 关键词
            if (CoreAnnotationHelper.hasKeyword(callingMethod, keyword)) {
                result.add(UsageInfo2UsageAdapter(usageInfo))
            }
        }
        
        return result
    }
    
    /**
     * 查找包含指定元素的 PHP 方法
     */
    private fun findContainingMethod(element: com.intellij.psi.PsiElement): Method? {
        var current: com.intellij.psi.PsiElement? = element
        
        while (current != null) {
            if (current is Method) {
                return current
            }
            current = current.parent
        }
        
        return null
    }
    
    /**
     * 显示用法搜索结果
     */
    private fun showUsages(project: Project, usages: List<Usage>, keyword: String) {
        val usageTargets = emptyArray<UsageTarget>()
        val presentation = UsageViewPresentation()
        presentation.tabName = "核心搜索: $keyword"
        presentation.tabText = "核心搜索: $keyword"
        presentation.scopeText = "项目范围"
        
        UsageViewManager.getInstance(project).showUsages(
            usageTargets,
            usages.toTypedArray(),
            presentation
        )
    }
}

