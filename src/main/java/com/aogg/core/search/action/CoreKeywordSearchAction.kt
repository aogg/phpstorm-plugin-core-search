package com.aogg.core.search.action

import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

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
            .toMutableSet()

        // 仅当当前类/父类未找到时，再全项目补充，避免每次全量扫描
        if (targetMethods.isEmpty()) {
            val projectMethods = CoreAnnotationHelper.findMethodsByKeyword(project, keyword)
            targetMethods.addAll(projectMethods)
        }
        if (targetMethods.isNotEmpty()) {
            val methodInfos = targetMethods.map { method ->
                val classFqn = (method.containingClass as? PhpClass)?.fqn ?: "<no-class>"
                "$classFqn::${method.name}"
            }
            ProjectLogHelper.log(
                project,
                "搜索CoreKeywordSearchAction: keyword=$keyword, targetMethods=${methodInfos.joinToString("; ")}"
            )
        }
        
        if (targetMethods.isEmpty()) {
            notifyInfo(project, "未找到包含 @$keyword 的方法")
            ProjectLogHelper.log(project, "CoreKeywordSearchAction: no methods with keyword=$keyword in class=${phpClass.fqn}")
            return
        }
        
        // 收集所有符合条件的用法
        val usages = mutableListOf<Usage>()
        var totalUsages = 0
        
        for (method in targetMethods) {
            // 如果方法定义在父类中，则只统计“当前类自身”对该方法的调用
            val isFromSuperClass = method.containingClass != null && method.containingClass != phpClass
            val limitToClass = if (isFromSuperClass) phpClass else null
            val methodUsages = findMethodUsages(project, method, limitToClass)
            totalUsages += methodUsages.size
            usages.addAll(methodUsages.map { UsageInfo2UsageAdapter(it) })
        }
        
        // 显示搜索结果
        if (usages.isNotEmpty()) {
            val usageInfos = usages.mapNotNull { usage ->
                val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo
                val element = info?.element ?: return@mapNotNull null
                val classFqn = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)?.fqn ?: "<no-class>"
                val methodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
                "$classFqn::$methodName"
            }
            ProjectLogHelper.log(
                project,
                "搜索CoreKeywordSearchAction: show usages keyword=$keyword, usageList=${usageInfos.joinToString(",")}"
            )
            showUsages(project, usages, keyword)
        } else {
            notifyInfo(project, "未找到调用 @$keyword 的代码位置")
            ProjectLogHelper.log(project, "CoreKeywordSearchAction: no usages found keyword=$keyword methods=${targetMethods.size}")
        }
    }
    
    /**
     * 获取类中所有带有指定关键词的方法
     */
    private fun getMethodsWithKeyword(phpClass: PhpClass, keyword: String): List<Method> {
        val coreMethods = CoreAnnotationHelper.getAllCoreMethods(phpClass)
        return coreMethods[keyword]?.toList() ?: emptyList()
    }
    
    /**
     * 查找方法的调用位置
     *
     * @param limitToClass 如果不为 null，则只保留位于该类内部的调用（用于父类方法，只看当前类对它的调用）
     */
    private fun findMethodUsages(
        project: Project,
        method: Method,
        limitToClass: PhpClass?
    ): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        val searchScope = GlobalSearchScope.projectScope(project)

        // 使用 ReferencesSearch 查找方法引用
        val references = ReferencesSearch.search(method, searchScope, false)

        for (reference in references) {
            val element = reference.element
            val range = reference.rangeInElement

            // 如果需要限制到“当前类”，则只保留位于该类内部的调用
            if (limitToClass != null) {
                val callerClass = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
                if (callerClass == null || callerClass != limitToClass) {
                    continue
                }
            }

            usages.add(UsageInfo(element, range.startOffset, range.endOffset, true))
        }

        return usages
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
    
    /**
     * 结果提示通知
     */
    private fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "核心搜索",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }
}

