package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreDetail
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreInstaller
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreItem
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreResult
import com.aicode.feature.agent.data.remote.skillstore.SkillHubApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 商店页面状态。 */
data class SkillStoreUiState(
    val query: String = "",
    val loading: Boolean = false,
    val items: List<SkillStoreItem> = emptyList(),
    /** 当前打开的详情；null 表示停留在列表。 */
    val detail: SkillStoreDetail? = null,
    val detailLoading: Boolean = false,
    val installing: Boolean = false,
    /** 一次性提示（安装成功/失败），展示后清空。 */
    val message: String? = null,
    /** 首次进入未搜索过的引导态。 */
    val initial: Boolean = true
)

/** 技能商店：搜索、看详情、安装。 */
@HiltViewModel
class SkillStoreViewModel @Inject constructor(
    private val api: SkillHubApi,
    private val installer: SkillStoreInstaller
) : ViewModel() {

    private val _state = MutableStateFlow(SkillStoreUiState())
    val state: StateFlow<SkillStoreUiState> = _state.asStateFlow()

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** 执行搜索；空白查询直接回到引导态，不打网络。 */
    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(items = emptyList(), initial = true, message = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, initial = false, message = null) }
            when (val result = api.search(query)) {
                is SkillStoreResult.Success -> _state.update {
                    it.copy(loading = false, items = result.data, message = if (result.data.isEmpty()) "没有找到匹配的技能" else null)
                }
                is SkillStoreResult.Failure -> _state.update {
                    it.copy(loading = false, items = emptyList(), message = "搜索失败：${result.message}")
                }
            }
        }
    }

    fun openDetail(item: SkillStoreItem) {
        val namespace = item.id.substringBeforeLast('/', missingDelimiterValue = "")
            .ifBlank { "@${item.publisher}" }
        viewModelScope.launch {
            _state.update { it.copy(detailLoading = true, message = null) }
            when (val result = api.detail(item.slug, namespace)) {
                is SkillStoreResult.Success -> _state.update { it.copy(detailLoading = false, detail = result.data) }
                is SkillStoreResult.Failure -> _state.update {
                    it.copy(detailLoading = false, message = "加载详情失败：${result.message}")
                }
            }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** 安装当前详情对应的技能。 */
    fun install() {
        val detail = _state.value.detail ?: return
        val namespace = detail.item.id.substringBeforeLast('/', missingDelimiterValue = "")
            .ifBlank { "@${detail.item.publisher}" }
        viewModelScope.launch {
            _state.update { it.copy(installing = true, message = null) }
            when (val result = installer.install(detail.item.slug, namespace)) {
                is SkillStoreResult.Success -> _state.update {
                    it.copy(installing = false, message = "已安装「${result.data.skillName}」，共 ${result.data.installedFiles} 个文件")
                }
                is SkillStoreResult.Failure -> _state.update {
                    it.copy(installing = false, message = "安装失败：${result.message}")
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}