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
 * 百智云（Baizhi）源。
 *
 * 接口实测（2026-11）：
 * - 列表 `GET /openapi/skillshub/skills?page=&per_page=&keyword=`；
 * - 详情 `GET /openapi/skillshub/skills/{id}`；
 * - 下载 `GET /openapi/skillshub/download/{id}`。
 *
 * 注意路径是 `openapi/skillshub/`，不是常规的 `/api/skills`（后者确实 404）。
 * 分页参数是 `per_page`（下划线），响应分页信息在 `pagination.hasNext`。
 */
@Singleton
class BaizhiSkillStoreSource @Inject constructor() : SkillStoreSource {

    private companion object {
        const val TAG = "BaizhiSkillStore"
        const val SOURCE_ID = "baizhi"
        const val BASE = "https://www.baizhi.cloud"
        const val OPENAPI = "/openapi/skillshub"
        const val TIMEOUT_SECONDS = 20L
    }

    override val id: String = SOURCE_ID
    override val displayName: String = "百智云"
    override val supportsSearch: Boolean = true
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
        val page = cursor?.toIntOrNull() ?: 1
        val url = buildString {
            append(BASE).append(OPENAPI).append("/skills")
            append("?page=").append(page)
            append("&per_page=").append(pageSize)
            val q = keyword?.trim().orEmpty()
            if (q.isNotEmpty()) append("&keyword=").append(q.urlEncoded())
        }
        return get(url) { body -> parsePage(body) }
    }

    override suspend fun detail(itemId: String): SkillStoreResult<SkillStoreDetail> {
        val url = "$BASE$OPENAPI/skills/${itemId.urlEncoded()}"
        return get(url) { body ->
            val obj = body.asJsonObject() ?: return@get null
            val skill = obj.getAsJsonObject("skill") ?: return@get null
            val item = parseItem(skill) ?: return@get null
            // 该源不给文件清单与 sha256，安装时从下载端点取正文。
            SkillStoreDetail(item = item, files = emptyList(), homepage = null)
        }
    }

    override suspend fun download(itemId: String): SkillStoreResult<Map<String, String>> {
        val url = "$BASE$OPENAPI/download/${itemId.urlEncoded()}"
        return get(url) { body -> mapOf("SKILL.md" to body) }
    }

    private fun parsePage(body: String): SkillStoreListPage? {
        val obj = body.asJsonObject() ?: return null
        val skills = obj.getAsJsonArray("skills") ?: return null
        val items = skills.mapNotNull { element ->
            element as? JsonObject ?: return@mapNotNull null
            parseItem(element)
        }
        val pagination = obj.getAsJsonObject("pagination")
        val hasNext = pagination?.boolOr("hasNext", false) == true
        val total = pagination?.intOr("total", 0)?.takeIf { it > 0 }
        return SkillStoreListPage(
            items = items,
            nextCursor = if (hasNext) null else null,
            total = total
        )
    }

    private fun parseItem(obj: JsonObject): SkillStoreItem? {
        val rawId = obj.stringOrNull("id") ?: return null
        val name = obj.stringOrNull("name") ?: rawId.substringAfterLast('/')
        return SkillStoreItem(
            id = "$SOURCE_ID:$rawId",
            sourceId = SOURCE_ID,
            sourceItemId = rawId,
            slug = name,
            displayName = name,
            description = obj.stringOrNull("description").orEmpty(),
            version = obj.stringOrNull("version").orEmpty(),
            category = obj.stringOrNull("category").orEmpty(),
            publisher = obj.stringOrNull("owner").orEmpty(),
            publisherVerified = obj.boolOr("official", false),
            downloads = obj.longOr("downloadCount", 0L),
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

    private fun JsonObject.intOr(key: String, fallback: Int): Int = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asInt else fallback
    }.getOrDefault(fallback)

    private fun JsonObject.longOr(key: String, fallback: Long): Long = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else fallback
    }.getOrDefault(fallback)

    private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean = runCatching {
        if (has(key) && !get(key).isJsonNull) get(key).asBoolean else fallback
    }.getOrDefault(fallback)

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
