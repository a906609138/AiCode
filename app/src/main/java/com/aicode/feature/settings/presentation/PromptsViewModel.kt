package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.BreakjailPromptProvider
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.settings.data.repository.BreakjailSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提示词页状态：按编号列出最终生效的片段（含来源），以及内置开关。 */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 破甲（置顶提示词）开关：正文取自 `~/.aicode/pj.md`，不参与片段编号体系。 */
    val breakjailEnabled: Boolean = false,
    /** 破甲就绪情况（未开启 / 文件不存在 / 已开启 N 字节），直接展示给用户排查。 */
    val breakjailStatus: String = "",
    /** 没有选中工作区时项目层不可写，编辑/新建回落到全局层。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * 提示词页状态：四级来源（项目 > 全局 > 本地 > 内置）的生效片段列表 + 内置开关。
 *
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository,
    private val breakjailSettingsRepository: BreakjailSettingsRepository,
    private val breakjailPromptProvider: BreakjailPromptProvider
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    breakjailEnabled = breakjailSettingsRepository.enabledFlow.first(),
                    breakjailStatus = breakjailPromptProvider.describe(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** 保存某编号的覆盖（新建与编辑同一入口）：写到所选作用域层；编辑改编号时清掉旧编号。 */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            refresh()
        }
    }

    /** 删除某编号的覆盖，自动回退到下一层。 */
    fun deleteFragment(number: Int) = deleteFragments(listOf(number))

    /**
     * 批量删除（列表多选批量操作）：一次 IO 里删完再刷新一次列表。
     * 逐条调 [deleteFragment] 会各自触发一轮全量重扫，编号多时列表会来回跳。
     */
    fun deleteFragments(numbers: List<Int>) {
        if (numbers.isEmpty()) return
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                numbers.forEach { catalog.deleteOverride(it, projectRoot) }
            }
            refresh()
        }
    }

    /** 重置：清空项目层与全局层的所有自定义片段，各编号回落到内置提示词。 */
    fun resetCustomFragments() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { catalog.deleteAllCustom(projectRoot) }
            refresh()
        }
    }

    /** 拖拽重排：按新顺序重新编号并落盘，改变注入顺序。乐观更新本地状态，避免重读导致列表跳动。 */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val numbers = reordered.map { it.number }.sorted()
        val renumbered = reordered.mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }

    /** 破甲开关：仅落盘；AIEditorApp 侧 collect 该 Flow 更新 SystemPromptProvider 读的缓存。 */
    fun setBreakjailEnabled(enabled: Boolean) {
        viewModelScope.launch {
            breakjailSettingsRepository.setEnabled(enabled)
            // 缓存同步写一次，避免依赖 app 层 collector 的调度时机：设置页里右一关，
            // 下一轮请求就该不再注入。
            breakjailPromptProvider.setEnabled(enabled)
            _state.update {
                it.copy(breakjailEnabled = enabled, breakjailStatus = breakjailPromptProvider.describe())
            }
        }
    }
}
