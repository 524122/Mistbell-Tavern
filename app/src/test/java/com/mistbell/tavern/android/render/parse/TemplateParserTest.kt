package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TemplateParser 白名单解析的 JVM 单测：元素 unwrap/整树丢弃、属性过滤、slot 校验、容错解析。 */
class TemplateParserTest {
    private fun diagOf(
        doc: TemplateDocument,
        code: String,
    ): Diagnostic? = doc.diagnostics.firstOrNull { it.code == code }

    private fun collectText(node: TemplateNode): String =
        when (node) {
            is TemplateNode.TextNode -> node.text
            is TemplateNode.ElementNode -> node.children.joinToString("") { collectText(it) }
            is TemplateNode.SlotNode -> ""
        }

    @Test
    fun `白名单元素保留为节点`() {
        val doc = TemplateParser.parse("<div><p>你好</p><span>段</span></div>")
        assertEquals("div", doc.tree.tag)
        val children = doc.tree.children
        assertEquals(2, children.size)
        val p = children[0] as TemplateNode.ElementNode
        assertEquals("p", p.tag)
        assertEquals("你好", (p.children.first() as TemplateNode.TextNode).text)
        assertEquals("span", (children[1] as TemplateNode.ElementNode).tag)
        assertTrue(doc.diagnostics.isEmpty())
    }

    @Test
    fun `良性元素 unwrap 保留子节点`() {
        val doc = TemplateParser.parse("<div><article><p>正文</p></article><span>侧栏</span></div>")
        // article 壳消失，p 提升到 div 的直接子节点
        val children = doc.tree.children
        assertEquals(2, children.size)
        assertEquals("p", (children[0] as TemplateNode.ElementNode).tag)
        assertEquals("span", (children[1] as TemplateNode.ElementNode).tag)
        assertEquals("正文侧栏", collectText(doc.tree))
        val diag = diagOf(doc, DiagnosticCodes.DROP_ELEMENT)
        assertNotNull(diag)
        assertEquals("article", diag?.target)
        assertEquals(DiagnosticSeverity.WARN, diag?.severity)
    }

    @Test
    fun `unwrap 元素的属性壳一并丢弃`() {
        val doc = TemplateParser.parse("<div><section class=\"sec\" data-x=\"y\"><b>粗</b></section></div>")
        assertEquals("b", (doc.tree.children.single() as TemplateNode.ElementNode).tag)
        assertEquals("粗", collectText(doc.tree))
    }

    @Test
    fun `危险元素整子树丢弃`() {
        val doc = TemplateParser.parse("<div>前<table><tr><td>格</td></tr></table>后</div>")
        val text = collectText(doc.tree)
        assertTrue(text.contains("前"))
        assertTrue(text.contains("后"))
        assertTrue(!text.contains("格"))
        val diag = diagOf(doc, DiagnosticCodes.DROP_ELEMENT)
        assertNotNull(diag)
        assertEquals("table", diag?.target)
    }

    @Test
    fun `script 内容不进树`() {
        val doc = TemplateParser.parse("<div><script>alert('evil')</script>正文</div>")
        assertEquals("正文", collectText(doc.tree))
        assertTrue(doc.diagnostics.none { it.code == DiagnosticCodes.DROP_ATTRIBUTE })
    }

    @Test
    fun `style 元素丢弃给样式提示`() {
        val doc = TemplateParser.parse("<div><style>.a { color: red; }</style></div>")
        val diag = diagOf(doc, DiagnosticCodes.DROP_ELEMENT)
        assertNotNull(diag)
        assertTrue(diag?.message?.contains("style.css") == true)
    }

    @Test
    fun `白名单外属性丢弃有诊断`() {
        val doc = TemplateParser.parse("<div onclick=\"evil()\" class=\"keep\" data-id=\"7\">文本</div>")
        val div = doc.tree
        assertEquals(mapOf("class" to "keep"), div.attrs)
        val diag = diagOf(doc, DiagnosticCodes.DROP_ATTRIBUTE)
        assertNotNull(diag)
        assertEquals("onclick", diag?.target)
    }

    @Test
    fun `href 仅 a 元素允许`() {
        val withA = TemplateParser.parse("<a href=\"https://x\">链</a>")
        assertEquals("https://x", (withA.tree as TemplateNode.ElementNode).attrs["href"])
        assertTrue(withA.diagnostics.isEmpty())
        val onDiv = TemplateParser.parse("<div href=\"https://x\">块</div>")
        assertTrue((onDiv.tree as TemplateNode.ElementNode).attrs.isEmpty())
        assertEquals("href", diagOf(onDiv, DiagnosticCodes.DROP_ATTRIBUTE)?.target)
    }

