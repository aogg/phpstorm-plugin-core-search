package com.aogg.core.search.helper

import com.aogg.core.search.model.DisplayItem
import com.aogg.core.search.model.UsageWithTarget
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Color
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.RegisterToolWindowTask
import java.io.File
import javax.swing.JSplitPane
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.event.TreeSelectionListener
import javax.swing.event.TreeSelectionEvent
import javax.swing.JCheckBox
import javax.swing.Box
import javax.swing.BoxLayout
import java.awt.event.ItemListener
import java.awt.event.ItemEvent
import com.intellij.openapi.editor.Editor

/**
 * UI 相关的辅助函数（对 UsageViewPresentation 使用反射尝试隐藏不需要的控件）
 */
object AutoDiscoverUiHelper {

    /** 工具窗口ID */
    const val TOOL_WINDOW_ID = "Auto Discover Results"

    private val candidateMethods = listOf(
        "setShowUsageType",
        "setShowReadOnlyStatus",
        "setShowCancelButton",
        "setShowFindOptions",
        "setShowToolbar",
        "setOpenInNewTab",
        "setCodeUsages"
    )

    /**
     * 尝试通过反射调用若干 setter，将不必要的面板项隐藏（多为 boolean setter，忽略不存在的方法）
     * 返回成功设置的方法名称列表，便于记录日志
     */
    fun tryHidePresentationOptions(presentation: UsageViewPresentation): List<String> {
        val succeeded = mutableListOf<String>()
        for (methodName in candidateMethods) {
            try {
                val method = presentation.javaClass.getMethod(methodName, Boolean::class.javaPrimitiveType)
                method.invoke(presentation, java.lang.Boolean.FALSE)
                succeeded.add(methodName)
            } catch (_: NoSuchMethodException) {
                // 忽略不存在的方法
            } catch (ex: Throwable) {
                // 忽略其他异常，避免影响主流程
            }
        }
        return succeeded
    }

    /**
     * 获取方法源码预览文本
     * 返回方法签名开始向上若干行、方法体向下若干行的源码片段
     * @param method Psi方法元素
     * @param contextLines 上下各显示多少行上下文（默认3行）
     * @return 源码预览文本，失败时返回空字符串
     */
    fun getMethodPreviewText(method: Method, contextLines: Int = 3): String {
        return ReadAction.compute<String, Throwable> {
            try {
                val containingFile = method.containingFile ?: return@compute ""
                val document = PsiDocumentManager.getInstance(method.project).getDocument(containingFile) ?: return@compute ""

                // 获取方法文本范围
                val methodTextRange = method.textRange
                val methodStartLine = document.getLineNumber(methodTextRange.startOffset)
                val methodEndLine = document.getLineNumber(methodTextRange.endOffset)

                // 计算预览范围：方法开始向上contextLines行，到方法结束向下contextLines行
                val previewStartLine = maxOf(0, methodStartLine - contextLines)
                val previewEndLine = minOf(document.lineCount - 1, methodEndLine + contextLines)

                // 获取预览文本
                val startOffset = document.getLineStartOffset(previewStartLine)
                val endOffset = document.getLineEndOffset(previewEndLine)
                val previewText = document.getText(TextRange(startOffset, endOffset))

                // 限制预览文本长度，避免过长的预览
                if (previewText.length > 2000) {
                    val methodStartOffset = document.getLineStartOffset(methodStartLine)
                    val methodEndOffset = document.getLineEndOffset(minOf(methodEndLine + contextLines, document.lineCount - 1))

                    // 如果方法本身不超长，优先显示完整方法
                    val methodText = document.getText(TextRange(methodStartOffset, methodEndOffset))
                    if (methodText.length <= 2000) {
                        return@compute methodText
                    }

                    // 否则截取方法开始部分
                    return@compute methodText.substring(0, 2000) + "\n... (预览已截断)"
                }

                return@compute previewText
            } catch (ex: Throwable) {
                return@compute ""
            }
        }
    }

    /**
     * 从Usage中的元素获取对应的方法预览文本
     * @param element Usage中的Psi元素
     * @param contextLines 上下各显示多少行上下文（默认3行）
     * @return 方法源码预览文本
     */
    fun getMethodPreviewFromElement(element: PsiElement, contextLines: Int = 3): String {
        val method = PsiTreeUtil.getParentOfType(element, Method::class.java)
        return if (method != null) getMethodPreviewText(method, contextLines) else ""
    }

