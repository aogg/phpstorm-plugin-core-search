package com.aogg.core.search.action

import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.aogg.core.search.helper.AutoDiscoverHelper
import com.aogg.core.search.settings.AutoDiscoverSettings

/**
 * 关键词搜索动作
 * 搜索调用当前类方法的位置，且调用处的方法有对应的 @core 关键词
 */
class CoreKeywordSearchAction(
    private val keyword: String,
    private val phpClass: PhpClass
) : AnAction(keyword) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 使用后台任务执行搜索，显示 IntelliJ 官方进度条
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "核心搜索: @$keyword", true) {
                override fun run(indicator: ProgressIndicator) {
                    // 所有 PSI / StubIndex 相关操作必须在 readAction 中执行
                    ApplicationManager.getApplication().runReadAction {
                        performSearch(project, indicator)
                    }
                }
            }
        )
    }
    
    /**
     * 获取类中所有带有指定关键词的方法
     */
    private fun getMethodsWithKeyword(phpClass: PhpClass, keyword: String): List<Method> {
        val coreMethods = CoreAnnotationHelper.getAllCoreMethods(phpClass)
        return coreMethods[keyword]?.toList() ?: emptyList()
    }

    /**
     * 执行核心搜索逻辑（在后台任务中运行）
     */
    private fun performSearch(project: Project, indicator: ProgressIndicator) {
        indicator.text = "正在收集包含 @$keyword 的方法..."
        indicator.isIndeterminate = true
        indicator.checkCanceled()

        // 获取当前类中所有带有该关键词的方法
        val targetMethods = getMethodsWithKeyword(phpClass, keyword)
            .toMutableSet()

        // 仅当当前类/父类未找到时，再全项目补充，避免每次全量扫描
        if (targetMethods.isEmpty()) {
            indicator.text = "正在全项目查找包含 @$keyword 的方法..."
            indicator.checkCanceled()
            val projectMethods = CoreAnnotationHelper.findMethodsByKeyword(project, keyword)
            targetMethods.addAll(projectMethods)
        }
        if (targetMethods.isNotEmpty()) {
            val methodInfos = targetMethods.map { method ->
                val classFqn = (method.containingClass as? PhpClass)?.fqn ?: "<no-class>"
                "$classFqn::${method.name}"
            }
            ProjectLogHelper.log(
                project,
                "搜索CoreKeywordSearchAction: keyword=$keyword, targetMethods=${methodInfos.joinToString("; ")}"
            )
        }

        if (targetMethods.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                notifyInfo(project, "未找到包含 @$keyword 的方法")
            }
            ProjectLogHelper.log(project, "CoreKeywordSearchAction: no methods with keyword=$keyword in class=${phpClass.fqn}")
            return
        }

        indicator.isIndeterminate = false
        indicator.fraction = 0.0

        // 收集所有符合条件的用法
        val usages = mutableListOf<Usage>()
        var totalUsages = 0

        val methodsList = targetMethods.toList()
        val methodCount = methodsList.size.coerceAtLeast(1)

        methodsList.forEachIndexed { index, method ->
            indicator.checkCanceled()
            val methodClass = (method.containingClass as? PhpClass)?.fqn ?: "<no-class>"
            indicator.text = "正在搜索方法引用: $methodClass::${method.name}"
            indicator.fraction = index.toDouble() / methodCount.toDouble()

            // 先搜索整个项目的方法调用
            val allMethodUsages = findMethodUsages(project, method, null, indicator)
            totalUsages += allMethodUsages.size

            // 二次过滤：只保留和当前类相关的调用
            val filteredUsages = filterRelatedUsages(allMethodUsages, phpClass)
            ProjectLogHelper.log(
                project,
                "二次过滤: method=$methodClass::${method.name}, 过滤前=${allMethodUsages.size}, 过滤后=${filteredUsages.size}"
            )
            usages.addAll(filteredUsages.map { UsageInfo2UsageAdapter(it) })
        }

        indicator.text = "正在准备显示搜索结果..."
        indicator.fraction = 1.0
        indicator.checkCanceled()

        // 显示搜索结果需要在 UI 线程执行
        ApplicationManager.getApplication().invokeLater {
            if (usages.isNotEmpty()) {
                val usageInfos = usages.mapNotNull { usage ->
                    val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo
                    val element = info?.element ?: return@mapNotNull null
                    val classFqn = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)?.fqn ?: "<no-class>"
                    val methodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
                    "$classFqn::$methodName"
                }
                ProjectLogHelper.log(
                    project,
                    "搜索CoreKeywordSearchAction: show usages keyword=$keyword, usageList=${usageInfos.joinToString(",")}"
                )
                showUsages(project, usages, keyword)
            } else {
                notifyInfo(project, "未找到调用 @$keyword 的代码位置")
                ProjectLogHelper.log(project, "CoreKeywordSearchAction: no usages found keyword=$keyword methods=${targetMethods.size}")
            }
        }
    }
    
    /**
     * 查找方法的调用位置
     *
     * @param limitToClass 如果不为 null，则只保留位于该类内部的调用（用于父类方法，只看当前类对它的调用）
     */
    private fun findMethodUsages(
        project: Project,
        method: Method,
        limitToClass: PhpClass?,
        indicator: ProgressIndicator? = null
    ): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        val searchScope = GlobalSearchScope.projectScope(project)

        // 使用 ReferencesSearch 查找方法引用
        val methodClass = (method.containingClass as? PhpClass)?.fqn ?: "<no-class>"
        ProjectLogHelper.log(project, "findMethodUsages: 开始搜索方法引用 method=$methodClass::${method.name}, limitToClass=${limitToClass?.fqn ?: "null"}")
        
        indicator?.checkCanceled()

        val references = ReferencesSearch.search(method, searchScope, false)
        val referencesList = references.findAll()
        
        ProjectLogHelper.log(project, "findMethodUsages: 找到方法引用数量 method=$methodClass::${method.name}, count=${referencesList.size}")

        for (reference in referencesList) {
            val element = reference.element
            val range = reference.rangeInElement

            // 如果需要限制到"当前类"，则只保留位于该类内部的调用
            if (limitToClass != null) {
                val callerClass = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
                if (callerClass == null || callerClass != limitToClass) {
                    continue
                }
            }

            usages.add(UsageInfo(element, range.startOffset, range.endOffset, true))
        }

        ProjectLogHelper.log(project, "findMethodUsages: 返回用法数量 method=$methodClass::${method.name}, usages=${usages.size}")
        return usages
    }
    
    /**
     * 二次过滤：只保留和当前类相关的调用
     * 检查调用方的对象类型或静态调用的类是否为当前类或其子类
     * 
     * @param usages 所有方法调用
     * @param phpClass 当前类
     * @return 过滤后的调用列表
     */
    private fun filterRelatedUsages(usages: List<UsageInfo>, phpClass: PhpClass): List<UsageInfo> {
        val filteredUsages = mutableListOf<UsageInfo>()
        
        for (usage in usages) {
            val element = usage.element
            if (element == null) {
                ProjectLogHelper.log(
                    phpClass.project,
                    "filterRelatedUsages: 二次过滤跳过用法，element 为空"
                )
                continue
            }

            // 仅通过 MethodReference + 类型判断来确认是否与当前类相关
            val methodReference = PsiTreeUtil.getParentOfType(
                element,
                com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                /* strict = */ false
            )

            if (methodReference == null) {
                ProjectLogHelper.log(
                    element.project,
                    "filterRelatedUsages: 二次过滤未匹配，原因=未找到 MethodReference，elementClass=${element.javaClass.name}, elementText=${element.text}"
                )
                continue
            }

            // 检查调用方的类型
            val callerType = getCallerType(methodReference, phpClass)
            if (callerType != null) {
                filteredUsages.add(usage)
                continue
            }

            // getCallerType 返回 null，说明类型判断未通过
            ProjectLogHelper.log(
                element.project,
                "filterRelatedUsages: 二次过滤未匹配，原因=调用方类型与当前类无关，methodText=${methodReference.text}, targetClass=${phpClass.fqn}"
            )
        }
        
        return filteredUsages
    }
    
    /**
     * 获取方法调用方的类型，判断是否和当前类相关
     * 
     * @param methodReference 方法引用
     * @param phpClass 当前类
     * @return 如果调用方类型和当前类相关则返回类型，否则返回 null
     */
    private fun getCallerType(
        methodReference: com.jetbrains.php.lang.psi.elements.MethodReference,
        phpClass: PhpClass
    ): PhpClass? {
        val classReference = methodReference.classReference
        
        // 先判断是否为“真正的静态调用”：classReference 存在且不是变量名（以 $ 开头的当成变量）
        if (classReference != null) {
            val className = classReference.text
            if (className.isNotEmpty() && !className.startsWith("$")) {
                // 静态调用：如 ClassName::method() 或 self::method()
                ProjectLogHelper.log(
                    methodReference.project,
                    "filterRelatedUsages: 检查静态调用 classReference=$className"
                )
                
                val phpIndex = com.jetbrains.php.PhpIndex.getInstance(methodReference.project)
                val resolvedClasses = phpIndex.getAnyByFQN(className)
                
                var matched = false
                for (resolved in resolvedClasses) {
                    if (resolved is PhpClass) {
                        ProjectLogHelper.log(
                            methodReference.project,
                            "filterRelatedUsages: 检查静态调用 resolved=${resolved.fqn}, target=${phpClass.fqn}"
                        )
                        if (isClassRelated(resolved, phpClass)) {
                            ProjectLogHelper.log(
                                methodReference.project,
                                "filterRelatedUsages: 匹配静态调用 class=${resolved.fqn}"
                            )
                            matched = true
                            return resolved
                        }
                        // 兜底规则：类名相同也认为相关（例如不同命名空间但类名相同的 StoreSubOrderIncome）
                        if (resolved.name == phpClass.name) {
                            ProjectLogHelper.log(
                                methodReference.project,
                                "filterRelatedUsages: 匹配静态调用（类名相同）resolved=${resolved.fqn}, target=${phpClass.fqn}"
                            )
                            matched = true
                            return resolved
                        }
                    }
                }

                if (!matched) {
                    // 当索引未能解析或都不匹配时，按短类名兜底：只要静态类名等于目标类名就认为相关
                    if (className == phpClass.name) {
                        ProjectLogHelper.log(
                            methodReference.project,
                            "filterRelatedUsages: 静态调用按类名兜底匹配 className=$className, target=${phpClass.fqn}"
                        )
                        return phpClass
                    }

                    ProjectLogHelper.log(
                        methodReference.project,
                        "filterRelatedUsages: 静态调用类型不匹配，classReference=$className, target=${phpClass.fqn}"
                    )
                }
                
                // 静态调用已经检查完毕，无需再走对象分支
                return null
            } else {
                // classReference 是变量（例如 $model），按对象调用处理
                ProjectLogHelper.log(
                    methodReference.project,
                    "filterRelatedUsages: classReference 为变量，按对象调用处理 classReference=$className"
                )
            }
        }

        run {
            // 对象调用：如 $obj->method()
            val firstPsiChild = methodReference.firstPsiChild
            
            ProjectLogHelper.log(
                methodReference.project,
                "filterRelatedUsages: 检查对象调用 text=${methodReference.text}, firstChild=${firstPsiChild?.text}"
            )
            
            // 获取调用对象的类型
            if (firstPsiChild is com.jetbrains.php.lang.psi.elements.PhpTypedElement) {
                val phpType = firstPsiChild.type
                val globalType = phpType.global(methodReference.project)
                
                ProjectLogHelper.log(
                    methodReference.project,
                    "filterRelatedUsages: 对象类型 type=${globalType}"
                )
                
                // 遍历所有类型
                var matched = false
                for (type in globalType.types) {
                    val typeString = type.toString()
                    ProjectLogHelper.log(
                        methodReference.project,
                        "filterRelatedUsages: 检查类型 typeString=$typeString"
                    )
                    
                    // 清理类型字符串，移除前缀 \ 和 #
                    val cleanFqn = typeString.removePrefix("\\").removePrefix("#")
                    
                    // 通过 PhpIndex 查找类
                    val phpIndex = com.jetbrains.php.PhpIndex.getInstance(methodReference.project)
                    val resolvedClasses = phpIndex.getClassesByFQN(cleanFqn)
                    
                    for (resolvedClass in resolvedClasses) {
                        ProjectLogHelper.log(
                            methodReference.project,
                            "filterRelatedUsages: 检查解析类 resolved=${resolvedClass.fqn}, target=${phpClass.fqn}"
                        )
                        if (isClassRelated(resolvedClass, phpClass)) {
                            ProjectLogHelper.log(
                                methodReference.project,
                                "filterRelatedUsages: 匹配对象调用 class=${resolvedClass.fqn}, variable=${firstPsiChild.text}"
                            )
                            matched = true
                            return resolvedClass
                        }
                    }
                }

                if (!matched) {
                    ProjectLogHelper.log(
                        methodReference.project,
                        "filterRelatedUsages: 对象调用类型不匹配，variable=${firstPsiChild.text}, target=${phpClass.fqn}, types=${globalType.types}"
                    )
                }
            } else {
                ProjectLogHelper.log(
                    methodReference.project,
                    "filterRelatedUsages: 对象调用类型不匹配，原因=firstPsiChild 非 PhpTypedElement, firstChild=${firstPsiChild?.javaClass?.name}, text=${firstPsiChild?.text}"
                )
            }
        }
        
        return null
    }
    
    /**
     * 检查调用方的类是否与当前类相关
     *
     * 规则：
     * - 相同类：class1 == class2
     * - 当前类的子类：class1 是 class2 的子类
     *
     * 注意：
     * - 这里只认为“目标类本身及其子类”为相关，父类不会被视为相关
     */
    private fun isClassRelated(class1: PhpClass, class2: PhpClass): Boolean {
        if (class1 == class2) {
            return true
        }

        // 检查 class1 是否是 class2 的子类（调用方是当前类的子类时也认为相关）
        if (isSubclassOf(class1, class2)) {
            return true
        }

        return false
    }
    
    /**
     * 检查 child 是否是 parent 的子类
     */
    private fun isSubclassOf(child: PhpClass, parent: PhpClass): Boolean {
        val visited = mutableSetOf<String>()
        return checkInheritance(child, parent, visited)
    }
    
    /**
     * 递归检查继承关系
     */
    private fun checkInheritance(child: PhpClass, parent: PhpClass, visited: MutableSet<String>): Boolean {
        val childFqn = child.fqn ?: return false
        if (visited.contains(childFqn)) {
            return false // 避免循环引用
        }
        visited.add(childFqn)
        
        val superClasses = child.supers
        for (superClass in superClasses) {
            if (superClass is PhpClass) {
                if (superClass == parent) {
                    return true
                }
                if (checkInheritance(superClass, parent, visited)) {
                    return true
                }
            }
        }
        
        return false
    }
        
    /**
     * 显示用法搜索结果
     */
    private fun showUsages(project: Project, usages: List<Usage>, keyword: String) {
        val usageTargets = emptyArray<UsageTarget>()
        val presentation = UsageViewPresentation()
        presentation.tabName = "核心搜索: $keyword"
        presentation.tabText = "核心搜索: $keyword"
        presentation.scopeText = "项目范围"
        
        UsageViewManager.getInstance(project).showUsages(
            usageTargets,
            usages.toTypedArray(),
            presentation
        )
    }
    
    /**
     * 结果提示通知
     */
    private fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "核心搜索",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }
}

