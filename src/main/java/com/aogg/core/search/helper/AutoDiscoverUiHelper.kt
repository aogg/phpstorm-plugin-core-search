package com.aogg.core.search.helper

import com.intellij.usages.UsageViewPresentation

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
}


