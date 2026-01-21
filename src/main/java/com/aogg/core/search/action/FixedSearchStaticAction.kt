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
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集静态方法（需要PSI数据）
            val staticMethods = ApplicationManager.getApplication().runReadAction<LinkedHashSet<Method>> {
                val methods = LinkedHashSet<Method>()
                collectStaticMethods(targetClass, methods, HashSet())
                methods
            }

            ProjectLogHelper.log(project, "固定搜索-静态调用: 收集到 ${staticMethods.size} 个静态方法")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示
            val batchSize = 3 // 每批处理3个方法，减少批次大小以提高响应性

            // 分批搜索，每批立即处理和显示
            val methodList = staticMethods.toList()
            for (i in methodList.indices step batchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + batchSize, methodList.size)
                val batch = methodList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / methodList.size.toDouble()
                indicator.text = "搜索静态方法调用... (${i}/${methodList.size})"

                // ⭐ 立即处理：搜索当前批次（需要PSI数据）
                val currentPage = (i / batchSize) + 1
                val totalPages = (methodList.size + batchSize - 1) / batchSize // 向上取整计算总页数
                val totalItems = methodList.size
                val currentItemIndex = i + batch.size // 当前处理的项数

                ApplicationManager.getApplication().runReadAction {
                    processMethodBatch(batch, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
                }

                // 在批次间让出控制权，让UI线程有机会响应
                ProgressManager.checkCanceled()
                Thread.sleep(10) // 短暂yield，让UI有机会响应
            }

            // 搜索完成后的最终处理
            indicator.fraction = 1.0
            indicator.text = "搜索完成"

            ApplicationManager.getApplication().invokeLater {
                if (cumulativeUsages.isEmpty()) {
                    FixedSearchHelper.notifyInfo(project, "未找到静态方法调用")
                }
            }

            ProjectLogHelper.log(project, "固定搜索-静态调用: 搜索完成，找到 ${cumulativeUsages.size} 个静态方法调用")
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-静态调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                FixedSearchHelper.notifyError(project, "搜索静态方法调用失败: ${ex.message}")
            }
        }
    }

    /**
     * 处理单个批次：搜索 + 过滤，直接累积到结果中
     * 优化：实现引用级分批，避免单个方法引用过多导致内存爆炸
     */
    private fun processMethodBatch(
        batch: List<Method>,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        // 对每个方法进行引用级分批处理
        for (method in batch) {
            processMethodReferences(method, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
        }
    }

    /**
     * 对单个方法的引用进行分批处理，避免内存爆炸
     * 优化：引用级批次也使用runReadAction，避免PSI访问阻塞UI
     */
    private fun processMethodReferences(
        method: Method,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        val methodRefsIterator = RefSearch.search(method, searchScope, false).iterator()
        val referenceBatchSize = 20 // 每次处理20个引用，避免内存占用过高

        // 使用迭代器分批获取引用，避免一次性加载所有引用
        while (methodRefsIterator.hasNext()) {
            // ⭐ 每个引用批次都独立使用runReadAction，避免长时间阻塞UI
            ApplicationManager.getApplication().runReadAction {
                val referenceBatch = mutableListOf<UsageWithTarget>()

                // 收集一批引用
                for (i in 0 until referenceBatchSize) {
                    if (!methodRefsIterator.hasNext()) break

                    val ref = methodRefsIterator.next()
                    val element = ref.element
                    val methodRef = PsiTreeUtil.getParentOfType(
                        element,
                        com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                        false
                    )

                    if (methodRef != null) {
                        val range = ref.rangeInElement
                        referenceBatch.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "${method.containingClass?.name}::${method.name}"
                        ))
                    }
                }

                // 如果收集到了引用，立即进行过滤
                if (referenceBatch.isNotEmpty()) {
                    filterRelatedStaticUsages(referenceBatch, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
                }
            }

            // ⭐ 在引用批次间yield，让UI有更多响应机会
            ProgressManager.checkCanceled()
            Thread.sleep(1) // 更短的yield时间，让UI更流畅
        }
    }

    /**
     * 增量显示搜索结果
     */
    private fun showIncrementalResults(project: Project, currentUsages: List<UsageWithTarget>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                FixedSearchHelper.showUsagesWithFallback(project, currentUsages, "固定搜索-静态调用")
            } catch (ex: Throwable) {
                // 记录详细错误日志
                ProjectLogHelper.log(project, "固定搜索-静态调用: 显示搜索结果失败，错误: ${ex.message}, 异常类型: ${ex.javaClass.simpleName}")

                // 显示错误通知给用户
                com.intellij.notification.Notifications.Bus.notify(
                    com.intellij.notification.Notification(
                        "core-search",
                        "固定搜索-静态调用",
                        "显示搜索结果失败: ${ex.message}",
                        com.intellij.notification.NotificationType.ERROR
                    ),
                    project
                )
            }
        }
    }

    private fun filterRelatedStaticUsages(
        usagesWithTarget: List<UsageWithTarget>,
        phpClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        var lastDisplayTime = System.currentTimeMillis()
        var hasNewResultsInBatch = false

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
                        cumulativeUsages.add(uwt)
                        hasNewResultsInBatch = true

                        ProjectLogHelper.log(
                            project,
                            "固定搜索-静态调用: filterRelatedStaticUsages 保留静态调用 classReference=$className, targetClass=${phpClass.fqn} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                        )

                        // 检查时间间隔或结果数量，如果超过5秒或累积了10个结果立即显示
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDisplayTime > 5000 || cumulativeUsages.size % 10 == 0) { // 5秒或每10个结果
                            showIncrementalResults(project, cumulativeUsages.toList())
                            lastDisplayTime = currentTime
                            hasNewResultsInBatch = false // 重置标记，因为已经显示了
                        }
                    } else {
                        // 静态调用但不相关，跳过
                        ProjectLogHelper.log(
                            project,
                            "固定搜索-静态调用: filterRelatedStaticUsages 跳过不相关的静态调用 classReference=$className, targetClass=${phpClass.fqn}, relatedFqns=${relatedClassFqns.joinToString()} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                        )
                    }
                } else {
                    // 动态调用（通过变量），跳过
                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-静态调用: filterRelatedStaticUsages 跳过动态调用 classReference=$className [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                    )
                }
            } else {
                // 没有classReference，可能不是方法调用，跳过
                ProjectLogHelper.log(
                    element.project,
                    "固定搜索-静态调用: filterRelatedStaticUsages 跳过无classReference的调用 [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                )
            }
        }

        // 如果这一批有新结果但还没显示（时间间隔不足5秒），在这里显示
        if (hasNewResultsInBatch) {
            showIncrementalResults(project, cumulativeUsages.toList())
        }
    }

    private fun collectStaticMethods(phpClass: PhpClass, result: MutableSet<Method>, visited: MutableSet<String>) {
        val fqn = phpClass.fqn ?: return
        if (visited.contains(fqn)) return
        visited.add(fqn)

        // 添加当前类的静态方法
        val currentMethods = phpClass.methods.filter { it.modifier.isStatic && !it.access.isPrivate }
        result.addAll(currentMethods)

        // 递归收集子类的静态方法
        try {
            val phpIndex = com.jetbrains.php.PhpIndex.getInstance(phpClass.project)
            val subClasses = phpIndex.getAllSubclasses(phpClass.fqn)
            for (subClass in subClasses) {
                collectStaticMethods(subClass, result, visited)
            }
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，跳过子类收集
            ProjectLogHelper.log(phpClass.project, "FixedSearchStaticAction: collectStaticMethods 索引未准备好，跳过子类收集 class=${phpClass.fqn}")
        }
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
