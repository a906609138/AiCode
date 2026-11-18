package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.expert.ExpertDefinition
import com.aicode.feature.agent.domain.expert.ExpertDefinitionRepository
import com.aicode.feature.agent.domain.expert.ExpertDefinitionScope
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 专家管理页的分类标签：`null` 表示「全部」。 */
data class ExpertCategoryTab(val key: String?, val label: String)

/** 列表项：定义 + 状态（置顶/禁用/内置）。 */
data class ExpertListItem(
    val definition: ExpertDefinition,
    val pinned: Boolean,
    val disabled: Boolean,
    val builtin: Boolean
) {
    /** 展示名：与定义一致。 */
    val title: String get() = definition.title
}

data class ExpertManagerUiState(
    val items: List<ExpertListItem> = emptyList(),
    val categories: List<ExpertCategoryTab> = emptyList(),
    val selectedCategory: String? = null,
    val query: String = "",
    val loading: Boolean = true,
    val hasWorkspace: Boolean = false,
    /** 一次性提示（保存/删除/失败）。 */
    val message: String? = null
) {
    /** 按分类与关键词过滤后的列表；置顶项排在前面。 */
    val filtered: List<ExpertListItem>
        get() {
            val q = query.trim()
            return items
                .filter { selectedCategory == null || it.definition.category == selectedCategory }
                .filter {
                    q.isEmpty() ||
                        it.definition.title.contains(q, ignoreCase = true) ||
                        it.definition.name.contains(q, ignoreCase = true) ||
                        it.definition.description.contains(q, ignoreCase = true)
                }
                .sortedWith(compareByDescending<ExpertListItem> { it.pinned }.thenBy { it.title.lowercase() })
        }

    val builtinCount: Int get() = items.count { it.builtin }
}

/**
 * 专家管理：列表、分类筛选、置顶、禁用、删除。
 *
 * 内置专家由 assets 释放，删了升级会拉回，故只允许禁用（[delete] 里也按名单拦截）。
 */
@HiltViewModel
class ExpertManagerViewModel @Inject constructor(
    private val repository: ExpertDefinitionRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExpertManagerUiState())
    val state: StateFlow<ExpertManagerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun selectCategory(key: String?) {
        _state.update { it.copy(selectedCategory = key) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val snapshot = withContext(Dispatchers.IO) {
                val projectRoot = workspaceRepository.currentPath()
                val definitions = repository.listAll(projectRoot)
                val pinned = repository.pinned(projectRoot)
                val disabled = repository.disabled(projectRoot)
                val builtin = repository.builtinNames()
                Triple(projectRoot, definitions, Triple(pinned, disabled, builtin))
            }
            val (projectRoot, definitions, marks) = snapshot
            val (pinned, disabled, builtin) = marks
            val items = definitions.map { definition ->
                ExpertListItem(
                    definition = definition,
                    pinned = definition.name.lowercase() in pinned,
                    disabled = definition.name.lowercase() in disabled,
                    builtin = definition.name.lowercase() in builtin
                )
            }
            _state.update {
                it.copy(
                    items = items,
                    categories = buildCategories(items),
                    loading = false,
                    hasWorkspace = projectRoot.isNotBlank()
                )
            }
        }
    }

    fun togglePinned(item: ExpertListItem) {
        val name = item.definition.name
        val target = !item.pinned
        mutate("置顶") {
            repository.setPinned(name, target, scopeOf(item), workspaceRepository.currentPath())
        }
    }

    fun toggleDisabled(item: ExpertListItem) {
        val name = item.definition.name
        val target = !item.disabled
        mutate(if (target) "禁用" else "启用") {
            repository.setDisabled(name, target, scopeOf(item), workspaceRepository.currentPath())
        }
    }

    /** 删除；内置专家会被仓库拒绝并回传原因。 */
    fun delete(item: ExpertListItem) {
        val name = item.definition.name
        mutate("删除") {
            repository.delete(
                name = name,
                scope = scopeOf(item),
                projectRoot = workspaceRepository.currentPath(),
                builtinNames = repository.builtinNames()
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 项目级定义写项目目录，其余写全局。 */
    private fun scopeOf(item: ExpertListItem): ExpertDefinitionScope =
        if (item.definition.scope == ExpertDefinitionScope.PROJECT && _state.value.hasWorkspace) {
            ExpertDefinitionScope.PROJECT
        } else {
            ExpertDefinitionScope.GLOBAL
        }

    private fun mutate(action: String, block: () -> Result<Unit>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            if (result.isFailure) {
                _state.update { it.copy(message = "$action 失败：${result.exceptionOrNull()?.message ?: "未知错误"}") }
            } else {
                refresh()
            }
        }
    }

    /** 分类标签：固定顺序在前，其余按出现顺序补在后面。 */
    private fun buildCategories(items: List<ExpertListItem>): List<ExpertCategoryTab> {
        val order = listOf(
            "writing" to "写作与内容",
            "data" to "数据与分析",
            "dev" to "开发",
            "design" to "设计",
            "business" to "商业",
            "office" to "办公",
            "life" to "生活",
            "emotion" to "情感",
            "other" to "其他"
        )
        val present = items.mapNotNull { it.definition.category.takeIf { c -> c.isNotBlank() } }.toSet()
        val known = order.filter { it.first in present }.map { ExpertCategoryTab(it.first, it.second) }
        val unknown = present.filter { key -> order.none { it.first == key } }
            .sorted()
            .map { ExpertCategoryTab(it, it) }
        return listOf(ExpertCategoryTab(null, "全部")) + known + unknown
    }
}
