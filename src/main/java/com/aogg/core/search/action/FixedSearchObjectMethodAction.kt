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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
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

        // 快速检查：如果处于 dumb mode，给出提示但仍然启动后台任务
        if (DumbService.isDumb(project)) {
            FixedSearchHelper.notifyInfo(project, "正在重建索引，搜索将在索引完成后开始")
        }

        val phpClass = FixedSearchHelper.resolvePhpClass(e) ?: run {
            FixedSearchHelper.notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-对象方法调用: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：对象方法调用", true) {
            override fun run(indicator: ProgressIndicator) {
                // 在后台任务中等待索引完成
                if (DumbService.isDumb(project)) {
                    indicator.text = "等待索引重建完成..."
                    try {
                        // 使用正确的等待方法
                        DumbService.getInstance(project).waitForSmartMode()
                        indicator.text = "索引重建完成，开始搜索..."
                    } catch (e: Exception) {
                        ProjectLogHelper.log(project, "等待索引完成时发生异常: ${e.message}")
                        // 如果等待失败，尝试直接继续，但会增加重试次数
                    }
                }

                // 执行搜索，包含重试机制
                performObjectMethodSearchWithRetry(project, indicator, phpClass)
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

        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 索引未准备好，搜索被中断")
            ApplicationManager.getApplication().invokeLater {
                FixedSearchHelper.notifyInfo(project, "索引重建中，请稍后再试")
            }
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
        try {
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
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，跳过此方法的搜索
            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 方法 ${method.name} 的搜索被跳过，索引未准备好")
            return
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
                // 记录详细错误日志
                ProjectLogHelper.log(project, "固定搜索-对象方法调用: 显示搜索结果失败，错误: ${ex.message}, 异常类型: ${ex.javaClass.simpleName}")

                // 显示错误通知给用户
                Notifications.Bus.notify(
                    Notification(
                        "core-search",
                        "固定搜索-对象方法调用",
                        "显示搜索结果失败: ${ex.message}",
                        NotificationType.ERROR
                    ),
                    project
                )
            }
        }
    }

    /**
     * 执行对象方法搜索，包含重试机制
     */
    private fun performObjectMethodSearchWithRetry(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        val maxRetries = 3
        var currentRetry = 0
        var lastException: Exception? = null

        while (currentRetry < maxRetries) {
            try {
                performOptimizedObjectMethodSearch(project, indicator, targetClass)
                return // 成功执行，退出重试循环
            } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
                lastException = ex
                currentRetry++

                if (currentRetry < maxRetries) {
                    ProjectLogHelper.log(project, "固定搜索-对象方法调用: 第${currentRetry}次重试，等待索引准备...")
                    indicator.text = "索引未准备好，正在重试 (${currentRetry}/${maxRetries})..."

                    try {
                        // 等待一小段时间，让索引有机会准备好
                        Thread.sleep((500 * currentRetry).toLong()) // 递增等待时间
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } catch (ex: Exception) {
                // 其他异常不重试，直接抛出
                throw ex
            }
        }

        // 如果重试多次仍然失败，抛出最后一个异常
        if (lastException != null) {
            throw lastException
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
