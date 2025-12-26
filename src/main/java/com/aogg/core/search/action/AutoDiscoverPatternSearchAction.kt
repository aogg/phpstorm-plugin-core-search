package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverHelper
import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.model.UsageWithTarget
import com.aogg.core.search.settings.AutoDiscoverSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.intellij.usageView.UsageInfo
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch as RefSearch
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.project.DumbAware

/**
 * 点击三级项后执行的搜索动作：按方法名模式收集方法并查找 usages
 */
class AutoDiscoverPatternSearchAction(
    private val pattern: String,
    private val phpClass: PhpClass
) : AnAction(pattern) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 尝试根据当前上下文解析 PhpClass（优先使用事件上下文），若失败则回退到构造时捕获的 phpClass
        val resolvedPhpClass = run {
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
            val cls = psiFile?.let { PsiTreeUtil.findChildOfType(it, PhpClass::class.java) }
            cls ?: phpClass
        }

        ProjectLogHelper.log(project, "自动发现: 点击三级项 pattern=$pattern resolvedClass=${resolvedPhpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "自动发现搜索: $pattern", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    performSearch(project, indicator, resolvedPhpClass)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        // 确保三级菜单项始终可点击
        e.presentation.isEnabled = true
        e.presentation.text = pattern
    }

    private fun performSearch(project: Project, indicator: ProgressIndicator, targetClass: PhpClass) {
        indicator.text = "收集匹配 $pattern 的方法..."
        indicator.isIndeterminate = true
        ProjectLogHelper.log(project, "自动发现: performSearch start pattern=$pattern class=${targetClass.fqn}")

        val targetMethods = mutableSetOf<Method>()
        // 优先当前类（传入的 targetClass）
        try {
            val settings = AutoDiscoverSettings.getInstance(project)
            val ignoreCase = settings.caseInsensitive
            val regex = AutoDiscoverHelper.patternToRegex(pattern, ignoreCase)
            ProjectLogHelper.log(project, "自动发现: performSearch: 使用正则 regex=$regex ignoreCase=$ignoreCase")
            ProjectLogHelper.log(project, "自动发现: performSearch: targetClass.methods.size=${targetClass.methods.size} class=${targetClass.fqn}")
            for (method in targetClass.methods) {
                val accessInfo = method.access
                val name = method.name
                ProjectLogHelper.log(project, "自动发现: performSearch: 检查方法 ${targetClass.fqn}::${name}, access=${accessInfo}, methodText=${method.text.take(80)}")
                if (!method.access.isPublic) {
                    ProjectLogHelper.log(project, "自动发现: performSearch: 跳过非 public 方法 ${name}")
                    continue
                }
                val matched = try {
                    regex.matches(name)
                } catch (ex: Throwable) {
                    ProjectLogHelper.log(project, "自动发现: performSearch: regex.match 异常 pattern=$pattern name=$name ex=${ex.message}")
                    false
                }
                ProjectLogHelper.log(project, "自动发现: performSearch: 方法匹配结果 name=$name matched=$matched")
                if (matched) {
                    targetMethods.add(method)
                }
            }
        } catch (ex: Throwable) {
            try {
                ProjectLogHelper.log(project, "自动发现: performSearch 遍历方法时异常: ${ex.message}\n${ex.stackTraceToString()}")
            } catch (_: Throwable) {
                ProjectLogHelper.log(project, "自动发现: performSearch 遍历方法时异常，但无法获取完整异常信息: ${ex.message}")
            }
        }

        // 目前仅在当前类内匹配；如需跨项目匹配可后续扩展

        if (targetMethods.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                notifyInfo(project, "未找到匹配规则 $pattern 的方法")
            }
            ProjectLogHelper.log(project, "自动发现: performSearch 未找到方法 pattern=$pattern class=${targetClass.fqn}")
            return
        }

        // 搜索所有 usages 并记录每条 usage 对应的目标方法名（target method）
        val usageWithTargets = mutableListOf<UsageWithTarget>()
        val methodsList = targetMethods.toList()
        for (method in methodsList) {
            indicator.checkCanceled()
            val refs = RefSearch.search(method, GlobalSearchScope.projectScope(project), false).findAll()
            for (ref in refs) {
                val element = ref.element
                val range = ref.rangeInElement
                usageWithTargets.add(UsageWithTarget(UsageInfo(element, range.startOffset, range.endOffset, true), method.name ?: "<unknown-target>"))
            }
        }
        ProjectLogHelper.log(project, "自动发现: raw usageWithTargets count=${usageWithTargets.size} for pattern=$pattern class=${targetClass.fqn}")

        // 二次过滤：只保留调用方为当前类或其子类的调用（同时在日志中记录被过滤的原因）
        val filteredUsageWithTargets = filterRelatedUsages(usageWithTargets, targetClass)
        ProjectLogHelper.log(project, "自动发现: filtered usageWithTargets count=${filteredUsageWithTargets.size} (raw=${usageWithTargets.size}) pattern=$pattern class=${targetClass.fqn}")

        ApplicationManager.getApplication().invokeLater {
            if (filteredUsageWithTargets.isNotEmpty()) {
                ProjectLogHelper.log(project, "自动发现: performSearch 找到 usages=${filteredUsageWithTargets.size} pattern=$pattern")
                try {
                    AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, filteredUsageWithTargets, pattern)
                } catch (ex: Throwable) {
                    ProjectLogHelper.log(project, "自动发现: 工具窗口显示失败，回退到弹窗 title=$pattern ex=${ex.message}\n${ex.stackTraceToString()}")
                    try {
                        val fallbackUsages = filteredUsageWithTargets.map { UsageInfo2UsageAdapter(it.usageInfo) as Usage }
                        AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, pattern)
                    } catch (exPopup: Throwable) {
                        ProjectLogHelper.log(project, "自动发现: 弹窗显示失败，回退到标准用法视图 title=$pattern ex=${exPopup.message}\n${exPopup.stackTraceToString()}")
                        // 最后回退到 UsageView（使用原始 UsageInfo 列表）
                        val usageTargets = emptyArray<UsageTarget>()
                        val presentation = UsageViewPresentation()
                        presentation.tabName = "$pattern-自动发现-核心搜索"
                        presentation.tabText = "$pattern-自动发现-核心搜索"
                        presentation.scopeText = "项目范围"
                        try {
                            val hidden = com.aogg.core.search.helper.AutoDiscoverUiHelper.tryHidePresentationOptions(presentation)
                            ProjectLogHelper.log(project, "自动发现: 尝试隐藏 UsageViewPresentation 选项 hidden=${hidden}")
                        } catch (exUi: Throwable) {
                            ProjectLogHelper.log(project, "自动发现: 隐藏 UsageViewPresentation 选项失败 ex=${exUi.message}")
                        }
                        val usageInfosForView = filteredUsageWithTargets.map { it.usageInfo }
                        UsageViewManager.getInstance(project).showUsages(
                            usageTargets,
                            usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                            presentation
                        )
                    }
                }
            } else {
                ProjectLogHelper.log(project, "自动发现: performSearch 未找到 usages pattern=$pattern")
                notifyInfo(project, "未找到调用匹配 $pattern 的方法的位置")
            }
        }
    }

    private fun showUsages(project: Project, usages: List<Usage>, title: String) {
        // 直接使用工具窗口显示结果（类似终端窗口）
        ProjectLogHelper.log(project, "自动发现: 直接调用工具窗口显示结果 title=$title usages=${usages.size}")
        try {
            // 将普通 Usage 列表转换为 UsageWithTarget 列表（尝试解析目标方法名）
            val uwtList = mutableListOf<UsageWithTarget>()
            for (u in usages) {
                val info = (u as? UsageInfo2UsageAdapter)?.usageInfo ?: continue
                val element = info.element ?: continue
                val methodRef = PsiTreeUtil.getParentOfType(
                    element,
                    com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                    /* strict = */ false
                )
                val targetName = try {
                    (methodRef?.resolve() as? Method)?.name ?: "<unknown-target>"
                } catch (_: Throwable) {
                    "<unknown-target>"
                }
                uwtList.add(UsageWithTarget(info, targetName))
            }
            AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, uwtList, title)
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "自动发现: 工具窗口显示失败，回退到弹窗 title=$title ex=${ex.message}\n${ex.stackTraceToString()}")
            try {
                AutoDiscoverUiHelper.showCustomUsagesPopup(project, usages, title)
            } catch (exPopup: Throwable) {
                ProjectLogHelper.log(project, "自动发现: 弹窗显示失败，回退到标准用法视图 title=$title ex=${exPopup.message}\n${exPopup.stackTraceToString()}")

                // 最后的回退方案：使用标准的 UsageView
                val usageTargets = emptyArray<UsageTarget>()
                val presentation = UsageViewPresentation()
                presentation.tabName = "$title-自动发现-核心搜索"
                presentation.tabText = "$title-自动发现-核心搜索"
                presentation.scopeText = "项目范围"

                try {
                    val hidden = com.aogg.core.search.helper.AutoDiscoverUiHelper.tryHidePresentationOptions(presentation)
                    ProjectLogHelper.log(project, "自动发现: 尝试隐藏 UsageViewPresentation 选项 hidden=${hidden}")
                } catch (exUi: Throwable) {
                    ProjectLogHelper.log(project, "自动发现: 隐藏 UsageViewPresentation 选项失败 ex=${exUi.message}")
                }

                UsageViewManager.getInstance(project).showUsages(
                    usageTargets,
                    usages.toTypedArray(),
                    presentation
                )
            }
        }
    }




    /**
     * 二次过滤：只保留和当前类相关的调用（调用方为当前类或当前类的子类，或静态调用目标类匹配）
     * 接受 UsageWithTarget 列表并返回过滤后的 UsageWithTarget 列表
     */
    private fun filterRelatedUsages(usagesWithTarget: List<UsageWithTarget>, phpClass: PhpClass): List<UsageWithTarget> {
        val filtered = mutableListOf<UsageWithTarget>()
        for (uwt in usagesWithTarget) {
            val usage = uwt.usageInfo
            val element = usage.element
            if (element == null) {
                ProjectLogHelper.log(phpClass.project, "自动发现: filterRelatedUsages 跳过 element 为空")
                continue
            }

            val methodReference = PsiTreeUtil.getParentOfType(
                element,
                com.jetbrains.php.lang.psi.elements.MethodReference::class.java,
                /* strict = */ false
            )

            if (methodReference == null) {
                try {
                    val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                    ProjectLogHelper.log(
                        element.project,
                        "自动发现: filterRelatedUsages 跳过 - 未找到 MethodReference, file=$filePath textPreview=${element.text.take(200)}"
                    )
                } catch (_: Throwable) {
                    ProjectLogHelper.log(element.project, "自动发现: filterRelatedUsages 跳过 - 未找到 MethodReference, 且读取 text 失败")
                }
                continue
            }

            val callerType = getCallerType(methodReference, phpClass)
            if (callerType != null) {
                filtered.add(uwt)
            } else {
                try {
                    val classRefText = methodReference.classReference?.text ?: "<no-classRef>"
                    val firstPsi = methodReference.firstPsiChild
                    val firstText = firstPsi?.text ?: "<no-firstChild>"
                    val filePath = element.containingFile?.virtualFile?.path ?: "<no-path>"
                    ProjectLogHelper.log(
                        element.project,
                        "自动发现: filterRelatedUsages 跳过 - callerType null, methodRef=${methodReference.text.take(200)}, classRef=$classRefText, firstChild=$firstText, file=$filePath"
                    )
                } catch (_: Throwable) {
                    ProjectLogHelper.log(element.project, "自动发现: filterRelatedUsages 跳过 - callerType null, 且读取 methodReference 信息失败")
                }
            }
        }
        return filtered
    }

    private fun getCallerType(
        methodReference: com.jetbrains.php.lang.psi.elements.MethodReference,
        phpClass: PhpClass
    ): PhpClass? {
        val classReference = methodReference.classReference

        if (classReference != null) {
            val className = classReference.text
            if (className.isNotEmpty() && !className.startsWith("$")) {
                val phpIndex = com.jetbrains.php.PhpIndex.getInstance(methodReference.project)
                val resolved = phpIndex.getAnyByFQN(className)
                for (r in resolved) {
                    if (r is PhpClass) {
                        if (isClassRelated(r, phpClass)) {
                            return r
                        }
                        if (r.name == phpClass.name) {
                            return r
                        }
                    }
                }
                if (className == phpClass.name) {
                    return phpClass
                }
                return null
            }
        }

        // 对象调用分支
        val firstPsiChild = methodReference.firstPsiChild
        if (firstPsiChild is com.jetbrains.php.lang.psi.elements.PhpTypedElement) {
            val phpType = firstPsiChild.type
            val globalType = phpType.global(methodReference.project)
            for (type in globalType.types) {
                val cleanFqn = type.toString().removePrefix("\\").removePrefix("#")
                val phpIndex = com.jetbrains.php.PhpIndex.getInstance(methodReference.project)
                val classes = phpIndex.getClassesByFQN(cleanFqn)
                for (c in classes) {
                    if (isClassRelated(c, phpClass)) {
                        return c
                    }
                }
            }
        }
        return null
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


    private fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "自动发现搜索",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }
}


