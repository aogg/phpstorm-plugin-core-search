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

    /** tab计数器，用于生成唯一的tab标题 */
    private var tabCounter = 0

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
     * @param targetLine 要定位到的目标行（如果为null则定位到方法起始行）
     * @return 只读编辑器实例，失败时返回null
     */
    fun createEditorForMethodPreview(project: Project, method: Method, searchKeyword: String = "", targetLine: Int? = null): com.intellij.openapi.editor.Editor? {
        return ReadAction.compute<com.intellij.openapi.editor.Editor?, Throwable> {
            try {
                val containingFile = method.containingFile ?: return@compute null
                val virtualFile = containingFile.virtualFile ?: return@compute null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@compute null

                // 确定定位行：优先使用传入的 targetLine，否则使用方法起始行
                val methodStartLine = document.getLineNumber(method.textRange.startOffset)
                val scrollToLine = targetLine ?: methodStartLine
                ProjectLogHelper.log(project, "Auto Discover: 创建方法预览编辑器 ${method.name} 在文件 ${virtualFile.path}，方法起始行=${methodStartLine + 1}，定位行=${scrollToLine + 1}")

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

                // 使用更可靠的滚动策略：监听组件显示事件 + 延迟执行
                var scrollAttempted = false
                val scrollRunnableHolder = arrayOfNulls<Runnable>(1)
                
                scrollRunnableHolder[0] = Runnable {
                    if (scrollAttempted) return@Runnable // 防止重复执行
                    scrollAttempted = true

                    try {
                        // 确保编辑器组件已经添加到容器中且可见
                        val editorComponent = editor.component
                        if (!editorComponent.isShowing) {
                            ProjectLogHelper.log(project, "Auto Discover: 编辑器组件尚未显示，延迟滚动")
                            // 如果组件还没显示，重新调度
                            com.intellij.util.Alarm().addRequest({
                                scrollAttempted = false // 重置标志，允许重试
                                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)
                            }, 100)
                            return@Runnable
                        }

                        // 使用 LogicalPosition 进行滚动（IntelliJ 的 scrollTo 方法接受 LogicalPosition）
                        val logicalPosition = LogicalPosition(scrollToLine, 0)

                        ProjectLogHelper.log(project, "Auto Discover: 滚动到第${scrollToLine + 1}行 - logicalPosition=$logicalPosition, 组件显示状态=${editorComponent.isShowing}")

                        // 先设置光标位置
                        editor.caretModel.moveToLogicalPosition(logicalPosition)

                        // 滚动到目标位置（使用 CENTER 确保在窗口中央）
                        editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)

                        // 高亮搜索关键词
                        if (searchKeyword.isNotEmpty()) {
                            highlightSearchKeywordInEditor(editor, searchKeyword)
                        }

                        // 验证滚动是否成功
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            try {
                                val visibleArea = editor.scrollingModel.visibleArea
                                val targetY = editor.logicalPositionToXY(logicalPosition).y
                                val isScrollSuccessful = targetY >= visibleArea.y && targetY <= (visibleArea.y + visibleArea.height)

                                ProjectLogHelper.log(project, "Auto Discover: 滚动验证 - 目标行Y=$targetY, 可见区域=$visibleArea, 滚动成功=$isScrollSuccessful")

                                if (!isScrollSuccessful) {
                                    ProjectLogHelper.log(project, "Auto Discover: 滚动失败，尝试使用备用滚动方法")
                                    // 备用滚动方案：使用外层滚动面板
                                    scrollEditorToLineUsingOuterScrollPane(editor, scrollToLine, project)
                                } else {
                                    ProjectLogHelper.log(project, "Auto Discover: 滚动成功")
                                }
                            } catch (verifyEx: Throwable) {
                                ProjectLogHelper.log(project, "Auto Discover: 滚动验证失败: ${verifyEx.message}")
                            }
                        }

                        ProjectLogHelper.log(project, "Auto Discover: 滚动和高亮完成")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "Auto Discover: 滚动失败: ${ex.message}")
                        // 失败时尝试备用方案
                        try {
                            scrollEditorToLineUsingOuterScrollPane(editor, scrollToLine, project)
                        } catch (fallbackEx: Throwable) {
                            ProjectLogHelper.log(project, "Auto Discover: 备用滚动也失败: ${fallbackEx.message}")
                        }
                    }
                }

                // 首先尝试立即执行
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)

                // 如果立即执行可能失败，再次尝试延迟执行
                com.intellij.util.Alarm().addRequest({
                    if (!scrollAttempted) {
                        ProjectLogHelper.log(project, "Auto Discover: 初始滚动未执行，重试")
                        scrollAttempted = false
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)
                    }
                }, 50)

                return@compute editor
            } catch (ex: Throwable) {
                return@compute null
            }
        }
    }

    /**
     * 使用外层 JBScrollPane 滚动编辑器到指定行
     * @param editor 编辑器实例
     * @param line 要滚动到的行号
     * @param project 项目实例（用于日志记录）
     */
    private fun scrollEditorToLineUsingOuterScrollPane(editor: com.intellij.openapi.editor.Editor, line: Int, project: Project? = null) {
        try {
            // 查找外层的 JBScrollPane
            val editorComponent = editor.component
            var scrollPane: javax.swing.JScrollPane? = null

            // 从 editor.component 开始向上查找 JBScrollPane
            var current = editorComponent.parent
            while (current != null && scrollPane == null) {
                if (current is javax.swing.JScrollPane) {
                    scrollPane = current
                }
                current = current.parent
            }

            if (scrollPane == null) {
                ProjectLogHelper.log(project, "Auto Discover: 未找到外层 JBScrollPane，使用编辑器内部滚动")
                // 回退到编辑器内部滚动
                val logicalPosition = LogicalPosition(line, 0)
                editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
                return
            }

            // 计算目标行的 Y 坐标
            val logicalPosition = LogicalPosition(line, 0)
            val point = editor.logicalPositionToXY(logicalPosition)

            ProjectLogHelper.log(project, "Auto Discover: 目标行 ${line + 1} 的 Y 坐标: ${point.y}")

            // 获取可见区域的高度
            val visibleHeight = scrollPane.viewport.extentSize.height

            // 计算滚动位置：目标行Y - 可见高度的一半（使目标行在中心）
            val scrollY = maxOf(0, point.y - visibleHeight / 2)

            // 获取滚动条
            val verticalScrollBar = scrollPane.verticalScrollBar

            // 设置滚动条位置
            verticalScrollBar.value = scrollY

            ProjectLogHelper.log(project, "Auto Discover: 外层滚动条滚动完成 - 目标行=${line + 1}, Y坐标=${point.y}, 可见高度=$visibleHeight, 滚动位置=$scrollY, 滚动条值=${verticalScrollBar.value}")

            // 验证滚动是否成功
            val currentScrollY = verticalScrollBar.value
            if (currentScrollY == scrollY) {
                ProjectLogHelper.log(project, "Auto Discover: ✅ 外层滚动条设置成功")
            } else {
                ProjectLogHelper.log(project, "Auto Discover: ⚠️ 外层滚动条设置未生效，期望=$scrollY, 实际=$currentScrollY")
            }

        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "Auto Discover: 外层滚动条滚动失败: ${ex.message}，使用编辑器内部滚动作为回退")
            // 回退到编辑器内部滚动
            try {
                val logicalPosition = LogicalPosition(line, 0)
                editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
            } catch (innerEx: Throwable) {
                ProjectLogHelper.log(project, "Auto Discover: 编辑器内部滚动也失败: ${innerEx.message}")
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
     * 临时高亮指定行（假设编辑器已滚动到正确位置）
     * @param editor 编辑器实例
     * @param line 要高亮的行号
     * @param durationMs 高亮持续时间（毫秒）
     * @param project 项目实例（用于日志记录）
     */
    private fun temporarilyHighlightLine(editor: com.intellij.openapi.editor.Editor, line: Int, durationMs: Int = 1000, project: Project? = null) {
        try {
            val document = editor.document
            // 确保行号有效
            if (line < 0 || line >= document.lineCount) {
                ProjectLogHelper.log(project, "Auto Discover: 行号无效，line=$line, document.lineCount=${document.lineCount}")
                return
            }

            ProjectLogHelper.log(project, "Auto Discover: 开始临时高亮第${line + 1}行")

            // 获取行的起始和结束偏移
            val lineStartOffset = document.getLineStartOffset(line)
            val lineEndOffset = document.getLineEndOffset(line)

            // 创建高亮属性（浅蓝色背景）
            val textAttributes = TextAttributes()
            textAttributes.backgroundColor = Color(173, 216, 230) // Light blue
            textAttributes.foregroundColor = Color.BLACK

            // 添加高亮
            val highlighter = editor.markupModel.addRangeHighlighter(
                lineStartOffset,
                lineEndOffset,
                HighlighterLayer.SELECTION - 1,
                textAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            ProjectLogHelper.log(project, "Auto Discover: 已添加临时高亮，高亮范围: $lineStartOffset-$lineEndOffset")

            // 请求焦点
            editor.contentComponent.requestFocusInWindow()
            ProjectLogHelper.log(project, "Auto Discover: 已请求编辑器焦点")

            // 延迟移除高亮
            com.intellij.util.Alarm().addRequest({
                try {
                    // 在移除高亮前获取预览的滚动条位置，判断滚动是否成功
                    try {
                        val scrollingModel = editor.scrollingModel
                        val visibleArea = scrollingModel.visibleArea

                        // 计算目标行在文档中的位置
                        val document = editor.document
                        val targetLineStartOffset = document.getLineStartOffset(line)
                        val targetLineEndOffset = document.getLineEndOffset(line)
                        val targetLineStartY = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
                        val targetLineEndY = editor.logicalPositionToXY(LogicalPosition(line + 1, 0)).y

                        // 检查目标行是否在可见区域内（滚动成功判断）
                        val isScrollSuccessful = targetLineStartY >= visibleArea.y && targetLineEndY <= (visibleArea.y + visibleArea.height)

                        ProjectLogHelper.log(project, "Auto Discover: 预览滚动条位置检查 - 目标行=$line, 目标行Y范围=[$targetLineStartY, $targetLineEndY], 可见区域=$visibleArea, 滚动成功=$isScrollSuccessful")

                        // 尝试获取编辑器组件的滚动条（使用更安全的方式）
                        val editorComponent = editor.component
                        var scrollPane: javax.swing.JScrollPane? = null

                        // 从 editor.component 开始向上查找 JScrollPane
                        var current = editorComponent.parent
                        while (current != null && scrollPane == null) {
                            if (current is javax.swing.JScrollPane) {
                                scrollPane = current
                            }
                            current = current.parent
                        }

                        if (scrollPane != null) {
                            val verticalScrollBar = scrollPane.verticalScrollBar
                            val horizontalScrollBar = scrollPane.horizontalScrollBar

                            ProjectLogHelper.log(project, "Auto Discover: 滚动条位置 - 垂直: 值=${verticalScrollBar.value}, 最小=${verticalScrollBar.minimum}, 最大=${verticalScrollBar.maximum}, 可见量=${verticalScrollBar.visibleAmount}")
                            ProjectLogHelper.log(project, "Auto Discover: 滚动条位置 - 水平: 值=${horizontalScrollBar.value}, 最小=${horizontalScrollBar.minimum}, 最大=${horizontalScrollBar.maximum}, 可见量=${horizontalScrollBar.visibleAmount}")
                        } else {
                            ProjectLogHelper.log(project, "Auto Discover: 未找到滚动面板")
                        }
                    } catch (scrollEx: Throwable) {
                        ProjectLogHelper.log(project, "Auto Discover: 获取滚动条位置失败: ${scrollEx.message}")
                    }

                    editor.markupModel.removeHighlighter(highlighter)
                    ProjectLogHelper.log(project, "Auto Discover: 高亮已自动移除")
                } catch (ex: Throwable) {
                    ProjectLogHelper.log(project, "Auto Discover: 移除高亮失败: ${ex.message}")

                    // 异常时记录滚动条位置信息
                    try {
                        val scrollingModel = editor.scrollingModel
                        val visibleArea = scrollingModel.visibleArea

                        ProjectLogHelper.log(project, "Auto Discover: 异常时可见区域: $visibleArea")

                        // 尝试获取编辑器组件的滚动条（使用更安全的方式）
                        val editorComponent = editor.component
                        var scrollPane: javax.swing.JScrollPane? = null

                        // 从 editor.component 开始向上查找 JScrollPane
                        var current = editorComponent.parent
                        while (current != null && scrollPane == null) {
                            if (current is javax.swing.JScrollPane) {
                                scrollPane = current
                            }
                            current = current.parent
                        }

                        if (scrollPane != null) {
                            val verticalScrollBar = scrollPane.verticalScrollBar
                            val horizontalScrollBar = scrollPane.horizontalScrollBar

                            ProjectLogHelper.log(project, "Auto Discover: 异常时滚动条位置 - 垂直: 值=${verticalScrollBar.value}, 最小=${verticalScrollBar.minimum}, 最大=${verticalScrollBar.maximum}, 可见量=${verticalScrollBar.visibleAmount}")
                            ProjectLogHelper.log(project, "Auto Discover: 异常时滚动条位置 - 水平: 值=${horizontalScrollBar.value}, 最小=${horizontalScrollBar.minimum}, 最大=${horizontalScrollBar.maximum}, 可见量=${horizontalScrollBar.visibleAmount}")
                        } else {
                            ProjectLogHelper.log(project, "Auto Discover: 未找到滚动面板")
                        }
                    } catch (scrollEx: Throwable) {
                        ProjectLogHelper.log(project, "Auto Discover: 获取滚动条位置失败: ${scrollEx.message}")
                    }
                }
            }, durationMs)

            ProjectLogHelper.log(project, "Auto Discover: 临时高亮和聚焦设置完成，将在 ${durationMs}ms 后移除高亮")

        } catch (ex: Throwable) {
            ProjectLogHelper.log(project, "Auto Discover: 临时高亮和聚焦失败: ${ex.message}")
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
     * 创建只读编辑器用于预览指定文件的指定行，支持点击临时高亮
     * @param project 项目实例
     * @param filePath 文件路径
     * @param line 要跳转的行号（从0开始）
     * @param searchKeyword 要高亮的搜索关键词
     * @return 只读编辑器实例，失败时返回null
     */
    fun createEditorForFileAtLine(project: Project, filePath: String, line: Int, searchKeyword: String = ""): com.intellij.openapi.editor.Editor? {
        return ReadAction.compute<com.intellij.openapi.editor.Editor?, Throwable> {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@compute null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@compute null

                // 记录正在创建编辑器预览的文件和行号
                ProjectLogHelper.log(project, "Auto Discover: 创建文件预览编辑器 ${filePath} 第${line + 1}行")

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

                // 计算有效行号，确保在有效范围内
                val effectiveLine = maxOf(0, minOf(line, document.lineCount - 1))

                // 使用更可靠的滚动策略：监听组件显示事件 + 延迟执行
                var scrollAttempted = false
                val scrollRunnableHolder = arrayOfNulls<Runnable>(1)
                
                scrollRunnableHolder[0] = Runnable {
                    if (scrollAttempted) return@Runnable // 防止重复执行
                    scrollAttempted = true

                    try {
                        // 确保编辑器组件已经添加到容器中且可见
                        val editorComponent = editor.component
                        if (!editorComponent.isShowing) {
                            ProjectLogHelper.log(project, "Auto Discover: 文件预览编辑器组件尚未显示，延迟滚动")
                            // 如果组件还没显示，重新调度
                            com.intellij.util.Alarm().addRequest({
                                scrollAttempted = false // 重置标志，允许重试
                                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)
                            }, 100)
                            return@Runnable
                        }

                        // 使用 LogicalPosition 进行滚动（IntelliJ 的 scrollTo 方法接受 LogicalPosition）
                        val logicalPosition = LogicalPosition(effectiveLine, 0)

                        ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动到第${effectiveLine + 1}行 - logicalPosition=$logicalPosition, 组件显示状态=${editorComponent.isShowing}")

                        // 先设置光标位置
                        editor.caretModel.moveToLogicalPosition(logicalPosition)

                        // 滚动到目标位置（使用 CENTER 确保在窗口中央）
                        editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)

                        // 高亮搜索关键词
                        if (searchKeyword.isNotEmpty()) {
                            highlightSearchKeywordInEditor(editor, searchKeyword)
                        }

                        // 验证滚动是否成功
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            try {
                                val visibleArea = editor.scrollingModel.visibleArea
                                val targetY = editor.logicalPositionToXY(logicalPosition).y
                                val isScrollSuccessful = targetY >= visibleArea.y && targetY <= (visibleArea.y + visibleArea.height)

                                ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动验证 - 目标行Y=$targetY, 可见区域=$visibleArea, 滚动成功=$isScrollSuccessful")

                                if (!isScrollSuccessful) {
                                    ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动失败，尝试使用备用滚动方法")
                                    // 备用滚动方案：使用外层滚动面板
                                    scrollEditorToLineUsingOuterScrollPane(editor, effectiveLine, project)
                                } else {
                                    ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动成功")
                                }
                            } catch (verifyEx: Throwable) {
                                ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动验证失败: ${verifyEx.message}")
                            }
                        }

                        ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动和高亮完成")
                    } catch (ex: Throwable) {
                        ProjectLogHelper.log(project, "Auto Discover: 文件预览滚动失败: ${ex.message}")
                        // 失败时尝试备用方案
                        try {
                            scrollEditorToLineUsingOuterScrollPane(editor, effectiveLine, project)
                        } catch (fallbackEx: Throwable) {
                            ProjectLogHelper.log(project, "Auto Discover: 文件预览备用滚动也失败: ${fallbackEx.message}")
                        }
                    }
                }

                // 首先尝试立即执行
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)

                // 如果立即执行可能失败，再次尝试延迟执行
                com.intellij.util.Alarm().addRequest({
                    if (!scrollAttempted) {
                        ProjectLogHelper.log(project, "Auto Discover: 文件预览初始滚动未执行，重试")
                        scrollAttempted = false
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(scrollRunnableHolder[0]!!)
                    }
                }, 50)

                // 添加鼠标点击监听器，用于临时高亮点击行
                editor.contentComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        // 获取点击位置的逻辑位置
                        val mousePosition = editor.xyToLogicalPosition(e.point)
                        val clickedLine = mousePosition.line

                        // 确保行号有效
                        if (clickedLine >= 0 && clickedLine < document.lineCount) {
                            // 获取行的起始和结束偏移
                            val lineStartOffset = document.getLineStartOffset(clickedLine)
                            val lineEndOffset = document.getLineEndOffset(clickedLine)

                            // 创建高亮属性（浅蓝色背景）
                            val textAttributes = TextAttributes()
                            textAttributes.backgroundColor = Color(173, 216, 230) // Light blue
                            textAttributes.foregroundColor = Color.BLACK

                            // 添加高亮
                            val highlighter = editor.markupModel.addRangeHighlighter(
                                lineStartOffset,
                                lineEndOffset,
                                HighlighterLayer.SELECTION - 1,
                                textAttributes,
                                HighlighterTargetArea.EXACT_RANGE
                            )

                            // 1秒后移除高亮
                            com.intellij.util.Alarm().addRequest({
                                try {
                                    editor.markupModel.removeHighlighter(highlighter)
                                } catch (_: Throwable) {
                                    // 移除失败不影响功能
                                }
                            }, 1000)
                        }
                    }
                })

                return@compute editor
            } catch (ex: Throwable) {
                return@compute null
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
            val lineContent = try {
                doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
            } catch (_: Throwable) {
                ""
            }
            val preview = if (lineContent.length > 120) lineContent.substring(0, 120) + "..." else lineContent

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
                    callerMethodName = callerMethodName,
                    lineContent = lineContent
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
            val methodNode = DefaultMutableTreeNode("$methodName (${methodItems.size})")
            
            // 第二层：按调用方法名分组
            val callerGroups = methodItems.groupBy { it.callerMethodName.ifEmpty { "其他" } }
            
            for ((callerName, callerItems) in callerGroups) {
                val callerNode = DefaultMutableTreeNode("$callerName (${callerItems.size})")
                
                for (item in callerItems) {
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
                    // 显示行内容（去除首尾空白）
                    val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.callerMethodName
                    val label = "$displayContent — $relPath"
                    val leafNode = DefaultMutableTreeNode(label)
                    leafNode.userObject = item
                    callerNode.add(leafNode)
                }
                methodNode.add(callerNode)
            }
            rootNode.add(methodNode)
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
                        val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.callerMethodName
                        this.text = "$displayContent — $relPath"
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

        // 分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewPanel
        splitPane.resizeWeight = 0.4
        splitPane.dividerLocation = 400
        splitPane.minimumSize = java.awt.Dimension(800, 600)
        // 设置最小宽度
        val leftScrollPane = splitPane.leftComponent as JBScrollPane
        leftScrollPane.minimumSize = java.awt.Dimension(200, 0)
        previewPanel.minimumSize = java.awt.Dimension(300, 0)

        // 控制面板（显示预览开关）
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)
        val showPreviewCheckBox = JCheckBox("显示预览", true)
        showPreviewCheckBox.addItemListener(object : ItemListener {
            override fun itemStateChanged(e: ItemEvent) {
                val showPreview = e.stateChange == ItemEvent.SELECTED
                splitPane.rightComponent = if (showPreview) previewPanel else null
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
                ProjectLogHelper.log(project, "Auto Discover: 树节点选择事件触发，开始处理预览")
                val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                    val selectedItem = selectedNode.userObject as DisplayItem
                    ProjectLogHelper.log(project, "Auto Discover: 选中节点 item=${selectedItem.callerMethodName}@${selectedItem.filePath}:${selectedItem.line}")

                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

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
                            // 创建新的编辑器预览，传递 selectedItem.line 作为目标行
                            val newEditor = createEditorForMethodPreview(project, method, title, selectedItem.line)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览方法 ${method.name} 在文件 ${method.containingFile?.virtualFile?.path}，高亮调用行=${selectedItem.line + 1}")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                // 回退到按行打开文件预览
                                val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                                editorHolder.editor = newEditor
                                if (newEditor != null) {
                                    previewPanel.removeAll()
                                    previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                    // 临时高亮并聚焦到调用行
                                    ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                    temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                                } else {
                                    previewPanel.removeAll()
                                    val fallbackTextArea = JBTextArea(if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览")
                                    fallbackTextArea.isEditable = false
                                    previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                }
                            }
                        } else {
                            // 回退到按行打开文件预览
                            val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                previewPanel.removeAll()
                                val fallbackTextArea = JBTextArea("无法找到对应的方法")
                                fallbackTextArea.isEditable = false
                                previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            }
                        }
                    } else {
                        // 回退到按行打开文件预览
                        val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                        editorHolder.editor = newEditor
                        if (newEditor != null) {
                            previewPanel.removeAll()
                            previewPanel.add(newEditor.component, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                            // 临时高亮并聚焦到调用行
                            ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                            temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                        } else {
                            previewPanel.removeAll()
                            val fallbackTextArea = JBTextArea("无法获取元素信息")
                            fallbackTextArea.isEditable = false
                            previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                        }
                    }
                } else {
                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

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
                ProjectLogHelper.log(project, "Auto Discover: 鼠标点击事件 clickCount=${e.clickCount}")
                if (e.clickCount == 2) {
                    ProjectLogHelper.log(project, "Auto Discover: 检测到双击，开始跳转到文件")
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                        val selectedItem = selectedNode.userObject as DisplayItem
                        ProjectLogHelper.log(project, "Auto Discover: 跳转到文件 ${selectedItem.filePath}:${selectedItem.line}")
                        val vf = LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
                        if (vf != null) {
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, selectedItem.line, 0).navigate(true)
                            ProjectLogHelper.log(project, "Auto Discover: 跳转完成")
                        } else {
                            ProjectLogHelper.log(project, "Auto Discover: 找不到文件 ${selectedItem.filePath}")
                        }
                    } else {
                        ProjectLogHelper.log(project, "Auto Discover: 双击的节点不是有效的DisplayItem")
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
        // 为避免重复标题，给每个tab添加序号后缀
        tabCounter++
        val uniqueTitle = "$title #$tabCounter"
        val content = contentFactory.createContent(mainPanel, uniqueTitle, true)

        // 添加内容监听器，在内容移除时释放编辑器资源
        content.addPropertyChangeListener { evt ->
            if ("disposed" == evt.propertyName) {
                releaseEditor(editorHolder.editor)
            }
        }

        // 确保新增tab而不是替换现有内容
        // 先检查是否已存在相同标题的内容，如果有则使用不同的标题
        val existingTitles = toolWindow.contentManager.contents.map { it.displayName }
        var finalTitle = uniqueTitle
        var counter = 1
        while (existingTitles.contains(finalTitle)) {
            finalTitle = "$uniqueTitle (${counter++})"
        }

        // 如果标题被修改了，重新创建content
        val finalContent = if (finalTitle != uniqueTitle) {
            contentFactory.createContent(mainPanel, finalTitle, true).apply {
                addPropertyChangeListener { evt ->
                    if ("disposed" == evt.propertyName) {
                        releaseEditor(editorHolder.editor)
                    }
                }
            }
        } else content

        toolWindow.contentManager.addContent(finalContent)

        // 激活新添加的tab
        toolWindow.contentManager.setSelectedContent(finalContent)

        // 显示工具窗口
        toolWindow.show(null)
        ProjectLogHelper.log(project, "Auto Discover: 工具窗口已显示 title=$title items=${items.size}")
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
            val lineContent = try {
                doc.getText(TextRange(lineStart, lineEnd)).trim()
            } catch (_: Throwable) {
                ""
            }
            val preview = if (lineContent.length > 120) lineContent.substring(0, 120) + "..." else lineContent

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
                    callerMethodName = callerMethodName,
                    lineContent = lineContent
                )
            )
        }

        // 按目标方法名分组（targetMethodName）
        val groupedItems = items.groupBy { if (it.targetMethodName.isNotEmpty()) it.targetMethodName else "其他" }

        // 创建树形结构
        val rootNode = DefaultMutableTreeNode("搜索结果")
        for ((targetName, methodItems) in groupedItems) {
            val methodNode = DefaultMutableTreeNode("$targetName (${methodItems.size})")
            
            // 第二层：按调用方法名分组
            val callerGroups = methodItems.groupBy { it.callerMethodName.ifEmpty { "其他" } }
            
            for ((callerName, callerItems) in callerGroups) {
                val callerNode = DefaultMutableTreeNode("$callerName (${callerItems.size})")
                
                for (item in callerItems) {
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
                    val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.callerMethodName
                    val label = "$displayContent — $relPath"
                    val leafNode = DefaultMutableTreeNode(label)
                    leafNode.userObject = item // 存储完整的DisplayItem对象
                    callerNode.add(leafNode)
                }
                methodNode.add(callerNode)
            }
            rootNode.add(methodNode)
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
                                java.io.File(base).toPath().relativize(java.io.File(item.filePath).toPath()).toString()
                                    .replace(java.io.File.separatorChar, '/')
                            } else item.filePath
                        } catch (_: Throwable) {
                            item.filePath
                        }
                        val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.callerMethodName
                        this.text = "$displayContent — $relPath"
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

        // 创建分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewPanel
        splitPane.resizeWeight = 0.4  // 树占40%，预览占60%
        splitPane.dividerLocation = 400

        // 创建控制面板
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)

        val showPreviewCheckBox = JCheckBox("显示预览", true)
        showPreviewCheckBox.addItemListener(object : ItemListener {
            override fun itemStateChanged(e: ItemEvent) {
                val showPreview = e.stateChange == ItemEvent.SELECTED
                splitPane.rightComponent = if (showPreview) previewPanel else null
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

        // 树选择监听，更新预览并高亮
        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent) {
                val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                    val selectedItem = selectedNode.userObject as DisplayItem

                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

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
                            // 创建新的编辑器预览，传递 selectedItem.line 作为目标行
                            val newEditor = createEditorForMethodPreview(project, method, title, selectedItem.line)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览方法 ${method.name} 在文件 ${method.containingFile?.virtualFile?.path}，高亮调用行=${selectedItem.line + 1}")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                // 回退到按行打开文件预览
                                val fallbackEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                                editorHolder.editor = fallbackEditor
                                if (fallbackEditor != null) {
                                    previewPanel.removeAll()
                                    previewPanel.add(fallbackEditor.component, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                    // 临时高亮并聚焦到调用行
                                    ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                    temporarilyHighlightLine(fallbackEditor, selectedItem.line, project = project)
                                } else {
                                    previewPanel.removeAll()
                                    val fallbackTextArea = JBTextArea(if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览")
                                    fallbackTextArea.isEditable = false
                                    previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                }
                            }
                        } else {
                            // 回退到按行打开文件预览
                            val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                previewPanel.removeAll()
                                val fallbackTextArea = JBTextArea("无法找到对应的方法")
                                fallbackTextArea.isEditable = false
                                previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            }
                        }
                    } else {
                        // 回退到按行打开文件预览
                        val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, title)
                        editorHolder.editor = newEditor
                        if (newEditor != null) {
                            previewPanel.removeAll()
                            previewPanel.add(newEditor.component, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                            // 临时高亮并聚焦到调用行
                            ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                            temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                        } else {
                            previewPanel.removeAll()
                            val fallbackTextArea = JBTextArea("无法获取元素信息")
                            fallbackTextArea.isEditable = false
                            previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                        }
                    }
                } else {
                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

                    previewPanel.removeAll()
                    val defaultTextArea = JBTextArea("选择一个具体的结果项查看预览")
                    defaultTextArea.isEditable = false
                    previewPanel.add(defaultTextArea, BorderLayout.CENTER)
                    previewPanel.revalidate()
                    previewPanel.repaint()
                }
            }
        })

        // 默认展开第一级节点并选择第一个叶子节点
        if (rootNode.childCount > 0) {
            val firstMethodNode = rootNode.getChildAt(0) as DefaultMutableTreeNode
            tree.expandPath(javax.swing.tree.TreePath(firstMethodNode.path))
            if (firstMethodNode.childCount > 0) {
                val firstCallerNode = firstMethodNode.getChildAt(0) as DefaultMutableTreeNode
                tree.expandPath(javax.swing.tree.TreePath(firstCallerNode.path))
                if (firstCallerNode.childCount > 0) {
                    val firstLeafNode = firstCallerNode.getChildAt(0) as DefaultMutableTreeNode
                    tree.selectionPath = javax.swing.tree.TreePath(firstLeafNode.path)
                }
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
            val lineContent = try {
                doc.getText(TextRange(lineStart, lineEnd)).trim()
            } catch (_: Throwable) {
                ""
            }
            val preview = if (lineContent.length > 120) lineContent.substring(0, 120) + "..." else lineContent

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
                    callerMethodName = callerMethodName,
                    lineContent = lineContent
                )
            )
        }

        // 核心搜索结果直接按照 目标方法名（关键词） -> 调用方法名 -> 结果 组织
        // 但由于核心搜索本身就是针对某个关键词（即目标方法名），所以第一层其实就是该关键词，或者我们可以省略第一层，
        // 不过为了保持一致性，我们还是用3层： Keyword -> Caller Method -> Usage
        // 但由于keyword是传入的参数，所以所有item的targetMethodName应该都是keyword

        val groupedItems = items.groupBy { item ->
            item.targetMethodName.ifEmpty { keyword }
        }

        val rootNode = DefaultMutableTreeNode("核心搜索结果")
        for ((targetName, methodItems) in groupedItems) {
            val targetNode = DefaultMutableTreeNode("$targetName (${methodItems.size})")
            
            // 第二层：按调用方法名分组
            val callerGroups = methodItems.groupBy { it.callerMethodName.ifEmpty { "其他" } }
            
            for ((callerName, callerItems) in callerGroups) {
                val callerNode = DefaultMutableTreeNode("$callerName (${callerItems.size})")
                
                for (item in callerItems) {
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
                    // 显示行内容（去除首尾空白）
                    val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.callerMethodName
                    val label = "$displayContent — $relPath"
                    val leafNode = DefaultMutableTreeNode(label)
                    leafNode.userObject = item
                    callerNode.add(leafNode)
                }
                targetNode.add(callerNode)
            }
            rootNode.add(targetNode)
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
                        // 兼容旧代码，如果没有lineContent字段则不显示
                        val displayContent = if (item.lineContent.isNotBlank()) item.lineContent else item.methodName
                        this.text = "$displayContent — $relPath"
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

        // 分割面板（左右分屏）
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = JBScrollPane(tree)
        splitPane.rightComponent = previewPanel
        splitPane.resizeWeight = 0.4
        splitPane.dividerLocation = 400
        splitPane.minimumSize = java.awt.Dimension(800, 600)

        // 设置最小宽度
        val leftScrollPane = splitPane.leftComponent as JBScrollPane
        leftScrollPane.minimumSize = java.awt.Dimension(200, 0)
        previewPanel.minimumSize = java.awt.Dimension(300, 0)

        // 控制面板（显示预览开关）
        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)
        val showPreviewCheckBox = JCheckBox("显示预览", true)
        showPreviewCheckBox.addItemListener(object : ItemListener {
            override fun itemStateChanged(e: ItemEvent) {
                val showPreview = e.stateChange == ItemEvent.SELECTED
                splitPane.rightComponent = if (showPreview) previewPanel else null
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

                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

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
                            // 创建新的编辑器预览，传递 selectedItem.line 作为目标行
                            val newEditor = createEditorForMethodPreview(project, method, keyword, selectedItem.line)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览方法 ${method.name} 在文件 ${method.containingFile?.virtualFile?.path}，高亮调用行=${selectedItem.line + 1}")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                // 回退到按行打开文件预览
                                val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, keyword)
                                editorHolder.editor = newEditor
                                if (newEditor != null) {
                                    previewPanel.removeAll()
                                    previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                    // 临时高亮并聚焦到调用行
                                    ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                    temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                                } else {
                                    previewPanel.removeAll()
                                    val fallbackTextArea = JBTextArea(if (selectedItem.previewText.isNotEmpty()) selectedItem.previewText else "无法获取方法预览")
                                    fallbackTextArea.isEditable = false
                                    previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                    previewPanel.revalidate()
                                    previewPanel.repaint()
                                }
                            }
                        } else {
                            // 回退到按行打开文件预览
                            val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, keyword)
                            editorHolder.editor = newEditor
                            if (newEditor != null) {
                                previewPanel.removeAll()
                                previewPanel.add(newEditor.component, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                                // 临时高亮并聚焦到调用行
                                ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                                temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                            } else {
                                previewPanel.removeAll()
                                val fallbackTextArea = JBTextArea("无法找到对应的方法")
                                fallbackTextArea.isEditable = false
                                previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                                previewPanel.revalidate()
                                previewPanel.repaint()
                            }
                        }
                    } else {
                        // 回退到按行打开文件预览
                        val newEditor = createEditorForFileAtLine(project, selectedItem.filePath, selectedItem.line, keyword)
                        editorHolder.editor = newEditor
                        if (newEditor != null) {
                            previewPanel.removeAll()
                            previewPanel.add(newEditor.component, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                            // 临时高亮并聚焦到调用行
                            ProjectLogHelper.log(project, "Auto Discover: 预览文件 ${selectedItem.filePath} 第${selectedItem.line + 1}行")
                            temporarilyHighlightLine(newEditor, selectedItem.line, project = project)
                        } else {
                            previewPanel.removeAll()
                            val fallbackTextArea = JBTextArea("无法获取元素信息")
                            fallbackTextArea.isEditable = false
                            previewPanel.add(fallbackTextArea, BorderLayout.CENTER)
                            previewPanel.revalidate()
                            previewPanel.repaint()
                        }
                    }
                } else {
                    // 释放之前的编辑器并彻底清理UI状态
                    releaseEditor(editorHolder.editor)
                    editorHolder.editor = null

                    // 彻底清理预览面板，确保之前的组件完全移除
                    previewPanel.removeAll()
                    previewPanel.revalidate()
                    previewPanel.repaint()

                    // 强制GC清理可能残留的引用
                    System.gc()

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
                ProjectLogHelper.log(project, "Auto Discover: 鼠标点击事件 clickCount=${e.clickCount}")
                if (e.clickCount == 2) {
                    ProjectLogHelper.log(project, "Auto Discover: 检测到双击，开始跳转到文件")
                    val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    if (selectedNode != null && !selectedNode.isRoot && selectedNode.userObject is DisplayItem) {
                        val selectedItem = selectedNode.userObject as DisplayItem
                        ProjectLogHelper.log(project, "Auto Discover: 跳转到文件 ${selectedItem.filePath}:${selectedItem.line}")
                        val vf = LocalFileSystem.getInstance().findFileByPath(selectedItem.filePath)
                        if (vf != null) {
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, selectedItem.line, 0).navigate(true)
                            ProjectLogHelper.log(project, "Auto Discover: 跳转完成")
                        } else {
                            ProjectLogHelper.log(project, "Auto Discover: 找不到文件 ${selectedItem.filePath}")
                        }
                    } else {
                        ProjectLogHelper.log(project, "Auto Discover: 双击的节点不是有效的DisplayItem")
                    }
                }
            }
        })

        // 展开并选中第一个叶子
        if (rootNode.childCount > 0) {
            val firstGroupNode = rootNode.getChildAt(0) as DefaultMutableTreeNode
            tree.expandPath(javax.swing.tree.TreePath(firstGroupNode.path))
            if (firstGroupNode.childCount > 0) {
                val firstCallerNode = firstGroupNode.getChildAt(0) as DefaultMutableTreeNode
                tree.expandPath(javax.swing.tree.TreePath(firstCallerNode.path))
                if (firstCallerNode.childCount > 0) {
                    val firstLeafNode = firstCallerNode.getChildAt(0) as DefaultMutableTreeNode
                    tree.selectionPath = javax.swing.tree.TreePath(firstLeafNode.path)
                }
            }
        }

        // 设置工具窗口内容
        val contentFactory = com.intellij.ui.content.ContentFactory.SERVICE.getInstance()
        // 为避免重复标题，给每个tab添加序号后缀
        tabCounter++
        val uniqueTitle = "核心搜索: @$keyword #$tabCounter"
        val content = contentFactory.createContent(mainPanel, uniqueTitle, true)

        // 添加内容监听器，在内容移除时释放编辑器资源
        content.addPropertyChangeListener { evt ->
            if ("disposed" == evt.propertyName) {
                releaseEditor(editorHolder.editor)
            }
        }

        // 确保新增tab而不是替换现有内容
        // 先检查是否已存在相同标题的内容，如果有则使用不同的标题
        val existingTitles = toolWindow.contentManager.contents.map { it.displayName }
        var finalTitle = uniqueTitle
        var counter = 1
        while (existingTitles.contains(finalTitle)) {
            finalTitle = "$uniqueTitle (${counter++})"
        }

        // 如果标题被修改了，重新创建content
        val finalContent = if (finalTitle != uniqueTitle) {
            contentFactory.createContent(mainPanel, finalTitle, true).apply {
                addPropertyChangeListener { evt ->
                    if ("disposed" == evt.propertyName) {
                        releaseEditor(editorHolder.editor)
                    }
                }
            }
        } else content

        toolWindow.contentManager.addContent(finalContent)

        // 激活新添加的tab
        toolWindow.contentManager.setSelectedContent(finalContent)

        // 显示工具窗口
        toolWindow.show(null)
        ProjectLogHelper.log(project, "核心搜索: 工具窗口已显示 keyword=$keyword items=${items.size}")
    }

    /**
     * 在预览文本中高亮搜索关键字
     */
    fun highlightSearchKeyword(textArea: JBTextArea, searchKeyword: String) {
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


