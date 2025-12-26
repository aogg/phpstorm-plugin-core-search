package com.aogg.core.search

import com.aogg.core.search.helper.AutoDiscoverDisplayHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoDiscoverDisplayHelperTest {

    @Test
    fun testGetMethodGroupFromName() {
        assertEquals("get", AutoDiscoverDisplayHelper.getMethodGroupFromName("getName"))
        assertEquals("set", AutoDiscoverDisplayHelper.getMethodGroupFromName("setValue"))
        assertEquals("is", AutoDiscoverDisplayHelper.getMethodGroupFromName("isActive"))
        assertEquals("has", AutoDiscoverDisplayHelper.getMethodGroupFromName("hasItems"))
        assertEquals("其他", AutoDiscoverDisplayHelper.getMethodGroupFromName("doSomething"))
    }

    @Test
    fun testFindDocsLabelFromBase() {
        val tempDir = createTempDir(prefix = "core-search-test")
        try {
            val docsDir = File(tempDir, "docs/核心逻辑/核心搜索")
            docsDir.mkdirs()
            val file = File(docsDir, "搜索逻辑.md")
            file.writeText("# 测试文档")

            val result = AutoDiscoverDisplayHelper.findDocsLabelFromBase(tempDir.absolutePath, "搜索逻辑", "搜索")
            assertTrue(result != null && result!!.startsWith("docs/"))
            assertTrue(result!!.contains("搜索逻辑.md"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}


