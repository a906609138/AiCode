package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreItem
import com.aicode.feature.settings.presentation.SkillStoreViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Search
import compose.icons.feathericons.Shield

/**
 * 技能商店弹层：搜索 → 列表 → 详情 → 安装。
 *
 * 只接 SkillHub（腾讯系）：其搜索/详情/文件接口匿名可用；列表接口需鉴权，故浏览走搜索。
 * 安装前展示来源与安全审核结论，第三方技能会在容器内执行脚本，用户需知情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillStoreSheet(
    onDismiss: () -> Unit,
    viewModel: SkillStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 一次性提示展示后自动清空，避免重组时反复弹出
    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(3_000)
            viewModel.clearMessage()
        }
    }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.skill_store_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
            Text(
                text = stringResource(R.string.skill_store_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.md)
            )

            SearchRow(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search
            )

            Spacer(Modifier.height(Spacing.md))

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
            }

            when {
                state.loading -> LoadingBox()
                state.detail != null -> SkillDetailPane(
                    detail = state.detail!!,
                    installing = state.installing,
                    onInstall = viewModel::install,
                    onBack = viewModel::closeDetail
                )
                state.items.isEmpty() -> EmptyBox(initial = state.initial)
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        SkillRow(item = item, onClick = { viewModel.openDetail(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.skill_store_search_hint)) },
            leadingIcon = {
                Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(Spacing.sm))
        TextButton(onClick = onSearch) {
            Text(stringResource(R.string.common_search))
        }
    }
}

/** 单条搜索结果：名称 + 发布方 + 描述。 */
@Composable
private fun SkillRow(item: SkillStoreItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.version.isNotBlank()) {
                Text(
                    text = "v${item.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.publisher.isNotBlank()) {
            Text(
                text = buildString {
                    append(item.publisher)
                    if (item.publisherVerified) append(" · 已认证")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 详情：展示完整描述、发布方、安全审核结论与安装按钮。 */
@Composable
private fun SkillDetailPane(
    detail: com.aicode.feature.agent.data.remote.skillstore.SkillStoreDetail,
    installing: Boolean,
    onInstall: () -> Unit,
    onBack: () -> Unit
) {
    val item = detail.item
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (item.publisher.isNotBlank()) {
            Text(
                text = buildString {
                    append(item.publisher)
                    if (item.publisherVerified) append(" · 已认证")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = item.description.ifBlank { stringResource(R.string.skill_store_no_description) },
            style = MaterialTheme.typography.bodyMedium
        )
        if (item.securityStatus != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.sm)
            ) {
                Icon(
                    FeatherIcons.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text = item.securityStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = stringResource(R.string.skill_store_files_count, detail.files.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(top = Spacing.md)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            TextButton(onClick = onInstall, enabled = !installing) {
                Icon(FeatherIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    stringResource(
                        if (installing) R.string.skill_store_installing else R.string.skill_store_install
                    )
                )
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
}

@Composable
private fun EmptyBox(initial: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(
                if (initial) R.string.skill_store_initial_hint else R.string.skill_store_empty
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}