    @Test
    fun `src alt 仅 img 元素允许`() {
        val withImg = TemplateParser.parse("<img src=\"pic.png\" alt=\"图\">")
        assertEquals("pic.png", (withImg.tree as TemplateNode.ElementNode).attrs["src"])
        assertEquals("图", (withImg.tree as TemplateNode.ElementNode).attrs["alt"])
        val onDiv = TemplateParser.parse("<div src=\"pic.png\" alt=\"图\">块</div>")
        assertTrue((onDiv.tree as TemplateNode.ElementNode).attrs.isEmpty())
        assertEquals(2, onDiv.diagnostics.count { it.code == DiagnosticCodes.DROP_ATTRIBUTE })
    }

    @Test
    fun `data-bind 属性按元素白名单保留`() {
        val onDiv = TemplateParser.parse("<div data-bind:text=\"state.名\" data-bind:visible=\"state.显\">块</div>")
        assertEquals(2, (onDiv.tree as TemplateNode.ElementNode).attrs.size)
        assertTrue(onDiv.diagnostics.isEmpty())
        // hr 的属性白名单只有 class/style，data-bind 丢弃
        val onHr = TemplateParser.parse("<hr data-bind:text=\"state.名\">")
        assertTrue((onHr.tree as TemplateNode.ElementNode).attrs.isEmpty())
        assertNotNull(diagOf(onHr, DiagnosticCodes.DROP_ATTRIBUTE))
    }

    @Test
    fun `slot 合法 name 生成 SlotNode`() {
        for (name in TemplateParser.SLOT_NAMES) {
            val doc = TemplateParser.parse("<slot name=\"$name\"></slot>")
            assertEquals(TemplateNode.SlotNode(name), doc.tree.children.single())
            assertTrue("slot=$name 不应有诊断", doc.diagnostics.isEmpty())
        }
    }

    @Test
    fun `slot 非法 name 丢弃子树有诊断`() {
        val doc = TemplateParser.parse("<slot name=\"widget\"><p>备选</p></slot>")
        assertTrue(doc.tree.children.isEmpty())
        assertNull(collectText(doc.tree).takeIf { it.contains("备选") })
        val diag = diagOf(doc, DiagnosticCodes.INVALID_SLOT)
        assertNotNull(diag)
        assertEquals("widget", diag?.target)
        assertEquals(DiagnosticSeverity.WARN, diag?.severity)
    }

    @Test
    fun `slot 缺 name 丢弃`() {
        val doc = TemplateParser.parse("<slot><p>备选</p></slot>")
        assertTrue(doc.tree.children.isEmpty())
        assertNotNull(diagOf(doc, DiagnosticCodes.INVALID_SLOT))
    }

    @Test
    fun `templateVersion 非法回退为 1`() {
        assertEquals(1, TemplateParser.parse("<div></div>", 0).templateVersion)
        assertEquals(1, TemplateParser.parse("<div></div>", -3).templateVersion)
        assertEquals(2, TemplateParser.parse("<div></div>", 2).templateVersion)
    }

    @Test
    fun `jsoup 容错解析永不上抛`() {
        // 未闭合标签/垃圾字符/空输入都不抛异常，产出的树可继续走级联
        val dirty = TemplateParser.parse("<div><p>未闭合<b>加粗</p>垃圾<<<>>>")
        assertEquals("div", dirty.tree.tag)
        val empty = TemplateParser.parse("")
        assertTrue(empty.tree.children.isEmpty())
        assertTrue(empty.diagnostics.isEmpty())
    }

    @Test
    fun `多根内容包透明 div`() {
        val doc = TemplateParser.parse("<p>一</p><p>二</p>")
        assertEquals("div", doc.tree.tag)
        assertEquals(2, doc.tree.children.size)
        assertEquals("一", collectText(doc.tree).substring(0, 1))
    }

    @Test
    fun `注释节点静默跳过`() {
        val doc = TemplateParser.parse("<div><!-- 主题作者留言 --><span>正文</span></div>")
        val span = doc.tree.children.single() as TemplateNode.ElementNode
        assertEquals("span", span.tag)
        assertTrue(doc.diagnostics.isEmpty())
    }
}
