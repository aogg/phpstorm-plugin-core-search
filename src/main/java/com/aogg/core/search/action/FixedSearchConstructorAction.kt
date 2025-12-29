package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.aogg.core.search.model.UsageWithTarget
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.UsageViewManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo

/**
 * 固定搜索 - 构造调用：搜索当前类或其子类的new构造调用
 */
class FixedSearchConstructorAction : AnAction("构造调用", "搜索构造调用", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val phpClass = resolvePhpClass(e) ?: run {
            notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-构造调用: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：构造调用", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performConstructorSearch(project, indicator, phpClass)
                }
            }
        })
    }

    private fun performConstructorSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索构造调用..."
        indicator.isIndeterminate = true

        try {
            // 收集所有new表达式的使用位置
            val newUsages = mutableListOf<UsageWithTarget>()

            // 搜索直接new targetClass的调用
            val directRefs = RefSearch.search(targetClass, GlobalSearchScope.projectScope(project), false).findAll()
            for (ref in directRefs) {
                val element = ref.element
                // 检查是否是new表达式
                val newExpression = PsiTreeUtil.getParentOfType(
                    element,
                    com.jetbrains.php.lang.psi.elements.NewExpression::class.java,
                    /* strict = */ false
                )
                if (newExpression != null) {
                    val range = ref.rangeInElement
                    newUsages.add(UsageWithTarget(
                        UsageInfo(element, range.startOffset, range.endOffset, true),
                        "new ${targetClass.name}"
                    ))
                }
            }

            // 搜索子类的构造调用
            val subClasses = getAllSubClasses(targetClass)
            for (subClass in subClasses) {
                val subRefs = RefSearch.search(subClass, GlobalSearchScope.projectScope(project), false).findAll()
                for (ref in subRefs) {
                    val element = ref.element
                    val newExpression = PsiTreeUtil.getParentOfType(
                        element,
                        com.jetbrains.php.lang.psi.elements.NewExpression::class.java,
                        /* strict = */ false
                    )
                    if (newExpression != null) {
                        val range = ref.rangeInElement
                        newUsages.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "new ${subClass.name}"
                        ))
                    }
                }
            }

            ProjectLogHelper.log(project, "固定搜索-构造调用: 找到 ${newUsages.size} 个构造调用")

            ApplicationManager.getApplication().invokeLater {
                if (newUsages.isNotEmpty()) {
                    try {
                        AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, newUsages, "固定搜索-构造调用")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "固定搜索-构造调用: 工具窗口显示失败，回退到弹窗 ex=${ex.message}")
                        try {
                            val fallbackUsages = newUsages.map { UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                            AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, "固定搜索-构造调用")
                        } catch (exPopup: Throwable) {
                            ProjectLogHelper.log(project, "固定搜索-构造调用: 弹窗显示失败，回退到标准用法视图 ex=${exPopup.message}")
                            val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
                            val presentation = UsageViewPresentation()
                            presentation.tabName = "固定搜索-构造调用"
                            presentation.tabText = "固定搜索-构造调用"
                            presentation.scopeText = "项目范围"
                            val usageInfosForView = newUsages.map { it.usageInfo }
                            UsageViewManager.getInstance(project).showUsages(
                                usageTargets,
                                usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                                presentation
                            )
                        }
                    }
                } else {
                    notifyInfo(project, "未找到构造调用")
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-构造调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索构造调用失败: ${ex.message}")
            }
        }
    }

    private fun getAllSubClasses(phpClass: PhpClass): Set<PhpClass> {
        val result = mutableSetOf<PhpClass>()
        val visited = mutableSetOf<String>()

        fun collectSubClasses(cls: PhpClass) {
            val fqn = cls.fqn ?: return
            if (visited.contains(fqn)) return
            visited.add(fqn)

            val phpIndex = com.jetbrains.php.PhpIndex.getInstance(cls.project)
            val subClasses = phpIndex.getAllSubclasses(cls.fqn)
            for (subClass in subClasses) {
                result.add(subClass)
                collectSubClasses(subClass) // 递归收集子类的子类
            }
        }

        collectSubClasses(phpClass)
        return result
    }

    private fun resolvePhpClass(e: AnActionEvent): PhpClass? {
        val project = e.project ?: return null

        // 优先使用PSI元素
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement is PhpClass) {
            return psiElement
        }

        // 从PSI文件解析
        var psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile == null) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (editor != null) {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            }
        }
        if (psiFile == null) {
            val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (virtualFile != null) {
                psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            }
        }

        if (psiFile != null && psiFile.name.endsWith(".php", ignoreCase = true)) {
            return PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)
        }

        return null
    }

    private fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "固定搜索",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }

    private fun notifyError(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "固定搜索",
                content,
                NotificationType.ERROR
            ),
            project
        )
    }
}
