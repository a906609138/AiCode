package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.expert.ExpertDefinitionScope
import com.aicode.feature.settings.presentation.ExpertEditorState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown

/** 编辑页分类选项：与列表页标签保持同一套 key。 */
private val EDITOR_CATEGORIES = listOf(
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

private val EFFORT_OPTIONS = listOf(
    "" to "继承父会话",
    "low" to "低",
    "medium" to "中",
    "high" to "高"
)

/**
 * 专家编辑页：基本信息 / 分类 / 作用域 / 模型 / 工具集 / 技能 / 正文。
 *
 * 内置专家可编辑：保存写到用户目录、同名覆盖内置，不改 assets。
 * 顶部提供保存与取消，正文用多行输入。
 */
@Composable
internal fun ExpertEditorSection(
    editor: ExpertEditorState,
    onBack: () -> Unit,
    onUpdate: ((ExpertEditorState) -> ExpertEditorState) -> Unit,
    onProviderChange: (String) -> Unit,
    onToggleTool: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        ) {
            TextButton(onClick = onBack) { Text("取消") }
            Spacer(Modifier.size(Spacing.sm))
            Text(
                text = editor.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSave, enabled = editor.canSave) {
                Text(if (editor.saving) "保存中…" else "保存")
            }
        }

        editor.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            SectionLabel("基本信息")
            OutlinedTextField(
                value = editor.name,
                onValueChange = { value -> onUpdate { it.copy(name = value) } },
                singleLine = true,
                isError = editor.name.isNotBlank() && !editor.nameValid,
                label = { Text("名称（文件名，仅字母数字_-）") },
                supportingText = if (editor.name.isNotBlank() && !editor.nameValid) {
                    { Text("只能包含字母、数字、下划线与连字符") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = editor.displayName,
                onValueChange = { value -> onUpdate { it.copy(displayName = value) } },
                singleLine = true,
                label = { Text("展示名") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = editor.description,
                onValueChange = { value -> onUpdate { it.copy(description = value) } },
                label = { Text("用途描述") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("作用域")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(
                    selected = editor.scope == ExpertDefinitionScope.GLOBAL,
                    onClick = { onUpdate { it.copy(scope = ExpertDefinitionScope.GLOBAL) } },
                    label = { Text("全局") }
                )
                FilterChip(
                    selected = editor.scope == ExpertDefinitionScope.PROJECT,
                    onClick = { onUpdate { it.copy(scope = ExpertDefinitionScope.PROJECT) } },
                    label = { Text("当前项目") }
                )
            }

            SectionLabel("分类")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(EDITOR_CATEGORIES, key = { it.first }) { (key, label) ->
                    FilterChip(
                        selected = editor.category == key,
                        onClick = { onUpdate { it.copy(category = key) } },
                        label = { Text(label) }
                    )
                }
            }

            SectionLabel("模型")
            PickerRow(
                label = "模型提供商",
                value = editor.providers.firstOrNull { it.first == editor.providerId }?.second
                    ?: "继承父会话",
                options = listOf("" to "继承父会话") + editor.providers,
                onSelect = onProviderChange
            )
            PickerRow(
                label = "模型",
                value = editor.model.ifBlank { "继承父会话" },
                options = listOf("" to "继承父会话") + editor.models.map { it to it },
                onSelect = { value -> onUpdate { it.copy(model = value) } }
            )
            PickerRow(
                label = "思考强度",
                value = EFFORT_OPTIONS.firstOrNull { it.first == editor.reasoningEffort }?.second ?: "继承父会话",
                options = EFFORT_OPTIONS,
                onSelect = { value -> onUpdate { it.copy(reasoningEffort = value) } }
            )

            SectionLabel("工具集")
            Text(
                text = if (editor.allowedTools.isEmpty()) "未选择则开放全部工具" else "已选 ${editor.allowedTools.size} 个工具",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Column(modifier = Modifier.heightIn(max = 220.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(editor.allTools, key = { it }) { tool ->
                        FilterChip(
                            selected = tool in editor.allowedTools,
                            onClick = { onToggleTool(tool) },
                            label = { Text(tool) }
                        )
                    }
                }
            }

            SectionLabel("技能")
            Text(
                text = if (editor.skills.isEmpty()) "未选择则召唤时不自动加载技能" else "已选 ${editor.skills.size} 个技能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(editor.allSkills, key = { it }) { skill ->
                    FilterChip(
                        selected = skill in editor.skills,
                        onClick = { onToggleSkill(skill) },
                        label = { Text(skill) }
                    )
                }
            }

            SectionLabel("提示词正文")
            OutlinedTextField(
                value = editor.prompt,
                onValueChange = { value -> onUpdate { it.copy(prompt = value) } },
                label = { Text("专家人设与方法论") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
    )
}

/** 一行「标签 + 当前值」的选项行，点击弹出下拉。 */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { expanded = true }) {
            Icon(FeatherIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    trailingIcon = {
                        if (key == options.firstOrNull { it.second == value }?.first) {
                            Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    }
                )
            }
        }
    }
}
