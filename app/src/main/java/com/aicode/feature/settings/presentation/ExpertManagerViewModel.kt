package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.expert.ExpertDefinition
import com.aicode.feature.agent.domain.expert.ExpertDefinitionRepository
import com.aicode.feature.agent.domain.expert.ExpertDefinitionScope
import com.aicode.feature.agent.domain.expert.ExpertType
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
    val message: String? = null,
    /** 编辑中的草稿；null 表示停留在列表页。 */
    val editor: ExpertEditorState? = null
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
 * 编辑页草稿。
 *
 * [originalName] 非空表示编辑现有定义（保存后删除旧名文件，支持改名）；
 * null 表示新建。内置专家编辑后写到用户目录，同名即覆盖内置，不改 assets。
 */
data class ExpertEditorState(
    val originalName: String? = null,
    val originalScope: ExpertDefinitionScope = ExpertDefinitionScope.GLOBAL,
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val category: String = "dev",
    val prompt: String = "",
    val scope: ExpertDefinitionScope = ExpertDefinitionScope.GLOBAL,
    val allowedTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val providerId: String = "",
    val model: String = "",
    val reasoningEffort: String = "",
    /** 可选工具全集（工具箱当前注册的）+ 已选项。 */
    val allTools: List<String> = emptyList(),
    val allSkills: List<String> = emptyList(),
    val providers: List<Pair<String, String>> = emptyList(),
    /** 当前 provider 下可选模型；providerId 为空时为空。 */
    val models: List<String> = emptyList(),
    val builtin: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null
) {
    val isNew: Boolean get() = originalName == null

    val title: String get() = if (isNew) "新建专家" else "编辑专家"

    /** 名称合法性：与文件名一一对应，只收 ASCII 字母数字下划线连字符。 */
    val nameValid: Boolean get() = name.trim().matches(Regex("^[a-zA-Z0-9_-]+$"))

    /** 能否保存：名称合法且正文非空。 */
    val canSave: Boolean get() = nameValid && prompt.trim().isNotEmpty() && !saving
}

/**
 * 专家管理：列表、分类筛选、置顶、禁用、删除、新建/编辑。
 *
 * 内置专家由 assets 释放，删了升级会拉回，故只允许禁用（[delete] 里也按名单拦截）。
 */
