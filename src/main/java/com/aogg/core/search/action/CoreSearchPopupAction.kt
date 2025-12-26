package com.aogg.core.search.action

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.openapi.actionSystem.ActionUpdateThread

/**
 * 核心搜索右键菜单动作组
 * 动态显示"搜索核心"菜单项，仅当类或其父类有 @core 注解时显示
 */
class CoreSearchPopupAction : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

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
        val project = event.project

        // 1) 直接选中类
        val contextElement = event.getData(CommonDataKeys.PSI_ELEMENT)
        if (contextElement is PhpClass) {
            ProjectLogHelper.log(project, "resolvePhpClass: use contextElement direct=${contextElement.fqn}")
            return contextElement
        }

        // 2) 选中元素向上找类
        val fromContext = contextElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java) }
        if (fromContext != null) {
            ProjectLogHelper.log(project, "resolvePhpClass: from context parent=${fromContext.fqn}")
            return fromContext
        }

        // 3) 文件内首个类（无 PSI_ELEMENT 时兜底）
        val psiFile = event.getData(CommonDataKeys.PSI_FILE)
        if (psiFile != null) {
            val classInFile = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)
            if (classInFile != null) {
                ProjectLogHelper.log(project, "resolvePhpClass: from file firstClass=${classInFile.fqn}")
                return classInFile
            } else {
                ProjectLogHelper.log(project, "resolvePhpClass: psiFile present but no PhpClass in file=${psiFile.name}")
            }
        } else {
            ProjectLogHelper.log(project, "resolvePhpClass: psiFile null in dataContext")
        }

        // 4) 光标所在位置向上找类
        val editor = event.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            ProjectLogHelper.log(project, "resolvePhpClass: editor null, cannot resolve from caret")
            return null
        }
        val psiFileFromEditor = psiFile
            ?: event.getData(CommonDataKeys.PSI_FILE)
            ?: project?.let { PsiDocumentManager.getInstance(it).getPsiFile(editor.document) }
        if (psiFileFromEditor == null) {
            ProjectLogHelper.log(project, "resolvePhpClass: psiFileFromEditor null (editor doc lookup failed), cannot resolve from caret")
            return null
        } else if (psiFile == null) {
            ProjectLogHelper.log(project, "resolvePhpClass: psiFile resolved from editor.document file=${psiFileFromEditor.name}")
        }
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFileFromEditor.findElementAt(offset)
        if (elementAtCaret == null) {
            ProjectLogHelper.log(project, "resolvePhpClass: elementAtCaret null offset=$offset file=${psiFileFromEditor.name}")
            return null
        }
        ProjectLogHelper.log(project, "resolvePhpClass: from caret offset=$offset element=${elementAtCaret.node?.elementType}")
        val classFromCaret = PsiTreeUtil.getParentOfType(elementAtCaret, PhpClass::class.java)
        if (classFromCaret == null) {
            ProjectLogHelper.log(project, "resolvePhpClass: parent PhpClass not found from caret")
        }
        return classFromCaret
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

