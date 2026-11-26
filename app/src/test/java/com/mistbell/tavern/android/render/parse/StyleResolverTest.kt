package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.ColorValue
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FontStyleValue
import com.mistbell.tavern.android.render.model.ResolvedStyle
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextDecorationValue
import com.mistbell.tavern.android.render.model.ThemeVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StyleResolver 级联的 JVM 单测：特异性排序、inline 优先、em/百分号折算、继承链、
 * 默认样式表、值类型校验与未知属性诊断。
 */
class StyleResolverTest {
    private fun resolveHtml(
        html: String,
        css: String = "",
        vars: ThemeVars = ThemeVars.EMPTY,
    ): ResolvedTemplate =
        StyleResolver.resolve(
            TemplateParser.parse(html),
            if (css.isEmpty()) null else CssParser.parse(css, vars),
            vars,
        )

    private fun findNode(
        root: StyledNode,
        tag: String,
    ): StyledNode? {
        val nodeTag =
            when (val node = root.node) {
                is TemplateNode.ElementNode -> node.tag
                is TemplateNode.SlotNode -> SLOT_TAG
                is TemplateNode.TextNode -> null
            }
        return if (nodeTag == tag) root else root.children.firstNotNullOfOrNull { findNode(it, tag) }
    }

    private fun styleOf(
        root: StyledNode,
        tag: String,
    ): ResolvedStyle = findNode(root, tag)!!.style

    @Test
    fun `id 特异性高于 class`() {
        // 协议解析器丢弃 id 属性，此处手构树携带 id 验证 CSS 匹配语义
        val doc =
            TemplateDocument(
                TemplateNode.ElementNode("div", mapOf("class" to "x", "id" to "main")),
                TemplateParser.DEFAULT_TEMPLATE_VERSION,
            )
        val result = StyleResolver.resolve(doc, CssParser.parse(".x { color: #111111 } #main { color: #222222 }"))
        assertEquals(ColorValue(COLOR_DARK), styleOf(result.tree, "div").color)
    }

    @Test
    fun `class 特异性高于元素`() {
        val result = resolveHtml("<div class=\"x\">块</div>", "div { color: #111111 } .x { color: #222222 }")
        assertEquals(ColorValue(COLOR_DARK), styleOf(result.tree, "div").color)
    }

    @Test
    fun `同特异性后声明胜出`() {
        val result = resolveHtml("<div>块</div>", "div { color: #111111 } div { color: #222222 }")
        assertEquals(ColorValue(COLOR_DARK), styleOf(result.tree, "div").color)
    }

    @Test
    fun `后代选择器命中跨层级祖先`() {
        val result = resolveHtml("<div><div><p>深</p></div></div>", "div p { color: #111111 }")
        assertEquals(ColorValue(COLOR_PLAIN), styleOf(result.tree, "p").color)
    }

    @Test
    fun `主题样式表覆盖默认样式表`() {
        val result = resolveHtml("<h1>标</h1>", "h1 { font-size: 40px }")
        assertEquals(THEME_H1_SIZE, styleOf(result.tree, "h1").fontSize?.value)
    }

    @Test
    fun `inline style 胜过样式表规则`() {
        val result = resolveHtml("<div class=\"x\" style=\"color: #333333\">块</div>", ".x { color: #111111 }")
        assertEquals(ColorValue(COLOR_INLINE), styleOf(result.tree, "div").color)
    }

    @Test
    fun `em 相对父字号折算为像素`() {
        // span 自身无 font-size 声明（默认样式表只给 p/div 设基线），继承父级 20px
        val result = resolveHtml("<div><span>行内</span></div>", "div { font-size: 20px } span { margin-left: 2em }")
        val margin = styleOf(result.tree, "span").marginLeft
        assertEquals(EM_MARGIN, margin?.value)
        assertEquals(CssUnit.PX, margin?.unit)
    }

    @Test
    fun `根节点 em 相对基线字号`() {
        val result = resolveHtml("<p style=\"padding-left: 1em\">段</p>")
        assertEquals(BASE_FONT_SIZE, styleOf(result.tree, "p").paddingLeft?.value)
    }

