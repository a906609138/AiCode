package com.aicode.feature.agent.domain.skillstore

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.skillstore.BaizhiSkillStoreSource
import com.aicode.feature.agent.data.remote.skillstore.ClawHubSkillStoreSource
import com.aicode.feature.agent.data.remote.skillstore.MeyoSkillStoreSource
import com.aicode.feature.agent.data.remote.skillstore.TencentSkillStoreSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能商店聚合：并发查全部源，任一源失败只丢该源的结果。
 *
 * 每个源的成败单独回传给 UI（[SkillStoreSearchResult.statuses]），这样「为什么这一栏是空的」
 * 不必靠猜：失败带原因，成功但零结果标「无结果」。
 */
@Singleton
class SkillStoreRepository @Inject constructor(
    tencent: TencentSkillStoreSource,
    meyo: MeyoSkillStoreSource,
    baizhi: BaizhiSkillStoreSource,
    clawHub: ClawHubSkillStoreSource
) {
    private companion object {
        const val TAG = "SkillStoreRepository"
    }

    /** 源清单：顺序即 UI 顶部标签顺序（与 AiMini 一致）。 */
    val sources: List<SkillStoreSource> = listOf(tencent, meyo, baizhi, clawHub)

    /** 按 id 取源；未知名返回 null。 */
    fun sourceById(sourceId: String): SkillStoreSource? = sources.firstOrNull { it.id == sourceId }

    /**
     * 跨源搜索/浏览。
     *
     * [sourceIds] 为空表示查全部源；只查当前标签页的源时传单个 id。
     * 结果按下载量降序、同 id 去重（先命中的源优先）。
     */
    suspend fun search(
        keyword: String?,
        sort: SkillStoreSort = SkillStoreSort.RECOMMENDED,
        category: String? = null,
        sourceIds: Set<String> = emptySet()
    ): SkillStoreSearchResult = coroutineScope {
        val targets = sources.filter { sourceIds.isEmpty() || it.id in sourceIds }
        val outcomes = targets.map { source ->
            async {
                try {
                    source.list(keyword = keyword, category = category, sort = sort)
                } catch (e: Exception) {
                    FileLogger.w(TAG, "${source.displayName} 查询异常", e)
                    SkillStoreResult.Failure(e.message ?: "请求异常", e)
                } to source
            }
        }.awaitAll()

        val seen = LinkedHashMap<String, SkillStoreItem>()
        val statuses = mutableListOf<SkillStoreSourceStatus>()
        for ((result, source) in outcomes) {
            when (result) {
                is SkillStoreResult.Failure -> statuses += SkillStoreSourceStatus(
                    sourceId = source.id,
                    displayName = source.displayName,
                    count = 0,
                    error = result.message
                )
                is SkillStoreResult.Success -> {
                    result.data.items.forEach { item -> seen.putIfAbsent(item.id, item) }
                    statuses += SkillStoreSourceStatus(
                        sourceId = source.id,
                        displayName = source.displayName,
                        count = result.data.items.size,
                        error = if (result.data.items.isEmpty()) "无结果" else null
                    )
                }
            }
        }

        val sorted = when (sort) {
            SkillStoreSort.DOWNLOADS -> seen.values.sortedByDescending { it.downloads }
            // 推荐与最新：源已按自身规则排好，保持聚合顺序，不再二次打乱。
            else -> seen.values.toList()
        }
        SkillStoreSearchResult(items = sorted, statuses = statuses)
    }

    /** 取详情：按条目里的 sourceId 找到对应源。 */
    suspend fun detail(item: SkillStoreItem): SkillStoreResult<SkillStoreDetail> {
        val source = sourceById(item.sourceId)
            ?: return SkillStoreResult.Failure("未知来源：${item.sourceId}")
        return source.detail(item.sourceItemId)
    }

    /** 取可安装正文：路径到内容的映射。 */
    suspend fun download(item: SkillStoreItem): SkillStoreResult<Map<String, String>> {
        val source = sourceById(item.sourceId)
            ?: return SkillStoreResult.Failure("未知来源：${item.sourceId}")
        return source.download(item.sourceItemId)
    }
}

/** 聚合结果：条目 + 每源状态。 */
data class SkillStoreSearchResult(
    val items: List<SkillStoreItem>,
    val statuses: List<SkillStoreSourceStatus> = emptyList()
)
