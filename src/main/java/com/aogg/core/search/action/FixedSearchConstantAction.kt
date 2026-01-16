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
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo

/**
 * 固定搜索 - 常量：搜索当前类或其子类的常量使用
 */
class FixedSearchConstantAction : AnAction("常量", "搜索常量使用", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            notifyInfo(project, "正在重建索引，请稍后再试")
            return
        }

        val phpClass = resolvePhpClass(e) ?: run {
            notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-常量: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：常量", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performConstantSearch(project, indicator, phpClass)
                }
            }
        })
    }

    private fun performConstantSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索常量使用..."
        indicator.isIndeterminate = true

        try {
            val constantUsages = mutableListOf<UsageWithTarget>()

            // 获取目标类及其子类的所有常量
            val constants = mutableSetOf<Field>()
            constants.addAll(getConstants(targetClass))

            val subClasses = getAllSubClasses(targetClass)
            for (subClass in subClasses) {
                constants.addAll(getConstants(subClass))
            }

            // 搜索每个常量的使用
            for (constant in constants) {
                val refs = RefSearch.search(constant, GlobalSearchScope.projectScope(project), false).findAll()
                for (ref in refs) {
                    val element = ref.element
                    val range = ref.rangeInElement
                    constantUsages.add(UsageWithTarget(
                        UsageInfo(element, range.startOffset, range.endOffset, true),
                        "${constant.containingClass?.name}::${constant.name}"
                    ))
                }
            }

            ProjectLogHelper.log(project, "固定搜索-常量: 找到 ${constantUsages.size} 个常量使用")

            ApplicationManager.getApplication().invokeLater {
                if (constantUsages.isNotEmpty()) {
                    try {
                        AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, constantUsages, "固定搜索-常量")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "固定搜索-常量: 工具窗口显示失败，回退到弹窗 ex=${ex.message}")
                        try {
                            val fallbackUsages = constantUsages.map { UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                            AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, "固定搜索-常量")
                        } catch (exPopup: Throwable) {
                            ProjectLogHelper.log(project, "固定搜索-常量: 弹窗显示失败，回退到标准用法视图 ex=${exPopup.message}")
                            val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
                            val presentation = UsageViewPresentation()
                            presentation.tabName = "固定搜索-常量"
                            presentation.tabText = "固定搜索-常量"
                            presentation.scopeText = "项目范围"
                            val usageInfosForView = constantUsages.map { it.usageInfo }
                            UsageViewManager.getInstance(project).showUsages(
                                usageTargets,
                                usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                                presentation
                            )
                        }
                    }
                } else {
                    notifyInfo(project, "未找到常量使用")
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-常量: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索常量使用失败: ${ex.message}")
            }
        }
    }

    private fun getConstants(phpClass: PhpClass): List<Field> {
        // 暂时简化：获取所有字段，实际使用时再过滤常量
        return phpClass.fields.filter { true } // 后续优化常量检查逻辑
    }

    private fun getAllSubClasses(phpClass: PhpClass): Set<PhpClass> {
        val result = mutableSetOf<PhpClass>()
        val visited = mutableSetOf<String>()

        fun collectSubClasses(cls: PhpClass) {
            val fqn = cls.fqn ?: return
            if (visited.contains(fqn)) return
            visited.add(fqn)

            try {
                val phpIndex = com.jetbrains.php.PhpIndex.getInstance(cls.project)
                val subClasses = phpIndex.getAllSubclasses(cls.fqn)
                for (subClass in subClasses) {
                    result.add(subClass)
                    collectSubClasses(subClass)
                }
            } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
                // 索引未准备好，跳过子类收集
                ProjectLogHelper.log(cls.project, "FixedSearchConstantAction: getAllSubClasses 索引未准备好，跳过子类收集 class=${cls.fqn}")
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
