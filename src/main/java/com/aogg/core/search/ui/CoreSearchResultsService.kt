package com.aogg.core.search.ui

import com.aogg.core.search.model.SearchDisplayItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/**
 * 工具窗口操作服务，提供给外部调用以在工具窗口中打开搜索结果 tab
 */
object CoreSearchResultsService {
    fun showSearchTab(project: Project, tabName: String, items: List<SearchDisplayItem>) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Core Search Results") ?: return
        val panel = CoreSearchResultsPanel(items)
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory.createContent(panel.component, tabName, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.show(null)
    }
}


