package com.aicode.feature.settings.domain.parser

import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.card.ShowCardTool
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.model.CardColor
import com.aicode.feature.settings.domain.model.ChartElement
import com.aicode.feature.settings.domain.model.ChartSeries
import com.aicode.feature.settings.domain.model.ContainerElement
import com.aicode.feature.settings.domain.model.TextBlockElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chart` 元素与 `showCard` 工具的解析行为。
 *
 * 图表解析挂在既有的 [AdaptiveCardParser] 上（面板脚本与 AI 共用同一套白名单），
 * 这里覆盖的是类型识别、参数别名与容错，不涉及像素级渲染。
 */
class AdaptiveCardParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun card(bodyElement: String): AdaptiveCardRoot =
        AdaptiveCardParser.parseAdaptiveCard("{\"body\":[$bodyElement]}")

    private fun firstChart(root: AdaptiveCardRoot): ChartElement {
        val element = root.body.firstOrNull()
        assertNotNull("body 里没有元素", element)
        assertTrue("首个元素不是图表，实际是 ${element?.javaClass?.simpleName}", element is ChartElement)
        return element as ChartElement
    }

    @Test
    fun testBarChartParsesCategoriesAndSeries() {
        val chart = firstChart(
            card(
                """
                {
                  "type": "Chart",
                  "kind": "bar",
                  "categories": ["周一", "周二", "周三"],
                  "series": [{ "name": "请求", "values": [120, 180, 140] }],
                  "unit": "次",
                  "caption": "近三天请求量"
                }
                """.trimIndent()
            )
        )

        assertEquals("bar", chart.kind)
        assertEquals(listOf("周一", "周二", "周三"), chart.categories)
        assertEquals(1, chart.series.size)
        assertEquals(ChartSeries("请求", listOf(120f, 180f, 140f), null), chart.series.first())
        assertEquals("次", chart.unit)
        assertEquals("近三天请求量", chart.caption)
        assertTrue(chart.showValues)
        assertTrue(chart.showLegend)
        assertEquals(150, chart.heightDp)
    }

    @Test
    fun testChartKindAliasesNormalize() {
        for (raw in listOf("hbar", "h-bar", "horizontal", "HBAR")) {
            assertEquals("hbar", firstChart(card("{ \"type\": \"Chart\", \"kind\": \"$raw\" }")).kind)
        }
        assertEquals("line", firstChart(card("{ \"type\": \"Chart\", \"kind\": \"line\" }")).kind)
        assertEquals("donut", firstChart(card("{ \"type\": \"Chart\", \"kind\": \"pie\" }")).kind)
        assertEquals("donut", firstChart(card("{ \"type\": \"Chart\", \"kind\": \"ring\" }")).kind)
        // 无法识别的类型按柱状渲染，不抛异常
        assertEquals("bar", firstChart(card("{ \"type\": \"Chart\", \"kind\": \"scatter\" }")).kind)
        assertEquals("bar", firstChart(card("{ \"type\": \"Chart\" }")).kind)
    }

    @Test
    fun testChartTypeAliasesAreAccepted() {
        for (type in listOf("chart", "graph", "plot", "barchart", "linechart", "donut")) {
            val root = card("""{ "type": "$type", "categories": ["A", "B"], "values": [1, 2] }""")
            assertTrue("type=$type 未识别为图表", root.body.firstOrNull() is ChartElement)
        }
    }

    @Test
    fun testChartAcceptsLabelsAndFlatValues() {
        val chart = firstChart(
            card("""{ "type": "Chart", "labels": ["X", "Y", "Z"], "data": [10, 20, 30] }""")
        )
        assertEquals(listOf("X", "Y", "Z"), chart.categories)
        assertEquals(1, chart.series.size)
        assertEquals(ChartSeries("", listOf(10f, 20f, 30f), null), chart.series.first())
    }

    @Test
    fun testChartSeriesAcceptsJsonArrayElement() {
        val chart = firstChart(
            card("""{ "type": "Chart", "categories": ["A", "B"], "series": [[1.5, 2.5]] }""")
        )
        assertEquals(ChartSeries("", listOf(1.5f, 2.5f), null), chart.series.first())
    }

    @Test
    fun testChartSeriesCustomColor() {
        val chart = firstChart(
            card("""{ "type": "Chart", "categories": ["A"], "series": [{ "values": [1], "color": "#ff0000" }] }""")
        )
        assertEquals(CardColor.Custom("#ff0000"), chart.series.first().color)
    }

    @Test
    fun testChartShowValuesAndLegendToggle() {
        val chart = firstChart(
            card("""{"type":"Chart","showValues":false,"showLegend":false,"series":[{"values":[1]}]}""")
        )
        assertFalse(chart.showValues)
        assertFalse(chart.showLegend)
    }

    @Test
    fun testChartMinMaxAndHeight() {
        val chart = firstChart(
            card("""{"type":"Chart","height":200,"minValue":0,"maxValue":1000,"series":[{"values":[5]}]}""")
        )
        assertEquals(200, chart.heightDp)
        assertEquals(0f, chart.minValue!!)
        assertEquals(1000f, chart.maxValue!!)
    }

    @Test
    fun testChartInsideContainer() {
        val root = AdaptiveCardParser.parseAdaptiveCard(
            """
            {"body":[{"type":"Container","items":[
              {"type":"TextBlock","text":"构建统计"},
              {"type":"Chart","kind":"line","categories":["a","b"],"series":[{"values":[1,2]}]}
            ]}]}
            """.trimIndent()
        )
        val container = root.body.firstOrNull() as? ContainerElement
        assertNotNull(container)
        assertEquals("构建统计", (container!!.items.first() as TextBlockElement).text)
        assertEquals("line", (container.items[1] as ChartElement).kind)
    }

    @Test
    fun testVisibleFalseChartIsSkipped() {
        val root = card(
            """{ "type": "Chart", "visible": false, "categories": ["A"], "series": [{"values": [1]}] }"""
        )
        assertTrue("visible=false 的图表不应渲染", root.body.isEmpty())
    }

    @Test
    fun testShowCardToolParsesObjectDirectly() = runTest {
        val cardObject = json.parseToJsonElement(
            """{"body":[{"type":"Chart","kind":"donut","categories":["甲","乙"],"series":[{"values":[6,4]}]}]}"""
        )
        val result = ShowCardTool().execute(mapOf("card" to cardObject))
        assertTrue(result is ToolResult.Success)

        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue((data["rendered"] as JsonPrimitive).booleanOrNull == true)
        assertEquals(1, (data["elements"] as JsonPrimitive).intOrNull)

        val body = (data["card"] as kotlinx.serialization.json.JsonObject)["body"]!!
        val first = body.jsonArray.first().jsonObject
        assertEquals("Chart", first["type"]!!.jsonPrimitive.content)
        assertEquals("donut", first["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun testShowCardToolAcceptsStringCard() = runTest {
        val raw = JsonPrimitive("""{"body":[{"type":"Metric","label":"余额","value":"18.42"}]}""")
        assertTrue(ShowCardTool().execute(mapOf("card" to raw)) is ToolResult.Success)
    }

    @Test
    fun testShowCardToolRejectsMissingOrEmptyCard() = runTest {
        assertTrue(ShowCardTool().execute(emptyMap()) is ToolResult.Error)
        assertTrue(ShowCardTool().execute(mapOf("card" to json.parseToJsonElement("{}"))) is ToolResult.Error)
        assertTrue(
            ShowCardTool().execute(mapOf("card" to json.parseToJsonElement("""{"body":[]}""")))
                is ToolResult.Error
        )
    }

    @Test
    fun testShowCardToolExposesJsonSchema() {
        val schema = ShowCardTool().toJsonSchema()
        assertEquals("object", schema["type"])
        assertEquals(listOf("card"), schema["required"])
        assertTrue(schema["properties"].toString().contains("card"))
    }

    @Test
    fun testParseCardIgnoresSurroundingProse() {
        val root = AdaptiveCardParser.parseAdaptiveCard(
            "下面是这张卡片：\n{\"body\":[{\"type\":\"Chart\",\"categories\":[\"a\"],\"series\":[{\"values\":[1]}]}]}\n希望有帮助。"
        )
        assertTrue(root.body.firstOrNull() is ChartElement)
    }

    @Test
    fun testParseCardOnBlankAndInvalidTextReturnsEmptyCard() {
        assertEquals(0, AdaptiveCardParser.parseAdaptiveCard("").body.size)
        assertEquals(0, AdaptiveCardParser.parseAdaptiveCard("没有 JSON").body.size)
    }
}
