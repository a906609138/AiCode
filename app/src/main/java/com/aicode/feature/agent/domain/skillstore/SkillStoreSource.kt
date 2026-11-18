package com.aicode.feature.agent.domain.skillstore

/**
 * 技能商店的单一数据源契约。
 *
 * 四个源（腾讯 SkillHub / 觅游 / 百智云 / ClawHub）的接口形态、分页方式、字段名各不相同，
 * 由各自实现类在内部消化，对外只暴露这一组方法，聚合层不必关心差异。
 *
 * 分页统一用字符串游标：腾讯/觅游/百智云传页码字符串，ClawHub 传它自己的 cursor；
 * null 表示第一页。实现类负责在游标为空时给各家的第一页默认值。
 */
interface SkillStoreSource {
    /** 稳定标识，用于同 id 去重与状态回传（如 `tencent`）。 */
    val id: String

    /** 顶部标签显示名。 */
    val displayName: String

    /**
     * 是否支持关键词搜索。
     *
     * ClawHub 的公开接口只有游标列表、没有搜索端点，故为 false；UI 据此把搜索框置灰，
     * 而不是让用户输入后拿到和一页列表相同的空转结果。
     */
    val supportsSearch: Boolean

    /** 是否支持分类筛选（目前只有腾讯提供分类端点）。 */
    val supportsCategory: Boolean

    /**
     * 拉取列表或搜索结果。
     *
     * @param keyword 为空表示浏览（推荐/最新），非空表示搜索
     * @param category 分类键；null 表示不筛选
     * @param sort 排序偏好；源不支持时忽略
     * @param cursor 翻页游标；null 为第一页
     */
    suspend fun list(
        keyword: String?,
        category: String?,
        sort: SkillStoreSort,
        cursor: String? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): SkillStoreResult<SkillStoreListPage>

    /** 拉取详情（含可安装文件清单）。 */
    suspend fun detail(itemId: String): SkillStoreResult<SkillStoreDetail>

    /**
     * 取可安装文件的内容。
     *
     * 返回路径到正文的映射；聚合层与安装器只管落盘，不关心各家是把内容放在详情里
     * 还是一个独立下载端点。
     */
    suspend fun download(itemId: String): SkillStoreResult<Map<String, String>>

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}
