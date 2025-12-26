package com.aogg.core.search.ui

import com.aogg.core.search.model.SearchDisplayItem
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.JTextArea
import javax.swing.tree.TreePath

/**
 * 单个 tab 的展示面板：左侧树按方法名分组显示结果，右侧预览并支持跳转
 */
class CoreSearchResultsPanel(private val items: List<SearchDisplayItem>) {

    val component: JPanel = JPanel(BorderLayout())

    init {
        val root = DefaultMutableTreeNode("搜索结果")
        // 按方法名分组
        val groupMap = linkedMapOf<String, MutableList<SearchDisplayItem>>()
        for (it in items) {
            val key = it.methodName.ifBlank { "其他" }
            groupMap.computeIfAbsent(key) { mutableListOf() }.add(it)
        }
        for ((group, list) in groupMap) {
            val groupNode = DefaultMutableTreeNode(group)
            for (hit in list) {
                val leaf = DefaultMutableTreeNode("${hit.methodName} — ${hit.filePath}:${hit.line + 1}")
                leaf.userObject = hit
                groupNode.add(leaf)
            }
            root.add(groupNode)
        }

        val treeModel = DefaultTreeModel(root)
        val tree = JTree(treeModel)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.isRootVisible = false

        val preview = JTextArea()
        preview.isEditable = false
        preview.lineWrap = true

        tree.addTreeSelectionListener(TreeSelectionListener { ev ->
            val node = ev.path.lastPathComponent as? DefaultMutableTreeNode ?: return@TreeSelectionListener
            val user = node.userObject
            if (user is SearchDisplayItem) {
                preview.text = user.preview + "\n\n" + "${user.filePath}:${user.line + 1}"
            } else {
                preview.text = ""
            }
        })

        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val sel = tree.selectionPath ?: return
                    val node = sel.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val user = node.userObject
                    if (user is SearchDisplayItem) {
                        val vf = LocalFileSystem.getInstance().findFileByPath(user.filePath)
                        if (vf != null) {
                            val proj = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
                            OpenFileDescriptor(proj, vf, user.line, 0).navigate(true)
                        }
                    }
                }
            }
        })

        val left = JBScrollPane(tree)
        val right = JBScrollPane(preview)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.resizeWeight = 0.5
        component.add(split, BorderLayout.CENTER)
    }
}


