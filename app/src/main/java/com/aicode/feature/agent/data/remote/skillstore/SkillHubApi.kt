package com.aicode.feature.agent.data.remote.skillstore

import com.aicode.core.util.FileLogger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 商店条目的通用模型：把各家源压平成 UI 需要的字段。 */
data class SkillStoreItem(
    /** 源内唯一标识（SkillHub 用 `namespace/slug`，如 `@kunlungrowth/pdf`）。 */
    val id: String,
    /** 技能名（目录名与 `/命令` 用）。 */
    val slug: String,
    val displayName: String,
    val description: String,
    val version: String,
    val category: String,
    val publisher: String,
    val publisherVerified: Boolean,
    val downloads: Long,
    val requiresApiKey: Boolean,
    /** 安全审核结论（如「安全，无风险」）；无结论时为 null。 */
    val securityStatus: String?
)

/** 商店技能详情：在列表字段基础上补文件清单与来源信息。 */
data class SkillStoreDetail(
    val item: SkillStoreItem,
    val files: List<SkillStoreFile>,
    val homepage: String?
)

/** 单个待安装文件。 */
data class SkillStoreFile(
    val path: String,
    val sha256: String,
    val size: Long
)

/** 商店交互结果：用密封类区分「成功 / 业务失败 / 网络失败」，UI 据此给不同文案。 */
sealed class SkillStoreResult<out T> {
    data class Success<T>(val data: T) : SkillStoreResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : SkillStoreResult<Nothing>()
}

/**
 * SkillHub（腾讯系）技能商店接口客户端。
 *
 * 实测（2026-11）可用且**无需鉴权**的端点：
 * - `GET /api/v1/search?q=&page_size=` 搜索；
 * - `GET /api/v1/skills/{slug}?namespace=` 详情；
 * - `GET /api/v1/skills/{slug}/files?namespace=` 文件清单（带 sha256）；
 * - `GET /api/v1/skills/{slug}/file?namespace=&path=` 单文件正文（302 跳对象存储，需跟随）。
 *
 * 列表端点 `POST /api/v1/skills` 需鉴权（401），故浏览一律走搜索接口，不尝试绕过。
 * 目录 `GET /api/v1/categories` 同样公开，供后续做分类筛选。
 */
@Singleton
class SkillHubApi @Inject constructor() {

    private companion object {
        const val TAG = "SkillHubApi"
        const val BASE = "https://api.skillhub.cn"

        /** 商店请求只需短超时：慢就换一次，不阻塞设置页。 */
        const val TIMEOUT_SECONDS = 20L
    }

    private val gson = Gson()

    /**
     * 独立客户端：跟随 302（文件正文会跳到对象存储）、带明确超时。
     *
     * 不复用全局 `OkHttpClient`：那个读超时为 0（为 SSE 长连接准备），商店请求一旦卡住会一直挂着。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "aicode-skillstore/1.0 (Android)")
                    .build()
            )
        }
        .build()

    // ── 线上响应模型（字段名与接口一致，只取用到的部分） ──

    private data class SearchResponse(@SerializedName("results") val results: List<SearchItem>?)
    private data class SearchItem(
        val slug: String?,
        val displayName: String?,
        val name: String?,
        val description: String?,
        val summary: String?,
        val version: String?,
        val category: String?,
        val downloads: Long?,
        val tags: List<String>?,
        val namespace: Namespace?,
        val publisher: Publisher?,
        val labels: Map<String, String>?
    )

    private data class Namespace(
        val canonicalName: String?,
        val handle: String?,
        val displayName: String?
    )

    private data class Publisher(
        val name: String?,
        val verified: Boolean?,
        val certifiedName: String?
    )

    private data class FilesResponse(
        val count: Long?,
        val files: List<FileEntry>?
    )

    private data class FileEntry(
        val path: String?,
        val sha256: String?,
        val size: Long?
    )

    private data class DetailResponse(
        val slug: String?,
        val displayName: String?,
        val description: String?,
        val summary: String?,
        val homepage: String?,
        val version: String?,
        val category: String?,
        val namespace: Namespace?,
        val publisher: Publisher?,
        val securityReports: Map<String, SecurityReport>?,
        val latestVersion: LatestVersion?
    )

    private data class SecurityReport(val status: String?, val statusText: String?)
    private data class LatestVersion(val version: String?)

    /** 搜索技能。[query] 为空时返回空结果（列表接口需鉴权，不做空查询）。 */
    suspend fun search(query: String, pageSize: Int = 20): SkillStoreResult<List<SkillStoreItem>> {
        val q = query.trim()
        if (q.isEmpty()) return SkillStoreResult.Success(emptyList())
        return request("$BASE/api/v1/search?q=${q.urlEncoded()}&page_size=$pageSize") { body ->
            val parsed = gson.fromJson(body, SearchResponse::class.java)
            parsed?.results.orEmpty().mapNotNull { it.toItem() }
        }
    }

