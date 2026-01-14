package com.aogg.core.search.model

/**
 * 搜索结果展示项模型
 */
data class SearchDisplayItem(
    val methodName: String,
    val filePath: String,
    val line: Int,
    val preview: String,
    val elementOffset: Int,
    val lineContent: String = ""
)


