package com.aogg.core.search.action

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.aogg.core.search.helper.CoreAnnotationHelper

/**
 * 核心搜索右键菜单动作组
 * 动态显示"搜索核心"菜单项，仅当类或其父类有 @core 注解时显示
 */
class CoreSearchPopupAction : ActionGroup() {
    
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()
        
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return emptyArray()
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return emptyArray()
        
        // 获取当前光标位置的 PHP 类
        val phpClass = getPhpClassAtCursor(editor, psiFile) ?: return emptyArray()
        
        // 检查是否有 @core 注解
        if (!CoreAnnotationHelper.hasCoreAnnotation(phpClass)) {
            return emptyArray()
        }
        
        // 获取所有唯一的关键词
        val keywords = CoreAnnotationHelper.getAllUniqueKeywords(phpClass)
        
        if (keywords.isEmpty()) {
            return emptyArray()
        }
        
        // 为每个关键词创建菜单项
        return keywords.map { keyword ->
            CoreKeywordSearchAction(keyword, phpClass)
        }.toTypedArray()
    }
    
    /**
     * 获取光标位置的 PHP 类
     */
    private fun getPhpClassAtCursor(editor: Editor, psiFile: PsiElement): PhpClass? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        
        return PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        
        // 只有在有项目、编辑器和文件时才启用
        val isEnabled = project != null && editor != null && psiFile != null
        
        e.presentation.isEnabledAndVisible = isEnabled
        
        if (isEnabled) {
            val phpClass = getPhpClassAtCursor(editor!!, psiFile!!)
            if (phpClass != null && CoreAnnotationHelper.hasCoreAnnotation(phpClass)) {
                e.presentation.text = "搜索核心"
                e.presentation.description = "根据 @core 注解搜索方法调用位置"
            } else {
                e.presentation.isEnabledAndVisible = false
            }
        }
    }
    
    override fun isPopup(): Boolean {
        return true
    }
}

