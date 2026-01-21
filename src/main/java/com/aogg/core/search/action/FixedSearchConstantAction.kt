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
import com.jetbrains.php.lang.psi.elements.Constant
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.intellij.usageView.UsageInfo

/**
 * 固定搜索 - 常量：搜索当前类的常量使用（不包括父类和子类）
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
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集常量（需要PSI数据）
            val constants = ApplicationManager.getApplication().runReadAction<Set<Any>> {
                collectConstants(targetClass)
            }

            ProjectLogHelper.log(project, "固定搜索-常量: 收集到 ${constants.size} 个常量")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示
            val batchSize = 3 // 每批处理3个常量，减少批次大小以提高响应性

            // 分批搜索，每批立即处理和显示
            val constantList = constants.toList()
            for (i in constantList.indices step batchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + batchSize, constantList.size)
                val batch = constantList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / constantList.size.toDouble()
                indicator.text = "搜索常量使用... (${i}/${constantList.size})"

                // ⭐ 立即处理：搜索当前批次（需要PSI数据）
                val currentPage = (i / batchSize) + 1
                val totalPages = (constantList.size + batchSize - 1) / batchSize // 向上取整计算总页数
                val totalItems = constantList.size
                val currentItemIndex = i + batch.size // 当前处理的项数

                ApplicationManager.getApplication().runReadAction {
                    processConstantBatch(batch, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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
                    notifyInfo(project, "未找到常量使用")
                }
            }

            ProjectLogHelper.log(project, "固定搜索-常量: 搜索完成，找到 ${cumulativeUsages.size} 个常量使用")
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-常量: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                notifyError(project, "搜索常量使用失败: ${ex.message}")
            }
        }
    }

    /**
     * 处理单个批次：搜索 + 显示，直接累积到结果中
     * 优化：实现引用级分批，避免单个常量引用过多导致内存爆炸
     */
    private fun processConstantBatch(
        batch: List<Any>,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass,
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        // 对每个常量进行引用级分批处理
        for (constant in batch) {
            processConstantReferences(constant, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
        }
    }

    /**
     * 对单个常量的引用进行分批处理，避免内存爆炸
     * 优化：引用级批次也使用runReadAction，避免PSI访问阻塞UI
     */
    private fun processConstantReferences(
        constant: Any,
        searchScope: GlobalSearchScope,
        targetClass: PhpClass, // 添加目标类参数，用于过滤引用
        project: Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        currentPage: Int,
        totalPages: Int,
        totalItems: Int,
        currentItemIndex: Int
    ) {
        // 处理两种类型的常量：Constant (const 关键字) 和 Field (静态字段)
        val psiElement = when (constant) {
            is Constant -> constant
            is Field -> constant
            else -> {
                ProjectLogHelper.log(project, "FixedSearchConstantAction: 未知的常量类型: ${constant.javaClass}")
                return
            }
        }

        val constantRefsIterator = RefSearch.search(psiElement, searchScope, false).iterator()
        val referenceBatchSize = 20 // 每次处理20个引用，避免内存占用过高

        // 使用迭代器分批获取引用，避免一次性加载所有引用
        while (constantRefsIterator.hasNext()) {
            // ⭐ 每个引用批次都独立使用runReadAction，避免长时间阻塞UI
            ApplicationManager.getApplication().runReadAction {
                val referenceBatch = mutableListOf<UsageWithTarget>()

                // 收集一批引用
                for (i in 0 until referenceBatchSize) {
                    if (!constantRefsIterator.hasNext()) break

                    val ref = constantRefsIterator.next()
                    val element = ref.element
                    val range = ref.rangeInElement

                    // 过滤：显示当前类和子类的引用
                    val elementFile = element.containingFile?.virtualFile
                    val targetClassFile = targetClass.containingFile?.virtualFile

                    // 1. 总是显示当前类文件中的引用
                    val isInCurrentClass = elementFile == targetClassFile

                    // 2. 检查是否在子类中（通过继承关系）
                    val isInSubclass = isReferenceInSubclass(element, targetClass, project)

                    // 3. 只显示当前类或子类中的引用
                    if (!isInCurrentClass && !isInSubclass) {
                        continue
                    }

                    // 获取常量名称和类名
                    val constantName = when (constant) {
                        is Constant -> constant.name
                        is Field -> constant.name
                        else -> "UNKNOWN"
                    }
                    val className = when (constant) {
                        is Constant -> (constant.parent as? PhpClass)?.name
                        is Field -> constant.containingClass?.name
                        else -> "UNKNOWN"
                    }

                    val targetConstantName = "${className}::${constantName}"
                    val filePath = element.containingFile?.virtualFile?.path ?: "UNKNOWN"
                    val lineNumber = element.containingFile?.let { file ->
                        PsiDocumentManager.getInstance(project).getDocument(file)?.getLineNumber(element.textOffset)
                    } ?: -1

                    // 记录详细的常量使用信息
                    ProjectLogHelper.log(project, "固定搜索-常量: 找到常量使用 - 常量: $targetConstantName, 文件: $filePath, 行: ${lineNumber + 1}, 调用代码: ${element.text}")

                    referenceBatch.add(UsageWithTarget(
                        UsageInfo(element, range.startOffset, range.endOffset, true),
                        targetConstantName
                    ))
                }

                // 如果收集到了引用，立即进行过滤和显示
                if (referenceBatch.isNotEmpty()) {
                    // 常量搜索不需要复杂的过滤，直接添加所有引用
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
                AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, currentUsages, "固定搜索-常量")
            } catch (ex: Throwable) {
                // 记录详细错误日志
                ProjectLogHelper.log(project, "固定搜索-常量: 显示搜索结果失败，错误: ${ex.message}, 异常类型: ${ex.javaClass.simpleName}")

                // 显示错误通知给用户
                Notifications.Bus.notify(
                    Notification(
                        "core-search",
                        "固定搜索-常量",
                        "显示搜索结果失败: ${ex.message}",
                        NotificationType.ERROR
                    ),
                    project
                )
            }
        }
    }

    /**
     * 检查引用是否在目标类的子类中
     */
    private fun isReferenceInSubclass(element: com.intellij.psi.PsiElement, targetClass: PhpClass, project: Project): Boolean {
        // 找到引用元素所在的类
        val containingClass = PsiTreeUtil.getParentOfType(element, PhpClass::class.java) ?: return false

        // 如果是同一个类，不算子类
        if (containingClass == targetClass) return false

        // 检查是否是子类（直接或间接继承）
        return isSubclassOf(containingClass, targetClass)
    }

    /**
     * 检查一个类是否是另一个类的子类（递归检查继承链）
     */
    private fun isSubclassOf(phpClass: PhpClass, potentialParent: PhpClass): Boolean {
        // 检查直接父类
        val superClass = phpClass.superClass ?: return false
        if (superClass == potentialParent) return true

        // 递归检查父类的父类
        return isSubclassOf(superClass, potentialParent)
    }

    private fun collectConstants(phpClass: PhpClass): Set<Any> {
        // 只收集当前类的常量，不考虑父类和子类
        val constants = mutableSetOf<Any>()

        // 1. 获取 const 关键字定义的常量
        try {
            // 遍历类的所有成员，查找 const 声明
            val allMembers = phpClass.children
            for (member in allMembers) {
                if (member is Constant) {
                    constants.add(member)
                }
            }
        } catch (e: Exception) {
            ProjectLogHelper.log(phpClass.project, "FixedSearchConstantAction: 获取 const 常量失败: ${e.message}")
        }

        // 2. 获取静态字段常量（符合命名约定的静态字段）
        // 注意：phpClass.fields 只返回当前类定义的字段，不包括父类
        val staticFieldConstants = phpClass.fields.filter { field ->
            field.modifier.isStatic &&
            field.name?.let { name ->
                name.all { char -> char.isUpperCase() || char == '_' } && name.isNotEmpty()
            } ?: false
        }
        constants.addAll(staticFieldConstants)

        return constants
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
