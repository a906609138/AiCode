package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.presentation.ExpertListItem
import com.aicode.feature.settings.presentation.ExpertManagerUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Search
import compose.icons.feathericons.Star
import compose.icons.feathericons.User
import compose.icons.feathericons.XCircle

/**
 * 专家管理列表：搜索、分类标签、卡片（置顶/禁用/内置徽标）+ 更多操作。
 *
 * 与 AiMini 一致：分类标签横向排列，卡片展示图标、标题、徽标与描述（最多两行）。
 * 内置专家不提供删除项，只提供禁用——删了 App 升级会重新释放，行为与预期冲突。
 */
@Composable
internal fun ExpertManagerSection(
    state: ExpertManagerUiState,
    onQueryChange: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (ExpertListItem) -> Unit,
    onTogglePinned: (ExpertListItem) -> Unit,
    onToggleDisabled: (ExpertListItem) -> Unit,
    onDelete: (ExpertListItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("搜索专家") },
            leadingIcon = {
                Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(horizontal = Spacing.lg)
        ) {
            items(state.categories, key = { it.key ?: "all" }) { tab ->
                FilterChip(
                    selected = state.selectedCategory == tab.key,
                    onClick = { onSelectCategory(tab.key) },
                    label = { Text(tab.label) }
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            )
        }

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

            state.filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.items.isEmpty()) "暂无专家，点右上角 + 新建" else "没有匹配的专家",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            ) {
                items(state.filtered, key = { it.definition.name }) { item ->
                    ExpertCard(
                        item = item,
                        onClick = { onEdit(item) },
                        onTogglePinned = { onTogglePinned(item) },
                        onToggleDisabled = { onToggleDisabled(item) },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpertCard(
    item: ExpertListItem,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleDisabled: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.User,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.definition.isTeam) {
                    ExpertBadge(text = "专家团", tint = MaterialTheme.colorScheme.tertiary)
                }
                if (item.builtin) {
                    ExpertBadge(text = "内置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.pinned) {
                    Icon(
                        FeatherIcons.Star,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (item.disabled) {
                    Icon(
                        FeatherIcons.XCircle,
                        contentDescription = "已禁用",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            FeatherIcons.MoreVertical,
                            contentDescription = "更多",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (item.pinned) "取消置顶" else "置顶") },
                            onClick = {
                                menuOpen = false
                                onTogglePinned()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.disabled) "启用" else "禁用") },
                            onClick = {
                                menuOpen = false
                                onToggleDisabled()
                            }
                        )
                        // 内置专家只能禁用：删了下次启动会被 assets 重新释放，删了白删。
                        if (!item.builtin) {
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
            if (item.definition.description.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = item.definition.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpertBadge(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .padding(start = Spacing.xs)
            .background(
                color = tint.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
