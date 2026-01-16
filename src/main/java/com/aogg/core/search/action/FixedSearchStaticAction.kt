package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.aogg.core.search.helper.FixedSearchHelper
import com.aogg.core.search.model.UsageWithTarget
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo

/**
 * 固定搜索 - 静态调用：搜索当前类或其子类的静态方法调用
 */
class FixedSearchStaticAction : AnAction("静态调用", "搜索静态方法调用", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            FixedSearchHelper.notifyInfo(project, "正在重建索引，请稍后再试")
            return
        }

        val phpClass = FixedSearchHelper.resolvePhpClass(e) ?: run {
            FixedSearchHelper.notifyError(project, "未找到 PHP 类")
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
            val targetClassMethods = getStaticMethods(targetClass)
            staticMethods.addAll(targetClassMethods)
            ProjectLogHelper.log(project, "固定搜索-静态调用: 目标类 ${targetClass.fqn} 有 ${targetClassMethods.size} 个静态方法")

            val subClasses = getAllSubClasses(targetClass)
            ProjectLogHelper.log(project, "固定搜索-静态调用: 找到 ${subClasses.size} 个子类")
            for (subClass in subClasses) {
                val subClassMethods = getStaticMethods(subClass)
                staticMethods.addAll(subClassMethods)
                if (subClassMethods.isNotEmpty()) {
                    ProjectLogHelper.log(project, "固定搜索-静态调用: 子类 ${subClass.fqn} 有 ${subClassMethods.size} 个静态方法")
                }
            }
            ProjectLogHelper.log(project, "固定搜索-静态调用: 总共收集到 ${staticMethods.size} 个静态方法")

            // 使用项目范围搜索静态方法调用，因为静态方法可以在任何地方被调用
            val searchScope = GlobalSearchScope.projectScope(project)

            // 搜索每个静态方法的使用
            val rawStaticUsages = mutableListOf<UsageWithTarget>()
            for (method in staticMethods) {
                val refs = RefSearch.search(method, searchScope, false).findAll()
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
                        rawStaticUsages.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "${method.containingClass?.name}::${method.name}"
                        ))
                    }
                }
            }

            // 过滤：只保留真正的静态调用（通过类名直接调用）
            val filteredStaticUsages = filterRelatedStaticUsages(rawStaticUsages, targetClass)
            ProjectLogHelper.log(project, "固定搜索-静态调用: 找到 ${filteredStaticUsages.size} 个静态方法调用 (raw=${rawStaticUsages.size})")

            ApplicationManager.getApplication().invokeLater {
                if (filteredStaticUsages.isNotEmpty()) {
                    FixedSearchHelper.showUsagesWithFallback(project, filteredStaticUsages, "固定搜索-静态调用")
                } else {
                    FixedSearchHelper.notifyInfo(project, "未找到静态方法调用")
                }
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-静态调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                FixedSearchHelper.notifyError(project, "搜索静态方法调用失败: ${ex.message}")
            }
        }
    }

    private fun filterRelatedStaticUsages(usagesWithTarget: List<UsageWithTarget>, phpClass: PhpClass): List<UsageWithTarget> {
        val filtered = mutableListOf<UsageWithTarget>()
        val project = phpClass.project

        // 获取目标类及其子类的FQN集合，用于快速校验
        val relatedClassFqns = mutableSetOf<String>()
        relatedClassFqns.add(phpClass.fqn ?: "")
        val subClasses = getAllSubClasses(phpClass)
        for (subClass in subClasses) {
            subClass.fqn?.let { relatedClassFqns.add(it) }
        }

        for (uwt in usagesWithTarget) {
            val usage = uwt.usageInfo
            val element = usage.element ?: continue

            // 查找包含此元素的方法引用
            val methodRef = PsiTreeUtil.getParentOfType(
                element,
                com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                /* strict = */ false
            ) ?: continue

            // 检查是否为真正的静态调用：classReference存在且不以$开头
            val classReference = methodRef.classReference
            if (classReference != null) {
                val className = classReference.text
                if (className.isNotEmpty() && !className.startsWith("$")) {
                    // 进一步校验：解析classReference指向的类是否属于目标类或其子类
                    val phpIndex = com.jetbrains.php.PhpIndex.getInstance(project)

                    var isRelated = false

                    // 首先尝试按完整FQN解析
                    val resolvedClassesByFQN = phpIndex.getAnyByFQN(className)
                    for (resolvedClass in resolvedClassesByFQN) {
                        if (resolvedClass is PhpClass) {
                            val resolvedFqn = resolvedClass.fqn
                            if (resolvedFqn != null && relatedClassFqns.contains(resolvedFqn)) {
                                isRelated = true
                                break
                            }
                        }
                    }

                    // 如果没找到，尝试按类名解析（在当前文件中查找）
                    if (!isRelated && element.containingFile != null) {
                        val classesInFile = phpIndex.getClassesByName(className)
                        for (resolvedClass in classesInFile) {
                            if (resolvedClass is PhpClass) {
                                val resolvedFqn = resolvedClass.fqn
                                if (resolvedFqn != null && relatedClassFqns.contains(resolvedFqn)) {
                                    isRelated = true
                                    break
                                }
                                // 检查是否是目标类本身（类名匹配）
                                if (resolvedClass.name == phpClass.name) {
                                    isRelated = true
                                    break
                                }
                            }
                        }
                    }

                    // 兜底检查：如果className就是目标类的类名
                    if (!isRelated && className == phpClass.name) {
                        isRelated = true
                    }

                    if (isRelated) {
                        // 是相关的静态调用，保留
                        filtered.add(uwt)
                        ProjectLogHelper.log(
                            project,
                            "固定搜索-静态调用: filterRelatedStaticUsages 保留静态调用 classReference=$className, targetClass=${phpClass.fqn}"
                        )
                    } else {
                        // 静态调用但不相关，跳过
                        ProjectLogHelper.log(
                            project,
                            "固定搜索-静态调用: filterRelatedStaticUsages 跳过不相关的静态调用 classReference=$className, targetClass=${phpClass.fqn}, relatedFqns=${relatedClassFqns.joinToString()}"
                        )
                    }
                } else {
                    // 动态调用（通过变量），跳过
                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-静态调用: filterRelatedStaticUsages 跳过动态调用 classReference=$className"
                    )
                }
            } else {
                // 没有classReference，可能不是方法调用，跳过
                ProjectLogHelper.log(
                    element.project,
                    "固定搜索-静态调用: filterRelatedStaticUsages 跳过无classReference的调用"
                )
            }
        }
        return filtered
    }

    private fun getStaticMethods(phpClass: PhpClass): List<Method> {
        // 包含 public、protected 和 package-private 的静态方法（排除 private）
        return phpClass.methods.filter { it.modifier.isStatic && !it.access.isPrivate }
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
                ProjectLogHelper.log(cls.project, "FixedSearchStaticAction: getAllSubClasses 索引未准备好，跳过子类收集 class=${cls.fqn}")
            }
        }

        collectSubClasses(phpClass)
        return result
    }

}
