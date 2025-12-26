package com.aogg.core.search.model

import com.intellij.usageView.UsageInfo

/**
 * 用于展示的显示项数据类
 */
data class DisplayItem(
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

/**
 * 包含使用信息和目标方法名的组合数据类
 */
data class UsageWithTarget(
    val usageInfo: UsageInfo,
    val targetMethodName: String
)
