package com.aogg.core.search.ui

import com.aogg.core.search.helper.ProjectLogHelper
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
class CoreSearchResultsPanel(private val items: List<SearchDisplayItem>, private val project: com.intellij.openapi.project.Project? = null) {

    val component: JPanel = JPanel(BorderLayout())

    init {
        ProjectLogHelper.log(project, "开始初始化 CoreSearchResultsPanel，items 数量: ${items.size}")
        val root = DefaultMutableTreeNode("搜索结果")
        // 按方法名分组
        val groupMap = linkedMapOf<String, MutableList<SearchDisplayItem>>()
        ProjectLogHelper.log(project, "开始按方法名分组搜索结果")
        for (it in items) {
            val key = it.methodName.ifBlank { "其他" }
            groupMap.computeIfAbsent(key) { mutableListOf() }.add(it)
        }
        ProjectLogHelper.log(project, "分组完成，共 ${groupMap.size} 个分组")
        for ((group, list) in groupMap) {
            val groupNode = DefaultMutableTreeNode(group)
            for (hit in list) {
                // 显示行内容（去除首尾空白）和文件路径
                val displayContent = if (hit.lineContent.isNotBlank()) hit.lineContent else hit.methodName
                val leaf = DefaultMutableTreeNode("$displayContent — ${hit.filePath}:${hit.line + 1}")
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

        // 提前创建右侧滚动面板，以便在监听器中访问
        val rightScrollPane = JBScrollPane(preview)

        // 添加组件监听器,用于在组件完全显示后滚动
        var pendingScroll = false
        
        fun executeScroll() {
            if (pendingScroll) {
                pendingScroll = false
                try {
                    ProjectLogHelper.log(project, "执行延迟滚动, preview内容长度=${preview.text.length}")
                    
                    // 方法1: 直接设置滚动条到最大值
                    val verticalScrollBar = rightScrollPane.verticalScrollBar
                    val maxValue = verticalScrollBar.maximum
                    verticalScrollBar.value = maxValue
                    
                    ProjectLogHelper.log(project, "方法1滚动完成: value=${verticalScrollBar.value}, max=${verticalScrollBar.maximum}")
                    
                    // 方法2: 使用 scrollRectToVisible 滚动到最后一行
                    val contentHeight = rightScrollPane.viewport.viewSize.height
                    val visibleHeight = rightScrollPane.viewport.extentSize.height
                    val bottomY = maxOf(0, contentHeight - visibleHeight)
                    
                    // 创建最后一行的矩形
                    val lastLineRect = java.awt.Rectangle(0, bottomY, 10, 20)
                    preview.scrollRectToVisible(lastLineRect)
                    
                    ProjectLogHelper.log(project, "方法2滚动完成: bottomY=$bottomY, viewHeight=$visibleHeight, contentHeight=$contentHeight")
                    
                    // 强制刷新
                    rightScrollPane.revalidate()
                    rightScrollPane.repaint()
                } catch (e: Exception) {
                    ProjectLogHelper.log(project, "延迟滚动失败: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // 添加组件监听器,监听组件显示和大小变化
        preview.addComponentListener(object : java.awt.event.ComponentListener {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                executeScroll()
            }
            
            override fun componentShown(e: java.awt.event.ComponentEvent) {
                executeScroll()
            }
            
            override fun componentHidden(e: java.awt.event.ComponentEvent) {}
            override fun componentMoved(e: java.awt.event.ComponentEvent) {}
        })

        tree.addTreeSelectionListener(TreeSelectionListener { ev ->
            ProjectLogHelper.log(project, "开始处理树节点选择事件")
            val node = ev.path.lastPathComponent as? DefaultMutableTreeNode ?: run {
                ProjectLogHelper.log(project, "节点不是 DefaultMutableTreeNode 类型，返回")
                return@TreeSelectionListener
            }
            val user = node.userObject
            if (user is SearchDisplayItem) {
                ProjectLogHelper.log(project, "选中节点包含 SearchDisplayItem: methodName=${user.methodName}, filePath=${user.filePath}, line=${user.line + 1}")
                val previewContent = user.preview + "\n\n" + "${user.filePath}:${user.line + 1}"
                preview.text = previewContent

                ProjectLogHelper.log(project, "预览内容已设置，长度: ${previewContent.length} 字符，行数: ${previewContent.lines().size}")

                // 计算匹配行在预览文本中的相对位置并滚动到该行
                val lines = previewContent.lines()
                ProjectLogHelper.log(project, "预览文本总行数: ${lines.size}")

                // 尝试找到包含方法名的行作为目标行
                var targetLineIndex = -1
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    // 查找包含方法名的行
                    if (line.contains(user.methodName) && !line.contains("${user.filePath}:")) {
                        targetLineIndex = i
                        ProjectLogHelper.log(project, "找到方法名行: 第${i + 1}行 - '$line'")
                        break
                    }
                }

                // 如果没找到方法名行，使用默认逻辑（第4行）
                if (targetLineIndex == -1) {
                    targetLineIndex = minOf(3, lines.size - 1) // 默认第4行，如果行数不够则最后一行
                    ProjectLogHelper.log(project, "未找到方法名行，使用默认目标行索引: $targetLineIndex")
                }

                ProjectLogHelper.log(project, "最终目标行索引: $targetLineIndex (第${targetLineIndex + 1}行)")

                try {
                    // 计算目标行的起始偏移量
                    val lineStartOffset = lines.take(targetLineIndex).sumOf { it.length + 1 } // +1 for \n
                    val safeOffset = minOf(lineStartOffset, previewContent.length - 1)

                    ProjectLogHelper.log(project, "预览文本长度: ${previewContent.length}, 行数: ${lines.size}, 计算的行起始偏移量: $lineStartOffset，安全偏移量: $safeOffset")

                    // 设置光标位置到最后
                    preview.caretPosition = previewContent.length
                    ProjectLogHelper.log(project, "已设置光标位置到最后: ${previewContent.length}")

                    // 标记需要滚动，由 ComponentListener 执行实际的滚动
                    pendingScroll = true
                    ProjectLogHelper.log(project, "已标记需要滚动")

                } catch (e: Exception) {
                    ProjectLogHelper.log(project, "预览滚动准备失败: ${e.message}")
                    e.printStackTrace()
                }

                ProjectLogHelper.log(project, "预览设置完成")
            } else {
                ProjectLogHelper.log(project, "选中节点的 userObject 不是 SearchDisplayItem 类型: ${user?.javaClass?.simpleName}")
                preview.text = ""
            }
        })

        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                ProjectLogHelper.log(project, "mouseClicked 被调用: clickCount=${e.clickCount}, button=${e.button}")
                if (e.clickCount == 2) {
                    ProjectLogHelper.log(project, "检测到双击事件，开始处理跳转逻辑")
                    val sel = tree.selectionPath ?: run {
                        ProjectLogHelper.log(project, "没有选中的树路径，返回")
                        return
                    }
                    val node = sel.lastPathComponent as? DefaultMutableTreeNode ?: run {
                        ProjectLogHelper.log(project, "选中的路径最后组件不是 DefaultMutableTreeNode 类型，返回")
                        return
                    }
                    val user = node.userObject
                    if (user is SearchDisplayItem) {
                        ProjectLogHelper.log(project, "准备跳转到文件: ${user.filePath}:${user.line + 1}")

                        val vf = LocalFileSystem.getInstance().findFileByPath(user.filePath)
                        if (vf != null) {
                            ProjectLogHelper.log(project, "成功找到虚拟文件: ${vf.path}")
                            val proj = ProjectManager.getInstance().openProjects.firstOrNull() ?: run {
                                ProjectLogHelper.log(project, "没有找到打开的项目，返回")
                                return
                            }
                            ProjectLogHelper.log(project, "找到项目: ${proj.name}，准备创建 OpenFileDescriptor 并跳转")
                            val descriptor = OpenFileDescriptor(proj, vf, user.line, 0)
                            descriptor.navigate(true)
                            ProjectLogHelper.log(project, "跳转完成")
                        } else {
                            ProjectLogHelper.log(project, "无法找到文件: ${user.filePath}")
                        }
                    } else {
                        ProjectLogHelper.log(project, "双击的节点不包含 SearchDisplayItem: ${user?.javaClass?.simpleName}")
                    }
                }
            }
        })

                val left = JBScrollPane(tree)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightScrollPane)
        split.resizeWeight = 0.5
        component.add(split, BorderLayout.CENTER)
    }
}