    @Test
    fun `font-size 百分比相对父字号`() {
        val result = resolveHtml("<div><span>行内</span></div>", "div { font-size: 20px } span { font-size: 50% }")
        assertEquals(HALF_FONT, styleOf(result.tree, "span").fontSize?.value)
    }

    @Test
    fun `父 color 子继承`() {
        val result = resolveHtml("<div><span>行内</span></div>", "div { color: #abcdef }")
        val span = findNode(result.tree, "span")!!
        assertEquals(ColorValue(COLOR_INHERITED), span.inherited.color)
        assertNull(span.style.color)
    }

    @Test
    fun `父 padding 不继承`() {
        val result = resolveHtml("<div><span>行内</span></div>", "div { padding: 8px }")
        val span = findNode(result.tree, "span")!!
        assertNull(span.style.paddingTop)
        assertNull(span.inherited.color)
    }

    @Test
    fun `line-height 倍数相对自身字号`() {
        val result = resolveHtml("<p>段</p>", "p { font-size: 20px; line-height: 1.5 }")
        assertEquals(LINE_HEIGHT_PX, styleOf(result.tree, "p").lineHeight?.value)
    }

    @Test
    fun `px 与 dp 等价`() {
        val result = resolveHtml("<p>段</p>", "p { width: 8dp; height: 10px }")
        assertEquals(8f, styleOf(result.tree, "p").width?.value)
        assertEquals(CssUnit.PX, styleOf(result.tree, "p").width?.unit)
        assertEquals(10f, styleOf(result.tree, "p").height?.value)
    }

    @Test
    fun `盒模型百分比保留原单位交布局消费`() {
        val result = resolveHtml("<p>段</p>", "p { width: 50% }")
        val width = styleOf(result.tree, "p").width
        assertEquals(50f, width?.value)
        assertEquals(CssUnit.PERCENT, width?.unit)
    }

    @Test
    fun `颜色缩写与透明格式解析`() {
        val result =
            resolveHtml(
                "<div><b>加</b><s>删</s></div>",
                "b { color: #f00 } s { color: #ff000080 }",
            )
        assertEquals(ColorValue(OPAQUE_RED), styleOf(result.tree, "b").color)
        assertEquals(ColorValue(HALF_ALPHA_RED), styleOf(result.tree, "s").color)
    }

    @Test
    fun `字重数字 100 到 900 之外丢弃`() {
        val ok = resolveHtml("<p>段</p>", "p { font-weight: 750 }")
        assertEquals(750, styleOf(ok.tree, "p").fontWeight)
        val bad = resolveHtml("<p>段</p>", "p { font-weight: 90 }")
        assertNull(styleOf(bad.tree, "p").fontWeight)
        assertTrue(bad.diagnostics.any { it.code == DiagnosticCodes.UNKNOWN_CSS_VALUE })
    }

    @Test
    fun `未知属性丢弃有诊断`() {
        val result = resolveHtml("<p>段</p>", "p { frobnicate: 8px; color: #111111 }")
        assertNotNull(styleOf(result.tree, "p").color)
        val diag = result.diagnostics.first { it.code == DiagnosticCodes.UNKNOWN_CSS_PROP }
        assertEquals("frobnicate", diag.target)
    }

