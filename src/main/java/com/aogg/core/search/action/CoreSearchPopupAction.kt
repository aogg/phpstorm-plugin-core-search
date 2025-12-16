package com.aogg.core.search.action

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 核心搜索右键菜单动作组
 * 动态显示"搜索核心"菜单项，仅当类或其父类有 @core 注解时显示
 */
class CoreSearchPopupAction : ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()

        // 优先使用右键选中的 PSI 元素，再回退到光标位置
        val phpClass = resolvePhpClass(e) ?: run {
            ProjectLogHelper.log(e.project, "getChildren: resolvePhpClass null")
            return arrayOf(CoreSearchInfoAction("未找到 PHP 类"))
        }

        // 检查是否有 @core 注解
        if (!CoreAnnotationHelper.hasCoreAnnotation(phpClass)) {
            ProjectLogHelper.log(e.project, "getChildren: no core annotations for class=${phpClass.fqn}")
            return arrayOf(CoreSearchInfoAction("未找到 @core 注解"))
        }

        // 获取所有唯一的关键词
        val keywords = CoreAnnotationHelper.getAllUniqueKeywords(phpClass)
        if (keywords.isEmpty()) {
            ProjectLogHelper.log(e.project, "getChildren: keywords empty for class=${phpClass.fqn}")
            return arrayOf(CoreSearchInfoAction("未找到 @core 关键词"))
        }

        // 为每个关键词创建菜单项
        val actions = keywords.map { keyword ->
            CoreKeywordSearchAction(keyword, phpClass) as AnAction
        }.toTypedArray()
        ProjectLogHelper.log(e.project, "getChildren: class=${phpClass.fqn}, keywords=$keywords, actions=${actions.size}")
        return actions
    }

    /**
     * 根据右键上下文或光标位置解析 PHP 类
     */
    private fun resolvePhpClass(event: AnActionEvent): PhpClass? {
        val contextElement = event.getData(CommonDataKeys.PSI_ELEMENT)
        if (contextElement is PhpClass) {
            ProjectLogHelper.log(event.project, "resolvePhpClass: use contextElement direct=${contextElement.fqn}")
            return contextElement
        }
        val fromContext = contextElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java) }
        if (fromContext != null) {
            ProjectLogHelper.log(event.project, "resolvePhpClass: from context parent=${fromContext.fqn}")
            return fromContext
        }

        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset) ?: return null
        ProjectLogHelper.log(event.project, "resolvePhpClass: from caret offset=$offset element=${elementAtCaret.node?.elementType}")
        return PsiTreeUtil.getParentOfType(elementAtCaret, PhpClass::class.java)
    }

    override fun update(e: AnActionEvent) {
        val phpClass = resolvePhpClass(e)
        val visible = true

        e.presentation.isEnabledAndVisible = visible
        if (visible) {
            e.presentation.text = "搜索核心"
            e.presentation.description = "根据 @core 注解搜索方法调用位置"
            ProjectLogHelper.log(e.project, "update: show menu class=${phpClass?.fqn}")
        }
        if (!visible) {
            ProjectLogHelper.log(e.project, "update: hide menu, phpClass=${phpClass?.fqn}")
        }
    }

    override fun isPopup(): Boolean {
        return true
    }

    /**
     * 当无法生成具体关键词子项时的占位动作
     */
    private class CoreSearchInfoAction(text: String) : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) {
            // 仅作为占位，不执行任何操作
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
    }
}

