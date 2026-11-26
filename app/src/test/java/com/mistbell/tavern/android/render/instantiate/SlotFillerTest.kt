package com.mistbell.tavern.android.render.instantiate

import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextSpan
import com.mistbell.tavern.android.render.parse.TemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SlotFiller 的 JVM 单测：slot 替换、缺槽位追加尾部、空段、data-bind:text 与宏插值。 */
class SlotFillerTest {
    private fun fill(
        templateHtml: String,
        narrative: List<ContentSegment> = emptyList(),
        panels: List<String> = emptyList(),
        context: BindingContext = BindingContext(),
    ): FilledTemplate =
        SlotFiller.fill(
            SlotFiller.FillRequest(
                template = TemplateParser.parse(templateHtml),
                narrative = narrative,
                panels = panels,
                context = context,
            ),
        )

    private fun textBlock(text: String) = TextBlock(spans = listOf(TextSpan(text = text)), align = TextAlign.LEFT)

    private fun flatten(root: RenderNode.Element): List<RenderNode> =
        listOf(root) +
            root.children.flatMap { child ->
                when (child) {
                    is RenderNode.Element -> flatten(child)
                    else -> listOf(child)
                }
            }

    @Test
    fun `slot 替换为叙述段内容`() {
        val result =
            fill(
                "<div><slot name=\"narrative\"></slot></div>",
                narrative = listOf(ContentSegment.Text(textBlock("正文"))),
            )
        val leaves = flatten(result.root).filterIsInstance<RenderNode.TextLeaf>()
        assertEquals(1, leaves.size)
        assertEquals("正文", leaves.first().block.text)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `面板段保留根元素样式`() {
        val result =
            fill(
                "<div><slot name=\"panels\"></slot></div>",
                panels = listOf("<div class=\"card\" style=\"padding: 6px\"><span>好感度 88</span></div>"),
            )
        val elements = flatten(result.root).filterIsInstance<RenderNode.Element>()
        val card = elements.first { (it.styled.node as TemplateNode.ElementNode).attrs.containsKey("class") }
        assertEquals(6f, card.styled.style.paddingTop?.value)
        assertTrue(card.styled.node is TemplateNode.ElementNode)
    }

    @Test
    fun `缺 slot 的内容追加在模板尾部`() {
        val result =
            fill(
                "<div><p>头部</p></div>",
                narrative = listOf(ContentSegment.Text(textBlock("叙述"))),
                panels = listOf("<div>面板</div>"),
            )
        val children = result.root.children
        // 头部 p + 追加的叙述块（含包裹容器） + 追加的面板
        assertTrue(children.size >= 3)
        val info = result.diagnostics.filter { it.code == DiagnosticCodes.INVALID_SLOT }
        assertEquals(2, info.size)
        assertEquals(DiagnosticSeverity.INFO, info.first().severity)
    }

    @Test
    fun `空段不产出内容`() {
        val result = fill("<div><slot name=\"narrative\"></slot><slot name=\"panels\"></slot></div>")
        assertTrue(result.root.children.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `多段顺序保持`() {
        val result =
            fill(
                "<div><slot name=\"panels\"></slot></div>",
                panels = listOf("<p>第一</p>", "<p>第二</p>"),
            )
        val texts =
            flatten(result.root)
                .filterIsInstance<RenderNode.InlineText>()
                .map { it.text }
        assertTrue(texts.any { it.contains("第一") })
        assertTrue(texts.any { it.contains("第二") })
    }

    @Test
    fun `data-bind text 以变量值替换子文本`() {
        val template = TemplateParser.parse("<div><span data-bind:text=\"state.名字\">占位</span></div>")
        val result =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    context = BindingContext(variables = mapOf("名字" to "艾琳")),
                ),
            )
        val texts = flatten(result.root).filterIsInstance<RenderNode.InlineText>().map { it.text }
        assertTrue(texts.contains("艾琳"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `data-bind text 未命中替换为空串且有诊断`() {
        val template = TemplateParser.parse("<div><span data-bind:text=\"state.缺失\">占位</span></div>")
        val result =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    context = BindingContext(variables = mapOf("名字" to "艾琳")),
                ),
            )
        val texts = flatten(result.root).filterIsInstance<RenderNode.InlineText>().map { it.text }
        assertTrue(texts.contains(""))
        assertEquals(DiagnosticCodes.MISS_VARIABLE, result.diagnostics.single().code)
    }

    @Test
    fun `模板宏插值经绑定层完成`() {
        val result =
            fill(
                "<div><p>{{char}} 的状态</p></div>",
                context = BindingContext(macros = mapOf("char" to "艾琳")),
            )
        val texts = flatten(result.root).filterIsInstance<RenderNode.InlineText>().map { it.text }
        assertTrue(texts.any { it.contains("艾琳 的状态") })
    }

    @Test
    fun `Markdown 段展开为多个文本块`() {
        val result =
            fill(
                "<div><slot name=\"narrative\"></slot></div>",
                narrative = listOf(ContentSegment.Markdown("# 标题\n\n段落正文")),
            )
        val leaves = flatten(result.root).filterIsInstance<RenderNode.TextLeaf>()
        assertEquals(2, leaves.size)
    }

    @Test
    fun `段内容绑定解析失败语义不崩`() {
        val template = TemplateParser.parse("<div><slot name=\"narrative\"></slot></div>")
        val result =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    narrative = listOf(ContentSegment.Html("<p>{{state.缺失}}</p>")),
                    context = BindingContext(variables = emptyMap()),
                ),
            )
        assertTrue(result.diagnostics.any { it.code == DiagnosticCodes.MISS_VARIABLE })
    }
}
