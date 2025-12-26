package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverHelper
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
import com.aogg.core.search.helper.AutoDiscoverDisplayHelper
import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.RegisterToolWindowTask
import java.io.File
import javax.swing.JSplitPane
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.ListSelectionListener
import javax.swing.event.ListSelectionEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.event.TreeSelectionListener
import javax.swing.event.TreeSelectionEvent
import javax.swing.JCheckBox
import javax.swing.Box
import javax.swing.BoxLayout
import java.awt.event.ItemListener
import java.awt.event.ItemEvent

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
                    showAutoDiscoverToolWindow(project, filteredUsageWithTargets, pattern)
                } catch (ex: Throwable) {
                    ProjectLogHelper.log(project, "自动发现: 工具窗口显示失败，回退到弹窗 title=$pattern ex=${ex.message}\n${ex.stackTraceToString()}")
                    try {
                        val fallbackUsages = filteredUsageWithTargets.map { UsageInfo2UsageAdapter(it.usageInfo) as Usage }
                        showCustomUsagesPopup(project, fallbackUsages, pattern)
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
            showAutoDiscoverToolWindow(project, uwtList, title)
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "自动发现: 工具窗口显示失败，回退到弹窗 title=$title ex=${ex.message}\n${ex.stackTraceToString()}")
            try {
                showCustomUsagesPopup(project, usages, title)
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

    // 新的工具窗口接收带 targetMethod 信息的 UsageWithTarget 列表
    private fun showAutoDiscoverToolWindow(project: Project, usagesWithTarget: List<UsageWithTarget>, title: String) {
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val toolWindowId = "Auto Discover Results"

        // 获取或创建工具窗口
        var toolWindow = toolWindowManager.getToolWindow(toolWindowId)
        if (toolWindow == null) {
            // 如果工具窗口不存在，创建永久工具窗口
            toolWindow = toolWindowManager.registerToolWindow(
                RegisterToolWindowTask(
                    toolWindowId,
                    ToolWindowAnchor.BOTTOM,
                    null,
                    false
                )
            )
        }

        // 准备数据
        val items = mutableListOf<DisplayItem>()
        val psiDocManager = com.intellij.psi.PsiDocumentManager.getInstance(project)

        for (uwt in usagesWithTarget) {
            val info = uwt.usageInfo
            val targetName = uwt.targetMethodName
            val element = info.element ?: continue
            val virtualFile = element.containingFile?.virtualFile ?: continue
            val doc = psiDocManager.getDocument(element.containingFile) ?: continue
            val elemOffset = element.textOffset
            val line = doc.getLineNumber(elemOffset)
            val lineStart = doc.getLineStartOffset(line)
            val lineEnd = doc.getLineEndOffset(line)
            val preview = try {
                val raw = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
                if (raw.length > 120) raw.substring(0, 120) + "..." else raw
            } catch (_: Throwable) {
                ""
            }
            val callerMethodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
            val previewText = AutoDiscoverUiHelper.getMethodPreviewFromElement(element, 3)
            items.add(
                DisplayItem(
                    title = callerMethodName,
                    filePath = virtualFile.path,
                    line = line,
                    preview = preview,
                    elementOffset = elemOffset,
                    methodName = targetName,
                    previewText = previewText,
                    targetMethodName = targetName,
                    callerMethodName = callerMethodName
                )
            )
        }

        // 按方法名分组并创建树形结构（与弹窗一致的视图）
        val groupedItems = items.groupBy { it.methodName }

        val rootNode = DefaultMutableTreeNode("搜索结果")
        for ((methodName, methodItems) in groupedItems) {
            val groupNode = DefaultMutableTreeNode("$methodName (${methodItems.size})")
            for (item in methodItems) {
                val relPath = try {
                    val base = project.basePath
                    if (base != null) {
                        java.io.File(base).toPath().relativize(java.io.File(item.filePath).toPath()).toString()
                            .replace(java.io.File.separatorChar, '/')
                    } else {
                        item.filePath
                    }
                } catch (_: Throwable) {
                    item.filePath
                }
                val label = "${relPath} — ${item.callerMethodName}"
                val leafNode = DefaultMutableTreeNode(label)
                leafNode.userObject = item
                groupNode.add(leafNode)
            }
            rootNode.add(groupNode)
        }

        val treeModel = DefaultTreeModel(rootNode)
        val tree = JTree(treeModel)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        // 预览区域
        val previewTextArea = JBTextArea()
        previewTextArea.isEditable = false
        previewTextArea.lineWrap = true
        previewTextArea.wrapStyleWord = true
        previewTextArea.rows = 15
        val previewScrollPane = JBScrollPane(previewTextArea)

        // 分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewScrollPane
        splitPane.resizeWeight = 0.4
        splitPane.dividerLocation = 500

        // 控制面板（显示预览开关）
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)
        val showPreviewCheckBox = JCheckBox("显示预览", true)
        showPreviewCheckBox.addItemListener(object : ItemListener {
            override fun itemStateChanged(e: ItemEvent) {
                val showPreview = e.stateChange == ItemEvent.SELECTED
                splitPane.bottomComponent = if (showPreview) previewScrollPane else null
                splitPane.revalidate()
                splitPane.repaint()
            }
        })
        controlPanel.add(showPreviewCheckBox)
        controlPanel.add(Box.createHorizontalGlue())

        // 组合主面板（包含工具栏）
        val toolbar = javax.swing.JPanel(java.awt.BorderLayout())
        val titleLabel = javax.swing.JLabel("$title-自动发现-核心搜索")
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD)
        toolbar.add(titleLabel, java.awt.BorderLayout.WEST)

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(controlPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)

        // 树选择监听，更新预览并高亮
        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent) {
                val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                    val selectedItem = selectedNode.userObject as DisplayItem
                    previewTextArea.text = if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览"
                    if (title.isNotEmpty()) {
                        highlightSearchKeyword(previewTextArea, title)
                    }
                } else {
                    previewTextArea.text = "选择一个具体的结果项查看预览"
                }
            }
        })

        // 双击打开文件
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                        val selectedItem = selectedNode.userObject as DisplayItem
                        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
                        if (vf != null) {
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, selectedItem.line, 0).navigate(true)
                        }
                    }
                }
            }
        })

        // 展开并选中第一个叶子
        if (rootNode.childCount > 0) {
            val firstGroupNode = rootNode.getChildAt(0) as DefaultMutableTreeNode
            tree.expandPath(javax.swing.tree.TreePath(firstGroupNode.path))
            if (firstGroupNode.childCount > 0) {
                val firstLeafNode = firstGroupNode.getChildAt(0) as DefaultMutableTreeNode
                tree.selectionPath = javax.swing.tree.TreePath(firstLeafNode.path)
            }
        }

        // 设置工具窗口内容
        val contentFactory = com.intellij.ui.content.ContentFactory.SERVICE.getInstance()
        val content = contentFactory.createContent(mainPanel, title, false)
        toolWindow.contentManager.removeAllContents(false)
        toolWindow.contentManager.addContent(content)

        // 显示工具窗口
        toolWindow.show(null)
        ProjectLogHelper.log(project, "自动发现: 工具窗口已显示 title=$title items=${items.size}")
    }

    private data class DisplayItem(
        val title: String,
        val filePath: String,
        val line: Int,
        val preview: String,
        val elementOffset: Int,
        val methodName: String = "",
        val previewText: String = "",
        val targetMethodName: String = "",
        val callerMethodName: String = ""
    )

    private data class UsageWithTarget(val usageInfo: UsageInfo, val targetMethodName: String)

    private fun showCustomUsagesPopup(project: Project, usages: List<Usage>, title: String) {
        ProjectLogHelper.log(project, "自动发现: 进入 showCustomUsagesPopup title=$title usages=${usages.size}")
        val items = mutableListOf<DisplayItem>()
        val psiDocManager = com.intellij.psi.PsiDocumentManager.getInstance(project)

        for (usage in usages) {
            val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo ?: continue
            val element = info.element ?: continue
            val virtualFile = element.containingFile?.virtualFile ?: continue
            val doc = psiDocManager.getDocument(element.containingFile) ?: continue
            val elemOffset = element.textOffset
            val line = doc.getLineNumber(elemOffset)
            val lineStart = doc.getLineStartOffset(line)
            val lineEnd = doc.getLineEndOffset(line)
            val preview = try {
                val raw = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
                if (raw.length > 120) raw.substring(0, 120) + "..." else raw
            } catch (_: Throwable) {
                ""
            }
            val callerMethodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
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
            val previewText = AutoDiscoverUiHelper.getMethodPreviewFromElement(element, 3)
            items.add(
                DisplayItem(
                    title = callerMethodName,
                    filePath = virtualFile.path,
                    line = line,
                    preview = preview,
                    elementOffset = elemOffset,
                    methodName = targetName,
                    previewText = previewText,
                    targetMethodName = targetName,
                    callerMethodName = callerMethodName
                )
            )
        }

        // 按目标方法名分组（targetMethodName）
        val groupedItems = items.groupBy { if (it.targetMethodName.isNotEmpty()) it.targetMethodName else "其他" }

        // 创建树形结构
        val rootNode = DefaultMutableTreeNode("搜索结果")
        for ((targetName, methodItems) in groupedItems) {
            val groupNode = DefaultMutableTreeNode("$targetName (${methodItems.size})")
            for (item in methodItems) {
                val relPath = try {
                    val base = project.basePath
                    if (base != null) {
                        java.io.File(base).toPath().relativize(java.io.File(item.filePath).toPath()).toString()
                            .replace(java.io.File.separatorChar, '/')
                    } else {
                        item.filePath
                    }
                } catch (_: Throwable) {
                    item.filePath
                }
                val label = "${relPath} — ${item.callerMethodName}"
                val leafNode = DefaultMutableTreeNode(label)
                leafNode.userObject = item // 存储完整的DisplayItem对象
                groupNode.add(leafNode)
            }
            rootNode.add(groupNode)
        }

        val treeModel = DefaultTreeModel(rootNode)
        val tree = JTree(treeModel)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        // 创建预览文本区域
        val previewTextArea = JBTextArea()
        previewTextArea.isEditable = false
        previewTextArea.lineWrap = true
        previewTextArea.wrapStyleWord = true
        previewTextArea.rows = 15
        previewTextArea.lineWrap = true
        previewTextArea.wrapStyleWord = true
        previewTextArea.rows = 15

        val previewScrollPane = JBScrollPane(previewTextArea)

        // 创建分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewScrollPane
        splitPane.resizeWeight = 0.4  // 树占40%，预览占60%
        splitPane.dividerLocation = 400

        // 创建控制面板
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)

        val showPreviewCheckBox = JCheckBox("显示预览", true)
        showPreviewCheckBox.addItemListener(object : ItemListener {
            override fun itemStateChanged(e: ItemEvent) {
                val showPreview = e.stateChange == ItemEvent.SELECTED
                splitPane.bottomComponent = if (showPreview) previewScrollPane else null
                splitPane.revalidate()
                splitPane.repaint()
            }
        })

        controlPanel.add(showPreviewCheckBox)
        controlPanel.add(Box.createHorizontalGlue()) // 添加弹性空间

        // 创建主面板
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(controlPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)

        // 树选择监听器，更新预览内容
        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent) {
                val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                    val selectedItem = selectedNode.userObject as DisplayItem
                    previewTextArea.text = if (selectedItem.previewText.isNotEmpty()) {
                        selectedItem.previewText
                    } else {
                        "无法获取方法预览"
                    }
                    // 高亮搜索关键字（简单实现）
                    if (title.isNotEmpty()) {
                        highlightSearchKeyword(previewTextArea, title)
                    }
                } else {
                    previewTextArea.text = "选择一个具体的结果项查看预览"
                }
            }
        })

        // 默认展开第一级节点并选择第一个叶子节点
        if (rootNode.childCount > 0) {
            val firstGroupNode = rootNode.getChildAt(0) as DefaultMutableTreeNode
            tree.expandPath(javax.swing.tree.TreePath(firstGroupNode.path))
            if (firstGroupNode.childCount > 0) {
                val firstLeafNode = firstGroupNode.getChildAt(0) as DefaultMutableTreeNode
                tree.selectionPath = javax.swing.tree.TreePath(firstLeafNode.path)
            }
        }

        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                        val selectedItem = selectedNode.userObject as DisplayItem
                        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
                        if (vf != null) {
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, selectedItem.line, 0).navigate(true)
                        }
                    }
                }
            }
        })

        val popupFactory = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
        try {
            // 记录前几个显示项用于排查
            val previewItems = StringBuilder()
            val previewCount = Math.min(5, items.size)
            for (i in 0 until previewCount) {
                try {
                    val item = items[i]
                    previewItems.append("[${i}]:${item.methodName}@${item.filePath}:${item.line + 1}; ")
                } catch (_: Throwable) {
                }
            }
            ProjectLogHelper.log(project, "自动发现: 准备显示自定义弹窗 title=$title items=${items.size} preview=$previewItems")
            val popup = popupFactory
                .createComponentPopupBuilder(mainPanel, tree)
                .setTitle("$title-自动发现-核心搜索")
                .setResizable(true)
                .setMovable(true)
                .setMinSize(java.awt.Dimension(800, 600))
                .createPopup()
            popup.showInFocusCenter()
            ProjectLogHelper.log(project, "自动发现: 自定义弹窗已显示 title=$title items=${items.size}")
        } catch (exPopup: Throwable) {
            ProjectLogHelper.log(project, "自动发现: 自定义弹窗显示失败 title=$title ex=${exPopup.message}\n${exPopup.stackTraceToString()}")
            throw exPopup
        }
    }

    /**
     * 试图在项目的 docs 目录下查找与当前类或 title 相关的文档文件，返回相对于项目根的路径（不包含前导斜杠），
     * 如果找到则前缀为 @docs/，否则返回 null。
     */
    private fun findDocsLabel(project: Project, phpClass: PhpClass, title: String): String? {
        val basePath = project.basePath ?: return null
        try {
            val baseDir = File(basePath)
            if (!baseDir.exists()) return null
            val className = phpClass.name ?: ""
            val candidates = baseDir.walkTopDown().filter { file ->
                file.isFile && file.path.contains("${File.separator}docs${File.separator}")
            }
            // 优先匹配文件名包含类名，其次匹配路径或文件名包含 title（pattern）
            val match = candidates.firstOrNull { it.name.contains(className, ignoreCase = true) }
                ?: candidates.firstOrNull { it.path.contains(title, ignoreCase = true) || it.name.contains(title, ignoreCase = true) }
            if (match != null) {
                val rel = match.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                return "docs/$rel"
            }
        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "自动发现: 查找 docsLabel 出错: ${ex.message}")
        }
        return null
    }

    private fun getEnclosingMethodName(usage: Usage): String {
        try {
            val element = (usage as? UsageInfo2UsageAdapter)?.usageInfo?.element ?: return ""
            val method = PsiTreeUtil.getParentOfType(element, Method::class.java)
            return method?.name ?: ""
        } catch (ex: Throwable) {
            return ""
        }
    }

    private fun getContainingFilePath(usage: Usage): String {
        try {
            val element = (usage as? UsageInfo2UsageAdapter)?.usageInfo?.element ?: return ""
            val file = element.containingFile?.virtualFile?.path ?: return ""
            return file
        } catch (ex: Throwable) {
            return ""
        }
    }

    /**
     * 根据方法名返回固定分组标识，用于对 usages 进行分组显示
     * 规则（优先级）：以 get/set/is/has 开头分别归类，否则归类到 "其他"
     */
    private fun getMethodGroup(usage: Usage): String {
        val methodName = getEnclosingMethodName(usage).lowercase()
        return when {
            methodName.startsWith("get") -> "get"
            methodName.startsWith("set") -> "set"
            methodName.startsWith("is") -> "is"
            methodName.startsWith("has") -> "has"
            else -> "其他"
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

    /**
     * 在预览文本中高亮搜索关键字
     */
    private fun highlightSearchKeyword(textArea: JBTextArea, searchKeyword: String) {
        try {
            val document = textArea.document
            val highlighter = textArea.highlighter
            val text = textArea.text

            // 移除之前的高亮
            highlighter.removeAllHighlights()

            // 查找并高亮关键字
            var index = 0
            while (index < text.length) {
                val foundIndex = text.indexOf(searchKeyword, index, ignoreCase = true)
                if (foundIndex == -1) break

                // 创建高亮
                val painter = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
                    java.awt.Color.YELLOW
                )
                highlighter.addHighlight(foundIndex, foundIndex + searchKeyword.length, painter)

                index = foundIndex + searchKeyword.length
            }
        } catch (ex: Throwable) {
            // 高亮失败不影响功能
            ProjectLogHelper.log(null, "自动发现: 预览高亮失败: ${ex.message}")
        }
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


