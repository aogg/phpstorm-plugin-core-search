package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.aogg.core.search.helper.SearchFilterHelper
import com.aogg.core.search.helper.FixedSearchHelper
import com.aogg.core.search.model.UsageWithTarget
import com.aogg.core.search.service.ObjectMethodSearchService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpExpression
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.PhpIndex
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo
import com.intellij.psi.util.PsiTreeUtil

/**
 * 固定搜索 - 对象方法调用：搜索当前类的对象方法调用（包含父类方法）
 * 实现分批处理性能优化：方法级分批和引用级分批
 */
class FixedSearchObjectMethodAction : AnAction("对象方法调用", "搜索对象方法调用", null) {

    // 分批处理配置 - 根据性能优化文档实现
    private val methodBatchSize = 3 // 方法级分批：每批处理3个方法
    private val referenceBatchSize = 20 // 引用级分批：每批处理20个引用，避免内存爆炸

    private val searchService = ObjectMethodSearchService()
    private val filterHelper = SearchFilterHelper.forObjectMethodSearch()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 检查是否处于 dumb mode（索引重建期间）
        if (DumbService.isDumb(project)) {
            FixedSearchHelper.notifyInfo(project, "正在重建索引，请稍后再试")
            return
        }

        val phpClass = FixedSearchHelper.resolvePhpClass(e) ?: run {
            FixedSearchHelper.notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-对象方法调用: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：对象方法调用", true) {
            override fun run(indicator: ProgressIndicator) {
                performOptimizedObjectMethodSearch(project, indicator, phpClass)
            }
        })
    }

    /**
     * 执行优化的对象方法调用搜索
     * 实现分批处理：方法级分批和引用级分批
     */
    private fun performOptimizedObjectMethodSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索对象方法调用..."
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集对象方法（需要PSI数据）
            val objectMethods = ApplicationManager.getApplication().runReadAction<LinkedHashSet<Pair<Method, String>>> {
                val methods = LinkedHashSet<Pair<Method, String>>()
                filterHelper.collectObjectMethods(targetClass, methods, HashSet())
                methods
            }

            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 收集到 ${objectMethods.size} 个对象方法")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示

            // 方法级分批：将方法分组，每批处理 methodBatchSize 个方法
            val methodList = objectMethods.toList()
            for (i in methodList.indices step methodBatchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + methodBatchSize, methodList.size)
                val methodBatch = methodList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / methodList.size.toDouble()
                indicator.text = "搜索对象方法调用... (${i}/${methodList.size})"

                // 处理当前方法批次
                ApplicationManager.getApplication().runReadAction {
                    processMethodBatch(methodBatch, searchScope, targetClass, project, cumulativeUsages)
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
                    FixedSearchHelper.notifyInfo(project, "未找到对象方法调用")
                }
            }

            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 搜索完成，找到 ${cumulativeUsages.size} 个对象方法调用")

        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                FixedSearchHelper.notifyError(project, "搜索对象方法调用失败: ${ex.message}")
            }
        }
    }

    /**
     * 处理方法批次 - 方法级分批的核心逻辑
     */
    private fun processMethodBatch(
        methodBatch: List<Pair<Method, String>>,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>
    ) {
        // 对每个方法进行引用级分批处理
        for ((method, className) in methodBatch) {
            processMethodReferencesWithBatching(method, className, searchScope, targetClass, project, cumulativeUsages)
        }
    }

    /**
     * 对单个方法的引用进行分批处理 - 引用级分批的核心逻辑
     */
    private fun processMethodReferencesWithBatching(
        method: Method,
        className: String,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>
    ) {
        val methodRefsIterator = RefSearch.search(method, searchScope, false).iterator()

        // 引用级分批：每次处理 referenceBatchSize 个引用，避免内存占用过高
        while (methodRefsIterator.hasNext()) {
            ApplicationManager.getApplication().runReadAction {
                val referenceBatch = mutableListOf<UsageWithTarget>()

                // 收集一批引用
                for (i in 0 until referenceBatchSize) {
                    if (!methodRefsIterator.hasNext()) break

                    val ref = methodRefsIterator.next()
                    val element = ref.element
                    val methodRef = PsiTreeUtil.getParentOfType(
                        element,
                        MethodReference::class.java,
                        false
                    )

                    if (methodRef != null) {
                        val range = ref.rangeInElement
                        referenceBatch.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "对象调用 — ${method.name}()（定义: $className）"
                        ))
                    }
                }

                // 过滤当前引用批次
                if (referenceBatch.isNotEmpty()) {
                    filterRelatedObjectUsages(referenceBatch, targetClass, project, cumulativeUsages)
                }
            }

            // 在引用批次间yield，让UI有更多响应机会
            ProgressManager.checkCanceled()
            Thread.sleep(1)
        }
    }

    /**
     * 过滤相关对象调用
     */
    private fun filterRelatedObjectUsages(
        usagesWithTarget: List<UsageWithTarget>,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>
    ) {
        var lastDisplayTime = System.currentTimeMillis()
        var hasNewResultsInBatch = false

        val (updatedLastDisplayTime, updatedHasNewResults, batchHasNewResults) = filterHelper.filterRelatedObjectUsages(
            usagesWithTarget,
            targetClass,
            project,
            cumulativeUsages,
            lastDisplayTime,
            hasNewResultsInBatch,
            { results -> showIncrementalResults(project, results) }
        )

        lastDisplayTime = updatedLastDisplayTime
        hasNewResultsInBatch = updatedHasNewResults

        // 如果这一批有新结果但还没显示，显示
        if (hasNewResultsInBatch) {
            showIncrementalResults(project, cumulativeUsages.toList())
        }
    }

    // 移除不再需要的辅助方法，因为它们现在在 SearchFilterHelper 中
    // isTargetClassInstance, resolveClassFqn, collectObjectMethods 等方法已移至助手类


    /**
     * 增量显示搜索结果
     */
    private fun showIncrementalResults(project: Project, currentUsages: List<UsageWithTarget>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, currentUsages, "固定搜索-对象方法调用")
            } catch (ex: Throwable) {
                try {
                    val fallbackUsages = currentUsages.map { com.intellij.usages.UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                    AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, "固定搜索-对象方法调用")
                } catch (exPopup: Throwable) {
                    showUsagesInStandardView(project, currentUsages)
                }
            }
        }
    }

    private fun showUsagesInStandardView(project: Project, usages: List<UsageWithTarget>) {
        val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
        val presentation = com.intellij.usages.UsageViewPresentation()
        presentation.tabName = "固定搜索-对象方法调用"
        presentation.tabText = "固定搜索-对象方法调用"
        presentation.scopeText = "项目范围"
        val usageInfosForView = usages.map { it.usageInfo }
        com.intellij.usages.UsageViewManager.getInstance(project).showUsages(
            usageTargets,
            usageInfosForView.map { com.intellij.usages.UsageInfo2UsageAdapter(it) }.toTypedArray(),
            presentation
        )
    }
}
