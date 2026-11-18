package com.aicode.feature.agent.data.remote.skillstore

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skillstore.SkillStoreDetail
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
 * ClawHub 源。
 *
 * 接口实测（2026-11）：
 * - 列表 `GET /api/v1/skills?cursor=&limit=`（游标分页，响应带 `nextCursor`）；
 * - 详情 `GET /api/v1/skills/{slug}`；
 * - 下载 `GET /api/v1/download?slug=`。
 *
 * 该源**没有关键词搜索接口**：`/api/v1/skills` 的 q/page/size 参数全部被忽略，恒返回同一批数据。
 * （站点另有 `/api/search`，但 1.0.3 的实现未使用它，此处保持一致，不私自加搜索。）
 * 因此 [supportsSearch] 为 false，UI 据此把搜索框置灰。
 */
@Singleton
class ClawHubSkillStoreSource @Inject constructor() : SkillStoreSource {

    private companion object {
        const val TAG = "ClawHubSkillStore"
        const val SOURCE_ID = "clawhub"
        const val BASE = "https://clawhub.ai"
        const val TIMEOUT_SECONDS = 20L
    }

    override val id: String = SOURCE_ID
    override val displayName: String = "ClawHub"
    override val supportsSearch: Boolean = false
    override val supportsCategory: Boolean = false

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
        // 不支持搜索：有关键词也不传，只按游标翻列表，避免让用户以为筛选生效了。
        val url = buildString {
            append(BASE).append("/api/v1/skills")
            append("?limit=").append(pageSize)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor.urlEncoded())
        }
        return get(url) { body -> parsePage(body) }
    }

    override suspend fun detail(itemId: String): SkillStoreResult<SkillStoreDetail> {
        val url = "$BASE/api/v1/skills/${itemId.urlEncoded()}"
        return get(url) { body ->
            val obj = body.asJsonObject() ?: return@get null
            val skill = obj.getAsJsonObject("skill") ?: obj
            val item = parseItem(skill) ?: return@get null
            SkillStoreDetail(item = item, files = emptyList(), homepage = null)
        }
    }

    override suspend fun download(itemId: String): SkillStoreResult<Map<String, String>> {
        val url = "$BASE/api/v1/download?slug=${itemId.urlEncoded()}"
        return get(url) { body -> mapOf("SKILL.md" to body) }
    }

    private fun parsePage(body: String): SkillStoreListPage? {
        val obj = body.asJsonObject() ?: return null
        val array = obj.getAsJsonArray("items") ?: return null
        val items = array.mapNotNull { element ->
            element as? JsonObject ?: return@mapNotNull null
            parseItem(element)
        }
        return SkillStoreListPage(
            items = items,
            nextCursor = obj.stringOrNull("nextCursor")
        )
    }

    private fun parseItem(obj: JsonObject): SkillStoreItem? {
        val slug = obj.stringOrNull("slug") ?: return null
        val stats = obj.getAsJsonObject("stats")
        val latest = obj.getAsJsonObject("latestVersion")
        return SkillStoreItem(
            id = "$SOURCE_ID:$slug",
            sourceId = SOURCE_ID,
            sourceItemId = slug,
            slug = slug,
            displayName = obj.stringOrNull("displayName") ?: slug,
            description = obj.stringOrNull("summary") ?: obj.stringOrNull("description").orEmpty(),
            version = latest?.stringOrNull("version").orEmpty(),
            category = "",
            publisher = obj.stringOrNull("ownerHandle") ?: obj.stringOrNull("owner").orEmpty(),
            publisherVerified = false,
            downloads = stats?.longOr("downloads", 0L) ?: 0L,
            requiresApiKey = false,
            securityStatus = null
        )
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

    private fun JsonObject.longOr(key: String, fallback: Long): Long = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else fallback
    }.getOrDefault(fallback)

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
