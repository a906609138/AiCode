package com.aicode.feature.agent.data.remote.skillstore

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skillstore.SkillStoreDetail
import com.aicode.feature.agent.domain.skillstore.SkillStoreFile
import com.aicode.feature.agent.domain.skillstore.SkillStoreItem
import com.aicode.feature.agent.domain.skillstore.SkillStoreListPage
import com.aicode.feature.agent.domain.skillstore.SkillStoreResult
import com.aicode.feature.agent.data.remote.skillstore.SkillStoreResult as ApiResult
import com.aicode.feature.agent.domain.skillstore.SkillStoreSort
import com.aicode.feature.agent.domain.skillstore.SkillStoreSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 腾讯 SkillHub 源：直接复用既有的 [SkillHubApi]，不重写请求层。
 *
 * 该源的列表端点是搜索接口（`POST /api/v1/skills` 需鉴权），所以浏览与搜索走同一条路；
 * 关键词为空时不发空查询（接口对空 q 返回空结果，意义不大），直接返回空页。
 *
 * 排序：SkillHub 搜索接口没有排序参数，三档在本源内不做区分，由聚合层统一排序。
 */
@Singleton
class TencentSkillStoreSource @Inject constructor(
    private val api: SkillHubApi
) : SkillStoreSource {

    private companion object {
        const val TAG = "TencentSkillStore"
        const val SOURCE_ID = "tencent"
    }

    override val id: String = SOURCE_ID
    override val displayName: String = "腾讯"
    override val supportsSearch: Boolean = true
    override val supportsCategory: Boolean = true

    override suspend fun list(
        keyword: String?,
        category: String?,
        sort: SkillStoreSort,
        cursor: String?,
        pageSize: Int
    ): SkillStoreResult<SkillStoreListPage> {
        val q = keyword?.trim().orEmpty()
        if (q.isEmpty()) {
            // 搜索接口需要关键词；无关键词时视为「无结果」，不冒充推荐列表。
            return SkillStoreResult.Success(SkillStoreListPage())
        }
        val result = api.search(q, pageSize)
        if (result is ApiResult.Failure) return SkillStoreResult.Failure(result.message, result.cause)
        val list = (result as? ApiResult.Success<List<com.aicode.feature.agent.data.remote.skillstore.SkillStoreItem>>)?.data
            ?: return SkillStoreResult.Failure("响应解析失败")
        return SkillStoreResult.Success(
            SkillStoreListPage(items = list.map { it.toDomainItem() })
        )
    }

    override suspend fun detail(itemId: String): SkillStoreResult<SkillStoreDetail> {
        val (namespace, slug) = splitId(itemId)
        val result = api.detail(slug, namespace)
        if (result is ApiResult.Failure) return SkillStoreResult.Failure(result.message, result.cause)
        val detail = (result as? ApiResult.Success<com.aicode.feature.agent.data.remote.skillstore.SkillStoreDetail>)?.data
            ?: return SkillStoreResult.Failure("响应解析失败")
        return SkillStoreResult.Success(
            SkillStoreDetail(
                item = detail.item.toDomainItem(),
                files = detail.files.map { SkillStoreFile(path = it.path, sha256 = it.sha256, size = it.size) },
                homepage = detail.homepage
            )
        )
    }

    override suspend fun download(itemId: String): SkillStoreResult<Map<String, String>> {
        val (namespace, slug) = splitId(itemId)
        val filesResult = api.files(slug, namespace)
        if (filesResult is ApiResult.Failure) return SkillStoreResult.Failure(filesResult.message, filesResult.cause)
        val files = (filesResult as? ApiResult.Success<List<com.aicode.feature.agent.data.remote.skillstore.SkillStoreFile>>)?.data
            ?: return SkillStoreResult.Failure("响应解析失败")
        val contents = LinkedHashMap<String, String>()
        for (file in files) {
            if (!file.path.endsWith(".md", ignoreCase = true)) continue
            val content = api.fileContent(slug, namespace, file.path)
            if (content is ApiResult.Failure) {
                FileLogger.w(TAG, "下载 ${file.path} 失败: ${content.message}")
                return SkillStoreResult.Failure(content.message, content.cause)
            }
            val text = (content as? ApiResult.Success<String>)?.data
                ?: return SkillStoreResult.Failure("下载失败：${file.path}")
            contents[file.path] = text
        }
        return SkillStoreResult.Success(contents)
    }

    /** 源内标识形如 `namespace/slug`；没有命名空间时整串当 slug。 */
    private fun splitId(itemId: String): Pair<String, String> {
        val trimmed = itemId.trim()
        val slash = trimmed.lastIndexOf('/')
        return if (slash > 0) trimmed.substring(0, slash) to trimmed.substring(slash + 1)
        else "" to trimmed
    }

    private fun com.aicode.feature.agent.data.remote.skillstore.SkillStoreItem.toDomainItem(): SkillStoreItem =
        SkillStoreItem(
            id = "$SOURCE_ID:$id",
            sourceId = SOURCE_ID,
            sourceItemId = id,
            slug = slug,
            displayName = displayName,
            description = description,
            version = version,
            category = category,
            publisher = publisher,
            publisherVerified = publisherVerified,
            downloads = downloads,
            requiresApiKey = requiresApiKey,
            securityStatus = securityStatus
        )
}
