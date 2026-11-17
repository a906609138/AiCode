package com.aicode.feature.agent.domain.expert

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 解析单个专家定义文件（`experts/<name>.md`）：YAML frontmatter 为配置，正文为专家提示词。
 *
 * 与子代理定义（[com.aicode.feature.agent.domain.subagent.AgentDefinitionParser]）同一套
 * frontmatter 约定，复用其取值辅助函数（列表/模式/inject 的宽松解析），避免两套规则漂移。
 */
object ExpertDefinitionParser {
    private const val TAG = "ExpertDefinitionParser"
    private const val MAX_DESC_CHARS = 500

    /** 正文为空视为非法定义（专家必须有提示词），返回 null。定义文件经 [provider] 以容器路径读取。 */
    fun parse(
        provider: FileAccessProvider,
        filePath: String,
        scope: ExpertDefinitionScope = ExpertDefinitionScope.GLOBAL
    ): ExpertDefinition? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "读取专家定义失败: $filePath", it)
            return null
        }
        return parseText(
            text = text,
            fallbackName = filePath.substringAfterLast('/').substringBeforeLast('.'),
            filePath = filePath,
            scope = scope
        )
    }

    /** 从原始文本解析定义（不依赖磁盘），供内置资产或插件包等来源使用。 */
    fun parseText(
        text: String,
        fallbackName: String,
        filePath: String? = null,
        scope: ExpertDefinitionScope = ExpertDefinitionScope.GLOBAL
    ): ExpertDefinition? {
        val (frontmatter, body) = splitAndParseFrontmatter(text) ?: return null
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: fallbackName
        val displayName = frontmatter["displayName"]?.toString()?.trim().orEmpty()
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val category = frontmatter["category"]?.toString()?.trim().orEmpty()

        return ExpertDefinition(
            name = name,
            displayName = displayName,
            description = description,
            category = category,
            type = parseType(frontmatter["type"]),
            members = AgentDefinitionParser.stringList(frontmatter["members"]),
            allowedTools = AgentDefinitionParser.stringList(frontmatter["tools"]),
            disallowedTools = AgentDefinitionParser.stringList(frontmatter["disallowedTools"]),
            inject = AgentDefinitionParser.parseInject(frontmatter["inject"]),
            skills = AgentDefinitionParser.stringList(frontmatter["skills"]),
            providerId = frontmatter["provider"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            model = frontmatter["model"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            reasoningEffort = frontmatter["reasoningEffort"]?.toString()?.trim()?.lowercase()
                ?.takeIf { it in VALID_EFFORTS },
            mode = AgentDefinitionParser.parseMode(frontmatter["mode"]),
            prompt = prompt,
            filePath = filePath,
            scope = scope
        )
    }

    /** type 取值：忽略大小写，`team` 视为专家团；缺省或非法值回退 SINGLE。 */
    internal fun parseType(raw: Any?): ExpertType {
        val token = raw?.toString()?.trim()?.lowercase().orEmpty()
        return if (token == "team") ExpertType.TEAM else ExpertType.SINGLE
    }

    /**
     * 拆出 frontmatter 与正文。frontmatter 顶层不是键值表时返回 null（该定义无效）；
     * YAML 语法错误时宽容处理为空配置、正文照常。
     */
    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String>? {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val loaded = try {
            org.yaml.snakeyaml.Yaml().load<Any>(block)
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析专家定义 YAML 失败", e)
            null
        }
        val map = loaded as? Map<*, *>
        if (loaded != null && map == null) {
            FileLogger.w(TAG, "专家定义 frontmatter 顶层不是键值表，已跳过: ${loaded.javaClass.simpleName}")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return (map ?: emptyMap<String, Any>()) as Map<String, Any> to rest
    }

    private val VALID_EFFORTS =
        com.aicode.feature.agent.domain.model.ReasoningEffort.entries.map { it.apiValue }.toSet()
}
