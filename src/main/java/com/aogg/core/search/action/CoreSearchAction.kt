package com.aogg.core.search.action

import com.aogg.core.search.helper.CoreAnnotationHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.intellij.openapi.actionSystem.Separator
import com.aogg.core.search.action.AutoDiscoverActionGroup
import com.aogg.core.search.action.FixedSearchActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread

/**
 * 固定显示的核心搜索入口
 * 作为二级菜单，展示公开方法的 @core 关键词
 */
class CoreSearchAction : ActionGroup("搜索核心", "根据 @core 注解搜索方法调用位置", null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()

        val phpClass = resolvePhpClass(e.dataContext) ?: run {
            ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: phpClass null")
            // 没有解析到类时仍然显示占位信息并追加固定的自动发现入口和固定搜索
            return arrayOf(CoreSearchInfoAction("未找到 PHP 类"), Separator.getInstance(), AutoDiscoverActionGroup(), FixedSearchActionGroup())
        }

        val list = mutableListOf<AnAction>()

        // 检查是否有@core注解和关键词
        if (CoreAnnotationHelper.hasCoreAnnotation(phpClass)) {
            val keywords = CoreAnnotationHelper.getAllUniqueKeywords(phpClass).sorted()
            if (keywords.isNotEmpty()) {
                // 有关键词时，添加关键词搜索项
                for (keyword in keywords) {
                    list.add(CoreKeywordSearchAction(keyword, phpClass))
                }
                ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: class=${phpClass.fqn}, keywords=$keywords")
            } else {
                // 有注解但无关键词时显示占位信息
                list.add(CoreSearchInfoAction("未找到 @core 关键词"))
                ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: keywords empty for class=${phpClass.fqn}")
            }
        } else {
            // 无@core注解时显示占位信息
            list.add(CoreSearchInfoAction("未找到 @core 注解"))
            ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: no core annotations for class=${phpClass.fqn}")
        }

        // 分隔线后追加固定的自动发现和固定搜索二级菜单
        list.add(Separator.getInstance())
        list.add(AutoDiscoverActionGroup())
        list.add(FixedSearchActionGroup())
        ProjectLogHelper.log(e.project, "CoreSearchAction.getChildren: final actions count=${list.size}")
        return list.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "搜索核心"
        e.presentation.description = "根据 @core 注解搜索方法调用位置"
        e.presentation.isPopupGroup = true
    }

    /**
     * 解析 PHP 类：只要是 PHP 文件且包含类，就返回文件中的首个 PhpClass
     */
    private fun resolvePhpClass(dataContext: DataContext): PhpClass? {
        val project = CommonDataKeys.PROJECT.getData(dataContext)

        var psiFile = CommonDataKeys.PSI_FILE.getData(dataContext)
        if (psiFile == null && project != null) {
            // 兜底：通过编辑器文档获取 PSI 文件
            val editor = CommonDataKeys.EDITOR.getData(dataContext)
            if (editor != null) {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (psiFile != null) {
                    ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: psiFile from editor.document file=${psiFile.name}")
                }
            }
        }
        if (psiFile == null && project != null) {
            // 兜底：通过虚拟文件获取 PSI 文件（例如项目视图右键）
            val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
            if (virtualFile != null) {
                psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile != null) {
                    ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: psiFile from virtualFile file=${psiFile.name}")
                }
            }
        }
        if (psiFile == null) {
            ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: psiFile null after fallbacks")
            return null
        }

        // 仅根据文件后缀判断是否 PHP 文件
        val fileName = psiFile.name
        if (!fileName.endsWith(".php", ignoreCase = true)) {
            ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: not php file name=$fileName")
            return null
        }

        val classInFile = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)
        if (classInFile != null) {
            ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: from file firstClass=${classInFile.fqn}")
            return classInFile
        }

        ProjectLogHelper.log(project, "CoreSearchAction.resolvePhpClass: psiFile present but no PhpClass in file=${psiFile.name}")
        return null
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


