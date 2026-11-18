package com.aicode.feature.agent.data.remote.skillstore

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skillstore.SkillStoreDetail
import com.aicode.feature.agent.domain.skillstore.SkillStoreFile
import com.aicode.feature.agent.domain.skillstore.SkillStoreItem
import com.aicode.feature.agent.domain.skillstore.SkillStoreListPage
import com.aicode.feature.agent.domain.skillstore.SkillStoreResult
import com.aicode.feature.agent.domain.skillstore.SkillStoreSort
import com.aicode.feature.agent.domain.skillstore.SkillStoreSource
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 觅游（Meyo）源。
 *
 * 接口实测（2026-11）：
 * - 列表 `GET /api/v1/skills?page=&pageSize=&tag=&sort=`；
 * - 搜索 `GET /api/v1/skills/search?keyword=&page=&pageSize=`；
 * - 详情 `GET /api/v1/skills/{name}`；
 * - 下载 `GET /api/v1/skills/download?name=`。
 *
 * 关键：搜索与列表是**两个不同路径**。实测给列表接口传 keyword 会被忽略
 * （total 恒为 44 万+），只有 `/search` 才真正按关键词过滤，故按关键词是否为空切换路径。
 *
 * 排序映射（反编译确认）：`UPDATED → updateTime`，推荐与下载量都用 `downloadCount`。
 */
@Singleton
class MeyoSkillStoreSource @Inject constructor() : SkillStoreSource {

    private companion object {
        const val TAG = "MeyoSkillStore"
        const val SOURCE_ID = "meyo"
        const val BASE = "https://www.meyo123.com"
        const val TIMEOUT_SECONDS = 20L
    }

    override val id: String = SOURCE_ID
    override val displayName: String = "觅游"
    override val supportsSearch: Boolean = true
    override val supportsCategory: Boolean = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "aicode-skillstore/1.0 (Android)")
                    .build()
            )
        }
        .build()

    override suspend fun list(
        keyword: String?,
        category: String?,
        sort: SkillStoreSort,
        cursor: String?,
        pageSize: Int
    ): SkillStoreResult<SkillStoreListPage> {
        val q = keyword?.trim().orEmpty()
        val page = cursor?.toIntOrNull() ?: 1
        val path = if (q.isEmpty()) "/api/v1/skills" else "/api/v1/skills/search"
        val url = buildString {
            append(BASE).append(path)
            append("?page=").append(page)
            append("&pageSize=").append(pageSize)
            if (q.isNotEmpty()) append("&keyword=").append(q.urlEncoded())
            if (!category.isNullOrBlank()) append("&tag=").append(category.urlEncoded())
            append("&sort=").append(sort.toApiSort())
        }
        return get(url) { body -> parsePage(body, page, pageSize) }
    }

    override suspend fun detail(itemId: String): SkillStoreResult<SkillStoreDetail> {
        val url = "$BASE/api/v1/skills/${itemId.urlEncoded()}"
        return get(url) { body ->
            val obj = body.asJsonObject() ?: return@get null
            val data = obj.getAsJsonObject("data") ?: return@get null
            val item = parseItem(data) ?: return@get null
            // 觅游的详情接口不返回文件清单，安装时从下载端点取正文。
            SkillStoreDetail(item = item, files = emptyList(), homepage = null)
        }
    }

    override suspend fun download(itemId: String): SkillStoreResult<Map<String, String>> {
        val url = "$BASE/api/v1/skills/download?name=${itemId.urlEncoded()}"
        return get(url) { body -> mapOf("SKILL.md" to body) }
    }

    private fun parsePage(body: String, page: Int, pageSize: Int): SkillStoreListPage? {
        val obj = body.asJsonObject() ?: return null
        val data = obj.getAsJsonObject("data") ?: return null
        val list = data.getAsJsonArray("list") ?: return null
        val items = list.mapNotNull { element ->
            element as? JsonObject ?: return@mapNotNull null
            parseItem(element)
        }
        val total = data.intOr("total", 0)
        // 该接口不返回游标，用页码 + 是否已取满推断有没有下一页。
        val hasNext = total > page * pageSize
        return SkillStoreListPage(
            items = items,
            nextCursor = if (hasNext) (page + 1).toString() else null,
            total = total
        )
    }

    private fun parseItem(obj: JsonObject): SkillStoreItem? {
        val name = obj.stringOrNull("name") ?: return null
        val display = obj.stringOrNull("alias")?.takeIf { it.isNotBlank() } ?: name
        return SkillStoreItem(
            id = "$SOURCE_ID:$name",
            sourceId = SOURCE_ID,
            sourceItemId = name,
            slug = name,
            displayName = display,
            description = obj.stringOrNull("description").orEmpty(),
            version = obj.stringOrNull("latestVersion").orEmpty(),
            category = obj.stringOrNull("context").orEmpty(),
            publisher = obj.stringOrNull("creator").orEmpty(),
            publisherVerified = obj.stringOrNull("status").equals("approved", ignoreCase = true),
            downloads = obj.longOr("downloadCount", 0L),
            requiresApiKey = false,
            securityStatus = null
        )
    }

    private fun SkillStoreSort.toApiSort(): String = when (this) {
        SkillStoreSort.UPDATED -> "updateTime"
        else -> "downloadCount"
    }

    private suspend fun <T> get(url: String, parse: (String) -> T?): SkillStoreResult<T> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SkillStoreResult.Failure("请求失败（HTTP ${response.code}）")
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = parse(body) ?: return@withContext SkillStoreResult.Failure("响应解析失败")
                    SkillStoreResult.Success(parsed)
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "请求失败: $url", e)
                SkillStoreResult.Failure(e.message ?: "网络异常", e)
            }
        }

    private fun String.asJsonObject(): JsonObject? = runCatching {
        JsonParser.parseString(this).asJsonObject
    }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asString else null
    }.getOrNull()

    private fun JsonObject.intOr(key: String, fallback: Int): Int = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asInt else fallback
    }.getOrDefault(fallback)

    private fun JsonObject.longOr(key: String, fallback: Long): Long = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else fallback
    }.getOrDefault(fallback)

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
