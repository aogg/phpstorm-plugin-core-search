package com.aogg.core.search.action

import com.aogg.core.search.helper.AutoDiscoverHelper
import com.aogg.core.search.settings.AutoDiscoverSettings
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager

/**
 * 固定显示的自动发现入口（作为二级菜单）
 * 悬停时会后台加载匹配规则并缓存结果
 */
class AutoDiscoverActionGroup : ActionGroup("自动发现", "自动发现方法名规则", null), DumbAware {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()

        val phpClass = resolvePhpClass(e.dataContext) ?: return arrayOf(InfoAction("未找到 PHP 类"))

        // 尝试从缓存中获取匹配规则
        val matched = AutoDiscoverHelper.collectMatchingRulesForClass(phpClass)
        if (matched.isNotEmpty()) {
            val actions = matched.map { pattern ->
                AutoDiscoverPatternSearchAction(pattern, phpClass) as AnAction
            }.toTypedArray()
            return actions
        }

        // 缓存为空：返回占位并在后台加载（下一次打开菜单会展示结果）
        // 后台任务仅负责刷新缓存
        val project = e.project
        submitBackgroundCollect(phpClass, project)

        return arrayOf(InfoAction("正在加载自动发现..."))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "自动发现"
        e.presentation.description = "自动根据方法名规则自动发现方法"
        e.presentation.isPopupGroup = true
    }

    private fun submitBackgroundCollect(phpClass: PhpClass, project: Project?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "自动发现：收集规则", false) {
            override fun run(indicator: ProgressIndicator) {
                // 在 read action 中读取 PSI
                ApplicationManager.getApplication().runReadAction {
                    AutoDiscoverHelper.collectMatchingRulesForClass(phpClass)
                }
                // 尝试触发 UI 刷新：更新 CoreSearchAction 所在分组的 presentation
                try {
                    val mgr = ActionManager.getInstance()
                    val action = mgr.getAction("com.aogg.core.search.CoreSearchAction")
                    // 触发一次更新：调用 update on action via an empty event is non-trivial here,
                    // 但至少我们请求更新整个 action system，IDE 会在下次弹出菜单时使用最新缓存。
                    action?.templatePresentation
                } catch (ignored: Exception) {
                    // 不影响主要逻辑
                }
            }
        })
    }

    /**
     * 解析 PHP 类（复用 CoreSearchAction 的解析策略）
     */
    private fun resolvePhpClass(dataContext: DataContext): PhpClass? {
        val project = CommonDataKeys.PROJECT.getData(dataContext)

        var psiFile = CommonDataKeys.PSI_FILE.getData(dataContext)
        if (psiFile == null && project != null) {
            val editor = CommonDataKeys.EDITOR.getData(dataContext)
            if (editor != null) {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            }
        }
        if (psiFile == null && project != null) {
            val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
            if (virtualFile != null) {
                psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            }
        }
        if (psiFile == null) return null

        val fileName = psiFile.name
        if (!fileName.endsWith(".php", ignoreCase = true)) {
            return null
        }

        val classInFile = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)
        return classInFile
    }

    private class InfoAction(text: String) : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) {
            // 不可点击的占位
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
    }
}


