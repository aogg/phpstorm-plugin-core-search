package com.aogg.core.search.action

import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 固定显示的核心搜索入口
 * 作为二级菜单，展示公开方法的 @core 关键词
 */
class CoreSearchAction : ActionGroup("搜索核心", "根据 @core 注解搜索方法调用位置", null), DumbAware {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()

        val phpClass = resolvePhpClass(e.dataContext) ?: run {
            ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: phpClass null")
            return arrayOf(CoreSearchInfoAction("未找到 PHP 类"))
        }

        if (!CoreAnnotationHelper.hasCoreAnnotation(phpClass)) {
            ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: no core annotations for class=${phpClass.fqn}")
            return arrayOf(CoreSearchInfoAction("未找到 @core 注解"))
        }

        val keywords = CoreAnnotationHelper.getAllUniqueKeywords(phpClass).sorted()
        if (keywords.isEmpty()) {
            ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: keywords empty for class=${phpClass.fqn}")
            return arrayOf(CoreSearchInfoAction("未找到 @core 关键词"))
        }

        val actions = keywords.map { keyword ->
            CoreKeywordSearchAction(keyword, phpClass) as AnAction
        }.toTypedArray()
        ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: class=${phpClass.fqn}, keywords=$keywords, actions=${actions.size}")
        return actions
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "搜索核心"
        e.presentation.description = "根据 @core 注解搜索方法调用位置"
    }

    override fun isPopup(): Boolean = true

    /**
     * 根据上下文解析 PHP 类
     */
    private fun resolvePhpClass(dataContext: DataContext): PhpClass? {
        val contextElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
        if (contextElement is PhpClass) {
            ProjectLogHelper.log(null, "CoreSearchAction.resolvePhpClass: use contextElement direct=${contextElement.fqn}")
            return contextElement
        }
        val fromContext = contextElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java) }
        if (fromContext != null) {
            ProjectLogHelper.log(null, "CoreSearchAction.resolvePhpClass: from context parent=${fromContext.fqn}")
            return fromContext
        }

        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset) ?: return null
        ProjectLogHelper.log(null, "CoreSearchAction.resolvePhpClass: from caret offset=$offset element=${elementAtCaret.node?.elementType}")
        return PsiTreeUtil.getParentOfType(elementAtCaret, PhpClass::class.java)
    }

    /**
     * 当无法生成具体关键词子项时的占位动作
     */
    private class CoreSearchInfoAction(text: String) : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) {
            // 占位动作，不执行任何操作
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
    }
}


