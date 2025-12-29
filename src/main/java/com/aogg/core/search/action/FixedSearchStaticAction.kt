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
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo

/**
 * 固定搜索 - 静态调用：搜索当前类或其子类的静态方法调用
 */
class FixedSearchStaticAction : AnAction("静态调用", "搜索静态方法调用", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val phpClass = resolvePhpClass(e) ?: run {
            notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-静态调用: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：静态调用", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performStaticSearch(project, indicator, phpClass)
                }
            }
        })
    }

    private fun performStaticSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索静态方法调用..."
        indicator.isIndeterminate = true

        try {
            val staticUsages = mutableListOf<UsageWithTarget>()

            // 获取目标类及其子类的所有静态方法
            val staticMethods = mutableSetOf<Method>()
            staticMethods.addAll(getStaticMethods(targetClass))

            val subClasses = getAllSubClasses(targetClass)
            for (subClass in subClasses) {
                staticMethods.addAll(getStaticMethods(subClass))
            }

            // 搜索每个静态方法的使用
            for (method in staticMethods) {
                val refs = RefSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
                for (ref in refs) {
                    val element = ref.element
                    // 检查是否是方法引用
                    val methodRef = PsiTreeUtil.getParentOfType(
                        element,
                        com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                        /* strict = */ false
                    )
                    if (methodRef != null) {
                        val range = ref.rangeInElement
                        staticUsages.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "${method.containingClass?.name}::${method.name}"
                        ))
                    }
                }
            }

            ProjectLogHelper.log(project, "固定搜索-静态调用: 找到 ${staticUsages.size} 个静态方法调用")

            ApplicationManager.getApplication().invokeLater {
                if (staticUsages.isNotEmpty()) {
                    try {
                        AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, staticUsages, "固定搜索-静态调用")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "固定搜索-静态调用: 工具窗口显示失败，回退到弹窗 ex=${ex.message}")
                        try {
                            val fallbackUsages = staticUsages.map { UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                            AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, "固定搜索-静态调用")
                        } catch (exPopup: Throwable) {
                            ProjectLogHelper.log(project, "固定搜索-静态调用: 弹窗显示失败，回退到标准用法视图 ex=${exPopup.message}")
                            val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
                            val presentation = UsageViewPresentation()
                            presentation.tabName = "固定搜索-静态调用"
                            presentation.tabText = "固定搜索-静态调用"
                            presentation.scopeText = "项目范围"
                            val usageInfosForView = staticUsages.map { it.usageInfo }
                            UsageViewManager.getInstance(project).showUsages(
                                usageTargets,
                                usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                                presentation
                            )
                        }
                    }
                } else {
                    notifyInfo(project, "未找到静态方法调用")
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-静态调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索静态方法调用失败: ${ex.message}")
            }
        }
    }

    private fun getStaticMethods(phpClass: PhpClass): List<Method> {
        return phpClass.methods.filter { it.modifier.isStatic && it.access.isPublic }
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
                collectSubClasses(subClass)
            }
        }

        collectSubClasses(phpClass)
        return result
    }

    private fun resolvePhpClass(e: AnActionEvent): PhpClass? {
        val project = e.project ?: return null

        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement is PhpClass) {
            return psiElement
        }

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
