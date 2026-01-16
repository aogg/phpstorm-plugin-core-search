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
 * 固定搜索 - 属性：搜索当前类或其子类的属性访问
 */
class FixedSearchPropertyAction : AnAction("属性", "搜索属性访问", null) {

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

        ProjectLogHelper.log(project, "固定搜索-属性: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：属性", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performPropertySearch(project, indicator, phpClass)
                }
            }
        })
    }

    private fun performPropertySearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索属性访问..."
        indicator.isIndeterminate = true

        try {

            // 获取目标类及其子类的所有属性（非静态的public字段）
            val properties = mutableSetOf<Field>()
            properties.addAll(getProperties(targetClass))

            val subClasses = getAllSubClasses(targetClass)
            for (subClass in subClasses) {
                properties.addAll(getProperties(subClass))
            }

            // 搜索每个属性的使用
            val rawPropertyUsages = mutableListOf<UsageWithTarget>()
            for (property in properties) {
                val refs = RefSearch.search(property, GlobalSearchScope.projectScope(project), false).findAll()
                for (ref in refs) {
                    val element = ref.element
                    val range = ref.rangeInElement
                    rawPropertyUsages.add(UsageWithTarget(
                        UsageInfo(element, range.startOffset, range.endOffset, true),
                        "${property.containingClass?.name}->${property.name}"
                    ))
                }
            }

            // 过滤：只保留相关类的属性访问
            val filteredPropertyUsages = filterRelatedPropertyUsages(rawPropertyUsages, targetClass)
            ProjectLogHelper.log(project, "固定搜索-属性: 找到 ${filteredPropertyUsages.size} 个属性访问 (raw=${rawPropertyUsages.size})")

            ApplicationManager.getApplication().invokeLater {
                if (filteredPropertyUsages.isNotEmpty()) {
                    try {
                        AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, filteredPropertyUsages, "固定搜索-属性")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "固定搜索-属性: 工具窗口显示失败，回退到弹窗 ex=${ex.message}")
                        try {
                            val fallbackUsages = filteredPropertyUsages.map { UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                            AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, "固定搜索-属性")
                        } catch (exPopup: Throwable) {
                            ProjectLogHelper.log(project, "固定搜索-属性: 弹窗显示失败，回退到标准用法视图 ex=${exPopup.message}")
                            val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
                            val presentation = UsageViewPresentation()
                            presentation.tabName = "固定搜索-属性"
                            presentation.tabText = "固定搜索-属性"
                            presentation.scopeText = "项目范围"
                            val usageInfosForView = filteredPropertyUsages.map { it.usageInfo }
                            UsageViewManager.getInstance(project).showUsages(
                                usageTargets,
                                usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                                presentation
                            )
                        }
                    }
                } else {
                    notifyInfo(project, "未找到属性访问")
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-属性: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索属性访问失败: ${ex.message}")
            }
        }
    }

    private fun getProperties(phpClass: PhpClass): List<Field> {
        // 暂时简化：获取所有非静态字段，实际使用时再过滤public属性
        return phpClass.fields.filter { !it.modifier.isStatic } // 后续优化访问级别检查逻辑
    }

    /**
     * 过滤属性使用：只保留相关类的属性访问
     * 对于属性访问，只有在当前类或其子类的方法中访问才算相关
     */
    private fun filterRelatedPropertyUsages(usagesWithTarget: List<UsageWithTarget>, phpClass: PhpClass): List<UsageWithTarget> {
        val filtered = mutableListOf<UsageWithTarget>()
        for (uwt in usagesWithTarget) {
            val usage = uwt.usageInfo
            val element = usage.element
            if (element == null) {
                ProjectLogHelper.log(phpClass.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 element 为空")
                continue
            }

            // 检查属性访问是否发生在相关类的方法中
            val containingMethod = PsiTreeUtil.getParentOfType(
                element,
                com.jetbrains.php.lang.psi.elements.Method::class.java,
                /* strict = */ false
            )

            if (containingMethod != null) {
                val containingClass = containingMethod.containingClass
                if (containingClass != null && isClassRelated(containingClass, phpClass)) {
                    filtered.add(uwt)
                } else {
                    try {
                        val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                        ProjectLogHelper.log(
                            element.project,
                            "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在相关类中, class=${containingClass?.fqn ?: "<no-class>"}, file=$filePath"
                        )
                    } catch (_: Throwable) {
                        ProjectLogHelper.log(element.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在相关类中，且读取信息失败")
                    }
                }
            } else {
                // 如果不在方法中，可能是全局作用域或其他情况，暂时跳过
                try {
                    val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在方法中, file=$filePath textPreview=${element.text.take(200)}"
                    )
                } catch (_: Throwable) {
                    ProjectLogHelper.log(element.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在方法中，且读取信息失败")
                }
            }
        }
        return filtered
    }

    private fun isClassRelated(class1: PhpClass, class2: PhpClass): Boolean {
        if (class1 == class2) return true
        if (isSubclassOf(class1, class2)) return true
        return false
    }

    private fun isSubclassOf(child: PhpClass, parent: PhpClass): Boolean {
        val visited = mutableSetOf<String>()
        return checkInheritance(child, parent, visited)
    }

    private fun checkInheritance(child: PhpClass, parent: PhpClass, visited: MutableSet<String>): Boolean {
        val childFqn = child.fqn ?: return false
        if (visited.contains(childFqn)) return false
        visited.add(childFqn)
        val supers = child.supers
        for (s in supers) {
            if (s is PhpClass) {
                if (s == parent) return true
                if (checkInheritance(s, parent, visited)) return true
            }
        }
        return false
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
                ProjectLogHelper.log(cls.project, "FixedSearchPropertyAction: getAllSubClasses 索引未准备好，跳过子类收集 class=${cls.fqn}")
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
