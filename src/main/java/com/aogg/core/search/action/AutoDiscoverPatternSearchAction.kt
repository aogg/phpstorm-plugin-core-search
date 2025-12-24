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
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.project.DumbAware

/**
 * 点击三级项后执行的搜索动作：按方法名模式收集方法并查找 usages
 */
class AutoDiscoverPatternSearchAction(
    private val pattern: String,
    private val phpClass: PhpClass
) : AnAction(pattern) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 尝试根据当前上下文解析 PhpClass（优先使用事件上下文），若失败则回退到构造时捕获的 phpClass
        val resolvedPhpClass = run {
            var psiFile = e.getData(CommonDataKeys.PSI_FILE)
            if (psiFile == null && project != null) {
                val editor = e.getData(CommonDataKeys.EDITOR)
                if (editor != null) {
                    psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                }
            }
            if (psiFile == null && project != null) {
                val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
                if (virtualFile != null) {
                    psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                }
            }
            val cls = psiFile?.let { PsiTreeUtil.findChildOfType(it, PhpClass::class.java) }
            cls ?: phpClass
        }

        ProjectLogHelper.log(project, "自动发现: 点击三级项 pattern=$pattern resolvedClass=${resolvedPhpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "自动发现搜索: $pattern", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performSearch(project, indicator, resolvedPhpClass)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        // 确保三级菜单项始终可点击
        e.presentation.isEnabled = true
        e.presentation.text = pattern
    }

    private fun performSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "收集匹配 $pattern 的方法..."
        indicator.isIndeterminate = true
        ProjectLogHelper.log(project, "自动发现: performSearch start pattern=$pattern class=${targetClass.fqn}")

        val targetMethods = mutableSetOf<Method>()
        // 优先当前类（传入的 targetClass）
        try {
            val settings = AutoDiscoverSettings.getInstance(project)
            val ignoreCase = settings.caseInsensitive
            val regex = AutoDiscoverHelper.patternToRegex(pattern, ignoreCase)
            ProjectLogHelper.log(project, "自动发现: 使用正则 regex=$regex ignoreCase=$ignoreCase")
            for (method in targetClass.methods) {
                val accessInfo = method.access
                val name = method.name ?: "<no-name>"
                ProjectLogHelper.log(project, "自动发现: 检查方法 ${targetClass.fqn}::${name}, access=${accessInfo}, methodText=${method.text.take(80)}")
                if (!method.access.isPublic) {
                    ProjectLogHelper.log(project, "自动发现: 跳过非 public 方法 ${name}")
                    continue
                }
                val matched = try {
                    regex.matches(name)
                } catch (ex: Throwable) {
                    ProjectLogHelper.log(project, "自动发现: regex.match 异常 pattern=$pattern name=$name ex=${ex.message}")
                    false
                }
                ProjectLogHelper.log(project, "自动发现: 方法匹配结果 name=$name matched=$matched")
                if (matched) {
                    targetMethods.add(method)
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "自动发现: performSearch 遍历方法时异常: ${ex.message}")
        }

        // 目前仅在当前类内匹配；如需跨项目匹配可后续扩展

        if (targetMethods.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                notifyInfo(project, "未找到匹配规则 $pattern 的方法")
            }
            ProjectLogHelper.log(project, "自动发现: performSearch 未找到方法 pattern=$pattern class=${targetClass.fqn}")
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
                ProjectLogHelper.log(project, "自动发现: performSearch 找到 usages=${usages.size} pattern=$pattern")
                showUsages(project, usages, pattern)
            } else {
                ProjectLogHelper.log(project, "自动发现: performSearch 未找到 usages pattern=$pattern")
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