    /** 拉取详情（含文件清单）。 */
    suspend fun detail(slug: String, namespace: String): SkillStoreResult<SkillStoreDetail> {
        val ns = namespace.urlEncoded()
        val detailResult = request("$BASE/api/v1/skills/${slug.urlEncoded()}?namespace=$ns") { body ->
            gson.fromJson(body, DetailResponse::class.java)
        }
        val detail = when (detailResult) {
            is SkillStoreResult.Failure -> return detailResult
            is SkillStoreResult.Success -> detailResult.data
                ?: return SkillStoreResult.Failure("技能详情为空")
        }
        val filesResult = files(slug, namespace)
        val files = when (filesResult) {
            is SkillStoreResult.Failure -> return filesResult
            is SkillStoreResult.Success -> filesResult.data
        }
        val item = SkillStoreItem(
            id = "${detail.namespace?.canonicalName ?: namespace}/$slug",
            slug = slug,
            displayName = detail.displayName ?: detail.slug ?: slug,
            description = detail.description ?: detail.summary.orEmpty(),
            version = detail.latestVersion?.version ?: detail.version.orEmpty(),
            category = detail.category.orEmpty(),
            publisher = detail.publisher?.name.orEmpty(),
            publisherVerified = detail.publisher?.verified == true,
            downloads = 0,
            requiresApiKey = false,
            securityStatus = detail.securityReports?.values?.firstNotNullOfOrNull { it.statusText }
        )
        return SkillStoreResult.Success(SkillStoreDetail(item, files, detail.homepage))
    }

    /** 文件清单。 */
    suspend fun files(slug: String, namespace: String): SkillStoreResult<List<SkillStoreFile>> =
        request("$BASE/api/v1/skills/${slug.urlEncoded()}/files?namespace=${namespace.urlEncoded()}") { body ->
            gson.fromJson(body, FilesResponse::class.java)?.files.orEmpty().mapNotNull { entry ->
                val path = entry.path ?: return@mapNotNull null
                SkillStoreFile(path = path, sha256 = entry.sha256.orEmpty(), size = entry.size ?: 0L)
            }
        }

    /** 下载单个文件正文（纯文本，技能都是 Markdown）。 */
    suspend fun fileContent(slug: String, namespace: String, path: String): SkillStoreResult<String> =
        request(
            "$BASE/api/v1/skills/${slug.urlEncoded()}/file?namespace=${namespace.urlEncoded()}&path=${path.urlEncoded()}"
        ) { it }

    /** 统一请求、解析与错误包装；[parse] 返回 null 视为业务失败。 */
    private suspend fun <T> request(url: String, parse: (String) -> T?): SkillStoreResult<T> =
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
                FileLogger.w(TAG, "商店请求失败: $url", e)
                SkillStoreResult.Failure(e.message ?: "网络异常", e)
            }
        }

    private fun SearchItem.toItem(): SkillStoreItem? {
        val s = slug ?: return null
        val ns = namespace?.canonicalName.orEmpty()
        return SkillStoreItem(
            id = if (ns.isBlank()) s else "$ns/$s",
            slug = s,
            displayName = displayName ?: name ?: s,
            description = description ?: summary.orEmpty(),
            version = version.orEmpty(),
            category = category.orEmpty(),
            publisher = publisher?.name.orEmpty(),
            publisherVerified = publisher?.verified == true,
            downloads = downloads ?: 0L,
            requiresApiKey = labels?.get("requires_api_key")?.equals("true", ignoreCase = true) == true,
            securityStatus = null
        )
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
