package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreInstaller
import com.aicode.feature.agent.domain.skillstore.SkillStoreResult
import com.aicode.feature.agent.domain.skillstore.SkillStoreDetail
import com.aicode.feature.agent.domain.skillstore.SkillStoreItem
import com.aicode.feature.agent.domain.skillstore.SkillStoreRepository
import com.aicode.feature.agent.domain.skillstore.SkillStoreSort
import com.aicode.feature.agent.domain.skillstore.SkillStoreSource
import com.aicode.feature.agent.domain.skillstore.SkillStoreSourceStatus
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
    val initial: Boolean = true,
    /** 当前选中的源；null 表示「全部」（不按源过滤，聚合所有源）。 */
    val selectedSourceId: String? = null,
    val sort: SkillStoreSort = SkillStoreSort.RECOMMENDED,
    /** 每源本次结果状态，直接展示，避免空白页猜不出原因。 */
    val statuses: List<SkillStoreSourceStatus> = emptyList(),
    /** 顶部源标签（含「全部」由 UI 自行补）。 */
    val sources: List<SkillStoreSource> = emptyList()
) {
    /** 当前选中的源；选中「全部」时为 null。 */
    val selectedSource: SkillStoreSource?
        get() = sources.firstOrNull { it.id == selectedSourceId }

    /**
     * 搜索框是否可用：选中的源明确不支持搜索时置灰。
     *
     * 选「全部」时任一源支持即可搜（ClawHub 那一路会返回它自己的列表，由状态行说明）。
     */
    val searchEnabled: Boolean
        get() = selectedSource?.supportsSearch ?: sources.any { it.supportsSearch }

    /** 置灰时给用户的解释。 */
    val searchDisabledHint: String?
        get() = if (!searchEnabled) "该来源不支持搜索，仅可浏览列表" else null
}

/** 技能商店：多源搜索、看详情、安装。 */
@HiltViewModel
class SkillStoreViewModel @Inject constructor(
    private val repository: SkillStoreRepository,
    private val installer: SkillStoreInstaller
) : ViewModel() {

    private val _state = MutableStateFlow(SkillStoreUiState(sources = repository.sources))
    val state: StateFlow<SkillStoreUiState> = _state.asStateFlow()

    init {
        // 进页即拉一次推荐列表，避免首屏空白还要用户先打字。
        refresh()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun selectSource(sourceId: String?) {
        if (_state.value.selectedSourceId == sourceId) return
        _state.update { it.copy(selectedSourceId = sourceId) }
        refresh()
    }

    fun selectSort(sort: SkillStoreSort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        refresh()
    }

    /** 按当前关键词/源/排序重新拉取。 */
    fun refresh() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(loading = true, initial = false, message = null) }
            try {
                val result = repository.search(
                    keyword = current.query.trim().takeIf { it.isNotEmpty() },
                    sort = current.sort,
                    sourceIds = current.selectedSourceId?.let { setOf(it) }.orEmpty()
                )
                _state.update {
                    it.copy(
                        loading = false,
                        items = result.items,
                        statuses = result.statuses,
                        message = if (result.items.isEmpty()) emptyMessage(result.statuses) else null
                    )
                }
            } catch (e: Exception) {
                // 聚合层已逐源兜底；能走到这里说明是更外围的错误，仍要复位 loading，
                // 否则页面会永远停在转圈上。
                _state.update {
                    it.copy(loading = false, items = emptyList(), message = "加载失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 搜索按钮：空白查询回到未过滤状态。 */
    fun search() {
        refresh()
    }

    fun openDetail(item: SkillStoreItem) {
        viewModelScope.launch {
            _state.update { it.copy(detailLoading = true, message = null) }
            try {
                val result = repository.detail(item)
                if (result is SkillStoreResult.Failure) {
                    _state.update { it.copy(detailLoading = false, message = "加载详情失败：${result.message}") }
                } else {
                    _state.update { it.copy(detailLoading = false, detail = (result as SkillStoreResult.Success<com.aicode.feature.agent.domain.skillstore.SkillStoreDetail>).data) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(detailLoading = false, message = "加载详情失败：${e.message}") }
            }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** 安装当前详情对应的技能。 */
    fun install() {
        val detail = _state.value.detail ?: return
        val item = detail.item
        viewModelScope.launch {
            _state.update { it.copy(installing = true, message = null) }
            try {
                val downloadResult = repository.download(item)
                if (downloadResult is SkillStoreResult.Failure) {
                    _state.update { it.copy(installing = false, message = "安装失败：${downloadResult.message}") }
                    return@launch
                }
                val contents = (downloadResult as SkillStoreResult.Success<Map<String, String>>).data
                val hashes = detail.files.associate { it.path to it.sha256 }
                val installResult = installer.installFromContents(item.slug, contents, hashes)
                if (installResult is SkillStoreResult.Failure) {
                    _state.update { it.copy(installing = false, message = "安装失败：${installResult.message}") }
                } else {
                    val installed = (installResult as SkillStoreResult.Success<com.aicode.feature.agent.data.remote.skillstore.SkillInstallResult>).data
                    _state.update {
                        it.copy(
                            installing = false,
                            message = "已安装「${installed.skillName}」，共 ${installed.installedFiles} 个文件"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(installing = false, message = "安装失败：${e.message}") }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 空结果时给一句能解释原因的话：全部源都失败时点明是网络问题。 */
    private fun emptyMessage(statuses: List<SkillStoreSourceStatus>): String {
        if (statuses.isEmpty()) return "没有找到匹配的技能"
        val allFailed = statuses.all { it.error != null && it.error != "无结果" }
        if (allFailed) return "所有来源都请求失败，请检查网络后重试"
        return "没有找到匹配的技能"
    }
}
