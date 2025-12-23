package com.aogg.core.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project

/**
 * 自动发现设置持久化
 * - rules: 每行一个通配符规则，例如 get*, get*attr
 * - caseInsensitive: 是否忽略大小写（默认 true）
 */
@State(name = "AutoDiscoverSettings", storages = [Storage("core-search-auto-discover.xml")])
class AutoDiscoverSettings : PersistentStateComponent<AutoDiscoverSettings.State> {

    data class State(
        var rules: MutableList<String> = mutableListOf("get*", "set*", "is*", "has*", "handle*"),
        var caseInsensitive: Boolean = true
    )

    private var myState: State = State()

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    var rules: MutableList<String>
        get() = myState.rules
        set(value) {
            myState.rules = value
        }

    var caseInsensitive: Boolean
        get() = myState.caseInsensitive
        set(value) {
            myState.caseInsensitive = value
        }

    companion object {
        fun getInstance(project: Project? = null): AutoDiscoverSettings {
            // 尝试通过 ServiceManager 获取已注册的组件，若不存在则返回临时实例以避免配置界面崩溃
            return ServiceManager.getService(AutoDiscoverSettings::class.java) ?: AutoDiscoverSettings()
        }
    }
}