    @Test
    fun `已知属性非法值丢弃有诊断`() {
        val result = resolveHtml("<p>段</p>", "p { color: 12px }")
        assertNull(styleOf(result.tree, "p").color)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCodes.UNKNOWN_CSS_VALUE })
    }

    @Test
    fun `display 枚举解析`() {
        val result = resolveHtml("<p>段</p>", "p { display: flex } q { display: bogus }")
        assertEquals(DisplayValue.FLEX, styleOf(result.tree, "p").display)
    }

    @Test
    fun `var 在级联生效`() {
        val result =
            resolveHtml(
                "<p>段</p>",
                "p { color: var(--mt-primary, #111111) }",
                ThemeVars(mapOf("--mt-primary" to "#223344")),
            )
        assertEquals(ColorValue(COLOR_TOKEN), styleOf(result.tree, "p").color)
    }

    @Test
    fun `margin 简写展开为四边`() {
        val result = resolveHtml("<p>段</p>", "p { margin: 4px 8px }")
        val style = styleOf(result.tree, "p")
        assertEquals(4f, style.marginTop?.value)
        assertEquals(8f, style.marginRight?.value)
        assertEquals(4f, style.marginBottom?.value)
        assertEquals(8f, style.marginLeft?.value)
    }

    @Test
    fun `border 简写解析宽度与颜色`() {
        val result = resolveHtml("<p>段</p>", "p { border: 2px solid #abcdef }")
        val style = styleOf(result.tree, "p")
        assertEquals(2f, style.borderWidth?.value)
        assertEquals(ColorValue(COLOR_INHERITED), style.borderColor)
    }

    @Test
    fun `默认样式 h1 比 p 字号大`() {
        val result = resolveHtml("<div><h1>标</h1><p>段</p></div>")
        val h1 = styleOf(result.tree, "h1").fontSize?.value ?: 0f
        val p = styleOf(result.tree, "p").fontSize?.value ?: 0f
        assertEquals(H1_DEFAULT_SIZE, h1)
        assertEquals(BASE_FONT_SIZE, p)
        assertTrue(h1 > p)
    }

    @Test
    fun `默认样式 strong 加粗 code 等宽`() {
        val result = resolveHtml("<p><strong>重点</strong><code>x = 1</code></p>")
        assertEquals(WEIGHT_BOLD, styleOf(result.tree, "strong").fontWeight)
        assertEquals("monospace", styleOf(result.tree, "code").fontFamily)
    }

    @Test
    fun `默认样式 a 有颜色和下划线`() {
        val result = resolveHtml("<a href=\"https://x\">链</a>")
        val style = styleOf(result.tree, "a")
        assertNotNull(style.color)
        assertEquals(TextDecorationValue.UNDERLINE, style.textDecoration)
    }

    @Test
    fun `默认样式 i 斜体 u 下划线 s 删除线`() {
        val result = resolveHtml("<div><i>斜</i><u>下</u><s>删</s></div>")
        assertEquals(FontStyleValue.ITALIC, styleOf(result.tree, "i").fontStyle)
        assertEquals(TextDecorationValue.UNDERLINE, styleOf(result.tree, "u").textDecoration)
        assertEquals(TextDecorationValue.LINE_THROUGH, styleOf(result.tree, "s").textDecoration)
    }

    @Test
    fun `默认样式 slot 块级全宽`() {
        val result = resolveHtml("<slot name=\"panels\"></slot>")
        assertEquals(DisplayValue.BLOCK, styleOf(result.tree, "slot").display)
        val width = styleOf(result.tree, "slot").width
        assertEquals(CssUnit.PERCENT, width?.unit)
    }

    @Test
    fun `默认样式表解析零诊断`() {
        assertTrue(StyleResolver.DEFAULT_STYLESHEET.diagnostics.isEmpty())
    }

    companion object {
        private const val SLOT_TAG = "slot"
        private const val BASE_FONT_SIZE = 16f
        private const val H1_DEFAULT_SIZE = 32f
        private const val THEME_H1_SIZE = 40f
        private const val HALF_FONT = 10f
        private const val EM_MARGIN = 40f
        private const val LINE_HEIGHT_PX = 30f
        private const val WEIGHT_BOLD = 700

        private const val COLOR_DARK = 0xFF222222L
        private const val COLOR_PLAIN = 0xFF111111L
        private const val COLOR_INLINE = 0xFF333333L
        private const val COLOR_INHERITED = 0xFFABCDEFL
        private const val COLOR_TOKEN = 0xFF223344L
        private const val OPAQUE_RED = 0xFFFF0000L
        private const val HALF_ALPHA_RED = 0x80FF0000L
    }
}
