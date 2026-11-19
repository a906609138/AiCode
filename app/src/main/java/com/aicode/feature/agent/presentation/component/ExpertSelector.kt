package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.expert.ExpertDefinition
import compose.icons.FeatherIcons
import compose.icons.feathericons.User

/**
 * 输入栏的专家按钮：点开列出可用专家，选中后当前会话以该专家身份运行。
 *
 * 专家是**会话角色**（人设 + 方法论 + 工具链），作用于当前对话；与子代理（新开独立会话）不同。
 * 未召唤任何专家时按钮为普通图标；已召唤时高亮，便于随时看出当前身份。
 */
@Composable
internal fun ExpertIconButton(
    experts: List<ExpertDefinition>,
    currentExpertName: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant

    IconButton(
        onClick = { showSheet = true },
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            imageVector = FeatherIcons.User,
            contentDescription = stringResource(R.string.expert_button_desc),
            tint = if (currentExpertName != null) accent else idle,
            modifier = Modifier.size(20.dp)
        )
    }

    if (showSheet) {
        ExpertPickerSheet(
            experts = experts,
            currentExpertName = currentExpertName,
            onSelect = {
                onSelect(it)
                showSheet = false
            },
            onDismiss = { showSheet = false }
        )
    }
}

/** 专家选择弹层：首项恒为「不使用专家」，避免用户选中后无法退回默认身份。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpertPickerSheet(
    experts: List<ExpertDefinition>,
    currentExpertName: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
            Text(
                text = stringResource(R.string.expert_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            Text(
                text = stringResource(R.string.expert_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                item {
                    ExpertRow(
                        title = stringResource(R.string.expert_none),
                        description = stringResource(R.string.expert_none_desc),
                        selected = currentExpertName == null,
                        onClick = { onSelect(null) }
                    )
                }
                items(experts, key = { it.name }) { expert ->
                    ExpertRow(
                        title = expert.title,
                        description = expert.description,
                        selected = expert.name == currentExpertName,
                        onClick = { onSelect(expert.name) }
                    )
                }
            }
            if (experts.isEmpty()) {
                Text(
                    text = stringResource(R.string.expert_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            }
            // 会话已绑定的专家被禁用后会从列表消失，这里得给个交代：
            // 否则用户看到「不使用专家」未选中、又找不到当前专家，会以为界面出错。
            if (currentExpertName != null && experts.none { it.name == currentExpertName }) {
                Text(
                    text = stringResource(R.string.expert_disabled_hint, currentExpertName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

/** 单个专家行：标题 + 一句说明；选中项用主色标出。 */
@Composable
private fun ExpertRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(
                text = stringResource(if (selected) R.string.expert_in_use else R.string.expert_use)
            )
        }
    }
}
