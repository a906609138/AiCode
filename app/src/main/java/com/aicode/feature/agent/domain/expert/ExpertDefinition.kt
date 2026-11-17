package com.aicode.feature.agent.domain.expert

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.subagent.InjectPart

/** 专家定义来源作用域：全局（跨项目共享）或项目级（仅当前工作区生效，可随项目提交）。 */
enum class ExpertDefinitionScope { GLOBAL, PROJECT }

/** 专家类型：单个专家（作用于当前会话）或专家团（团长拆解后派成员并行）。 */
enum class ExpertType { SINGLE, TEAM }

/**
 * 一个专家定义，来自 `experts/<name>.md` 的 frontmatter + 正文。
 *
 * 专家与「子代理」是两个正交维度：
 * - 子代理（[com.aicode.feature.agent.domain.subagent.AgentDefinition]）是**新开的独立会话**，看不到主对话历史；
 * - 专家是**当前会话切换到的角色**（人设 + 方法论 + 工具链），能看到完整对话历史。
 *
 * 两者共用同一套工具收窄与提示词注入机制，便于维护。
 *
 * @param name 唯一标识，建议与文件名一致
 * @param displayName 展示名（如「代码评审官」）；空则回退 [name]
 * @param description 何时该召唤它，注入选择器供用户辨认
 * @param category 分组（dev / writing / life 等），用于选择器归类
 * @param type SINGLE 或 TEAM；TEAM 时 [members] 生效
 * @param members 专家团成员名列表（仅 TEAM 有意义）
 * @param allowedTools 工具白名单；空表示继承全量工具
 * @param disallowedTools 工具黑名单，先于白名单生效
 * @param inject 要注入的提示词片段
 * @param skills 召唤时自动加载正文的技能名列表
 * @param providerId 绑定的 provider id；null 表示沿用当前会话
 * @param model 绑定的模型名；null 表示沿用当前会话
 * @param reasoningEffort 思考强度；null 表示沿用当前会话
 * @param prompt 专家自身的系统提示词（正文）
 * @param filePath 定义文件的容器路径，供设置页展示与删除
 */
data class ExpertDefinition(
    val name: String,
    val displayName: String = "",
    val description: String = "",
    val category: String = "",
    val type: ExpertType = ExpertType.SINGLE,
    val members: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = DEFAULT_INJECT,
    val skills: List<String> = emptyList(),
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val prompt: String,
    val filePath: String? = null,
    val scope: ExpertDefinitionScope = ExpertDefinitionScope.GLOBAL
) {
    /** 展示名：displayName 为空时回退 name。 */
    val title: String get() = displayName.ifBlank { name }

    /** 是否专家团（团长）。 */
    val isTeam: Boolean get() = type == ExpertType.TEAM

    /**
     * 按白名单/黑名单裁剪工具名集合。
     *
     * 黑名单先生效，再与白名单取交集；支持 `mcp__server__*` 形式的通配。
     * `task` 保留：专家团的团长需要用 `task` 派成员。
     */
    fun filterToolNames(all: List<String>): List<String> =
        all.filter { allowsTool(it) }

    /** 该定义是否允许 [toolName]。 */
    fun allowsTool(toolName: String): Boolean =
        !isDenied(toolName) && (allowedTools.isEmpty() || allowedTools.any { matches(it, toolName) })

    private fun isDenied(toolName: String): Boolean = disallowedTools.any { matches(it, toolName) }

    /** 支持精确名与 `前缀*` 通配（用于 `mcp__server__*` 一次禁掉某 server 全部工具）。 */
    private fun matches(pattern: String, toolName: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p == "*") return true
        if (p.endsWith("*")) return toolName.startsWith(p.dropLast(1), ignoreCase = true)
        return p.equals(toolName, ignoreCase = true)
    }

    companion object {
        /** 缺省注入项：主规则 + 项目规则 + 记忆；技能正文由 [skills] 单独加载。 */
        val DEFAULT_INJECT: Set<InjectPart> = setOf(
            InjectPart.MAIN_RULES,
            InjectPart.PROJECT_RULES,
            InjectPart.MEMORY
        )
    }
}
