package com.aogg.core.search.helper

import com.aogg.core.search.model.UsageWithTarget
import com.aogg.core.search.helper.AutoDiscoverUiHelper
import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageViewPresentation
import com.intellij.usages.UsageViewManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 固定搜索的通用工具方法
 */
object FixedSearchHelper {
    fun resolvePhpClass(e: AnActionEvent): PhpClass? {
        val project = e.project ?: return null

        // 检查是否处于 dumb mode（索引重建期间）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            return null
        }

        try {

        // 1) 直接选中类
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement is PhpClass) {
            ProjectLogHelper.log(project, "FixedSearchHelper.resolvePhpClass: use contextElement direct=${psiElement.fqn}")
            return psiElement
        }

        // 2) 选中元素向上找类
        val fromContext = psiElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java) }
        if (fromContext != null) {
            ProjectLogHelper.log(project, "FixedSearchHelper.resolvePhpClass: from context parent=${fromContext.fqn}")
            return fromContext
        }

        // 3) 文件内首个类（兜底）
        var psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile == null) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (editor != null) {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            }
        }
        if (psiFile == null) {
            val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (virtualFile != null) {
                psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            }
        }

        if (psiFile != null && psiFile.name.endsWith(".php", ignoreCase = true)) {
            val classInFile = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)
            if (classInFile != null) {
                ProjectLogHelper.log(project, "FixedSearchHelper.resolvePhpClass: from file firstClass=${classInFile.fqn}")
                return classInFile
            } else {
                ProjectLogHelper.log(project, "FixedSearchHelper.resolvePhpClass: psiFile present but no PhpClass in file=${psiFile.name}")
            }
        }

        return null
        } catch (ex: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未准备好，返回null，让调用方处理
            ProjectLogHelper.log(project, "FixedSearchHelper.resolvePhpClass: 索引未准备好，跳过解析")
            return null
        }
    }

    fun notifyInfo(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "搜索工具",
                content,
                NotificationType.INFORMATION
            ),
            project
        )
    }

    fun notifyError(project: Project, content: String) {
        Notifications.Bus.notify(
            Notification(
                "core-search",
                "搜索工具",
                content,
                NotificationType.ERROR
            ),
            project
        )
    }

    fun showUsagesWithFallback(project: Project, usages: List<UsageWithTarget>, title: String) {
        if (usages.isNotEmpty()) {
            try {
                AutoDiscoverUiHelper.showAutoDiscoverToolWindow(project, usages, title)
            } catch (ex: Throwable) {
                ProjectLogHelper.log(project, "$title: 工具窗口显示失败，回退到弹窗 ex=${ex.message}")
                try {
                    val fallbackUsages = usages.map { UsageInfo2UsageAdapter(it.usageInfo) as com.intellij.usages.Usage }
                    AutoDiscoverUiHelper.showCustomUsagesPopup(project, fallbackUsages, title)
                } catch (exPopup: Throwable) {
                    ProjectLogHelper.log(project, "$title: 弹窗显示失败，回退到标准用法视图 ex=${exPopup.message}")
                    val usageTargets = emptyArray<com.intellij.usages.UsageTarget>()
                    val presentation = UsageViewPresentation()
                    presentation.tabName = title
                    presentation.tabText = title
                    presentation.scopeText = "项目范围"
                    val usageInfosForView = usages.map { it.usageInfo }
                    UsageViewManager.getInstance(project).showUsages(
                        usageTargets,
                        usageInfosForView.map { UsageInfo2UsageAdapter(it) }.toTypedArray(),
                        presentation
                    )
                }
            }
        } else {
            notifyInfo(project, "未找到相关调用")
        }
    }
}
