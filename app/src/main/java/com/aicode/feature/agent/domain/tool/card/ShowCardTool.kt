package com.aicode.feature.agent.domain.tool.card

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.parser.AdaptiveCardParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 把一段 Adaptive Card JSON 渲染成对话里的原生卡片。
 *
 * 与面板脚本共用 [AdaptiveCardParser] 的元素白名单，模型只需要产出 JSON，
 * 不需要关心 Compose 组件如何摆放；卡片随工具消息落库，重开对话仍在。
 */
class ShowCardTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "ShowCardTool"
    }

    override val name = "showCard"
    override val description =
        "把一张自适应卡片（Adaptive Card JSON）渲染到当前对话里，用于展示结构化信息：" +
            "指标、状态、对比、明细、标签页、按钮、图表。只在内容适合卡片化时使用，" +
            "普通回答继续用 Markdown，不要把长段落塞进卡片。" +
            "card 为卡片根对象，形如 {\"body\":[{\"type\":\"chart\",\"kind\":\"bar\",...}]}；" +
            "支持的元素类型：textblock/text、metric、badge、statusdot、progressbar、factset、divider、" +
            "spacer、image、chart、container、row、columnset/column、flowrow、scrollrow、tabset/tab、button。" +
            "chart 支持 kind=bar|hbar|line|donut，需 categories 与 series[{name,values}]，可带 unit、caption。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.USER_INTERACTION)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "card" to ToolParameter(
            name = "card",
            type = ParameterType.OBJECT,
            description = "卡片根对象，必须含 body 数组；可选 compact（收起态单元素）、version。",
            required = true
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "一句话说明这张卡片在讲什么，仅用于工具行摘要，不参与渲染。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val raw = args["card"] ?: return ToolResult.Error("缺少必需参数：card", "MISSING_CARD")
        val card: AdaptiveCardRoot = when (raw) {
            is JsonObject -> AdaptiveCardParser.parseAdaptiveCard(raw)
            is JsonPrimitive -> AdaptiveCardParser.parseAdaptiveCard(raw.contentOrNull.orEmpty())
            else -> return ToolResult.Error("card 必须是 JSON 对象", "INVALID_CARD")
        }

        if (card.body.isEmpty()) {
            return ToolResult.Error(
                "卡片解析后没有任何可渲染元素：card.body 不能为空，且元素 type 必须在支持列表内",
                "EMPTY_CARD"
            )
        }

        FileLogger.d(TAG, "showCard 渲染 ${card.body.size} 个元素")
        val payload = when (raw) {
            is JsonObject -> raw
            else -> buildJsonObject { put("raw", raw) }
        }
        return ToolResult.Success(
            buildJsonObject {
                put("rendered", true)
                put("elements", card.body.size)
                put("card", payload)
            }
        )
    }
}
