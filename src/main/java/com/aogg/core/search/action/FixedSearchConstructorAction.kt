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

        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            notifyInfo(project, "正在重建索引，请稍后再试")
            return
        }

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
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集相关类（目标类及其子类）
            val relatedClasses = ApplicationManager.getApplication().runReadAction<LinkedHashSet<PhpClass>> {
                val classes = LinkedHashSet<PhpClass>()
                collectRelatedClasses(targetClass, classes, HashSet())
                classes
            }

            ProjectLogHelper.log(project, "固定搜索-构造调用: 收集到 ${relatedClasses.size} 个相关类")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示
            val batchSize = 3 // 每批处理3个类，减少批次大小以提高响应性

            // 分批搜索，每批立即处理和显示
            val classList = relatedClasses.toList()
            for (i in classList.indices step batchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + batchSize, classList.size)
                val batch = classList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / classList.size.toDouble()
                indicator.text = "搜索构造调用... (${i}/${classList.size})"

                // ⭐ 立即处理：搜索当前批次（需要PSI数据）
                val currentPage = (i / batchSize) + 1
                val totalPages = (classList.size + batchSize - 1) / batchSize // 向上取整计算总页数
                val totalItems = classList.size
                val currentItemIndex = i + batch.size // 当前处理的项数

                ApplicationManager.getApplication().runReadAction {
                    processClassBatch(batch, searchScope, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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
                    notifyInfo(project, "未找到构造调用")
                }
            }

            ProjectLogHelper.log(project, "固定搜索-构造调用: 搜索完成，找到 ${cumulativeUsages.size} 个构造调用")
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-构造调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索构造调用失败: ${ex.message}")
            }
        }
    }

    /**
     * 处理单个批次：搜索类的构造调用
     * 优化：实现引用级分批，避免单个类引用过多导致内存爆炸
     */
    private fun processClassBatch(
        batch: List<PhpClass>,
        searchScope: GlobalSearchScope,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        // 对每个类进行引用级分批处理
        for (cls in batch) {
            processClassReferences(cls, searchScope, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
        }
    }

    /**
     * 对单个类的引用进行分批处理，避免内存爆炸
     * 优化：引用级批次也使用runReadAction，避免PSI访问阻塞UI
     */
    private fun processClassReferences(
        cls: PhpClass,
        searchScope: GlobalSearchScope,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        val classRefsIterator = RefSearch.search(cls, searchScope, false).iterator()
        val referenceBatchSize = 20 // 每次处理20个引用，避免内存占用过高

        // 使用迭代器分批获取引用，避免一次性加载所有引用
        while (classRefsIterator.hasNext()) {
            // ⭐ 每个引用批次都独立使用runReadAction，避免长时间阻塞UI
            ApplicationManager.getApplication().runReadAction {
                val referenceBatch = mutableListOf<UsageWithTarget>()

                // 收集一批引用
                for (i in 0 until referenceBatchSize) {
                    if (!classRefsIterator.hasNext()) break

                    val ref = classRefsIterator.next()
                    val element = ref.element
                    // 检查是否是new表达式
                    val newExpression = PsiTreeUtil.getParentOfType(
                        element,
                        com.jetbrains.php.lang.psi.elements.NewExpression::class.java,
                        false
                    )

                    if (newExpression != null) {
                        val range = ref.rangeInElement
                        referenceBatch.add(UsageWithTarget(
                            UsageInfo(element, range.startOffset, range.endOffset, true),
                            "new ${cls.name}"
                        ))
                    }
                }

                // 如果收集到了引用，立即添加到累积结果中
                if (referenceBatch.isNotEmpty()) {
                    cumulativeUsages.addAll(referenceBatch)

                    // 增量显示：检查时间间隔或结果数量，如果超过5秒或累积了10个结果立即显示
                    val currentTime = System.currentTimeMillis()
                    val lastDisplayTime = cumulativeUsages.size // 简化实现，使用累积数量作为时间戳
                    if (cumulativeUsages.size % 10 == 0) { // 每10个结果显示一次
                        showIncrementalResults(project, cumulativeUsages.toList())
                    }
                }
            }

            // ⭐ 在引用批次间yield，让UI有更多响应机会
            ProgressManager.checkCanceled()
            Thread.sleep(1) // 更短的yield时间，让UI更流畅
        }

        // 如果这一批有新结果但还没显示，确保最后一次显示
        if (cumulativeUsages.isNotEmpty() && cumulativeUsages.size % 10 != 0) {
            showIncrementalResults(project, cumulativeUsages.toList())
        }
    }

    /**
     * 增量显示搜索结果
     */
    private fun showIncrementalResults(project: Project, currentUsages: List<UsageWithTarget>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, currentUsages, "固定搜索-构造调用")
            } catch (ex: Throwable) {
                // 记录详细错误日志
                ProjectLogHelper.log(project, "固定搜索-构造调用: 显示搜索结果失败，错误: ${ex.message}, 异常类型: ${ex.javaClass.simpleName}")

                // 显示错误通知给用户
                Notifications.Bus.notify(
                    Notification(
                        "core-search",
                        "固定搜索-构造调用",
                        "显示搜索结果失败: ${ex.message}",
                        NotificationType.ERROR
                    ),
                    project
                )
            }
        }
    }

    private fun collectRelatedClasses(phpClass: PhpClass, result: MutableSet<PhpClass>, visited: MutableSet<String>) {
        val fqn = phpClass.fqn ?: return
        if (visited.contains(fqn)) return
        visited.add(fqn)

        // 添加当前类
        result.add(phpClass)

        // 递归收集子类
        try {
            val phpIndex = com.jetbrains.php.PhpIndex.getInstance(phpClass.project)
            val subClasses = phpIndex.getAllSubclasses(phpClass.fqn)
            for (subClass in subClasses) {
                collectRelatedClasses(subClass, result, visited)
            }
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，跳过子类收集
            ProjectLogHelper.log(phpClass.project, "FixedSearchConstructorAction: collectRelatedClasses 索引未准备好，跳过子类收集 class=${phpClass.fqn}")
        }
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
                    collectSubClasses(subClass) // 递归收集子类的子类
                }
            } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
                // 索引未准备好，跳过子类收集
                ProjectLogHelper.log(cls.project, "FixedSearchConstructorAction: getAllSubClasses 索引未准备好，跳过子类收集 class=${cls.fqn}")
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
