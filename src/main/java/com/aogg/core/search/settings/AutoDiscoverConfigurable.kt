package com.aogg.core.search.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAware
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.BorderFactory
import java.awt.BorderLayout
import java.awt.Dimension

/**
 * 设置页面：允许用户编辑自动发现规则和大小写敏感开关
 */
class AutoDiscoverConfigurable : Configurable, DumbAware {

    private var mainPanel: JPanel? = null
    private var rulesArea: JTextArea? = null
    private var caseCheck: JCheckBox? = null

    override fun getDisplayName(): String {
        return "核心搜索 - 自动发现"
    }

    override fun createComponent(): javax.swing.JComponent? {
        if (mainPanel == null) {
            mainPanel = JPanel(BorderLayout())
            val inner = JPanel()
            inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
            inner.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            inner.add(JLabel("规则（每行一个，支持 * 通配符，例如 get* 或 get*attr）："))
            rulesArea = JTextArea()
            rulesArea!!.lineWrap = false
            rulesArea!!.preferredSize = Dimension(600, 200)
            val scroll = JScrollPane(rulesArea)
            inner.add(scroll)
            inner.add(Box.createVerticalStrut(8))

            caseCheck = JCheckBox("忽略大小写（默认已勾选）")
            inner.add(caseCheck)

            mainPanel!!.add(inner, BorderLayout.NORTH)

            // 尝试加载当前设置；防御性处理，避免在 Settings 打开时抛出异常导致界面卡住
            try {
                val settings = AutoDiscoverSettings.getInstance()
                if (settings != null) {
                    rulesArea!!.text = settings.rules.joinToString("\n")
                    caseCheck!!.isSelected = settings.caseInsensitive
                } else {
                    rulesArea!!.text = "get*\nset*\nis*\nhas*\nhandle*"
                    caseCheck!!.isSelected = true
                }
            } catch (ex: Throwable) {
                // 记录日志，并在界面显示错误提示，防止设置页面无限加载
                try {
                    com.aogg.core.search.helper.ProjectLogHelper.log(null, "AutoDiscoverConfigurable.createComponent exception: ${ex.message}")
                } catch (_: Throwable) {
                }
                rulesArea!!.text = "加载设置失败，请查看日志"
                caseCheck!!.isSelected = true
            }
        }
        return mainPanel
    }

    override fun isModified(): Boolean {
        val settings = AutoDiscoverSettings.getInstance()
        val text = rulesArea?.text ?: ""
        val list = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val ci = caseCheck?.isSelected ?: true
        return settings.rules != list || settings.caseInsensitive != ci
    }

    override fun apply() {
        val settings = AutoDiscoverSettings.getInstance()
        val text = rulesArea?.text ?: ""
        val list = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        settings.rules = list
        settings.caseInsensitive = caseCheck?.isSelected ?: true
        // 设置变化后，缓存由 Helper 在下一次请求时失效/刷新
    }

    override fun reset() {
        val settings = AutoDiscoverSettings.getInstance()
        rulesArea?.text = settings.rules.joinToString("\n")
        caseCheck?.isSelected = settings.caseInsensitive
    }

    override fun disposeUIResources() {
        mainPanel = null
        rulesArea = null
        caseCheck = null
    }
}


