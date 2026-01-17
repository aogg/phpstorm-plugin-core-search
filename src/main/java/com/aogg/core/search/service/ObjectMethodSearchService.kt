package com.aogg.core.search.service

import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.aogg.core.search.helper.FixedSearchHelper
import com.aogg.core.search.model.UsageWithTarget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
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

/**
 * 对象方法调用搜索服务
 * 封装了对象方法调用的搜索、过滤和显示逻辑
 */
class ObjectMethodSearchService {

    /**
     * 执行对象方法调用搜索
     */
    fun performObjectMethodSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "搜索对象方法调用..."
        indicator.isIndeterminate = false // 使用确定进度

        try {
            // 1. 收集对象方法（需要PSI数据）
            val objectMethods = ApplicationManager.getApplication().runReadAction<LinkedHashSet<Pair<Method, String>>> {
                val methods = LinkedHashSet<Pair<Method, String>>()
                collectObjectMethods(targetClass, methods, HashSet())
                methods
            }

            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 收集到 ${objectMethods.size} 个对象方法")

            val searchScope = GlobalSearchScope.projectScope(project)
            val cumulativeUsages = mutableListOf<UsageWithTarget>() // 累积所有结果，用于最终显示
            val batchSize = 3 // 每批处理3个方法，减少批次大小以提高响应性

            // 分批搜索，每批立即处理和显示
            val methodList = objectMethods.toList()
            for (i in methodList.indices step batchSize) {
                if (indicator.isCanceled) break

                val batchEnd = minOf(i + batchSize, methodList.size)
                val batch = methodList.subList(i, batchEnd)

                // 更新进度
                indicator.fraction = i.toDouble() / methodList.size.toDouble()
                indicator.text = "搜索对象方法调用... (${i}/${methodList.size})"

                // ⭐ 立即处理：搜索当前批次（需要PSI数据）
                val currentPage = (i / batchSize) + 1
                val totalPages = (methodList.size + batchSize - 1) / batchSize // 向上取整计算总页数
                val totalItems = methodList.size
                val currentItemIndex = i + batch.size // 当前处理的项数

                ApplicationManager.getApplication().runReadAction {
                    processBatch(batch, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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
                    showNoResults(project)
                }
            }

            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 搜索完成，找到 ${cumulativeUsages.size} 个对象方法调用")

        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "固定搜索-对象方法调用: 搜索异常 ${ex.message}")
            ApplicationManager.getApplication().invokeLater {
                showSearchError(project, ex.message ?: "未知错误")
            }
        }
    }

    /**
     * 处理单个批次：搜索 + 过滤，直接累积到结果中
     * 优化：实现引用级分批，避免单个方法引用过多导致内存爆炸
     */
    private fun processBatch(
        batch: List<Pair<Method, String>>,
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
        for ((method, className) in batch) {
            processMethodReferences(method, className, searchScope, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
        }
    }

    /**
     * 对单个方法的引用进行分批处理，避免内存爆炸
     * 优化：引用级批次也使用runReadAction，避免PSI访问阻塞UI
     */
    private fun processMethodReferences(
        method: Method,
        className: String,
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

                // 如果收集到了引用，立即进行过滤
                if (referenceBatch.isNotEmpty()) {
                    filterRelatedObjectUsages(referenceBatch, targetClass, project, cumulativeUsages, currentPage, totalPages, totalItems, currentItemIndex)
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

    private fun filterRelatedObjectUsages(
        usagesWithTarget: List<UsageWithTarget>,
        targetClass: PhpClass,
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
            val element = usage.element ?: continue

            // 查找包含此元素的方法引用
            val methodRef = PsiTreeUtil.getParentOfType(
                element,
                MethodReference::class.java,
                /* strict = */ false
            ) ?: continue

            // 检查是否为真正的对象调用：通过->操作符识别对象调用
            val classReference = methodRef.classReference
            if (classReference != null) {
                val methodRefText = methodRef.text
                if (methodRefText.contains("->")) {
                    // 二次过滤：检查调用对象是否是当前类实例
                    if (isTargetClassInstance(classReference, targetClass)) {
                        // 是当前类的对象调用，保留
                        cumulativeUsages.add(uwt)
                        hasNewResultsInBatch = true

                        ProjectLogHelper.log(
                            element.project,
                            "固定搜索-对象方法调用: filterRelatedObjectUsages 保留对象调用 method=${uwt.targetMethodName}, methodRefText=$methodRefText, targetClass=${targetClass.fqn} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                        )

                        // 检查时间间隔或结果数量，如果超过5秒或累积了10个结果立即显示
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDisplayTime > 5000 || cumulativeUsages.size % 10 == 0) { // 5秒或每10个结果
                            showIncrementalResults(project, cumulativeUsages.toList())
                            lastDisplayTime = currentTime
                            hasNewResultsInBatch = false // 重置标记，因为已经显示了
                        }
                    } else {
                        // 调用对象不是当前类实例，跳过
                        ProjectLogHelper.log(
                            element.project,
                            "固定搜索-对象方法调用: filterRelatedObjectUsages 跳过非目标类实例调用 method=${uwt.targetMethodName}, methodRefText=$methodRefText, targetClass=${targetClass.fqn} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                        )
                    }
                } else {
                    // 静态调用（通过类名），跳过
                    ProjectLogHelper.log(
                        element.project,
                        "固定搜索-对象方法调用: filterRelatedObjectUsages 跳过静态调用 method=${uwt.targetMethodName}, methodRefText=$methodRefText [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                    )
                }
            } else {
                // 没有classReference，可能不是方法调用，跳过
                ProjectLogHelper.log(
                    element.project,
                    "固定搜索-对象方法调用: filterRelatedObjectUsages 跳过无classReference的调用 method=${uwt.targetMethodName} [页${currentPage}/${totalPages}, 总量${totalItems}, 当前${currentItemIndex}]"
                )
            }
        }

        // 如果这一批有新结果但还没显示（时间间隔不足2秒），在这里显示
        if (hasNewResultsInBatch) {
            showIncrementalResults(project, cumulativeUsages.toList())
        }
    }

    /**
     * 检查变量是否是目标类的实例
     */
    private fun isTargetClassInstance(classReference: PhpExpression, targetClass: PhpClass): Boolean {
        val project = targetClass.project
        val targetClassFqn = targetClass.fqn ?: return false

        // 获取目标类及其子类的FQN集合，用于快速校验
        val relatedClassFqns = mutableSetOf<String>()
        relatedClassFqns.add(targetClassFqn)

        // 由于类型推断复杂，这里采用简化策略：
        // 1. 检查变量名是否暗示是目标类（简单启发式）
        // 2. 检查附近是否有相关的new表达式或赋值

        val variableName = classReference.text
        if (variableName.startsWith("$")) {
            val varName = variableName.substring(1)

            // 启发式检查：变量名是否包含类名的简化形式
            val className = targetClass.name ?: ""
            if (varName.contains(className.lowercase()) ||
                className.lowercase().contains(varName) ||
                varName == className.lowercase()) {
                return true
            }

            // 检查当前作用域内是否有相关的new表达式或赋值
            val containingMethod = PsiTreeUtil.getParentOfType(classReference, Method::class.java)
            val containingFunction = PsiTreeUtil.getParentOfType(classReference, Function::class.java)

            // 在方法或函数内查找变量声明
            val searchScope = containingMethod ?: containingFunction
            if (searchScope != null) {
                // 查找变量赋值，如 $var = new TargetClass()
                val assignments = PsiTreeUtil.findChildrenOfType(searchScope, AssignmentExpression::class.java)
                for (assignment in assignments) {
                    val variable = assignment.variable
                    if (variable?.text == variableName) {
                        // 检查赋值右边的值
                        val value = assignment.value
                        if (value is NewExpression) {
                            val newClassReference = value.classReference
                            if (newClassReference != null) {
                                // 简化检查：直接比较类名，避免索引访问
                                val newClassName = newClassReference.text
                                val targetClassName = targetClass.name ?: ""
                                if (newClassName == targetClassName || newClassName == targetClassFqn) {
                                    return true
                                }
                            }
                        }
                    }
                }

                // 查找参数声明，如 function test(TargetClass $var)
                if (containingMethod != null) {
                    val parameters = containingMethod.parameters
                    for (parameter in parameters) {
                        val parameterName = "$" + parameter.name
                        if (parameterName == variableName) {
                            val parameterType = parameter.declaredType
                            if (parameterType != null) {
                                // 对于 PhpType，需要使用不同的方式获取类型信息
                                val typeString = parameterType.toString()
                                if (relatedClassFqns.any { typeString.contains(it) }) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }

            // 检查全局作用域或其他常见模式
            // 这里可以添加更多启发式规则
        }

        // 如果无法确定，默认不匹配（宁可错过，不可误报）
        return false
    }

    /**
     * 解析类引用的FQN
     */
    private fun resolveClassFqn(classReference: PhpExpression, project: Project): String? {
        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            return null
        }

        try {
            val phpIndex = PhpIndex.getInstance(project)
            val className = classReference.text

            // 尝试按完整FQN解析
            val resolvedClassesByFQN = phpIndex.getAnyByFQN(className)
            for (resolvedClass in resolvedClassesByFQN) {
                if (resolvedClass is PhpClass) {
                    return resolvedClass.fqn
                }
            }

            // 尝试按类名解析
            val classesByName = phpIndex.getClassesByName(className)
            for (resolvedClass in classesByName) {
                if (resolvedClass is PhpClass) {
                    return resolvedClass.fqn
                }
            }

            return null
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，返回null，让调用方处理
            ProjectLogHelper.log(project, "ObjectMethodSearchService: resolveClassFqn 索引未准备好，跳过解析 classReference=${classReference.text}")
            return null
        }
    }

    private fun collectObjectMethods(phpClass: PhpClass, result: MutableSet<Pair<Method, String>>, visited: MutableSet<String>) {
        val fqn = phpClass.fqn ?: return
        if (visited.contains(fqn)) return
        visited.add(fqn)

        // 添加当前类的非静态方法
        val currentMethods = phpClass.methods.filter { !it.modifier.isStatic && !it.access.isPrivate }
        for (method in currentMethods) {
            result.add(Pair(method, phpClass.name ?: "当前类"))
        }

        // 递归收集父类的非静态方法
        val superClass = phpClass.superClass
        if (superClass != null) {
            collectObjectMethods(superClass, result, visited)
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

    private fun showNoResults(project: Project) {
        FixedSearchHelper.notifyInfo(project, "未找到对象方法调用")
    }

    private fun showSearchError(project: Project, errorMessage: String) {
        FixedSearchHelper.notifyError(project, "搜索对象方法调用失败: $errorMessage")
    }
}
