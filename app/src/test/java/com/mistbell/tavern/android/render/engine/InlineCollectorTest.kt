package com.mistbell.tavern.android.render.engine

import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FontStyleValue
import com.mistbell.tavern.android.render.model.InheritedStyle
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.ResolvedStyle
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** InlineCollector 的 JVM 单测：行内合并、块级打断、br 换行、嵌套样式合并、空白与隐藏。 */
class InlineCollectorTest {
    private fun element(
        tag: String,
        style: ResolvedStyle = ResolvedStyle(),
        attrs: Map<String, String> = emptyMap(),
        children: List<RenderNode> = emptyList(),
    ): RenderNode.Element {
        val node = TemplateNode.ElementNode(tag, attrs, emptyList())
        return RenderNode.Element(StyledNode(node, style, InheritedStyle.ROOT), children)
    }

    private fun text(value: String): RenderNode.InlineText {
        val node = TemplateNode.TextNode(value)
        return RenderNode.InlineText(StyledNode(node, ResolvedStyle(), InheritedStyle.ROOT), value)
    }

    private fun leavesOf(root: RenderNode.Element): List<RenderNode.TextLeaf> {
        val out = mutableListOf<RenderNode.TextLeaf>()

        fun walk(node: RenderNode) {
            if (node is RenderNode.TextLeaf) out += node
            if (node is RenderNode.Element) node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    @Test
    fun `行内元素与文本合并为单个 TextBlock`() {
        val bold =
            element(
                "b",
                style = ResolvedStyle(fontWeight = BOLD_WEIGHT_TEST),
                children = listOf(text("粗")),
            )
        val root = element("div", children = listOf(text("前"), bold, text("后")))
        val leaves = leavesOf(InlineCollector.collect(root))
        assertEquals(1, leaves.size)
        assertEquals(3, leaves[0].block.spans.size)
        assertEquals("前粗后", leaves[0].block.text)
    }

    @Test
    fun `块级子元素打断收集`() {
        val root =
            element(
                "div",
                children =
                    listOf(
                        text("第一段"),
                        element("p", children = listOf(text("第二段"))),
                        text("第三段"),
                    ),
            )
        val collected = InlineCollector.collect(root)
        val leaves = leavesOf(collected)
        assertEquals(3, leaves.size)
        assertEquals(listOf("第一段", "第二段", "第三段"), leaves.map { it.block.text })
        // 第二段在 p 元素内部（结构保留），一三段为 div 的直接子块
        assertEquals(3, collected.children.size)
    }

    @Test
    fun `br 产出段内换行`() {
        val root =
            element("div", children = listOf(text("甲"), element("br"), text("乙")))
        val leaves = leavesOf(InlineCollector.collect(root))
        assertEquals(1, leaves.size)
        assertEquals("甲\n乙", leaves[0].block.text)
    }

    @Test
    fun `嵌套行内样式合并到同一 span`() {
        val root =
            element(
                "div",
                children =
                    listOf(
                        element(
                            "b",
                            style = ResolvedStyle(fontWeight = BOLD_WEIGHT_TEST),
                            children =
                                listOf(
                                    element(
                                        "i",
                                        style = ResolvedStyle(fontStyle = FontStyleValue.ITALIC),
                                        children = listOf(text("强调")),
                                    ),
                                ),
                        ),
                    ),
            )
        val leaves = leavesOf(InlineCollector.collect(root))
        assertEquals(1, leaves.size)
        val span = leaves[0].block.spans.single()
        assertEquals("强调", span.text)
        assertTrue(span.bold)
        assertTrue(span.italic)
    }

    @Test
    fun `链接 href 与样式透传到后代`() {
        val link =
            element(
                "a",
                style = ResolvedStyle(fontWeight = BOLD_WEIGHT_TEST),
                attrs = mapOf("href" to "https://example.com"),
                children = listOf(text("链接文字")),
            )
        val root = element("div", children = listOf(link))
        val span = leavesOf(InlineCollector.collect(root)).single().block.spans.single()
        assertEquals("链接文字", span.text)
        assertEquals("https://example.com", span.href)
        assertTrue(span.bold)
    }

    @Test
    fun `纯空白块不产出`() {
        val root = element("div", children = listOf(text("   "), text("\n\t")))
        val collected = InlineCollector.collect(root)
        assertTrue(leavesOf(collected).isEmpty())
    }

    @Test
    fun `连续空白折叠为单空格`() {
        val root = element("div", children = listOf(text("甲   乙")))
        val block = leavesOf(InlineCollector.collect(root)).single().block
        assertEquals("甲 乙", block.text)
    }

    @Test
    fun `display none 的行内元素跳过`() {
        val hidden =
            element(
                "span",
                style = ResolvedStyle(display = DisplayValue.NONE),
                children = listOf(text("丢弃")),
            )
        val root = element("div", children = listOf(text("保留"), hidden))
        val block = leavesOf(InlineCollector.collect(root)).single().block
        assertEquals("保留", block.text)
    }

    @Test
    fun `块级默认排版进入 TextBlock`() {
        val style =
            ResolvedStyle(
                textAlign = TextAlign.CENTER,
                fontSize = Length.px(18f),
                lineHeight = Length.px(27f),
            )
        val root = element("p", style = style, children = listOf(text("标题文字")))
        val block = leavesOf(InlineCollector.collect(root)).single().block
        assertEquals(TextAlign.CENTER, block.align)
        assertEquals(18f, block.fontSizePx)
        assertEquals(27f, block.lineHeightPx)
    }

    @Test
    fun `已有 TextLeaf 子块原样保留`() {
        val block =
            TextBlock(
                spans = listOf(TextSpan(text = "一等内容")),
                align = TextAlign.LEFT,
            )
        val root = element("div", children = listOf(RenderNode.TextLeaf(block)))
        val leaves = leavesOf(InlineCollector.collect(root))
        assertEquals(1, leaves.size)
        assertEquals("一等内容", leaves[0].block.text)
        assertNull(leaves[0].block.spans.single().colorArgb)
    }

    companion object {
        /** b/strong 在默认样式表中的字重（测试直接构造级联结果） */
        const val BOLD_WEIGHT_TEST = 700
    }
}