    /**
     * 创建只读编辑器用于预览目标方法
     * @param project 项目实例
     * @param method 目标方法
     * @param searchKeyword 要高亮的搜索关键词
     * @return 只读编辑器实例，失败时返回null
     */
    fun createEditorForMethodPreview(project: Project, method: Method, searchKeyword: String = ""): com.intellij.openapi.editor.Editor? {
        return ReadAction.compute<com.intellij.openapi.editor.Editor?, Throwable> {
            try {
                val containingFile = method.containingFile ?: return@compute null
                val virtualFile = containingFile.virtualFile ?: return@compute null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@compute null

                // 创建只读编辑器（使用 createEditor 保证使用与主编辑器一致的颜色方案）
                val editorFactory = EditorFactory.getInstance()
                val editor = editorFactory.createEditor(document, project, virtualFile, /*isViewer=*/ true)

                // 设置编辑器属性并强制应用全局配色方案以与主编辑器保持一致
                try {
                    val scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme
                    if (editor is EditorEx) {
                        editor.setOneLineMode(false)
                        editor.isViewer = true
                        editor.colorsScheme = scheme
                    }
                } catch (_: Throwable) {
                    // 忽略配色设置失败，不影响主要功能
                }

                // 滚动到方法位置
                val methodTextRange = method.textRange
                val startLine = document.getLineNumber(methodTextRange.startOffset)
                val startColumn = methodTextRange.startOffset - document.getLineStartOffset(startLine)

                val logicalPosition = LogicalPosition(startLine, startColumn)
                editor.caretModel.moveToLogicalPosition(logicalPosition)
                editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)

                // 高亮搜索关键词
                if (searchKeyword.isNotEmpty()) {
                    highlightSearchKeywordInEditor(editor, searchKeyword)
                }

                return@compute editor
            } catch (ex: Throwable) {
                return@compute null
            }
        }
    }

    /**
     * 在编辑器中高亮搜索关键词
     * @param editor 编辑器实例
     * @param searchKeyword 要高亮的关键词
     */
    private fun highlightSearchKeywordInEditor(editor: com.intellij.openapi.editor.Editor, searchKeyword: String) {
        try {
            val document = editor.document
            val text = document.text
            val markupModel = editor.markupModel

            // 移除之前的高亮
            markupModel.removeAllHighlighters()

            // 查找并高亮所有匹配的关键词
            var index = 0
            while (index < text.length) {
                val foundIndex = text.indexOf(searchKeyword, index, ignoreCase = true)
                if (foundIndex == -1) break

                // 创建高亮属性
                val textAttributes = TextAttributes()
                textAttributes.backgroundColor = Color.YELLOW
                textAttributes.foregroundColor = Color.BLACK

                // 添加高亮
                markupModel.addRangeHighlighter(
                    foundIndex,
                    foundIndex + searchKeyword.length,
                    HighlighterLayer.SELECTION - 1,
                    textAttributes,
                    HighlighterTargetArea.EXACT_RANGE
                )

                index = foundIndex + searchKeyword.length
            }
        } catch (ex: Throwable) {
            // 高亮失败不影响功能
        }
    }

    /**
     * 释放编辑器资源
     * @param editor 要释放的编辑器实例
     */
    fun releaseEditor(editor: com.intellij.openapi.editor.Editor?) {
        if (editor != null) {
            try {
                EditorFactory.getInstance().releaseEditor(editor)
            } catch (ex: Throwable) {
                // 释放失败不影响功能
            }
        }
    }

    /**
     * 显示自动发现工具窗口
     * @param project 项目实例
     * @param usagesWithTarget 使用信息和目标方法名的列表
     * @param title 窗口标题
     */
    fun showAutoDiscoverToolWindow(project: Project, usagesWithTarget: List<UsageWithTarget>, title: String) {
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)

        // 获取或创建工具窗口
        var toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            // 如果工具窗口不存在，创建永久工具窗口
            toolWindow = toolWindowManager.registerToolWindow(
                RegisterToolWindowTask(
                    TOOL_WINDOW_ID,
                    ToolWindowAnchor.BOTTOM,
                    null,
                    false
                )
            )
        }

        // 准备数据
        val items = mutableListOf<DisplayItem>()
        val psiDocManager = PsiDocumentManager.getInstance(project)

        for (uwt in usagesWithTarget) {
            val info = uwt.usageInfo
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

            // 解析目标方法名和调用方法名
            val methodRef = PsiTreeUtil.getParentOfType(
                element,
                MethodReference::class.java,
                /* strict = */ false
            )
            val targetMethodName = try {
                (methodRef?.resolve() as? Method)?.name ?: "<unknown-target>"
            } catch (_: Throwable) {
                "<unknown-target>"
            }
            val callerMethodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"

            val previewText = getMethodPreviewFromElement(element, 3)
            items.add(
                DisplayItem(
                    title = callerMethodName,
                    filePath = virtualFile.path,
                    line = line,
                    preview = preview,
                    elementOffset = elemOffset,
                    methodName = targetMethodName,
                    previewText = previewText,
                    targetMethodName = targetMethodName,
                    callerMethodName = callerMethodName
                )
            )
        }

        // 按目标方法名分组（无法解析时归入"其他"分组）
        val groupedItems = items.groupBy { item ->
            if (item.targetMethodName.isNotEmpty() && item.targetMethodName != "<unknown-target>") {
                item.targetMethodName
            } else {
                "其他"
            }
        }

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
                val label = "${item.callerMethodName} (${relPath})"
                val leafNode = DefaultMutableTreeNode(label)
                leafNode.userObject = item
                groupNode.add(leafNode)
            }
            rootNode.add(groupNode)
        }

        val treeModel = DefaultTreeModel(rootNode)
        val tree = JTree(treeModel)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        // 自定义渲染器：当节点的 userObject 为 DisplayItem 时，显示为 "相对路径 — 调用方法名"，并设置 tooltip 为完整路径
        tree.cellRenderer = object : javax.swing.tree.DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                val comp = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                try {
                    if (value is DefaultMutableTreeNode && value.userObject is DisplayItem) {
                        val item = value.userObject as DisplayItem
                        val relPath = try {
                            val base = project.basePath
                            if (base != null) {
                                java.io.File(base).toPath().relativize(java.io.File(item.filePath).toPath()).toString()
                                    .replace(java.io.File.separatorChar, '/')
                            } else item.filePath
                        } catch (_: Throwable) {
                            item.filePath
                        }
                        this.text = "$relPath — ${item.callerMethodName}"
                        this.toolTipText = item.filePath
                    }
                } catch (_: Throwable) {
                    // 渲染异常不应阻塞主流程，保留默认渲染
                }
                return comp
            }
        }

        // 预览区域（使用编辑器）
        val editorHolder = object {
            var editor: com.intellij.openapi.editor.Editor? = null
        }
        val previewPanel = JPanel(BorderLayout())
        val previewScrollPane = JBScrollPane(previewPanel)

        // 分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewScrollPane
        splitPane.resizeWeight = 0.4
        splitPane.dividerLocation = 400
        splitPane.minimumSize = java.awt.Dimension(800, 600)
        // 设置最小宽度
        val leftScrollPane = splitPane.leftComponent as JBScrollPane
        leftScrollPane.minimumSize = java.awt.Dimension(200, 0)
        val rightScrollPane = splitPane.rightComponent as JBScrollPane
        rightScrollPane.minimumSize = java.awt.Dimension(300, 0)

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

                    // 释放之前的编辑器
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 获取目标方法进行预览
                    val element = try {
                        val psiFile = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)!!)
                        psiFile?.findElementAt(selectedItem.elementOffset)
                    } catch (ex: Throwable) {
                        null
                    }

                    if (element != null) {
                        val method = PsiTreeUtil.getParentOfType(element, Method::class.java)
                        if (method != null) {
                            // 创建新的编辑器预览
                            val newEditor = createEditorForMethodPreview(project, method, title)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            } else {
                                previewPanel.removeAll()
                                val fallbackTextArea = JBTextArea(if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览")
                                fallbackTextArea.isEditable = false
                                previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            }
                        } else {
                            previewPanel.removeAll()
                            val fallbackTextArea = JBTextArea("无法找到对应的方法")
                            fallbackTextArea.isEditable = false
                            previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                        }
                    } else {
                        previewPanel.removeAll()
                        val fallbackTextArea = JBTextArea("无法获取元素信息")
                        fallbackTextArea.isEditable = false
                        previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                        previewPanel.revalidate()
                        previewPanel.repaint()
                    }
                } else {
                    // 释放之前的编辑器
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    previewPanel.removeAll()
                    val defaultTextArea = JBTextArea("选择一个具体的结果项查看预览")
                    defaultTextArea.isEditable = false
                    previewPanel.add(defaultTextArea, BorderLayout.CENTER)
                    previewPanel.revalidate()
                    previewPanel.repaint()
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
                        val vf = LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
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

        // 添加内容监听器，在内容移除时释放编辑器资源
        content.addPropertyChangeListener { evt ->
            if ("disposed" == evt.propertyName) {
                releaseEditor(editorHolder.editor)
            }
        }

        toolWindow.contentManager.removeAllContents(false)
        toolWindow.contentManager.addContent(content)

        // 显示工具窗口
        toolWindow.show(null)
        ProjectLogHelper.log(project, "自动发现: 工具窗口已显示 title=$title items=${items.size}")
    }

    /**
     * 显示自定义用法弹窗（作为工具窗口的回退方案）
     * @param project 项目实例
     * @param usages 使用信息列表
     * @param title 窗口标题
     */
    fun showCustomUsagesPopup(project: Project, usages: List<Usage>, title: String) {
        ProjectLogHelper.log(project, "自动发现: 进入 showCustomUsagesPopup title=$title usages=${usages.size}")
        val items = mutableListOf<DisplayItem>()
        val psiDocManager = PsiDocumentManager.getInstance(project)

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
                val raw = doc.getText(TextRange(lineStart, lineEnd)).trim()
                if (raw.length > 120) raw.substring(0, 120) + "..." else raw
            } catch (_: Throwable) {
                ""
            }
            val callerMethodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
            val methodRef = PsiTreeUtil.getParentOfType(
                element,
                MethodReference::class.java,
                /* strict = */ false
            )
            val targetName = try {
                (methodRef?.resolve() as? Method)?.name ?: "<unknown-target>"
            } catch (_: Throwable) {
                "<unknown-target>"
            }
            val previewText = getMethodPreviewFromElement(element, 3)
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
                        val vf = LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
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
     * 显示核心搜索结果到工具窗口
     * @param project 项目实例
     * @param usages 使用信息列表
     * @param keyword 搜索关键词
     */
    fun showCoreSearchToolWindow(project: Project, usages: List<Usage>, keyword: String) {
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)

        // 获取或创建工具窗口
        var toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            // 如果工具窗口不存在，创建永久工具窗口
            toolWindow = toolWindowManager.registerToolWindow(
                RegisterToolWindowTask(
                    TOOL_WINDOW_ID,
                    ToolWindowAnchor.BOTTOM,
                    null,
                    false
                )
            )
        }

        // 准备数据
        val items = mutableListOf<DisplayItem>()
        val psiDocManager = PsiDocumentManager.getInstance(project)

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
                val raw = doc.getText(TextRange(lineStart, lineEnd)).trim()
                if (raw.length > 120) raw.substring(0, 120) + "..." else raw
            } catch (_: Throwable) {
                ""
            }

            val callerMethodName = PsiTreeUtil.getParentOfType(element, Method::class.java)?.name ?: "<no-method>"
            val previewText = getMethodPreviewFromElement(element, 3)

            items.add(
                DisplayItem(
                    title = callerMethodName,
                    filePath = virtualFile.path,
                    line = line,
                    preview = preview,
                    elementOffset = elemOffset,
                    methodName = callerMethodName,
                    previewText = previewText,
                    targetMethodName = keyword, // 使用关键词作为目标方法名
                    callerMethodName = callerMethodName
                )
            )
        }

        // 按调用方法名分组
        val groupedItems = items.groupBy { item ->
            if (item.callerMethodName.isNotEmpty() && item.callerMethodName != "<no-method>") {
                item.callerMethodName
            } else {
                "其他"
            }
        }

        val rootNode = DefaultMutableTreeNode("核心搜索结果")
        for ((methodName, methodItems) in groupedItems) {
            val groupNode = DefaultMutableTreeNode("$methodName (${methodItems.size})")
            for (item in methodItems) {
                val relPath = try {
                    val base = project.basePath
                    if (base != null) {
                        File(base).toPath().relativize(File(item.filePath).toPath()).toString()
                            .replace(File.separatorChar, '/')
                    } else {
                        item.filePath
                    }
                } catch (_: Throwable) {
                    item.filePath
                }
                val label = "$relPath — $keyword"
                val leafNode = DefaultMutableTreeNode(label)
                leafNode.userObject = item
                groupNode.add(leafNode)
            }
            rootNode.add(groupNode)
        }

        val treeModel = DefaultTreeModel(rootNode)
        val tree = JTree(treeModel)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        // 自定义渲染器
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                val comp = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                try {
                    if (value is DefaultMutableTreeNode && value.userObject is DisplayItem) {
                        val item = value.userObject as DisplayItem
                        val relPath = try {
                            val base = project.basePath
                            if (base != null) {
                                File(base).toPath().relativize(File(item.filePath).toPath()).toString()
                                    .replace(File.separatorChar, '/')
                            } else item.filePath
                        } catch (_: Throwable) {
                            item.filePath
                        }
                        this.text = "$relPath — ${item.callerMethodName}"
                        this.toolTipText = item.filePath
                    }
                } catch (_: Throwable) {
                    // 渲染异常不应阻塞主流程，保留默认渲染
                }
                return comp
            }
        }

        // 预览区域（使用编辑器）
        val editorHolder = object {
            var editor: Editor? = null
        }
        val previewPanel = JPanel(BorderLayout())
        val previewScrollPane = JBScrollPane(previewPanel)

        // 分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewScrollPane
        splitPane.resizeWeight = 0.4
        splitPane.dividerLocation = 400
        splitPane.minimumSize = java.awt.Dimension(800, 600)

        // 设置最小宽度
        val leftScrollPane = splitPane.leftComponent as JBScrollPane
        leftScrollPane.minimumSize = java.awt.Dimension(200, 0)
        val rightScrollPane = splitPane.rightComponent as JBScrollPane
        rightScrollPane.minimumSize = java.awt.Dimension(300, 0)

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
        val toolbar = JPanel(java.awt.BorderLayout())
        val titleLabel = javax.swing.JLabel("核心搜索: @$keyword")
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

                    // 释放之前的编辑器
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 获取目标方法进行预览
                    val element = try {
                        val psiFile = PsiManager.getInstance(project).findFile(
                            LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)!!
                        )
                        psiFile?.findElementAt(selectedItem.elementOffset)
                    } catch (ex: Throwable) {
                        null
                    }

                    if (element != null) {
                        val method = PsiTreeUtil.getParentOfType(element, Method::class.java)
                        if (method != null) {
                            // 创建新的编辑器预览
                            val newEditor = createEditorForMethodPreview(project, method, keyword)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            } else {
                                previewPanel.removeAll()
                                val fallbackTextArea = JBTextArea(if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览")
                                fallbackTextArea.isEditable = false
                                previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            }
                        } else {
                            previewPanel.removeAll()
                            val fallbackTextArea = JBTextArea("无法找到对应的方法")
                            fallbackTextArea.isEditable = false
                            previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                        }
                    } else {
                        previewPanel.removeAll()
                        val fallbackTextArea = JBTextArea("无法获取元素信息")
                        fallbackTextArea.isEditable = false
                        previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                        previewPanel.revalidate()
                        previewPanel.repaint()
                    }
                } else {
                    // 释放之前的编辑器
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    previewPanel.removeAll()
                    val defaultTextArea = JBTextArea("选择一个具体的结果项查看预览")
                    defaultTextArea.isEditable = false
                    previewPanel.add(defaultTextArea, BorderLayout.CENTER)
                    previewPanel.revalidate()
                    previewPanel.repaint()
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
                        val vf = LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
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
        val content = contentFactory.createContent(mainPanel, "核心搜索: @$keyword", false)

        // 添加内容监听器，在内容移除时释放编辑器资源
        content.addPropertyChangeListener { evt ->
            if ("disposed" == evt.propertyName) {
                releaseEditor(editorHolder.editor)
            }
        }

        toolWindow.contentManager.removeAllContents(false)
        toolWindow.contentManager.addContent(content)

        // 显示工具窗口
        toolWindow.show(null)
        ProjectLogHelper.log(project, "核心搜索: 工具窗口已显示 keyword=$keyword items=${items.size}")
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
                    Color.YELLOW
                )
                highlighter.addHighlight(foundIndex, foundIndex + searchKeyword.length, painter)

                index = foundIndex + searchKeyword.length
            }
        } catch (ex: Throwable) {
            // 高亮失败不影响功能
            ProjectLogHelper.log(null, "自动发现: 预览高亮失败: ${ex.message}")
        }
    }
}


