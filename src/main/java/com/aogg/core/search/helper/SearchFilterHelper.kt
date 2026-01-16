package com.aogg.core.search.helper

import com.aogg.core.search.model.UsageWithTarget
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpExpression
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.PhpIndex
import com.intellij.psi.util.PsiTreeUtil

/**
 * 搜索过滤助手类
 * 提供通用的搜索过滤和日志记录功能，可被不同类型的搜索复用
 */
class SearchFilterHelper(private val searchTypeName: String) {

    /**
     * 过滤相关调用并记录日志
     * @param usagesWithTarget 待过滤的使用列表
     * @param targetClass 目标类
     * @param project 项目实例
     * @param cumulativeUsages 累积结果列表
     * @param lastDisplayTime 上次显示时间
     * @param hasNewResultsInBatch 批次是否有新结果的标记
     * @param showIncrementalResults 增量显示回调函数
     * @return Triple(更新后的lastDisplayTime, hasNewResultsInBatch, 是否有新结果)
     */
    fun filterRelatedObjectUsages(
        usagesWithTarget: List<UsageWithTarget>,
        targetClass: PhpClass,
        project: com.intellij.openapi.project.Project,
        cumulativeUsages: MutableList<UsageWithTarget>,
        lastDisplayTime: Long,
        hasNewResultsInBatch: Boolean,
        showIncrementalResults: (List<UsageWithTarget>) -> Unit
    ): Triple<Long, Boolean, Boolean> {
        var currentLastDisplayTime = lastDisplayTime
        var currentHasNewResults = hasNewResultsInBatch
        var batchHasNewResults = false

        for (uwt in usagesWithTarget) {
            val usage = uwt.usageInfo
            val element = usage.element ?: continue

            // 查找包含此元素的方法引用
            val methodRef = PsiTreeUtil.getParentOfType(
                element,
                com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                false
            ) ?: continue

            // 检查是否为真正的对象调用：classReference存在且以$开头（变量）
            val classReference = methodRef.classReference
            if (classReference != null) {
                val className = classReference.text
                if (className.isNotEmpty() && className.startsWith("$")) {
                    // 二次过滤：检查调用对象是否是当前类实例
                    if (isTargetClassInstance(classReference, targetClass)) {
                        // 是当前类的对象调用，保留
                        cumulativeUsages.add(uwt)
                        currentHasNewResults = true
                        batchHasNewResults = true

                        ProjectLogHelper.log(
                            element.project,
                            "$searchTypeName: filterRelatedObjectUsages 保留对象调用 method=${uwt.targetMethodName}, classReference=$className, targetClass=${targetClass.fqn}"
                        )

                        // 检查时间间隔或结果数量，如果超过5秒或累积了10个结果立即显示
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - currentLastDisplayTime > 5000 || cumulativeUsages.size % 10 == 0) { // 5秒或每10个结果
                            showIncrementalResults(cumulativeUsages.toList())
                            currentLastDisplayTime = currentTime
                            currentHasNewResults = false // 重置标记，因为已经显示了
                        }
                    } else {
                        // 调用对象不是当前类实例，跳过
                        ProjectLogHelper.log(
                            element.project,
                            "$searchTypeName: filterRelatedObjectUsages 跳过非目标类实例调用 method=${uwt.targetMethodName}, classReference=$className, targetClass=${targetClass.fqn}"
                        )
                    }
                } else {
                    // 静态调用（通过类名），跳过
                    ProjectLogHelper.log(
                        element.project,
                        "$searchTypeName: filterRelatedObjectUsages 跳过静态调用 method=${uwt.targetMethodName}, classReference=$className"
                    )
                }
            } else {
                // 没有classReference，可能不是方法调用，跳过
                ProjectLogHelper.log(
                    element.project,
                    "$searchTypeName: filterRelatedObjectUsages 跳过无classReference的调用 method=${uwt.targetMethodName}"
                )
            }
        }

        return Triple(currentLastDisplayTime, currentHasNewResults, batchHasNewResults)
    }

    /**
     * 检查变量是否是目标类的实例
     */
    fun isTargetClassInstance(classReference: PhpExpression, targetClass: PhpClass): Boolean {
        val project = targetClass.project
        val targetClassFqn = targetClass.fqn ?: return false

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

            val searchScope = containingMethod ?: containingFunction
            if (searchScope != null) {
                val assignments = PsiTreeUtil.findChildrenOfType(searchScope, AssignmentExpression::class.java)
                for (assignment in assignments) {
                    val variable = assignment.variable
                    if (variable?.text == variableName) {
                        val value = assignment.value
                        if (value is NewExpression) {
                            val newClassReference = value.classReference
                            if (newClassReference != null) {
                                val newClassFqn = resolveClassFqn(newClassReference, project)
                                if (newClassFqn != null && newClassFqn == targetClassFqn) {
                                    return true
                                }
                            }
                        }
                    }
                }

                // 查找参数声明
                if (containingMethod != null) {
                    val parameters = containingMethod.parameters
                    for (parameter in parameters) {
                        val parameterName = "$" + parameter.name
                        if (parameterName == variableName) {
                            val parameterType = parameter.declaredType
                            if (parameterType != null) {
                                val typeString = parameterType.toString()
                                if (typeString.contains(targetClassFqn)) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * 解析类引用的FQN
     */
    fun resolveClassFqn(classReference: PhpExpression, project: com.intellij.openapi.project.Project): String? {
        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            return null
        }

        try {
            val phpIndex = PhpIndex.getInstance(project)
            val className = classReference.text

            val resolvedClassesByFQN = phpIndex.getAnyByFQN(className)
            for (resolvedClass in resolvedClassesByFQN) {
                if (resolvedClass is PhpClass) {
                    return resolvedClass.fqn
                }
            }

            val classesByName = phpIndex.getClassesByName(className)
            for (resolvedClass in classesByName) {
                if (resolvedClass is PhpClass) {
                    return resolvedClass.fqn
                }
            }

            return null
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，返回null，让调用方处理
            ProjectLogHelper.log(project, "$searchTypeName: resolveClassFqn 索引未准备好，跳过解析 classReference=${classReference.text}")
            return null
        }
    }

    /**
     * 收集对象方法（非静态方法）
     */
    fun collectObjectMethods(phpClass: PhpClass, result: MutableSet<Pair<Method, String>>, visited: MutableSet<String>) {
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

    companion object {
        /**
         * 为对象方法调用搜索创建过滤助手
         */
        fun forObjectMethodSearch(): SearchFilterHelper {
            return SearchFilterHelper("固定搜索-对象方法调用")
        }

        /**
         * 为其他类型的搜索创建过滤助手
         */
        fun forSearchType(searchTypeName: String): SearchFilterHelper {
            return SearchFilterHelper(searchTypeName)
        }
    }
}
