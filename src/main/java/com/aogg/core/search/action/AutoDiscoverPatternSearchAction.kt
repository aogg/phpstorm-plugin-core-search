package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverHelper
import com.aogg.core.search.settings.AutoDiscoverSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.intellij.usageView.UsageInfo
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 点击三级项后执行的搜索动作：按方法名模式收集方法并查找 usages
 */
class AutoDiscoverPatternSearchAction(
    private val pattern: String,
    private val phpClass: PhpClass
) : AnAction(pattern) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "自动发现搜索: $pattern", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performSearch(project, indicator)
                }
            }
        })
    }

    private fun performSearch(project: Project, indicator: ProgressIndicator) {
        indicator.text = "收集匹配 $pattern 的方法..."
        indicator.isIndeterminate = true

        val targetMethods = mutableSetOf<Method>()
        // 优先当前类
        for (method in phpClass.methods) {
            if (!method.access.isPublic) continue
            val name = method.name ?: continue
            if (AutoDiscoverHelper.methodMatches(pattern, name, AutoDiscoverSettings.getInstance(project).caseInsensitive)) {
                targetMethods.add(method)
            }
        }

        // 目前仅在当前类内匹配；如需跨项目匹配可后续扩展

        if (targetMethods.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                notifyInfo(project, "未找到匹配规则 $pattern 的方法")
            }
            return
        }

        // 搜索所有 usages 并展示（复用 CoreKeywordSearchAction 风格）
        val usages = mutableListOf<Usage>()
        val methodsList = targetMethods.toList()
        for (method in methodsList) {
            indicator.checkCanceled()
            val refs = RefSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
            for (ref in refs) {
                val element = ref.element
                val range = ref.rangeInElement
                usages.add(UsageInfo2UsageAdapter(UsageInfo(element, range.startOffset, range.endOffset, true)))
            }
        }

        ApplicationManager.getApplication().invokeLater {
            if (usages.isNotEmpty()) {
                showUsages(project, usages, pattern)
            } else {
                notifyInfo(project, "未找到调用匹配 $pattern 的方法的位置")
            }
        }
    }

    private fun showUsages(project: Project, usages: List<Usage>, title: String) {
        val usageTargets = emptyArray<UsageTarget>()
        val presentation = UsageViewPresentation()
        presentation.tabName = "自动发现: $title"
        presentation.tabText = "自动发现: $title"
        presentation.scopeText = "项目范围"

        UsageViewManager.getInstance(project).showUsages(
            usageTargets,
            usages.toTypedArray(),
            presentation
        )
    }

    private fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "自动发现搜索",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }
}


