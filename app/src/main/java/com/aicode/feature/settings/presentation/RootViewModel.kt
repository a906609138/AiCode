package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.root.RootManager
import com.aicode.feature.agent.domain.root.RootState
import com.aicode.feature.settings.data.repository.RootToolSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页「Root 工具」一行的状态。 */
data class RootToolUiState(
    /** 设备是否可用 root（决定整行是否展示）。 */
    val available: Boolean = false,
    /** 用户是否已开启开关。 */
    val enabled: Boolean = false
)

/** Root 执行后端的设置页状态与操作入口。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val rootManager: RootManager,
    private val rootToolSettings: RootToolSettingsRepository
) : ViewModel() {

    val state: StateFlow<RootToolUiState> =
        combine(rootManager.state, rootToolSettings.enabledFlow) { rootState, enabled ->
            RootToolUiState(available = rootState == RootState.READY, enabled = enabled)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RootToolUiState()
        )

    init {
        refresh()
    }

    /** 重新探测 root 状态（如首次进入设置页、从授权管理器返回后）。 */
    fun refresh() {
        viewModelScope.launch { rootManager.refreshState() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { rootToolSettings.setEnabled(enabled) }
    }
}
