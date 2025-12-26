package com.aogg.core.search.helper

import com.intellij.usages.UsageViewPresentation
import com.jetbrains.php.lang.psi.elements.Method
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil

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
}


