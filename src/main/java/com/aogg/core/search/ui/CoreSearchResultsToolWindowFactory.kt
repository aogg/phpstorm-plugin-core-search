package com.aogg.core.search.ui

import com.aogg.core.search.model.SearchDisplayItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.Content
import com.intellij.openapi.components.ServiceManager
import javax.swing.JPanel

/**
 * 工具窗口工厂：注册 Core Search Results 工具窗口并提供静态服务接口以便在任意位置打开 tab
 */
class CoreSearchResultsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 初始内容为空的容器面板
        val container = javax.swing.JPanel()
        container.layout = java.awt.BorderLayout()
        val content = ContentFactory.SERVICE.getInstance().createContent(container, "Results", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object CoreSearchResultsService {
        /**
         * 在 Core Search Results 工具窗口中创建一个新的 tab 并显示给用户
         */
        fun showSearchTab(project: Project, tabName: String, items: List<SearchDisplayItem>) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Core Search Results")
                ?: return
            val panel = CoreSearchResultsPanel(items, project)
            val contentFactory = ContentFactory.SERVICE.getInstance()
            val content: Content = contentFactory.createContent(panel.component, tabName, false)
            // 支持关闭和刷新：允许用户关闭 tab
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.show(null)
        }
    }
}


