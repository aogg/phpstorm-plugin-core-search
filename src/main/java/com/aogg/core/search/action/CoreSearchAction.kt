package com.aogg.core.search.action

import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 固定显示的核心搜索入口
 * 允许在编辑器右键菜单中始终显示，选择关键词后触发搜索
 */
class CoreSearchAction : AnAction("搜索核心"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val phpClass = resolvePhpClass(e.dataContext)
        if (phpClass == null) {
            ProjectLogHelper.log(project, "CoreSearchAction.actionPerformed: phpClass null")
            Messages.showInfoMessage(project, "未找到 PHP 类，请将光标放在类定义内。", "核心搜索")
            return
        }

        val keywords = CoreAnnotationHelper.getAllUniqueKeywords(phpClass)
        if (keywords.isEmpty()) {
            ProjectLogHelper.log(project, "CoreSearchAction.actionPerformed: keywords empty class=${phpClass.fqn}")
            Messages.showInfoMessage(project, "未找到 @core 关键词。", "核心搜索")
            return
        }

        if (keywords.size == 1) {
            val keyword = keywords.first()
            ProjectLogHelper.log(project, "CoreSearchAction.actionPerformed: single keyword=$keyword class=${phpClass.fqn}")
            CoreKeywordSearchAction(keyword, phpClass).actionPerformed(e)
            return
        }

        val keywordList = keywords.sorted()
        ProjectLogHelper.log(project, "CoreSearchAction.actionPerformed: show chooser keywords=$keywordList class=${phpClass.fqn}")
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(keywordList)
            .setTitle("选择核心关键词")
            .setItemChosenCallback { keyword ->
                ProjectLogHelper.log(project, "CoreSearchAction.actionPerformed: choose keyword=$keyword class=${phpClass.fqn}")
                CoreKeywordSearchAction(keyword, phpClass).actionPerformed(e)
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "搜索核心"
        e.presentation.description = "根据 @core 注解搜索方法调用位置"
    }

    /**
     * 根据上下文解析 PHP 类
     */
    private fun resolvePhpClass(dataContext: DataContext): PhpClass? {
        val contextElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
        if (contextElement is PhpClass) {
            return contextElement
        }
        val fromContext = contextElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java) }
        if (fromContext != null) {
            return fromContext
        }

        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(elementAtCaret, PhpClass::class.java)
    }
}


