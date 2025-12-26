package com.aogg.core.search.helper

import com.intellij.usages.UsageViewPresentation
import com.jetbrains.php.lang.psi.elements.Method
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
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
import java.awt.Color
import com.intellij.openapi.editor.ex.EditorEx

/**
 * UI 相关的辅助函数（对 UsageViewPresentation 使用反射尝试隐藏不需要的控件）
 */
object AutoDiscoverUiHelper {

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
}


