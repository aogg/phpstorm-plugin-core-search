package com.aogg.core.search.action

import com.aogg.core.search.helper.ProjectLogHelper
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAware

/**
 * 固定搜索二级菜单，包含固定的4个搜索类型子项
 */
class FixedSearchActionGroup : ActionGroup("固定搜索", "固定类型的搜索", null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()

        return arrayOf(
            FixedSearchConstructorAction(),
            FixedSearchStaticAction(),
            FixedSearchConstantAction(),
            FixedSearchPropertyAction()
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "固定搜索"
        e.presentation.description = "固定类型的搜索"
        e.presentation.isPopupGroup = true
    }
}