@HiltViewModel
class ExpertManagerViewModel @Inject constructor(
    private val repository: ExpertDefinitionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val toolRegistry: com.aicode.feature.agent.domain.tool.ToolRegistry,
    private val skillRepository: com.aicode.feature.agent.domain.skill.SkillRepository,
    private val providerRepository: com.aicode.feature.settings.domain.repository.AIProviderRepository
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

    // ── 编辑页 ──

    /** 打开新建页：默认分类 dev、全局作用域。 */
    fun openCreate() {
        viewModelScope.launch {
            val options = withContext(Dispatchers.IO) { loadOptions() }
            _state.update {
                it.copy(
                    editor = ExpertEditorState(
                        allTools = options.tools,
                        allSkills = options.skills,
                        providers = options.providers
                    )
                )
            }
        }
    }

    /** 打开编辑页：把现有定义回填到草稿。 */
    fun openEdit(item: ExpertListItem) {
        viewModelScope.launch {
            val definition = item.definition
            val options = withContext(Dispatchers.IO) { loadOptions() }
            val providerId = definition.providerId.orEmpty()
            val models = withContext(Dispatchers.IO) { modelsOf(providerId) }
            _state.update {
                it.copy(
                    editor = ExpertEditorState(
                        originalName = definition.name,
                        originalScope = definition.scope,
                        name = definition.name,
                        displayName = definition.displayName,
                        description = definition.description,
                        category = definition.category.ifBlank { "dev" },
                        prompt = definition.prompt,
                        scope = definition.scope,
                        allowedTools = definition.allowedTools,
                        skills = definition.skills,
                        providerId = providerId,
                        model = definition.model.orEmpty(),
                        reasoningEffort = definition.reasoningEffort.orEmpty(),
                        allTools = options.tools,
                        allSkills = options.skills,
                        providers = options.providers,
                        models = models,
                        builtin = item.builtin
                    )
                )
            }
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    /** 草稿字段更新：统一走 copy，避免为每个字段单写一个方法。 */
    fun updateEditor(transform: (ExpertEditorState) -> ExpertEditorState) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = transform(editor))
        }
    }

    /** 切 provider 时重算可选模型，并清掉不再适用的已选模型。 */
    fun setEditorProvider(providerId: String) {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { modelsOf(providerId) }
            updateEditor { editor ->
                editor.copy(
                    providerId = providerId,
                    models = models,
                    model = editor.model.takeIf { it in models }.orEmpty()
                )
            }
        }
    }

    fun toggleEditorTool(name: String) {
        updateEditor { editor ->
            editor.copy(
                allowedTools = if (name in editor.allowedTools) editor.allowedTools - name
                else editor.allowedTools + name
            )
        }
    }

    fun toggleEditorSkill(name: String) {
        updateEditor { editor ->
            editor.copy(
                skills = if (name in editor.skills) editor.skills - name else editor.skills + name
            )
        }
    }

    /** 保存草稿：新建写新文件；改名时先删旧文件，避免留两份。 */
    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (!editor.canSave) return
        viewModelScope.launch {
            updateEditor { it.copy(saving = true, error = null) }
            val projectRoot = workspaceRepository.currentPath()
            val definition = ExpertDefinition(
                name = editor.name.trim(),
                displayName = editor.displayName.trim(),
                description = editor.description.trim(),
                category = editor.category.trim(),
                type = ExpertType.SINGLE,
                allowedTools = editor.allowedTools,
                inject = ExpertDefinition.DEFAULT_INJECT,
                skills = editor.skills,
                providerId = editor.providerId.ifBlank { null },
                model = editor.model.ifBlank { null },
                reasoningEffort = editor.reasoningEffort.ifBlank { null },
                prompt = editor.prompt
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 改名/改作用域：先清旧文件，否则会同时存在两份定义
                    val oldName = editor.originalName
                    if (oldName != null && (oldName != definition.name || editor.originalScope != editor.scope)) {
                        repository.delete(
                            name = oldName,
                            scope = editor.originalScope,
                            projectRoot = projectRoot,
                            builtinNames = emptySet()
                        )
                    }
                    repository.save(definition, editor.scope, projectRoot).getOrThrow()
                }
            }
            if (result.isFailure) {
                updateEditor { it.copy(saving = false, error = result.exceptionOrNull()?.message ?: "保存失败") }
            } else {
                _state.update { it.copy(editor = null, message = "已保存「${definition.title}」") }
                refresh()
            }
        }
    }

    /** 读取可选工具、技能、provider 清单。 */
    private data class EditorOptions(
        val tools: List<String>,
        val skills: List<String>,
        val providers: List<Pair<String, String>>
    )

    private suspend fun loadOptions(): EditorOptions {
        val tools = runCatching { toolRegistry.getToolNames().sorted() }.getOrDefault(emptyList())
        val skills = runCatching { skillRepository.listSkills().map { it.name }.sorted() }
            .getOrDefault(emptyList())
        val providers = runCatching {
            providerRepository.getAllProviders().first().map { it.id to it.name }
        }.getOrDefault(emptyList())
        return EditorOptions(tools, skills, providers)
    }

    /** 某 provider 下的模型清单；providerId 为空时返回空。 */
    private suspend fun modelsOf(providerId: String): List<String> {
        if (providerId.isBlank()) return emptyList()
        return runCatching {
            providerRepository.getAllProviders().first()
                .firstOrNull { it.id == providerId }?.models.orEmpty()
        }.getOrDefault(emptyList())
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
