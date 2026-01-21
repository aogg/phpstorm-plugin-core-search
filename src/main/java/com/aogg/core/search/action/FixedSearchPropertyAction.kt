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
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集属性（需要PSI数据）
            val properties = ApplicationManager.getApplication().runReadAction<LinkedHashSet<Field>> {
                val fields = LinkedHashSet<Field>()
                collectProperties(targetClass, fields, HashSet())
                fields
            }

            ProjectLogHelper.log(project, "固定搜索-属性: 收集到 ${properties.size} 个属性")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示
            val batchSize = 3 // 每批处理3个属性，减少批次大小以提高响应性

            // 分批搜索，每批立即处理和显示
            val propertyList = properties.toList()
            for (i in propertyList.indices step batchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + batchSize, propertyList.size)
                val batch = propertyList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / propertyList.size.toDouble()
                indicator.text = "搜索属性访问... (${i}/${propertyList.size})"

                // ⭐ 立即处理：搜索当前批次（需要PSI数据）
                val currentPage = (i / batchSize) + 1
                val totalPages = (propertyList.size + batchSize - 1) / batchSize // 向上取整计算总页数
                val totalItems = propertyList.size
                val currentItemIndex = i + batch.size // 当前处理的项数

                ApplicationManager.getApplication().runReadAction {
                    processPropertyBatch(batch, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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
                    notifyInfo(project, "未找到属性访问")
                }
            }

            ProjectLogHelper.log(project, "固定搜索-属性: 搜索完成，找到 ${cumulativeUsages.size} 个属性访问")
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-属性: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索属性访问失败: ${ex.message}")
            }
        }
    }

    private fun collectProperties(phpClass: PhpClass, result: MutableSet<Field>, visited: MutableSet<String>) {
        val fqn = phpClass.fqn ?: return
        if (visited.contains(fqn)) return
        visited.add(fqn)

        // 添加当前类的非静态属性
        val currentProperties = phpClass.fields.filter { !it.modifier.isStatic }
        result.addAll(currentProperties)

        // 递归收集子类的非静态属性
        try {
            val phpIndex = com.jetbrains.php.PhpIndex.getInstance(phpClass.project)
            val subClasses = phpIndex.getAllSubclasses(phpClass.fqn)
            for (subClass in subClasses) {
                collectProperties(subClass, result, visited)
            }
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，跳过子类收集
            ProjectLogHelper.log(phpClass.project, "FixedSearchPropertyAction: collectProperties 索引未准备好，跳过子类收集 class=${phpClass.fqn}")
        }
    }

    private fun getProperties(phpClass: PhpClass): List<Field> {
        // 暂时简化：获取所有非静态字段，实际使用时再过滤public属性
        return phpClass.fields.filter { !it.modifier.isStatic } // 后续优化访问级别检查逻辑
    }

    /**
     * 处理单个批次：搜索 + 过滤，直接累积到结果中
     * 优化：实现引用级分批，避免单个属性引用过多导致内存爆炸
     */
    private fun processPropertyBatch(
        batch: List<Field>,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        // 对每个属性进行引用级分批处理
        for (property in batch) {
            processPropertyReferences(property, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
        }
    }

    /**
     * 对单个属性的引用进行分批处理，避免内存爆炸
     * 优化：引用级批次也使用runReadAction，避免PSI访问阻塞UI
     */
    private fun processPropertyReferences(
        property: Field,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        val propertyRefsIterator = RefSearch.search(property, searchScope, false).iterator()
        val referenceBatchSize = 20 // 每次处理20个引用，避免内存占用过高

        // 使用迭代器分批获取引用，避免一次性加载所有引用
        while (propertyRefsIterator.hasNext()) {
            // ⭐ 每个引用批次都独立使用runReadAction，避免长时间阻塞UI
            ApplicationManager.getApplication().runReadAction {
                val referenceBatch = mutableListOf<UsageWithTarget>()

                // 收集一批引用
                for (i in 0 until referenceBatchSize) {
                    if (!propertyRefsIterator.hasNext()) break

                    val ref = propertyRefsIterator.next()
                    val element = ref.element
                    val range = ref.rangeInElement
                    referenceBatch.add(UsageWithTarget(
                        UsageInfo(element, range.startOffset, range.endOffset, true),
                        "${property.containingClass?.name}->${property.name}"
                    ))
                }

                // 如果收集到了引用，立即进行过滤
                if (referenceBatch.isNotEmpty()) {
                    filterRelatedPropertyUsages(referenceBatch, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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
                AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, currentUsages, "固定搜索-属性")
            } catch (ex: Throwable) {
                // 记录详细错误日志
                ProjectLogHelper.log(project, "固定搜索-属性: 显示搜索结果失败，错误: ${ex.message}, 异常类型: ${ex.javaClass.simpleName}")

                // 显示错误通知给用户
                Notifications.Bus.notify(
                    Notification(
                        "core-search",
                        "固定搜索-属性",
                        "显示搜索结果失败: ${ex.message}",
                        NotificationType.ERROR
                    ),
                    project
                )
            }
        }
    }

    /**
     * 过滤属性使用：只保留相关类的属性访问
     * 对于属性访问，只有在当前类或其子类的方法中访问才算相关
     */
    private fun filterRelatedPropertyUsages(
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

        for (uwt in usagesWithTarget) {
            val usage = uwt.usageInfo
            val element = usage.element
            if (element == null) {
                ProjectLogHelper.log(phpClass.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 element 为空 [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]")
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
                    cumulativeUsages.add(uwt)
                    hasNewResultsInBatch = true

                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-属性: filterRelatedPropertyUsages 保留属性访问 class=${containingClass.fqn}, targetClass=${phpClass.fqn} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                    )

                    // 检查时间间隔或结果数量，如果超过5秒或累积了10个结果立即显示
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastDisplayTime > 5000 || cumulativeUsages.size % 10 == 0) { // 5秒或每10个结果
                        showIncrementalResults(project, cumulativeUsages.toList())
                        lastDisplayTime = currentTime
                        hasNewResultsInBatch = false // 重置标记，因为已经显示了
                    }
                } else {
                    try {
                        val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                        ProjectLogHelper.log(
                            element.project,
                            "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在相关类中, class=${containingClass?.fqn ?: "<no-class>"}, targetClass=${phpClass.fqn}, file=$filePath [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                        )
                    } catch (_: Throwable) {
                        ProjectLogHelper.log(element.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在相关类中，且读取信息失败 [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]")
                    }
                }
            } else {
                // 如果不在方法中，可能是全局作用域或其他情况，暂时跳过
                try {
                    val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在方法中, file=$filePath textPreview=${element.text.take(200)} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                    )
                } catch (_: Throwable) {
                    ProjectLogHelper.log(element.project, "固定搜索-属性: filterRelatedPropertyUsages 跳过 - 不在方法中，且读取信息失败 [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]")
                }
            }
        }

        // 如果这一批有新结果但还没显示（时间间隔不足5秒），在这里显示
        if (hasNewResultsInBatch) {
            showIncrementalResults(project, cumulativeUsages.toList())
        }
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

