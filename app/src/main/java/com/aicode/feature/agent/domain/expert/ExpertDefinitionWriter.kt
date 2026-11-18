package com.aicode.feature.agent.domain.expert


/**
 * 把 [ExpertDefinition] 写回 frontmatter + 正文的 Markdown。
 *
 * 只输出非默认字段，保持与内置专家定义一致的简洁形态（对照 assets/experts 下的内置定义）：
 * 列表用 `[a, b]` 行内形式，空集合整行省略，避免写出 `tools: []` 这种噪音。
 *
 * 与 [ExpertDefinitionParser] 构成往返：解析器读、这里写，字段名必须一一对应
 * （`displayName` / `disallowedTools` / `reasoningEffort` 的大小写都要与解析端一致）。
 */
object ExpertDefinitionWriter {

    fun write(definition: ExpertDefinition): String = buildString {
        append("---\n")
        append("name: ").append(definition.name.trim()).append('\n')
        if (definition.displayName.isNotBlank()) {
            append("displayName: ").append(definition.displayName.trim()).append('\n')
        }
        if (definition.description.isNotBlank()) {
            append("description: ").append(definition.description.trim().replace('\n', ' ')).append('\n')
        }
        if (definition.category.isNotBlank()) {
            append("category: ").append(definition.category.trim()).append('\n')
        }
        if (definition.isTeam) {
            append("type: team\n")
            appendInlineList("members", definition.members)
        }
        appendInlineList("tools", definition.allowedTools)
        appendInlineList("disallowedTools", definition.disallowedTools)
        val inject = definition.inject.map { it.token }.sorted()
        if (inject != DEFAULT_INJECT_WIRE) {
            appendInlineList("inject", inject)
        }
        appendInlineList("skills", definition.skills)
        definition.providerId?.takeIf { it.isNotBlank() }?.let { append("provider: $it\n") }
        definition.model?.takeIf { it.isNotBlank() }?.let { append("model: $it\n") }
        definition.reasoningEffort?.takeIf { it.isNotBlank() }?.let { append("reasoningEffort: $it\n") }
        definition.mode?.let { append("mode: ${it.name.lowercase()}\n") }
        append("---\n")
        definition.prompt.trim().let { append(it) }
    }

    /** 与缺省注入项等价的 wire 形态，用于判断是否需要写 `inject` 行。 */
    private val DEFAULT_INJECT_WIRE: List<String> =
        ExpertDefinition.DEFAULT_INJECT.map { it.token }.sorted()

    private fun StringBuilder.appendInlineList(key: String, values: List<String>) {
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        append(key).append(": [").append(cleaned.joinToString(", ")).append("]\n")
    }
}
