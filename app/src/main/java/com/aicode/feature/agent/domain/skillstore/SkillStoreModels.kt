package com.aicode.feature.agent.domain.skillstore

/**
 * 商店条目的统一模型：把四家源压平成 UI 只需要的一组字段。
 *
 * [id] 带源前缀（`tencent:ns/slug`），聚合多个源时用它去重；
 * 各源内部标识另存 [sourceId] + [sourceItemId]，详情与下载都按这两个回查。
 */
data class SkillStoreItem(
    /** 全局唯一标识：`<sourceId>:<sourceItemId>`。 */
    val id: String,
    val sourceId: String,
    /** 源内标识（腾讯是 `namespace/slug`，觅游是技能名，百智云/ClawHub 是 slug）。 */
    val sourceItemId: String,
    /** 技能名，安装后的目录名与 `/命令` 用它。 */
    val slug: String,
    val displayName: String,
    val description: String,
    val version: String,
    val category: String,
    val publisher: String,
    val publisherVerified: Boolean,
    val downloads: Long,
    val requiresApiKey: Boolean,
    /** 安全审核结论；源未提供时为 null。 */
    val securityStatus: String?
)

/** 列表一页：条目 + 下一页游标。 */
data class SkillStoreListPage(
    val items: List<SkillStoreItem> = emptyList(),
    /** 下一页游标；null 表示没有更多。 */
    val nextCursor: String? = null,
    /** 源报告的总数（未知时为 null）。 */
    val total: Int? = null
)

/** 详情：在列表字段基础上补文件清单与主页。 */
data class SkillStoreDetail(
    val item: SkillStoreItem,
    val files: List<SkillStoreFile>,
    val homepage: String?
)

/** 待安装文件。 */
data class SkillStoreFile(
    val path: String,
    /** 源提供时用于安装前校验；为空表示该源不给哈希。 */
    val sha256: String,
    val size: Long
)

/** 结果封装：区分成功与失败，UI 据此给不同文案。 */
sealed class SkillStoreResult<out T> {
    data class Success<T>(val data: T) : SkillStoreResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : SkillStoreResult<Nothing>()
}

/** 排序偏好，对应 UI 的「推荐 / 下载量 / 最新」。 */
enum class SkillStoreSort {
    RECOMMENDED,
    DOWNLOADS,
    UPDATED
}

/** 单个源的本次查询状态：给 UI 一行「腾讯 20 · 觅游 无结果 · 百智云 超时」。 */
data class SkillStoreSourceStatus(
    val sourceId: String,
    val displayName: String,
    val count: Int,
    /** 非空表示该源本次失败，直接展示给用户。 */
    val error: String? = null
)
