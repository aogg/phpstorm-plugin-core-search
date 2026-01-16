package com.aogg.core.search.action

import com.aogg.core.search.helper.ProjectLogHelper
import com.aogg.core.search.service.ObjectMethodSearchService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * 固定搜索 - 对象方法调用：搜索当前类的对象方法调用（包含父类方法）
 */
class FixedSearchObjectMethodAction : AnAction("对象方法调用", "搜索对象方法调用", null) {

    private val searchService = ObjectMethodSearchService()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val phpClass = FixedSearchUtils.resolvePhpClass(e) ?: run {
            FixedSearchUtils.notifyError(project, "未找到 PHP 类")
            return
        }

        ProjectLogHelper.log(project, "固定搜索-对象方法调用: 开始搜索 class=${phpClass.fqn}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "固定搜索：对象方法调用", true) {
            override fun run(indicator: ProgressIndicator) {
                searchService.performObjectMethodSearch(project, indicator, phpClass)
            }
        })
    }
}
