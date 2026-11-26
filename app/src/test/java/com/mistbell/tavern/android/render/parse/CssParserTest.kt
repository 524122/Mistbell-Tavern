package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.ThemeVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CssParser 结构解析的 JVM 单测：规则/选择器/声明、畸形容错、var 桥接。值类型校验见 StyleResolverTest。 */
class CssParserTest {
    private fun vars(vararg pairs: Pair<String, String>): ThemeVars = ThemeVars(mapOf(*pairs))

    @Test
    fun `正常规则解析为规则列表`() {
        val sheet = CssParser.parse("p { color: #ff0000; font-size: 18px; }")
        assertTrue(sheet.diagnostics.isEmpty())
        val rule = sheet.rules.single()
        assertEquals(1, rule.selectors.size)
        assertEquals(listOf("p"), rule.selectors.first().compounds.map { it.tag })
        assertEquals(2, rule.declarations.size)
        assertEquals("color", rule.declarations[0].property)
        assertEquals("#ff0000", rule.declarations[0].value)
    }

    @Test
    fun `逗号分组选择器展开`() {
        val sheet = CssParser.parse("h1, .title { color: #112233 }")
        val rule = sheet.rules.single()
        assertEquals(2, rule.selectors.size)
        assertEquals(SelectorSpecificity(0, 0, 1), rule.selectors[0].specificity)
        assertEquals(SelectorSpecificity(0, 1, 0), rule.selectors[1].specificity)
    }

    @Test
    fun `后代选择器解析并累加特异性`() {
        val sheet = CssParser.parse("div p.x #y { color: #112233 }")
        val selector = sheet.rules.single().selectors.single()
        assertEquals(3, selector.compounds.size)
        assertEquals(SelectorSpecificity(1, 1, 2), selector.specificity)
    }

    @Test
    fun `复合选择器组合元素类与id`() {
        val sheet = CssParser.parse("h2.title#main { color: #112233 }")
        val compound = sheet.rules.single().selectors.single().compounds.single()
        assertEquals("h2", compound.tag)
        assertEquals(listOf("title"), compound.classes)
        assertEquals(listOf("main"), compound.ids)
        assertEquals(SelectorSpecificity(1, 1, 1), compound.specificity)
    }

    @Test
    fun `特异性三元组按字典序比较`() {
        val ids = SelectorSpecificity(1, 0, 0)
        val classes = SelectorSpecificity(0, 3, 2)
        val elements = SelectorSpecificity(0, 0, 5)
        assertTrue(ids > classes)
        assertTrue(classes > elements)
    }

    @Test
    fun `截断的块不抛异常且有诊断`() {
        val sheet = CssParser.parse("p { color: #ffffff")
        // 未闭合块按已读取部分容错：声明保留、诊断告警
        assertEquals(1, sheet.rules.single().declarations.size)
        assertTrue(sheet.diagnostics.isNotEmpty())
    }

    @Test
    fun `缺冒号的声明丢弃且有诊断`() {
        val sheet = CssParser.parse("p { color #ffffff; display: flex; }")
        val rule = sheet.rules.single()
        assertEquals(listOf("display"), rule.declarations.map { it.property })
        assertTrue(sheet.diagnostics.isNotEmpty())
    }

    @Test
    fun `多余分号静默跳过`() {
        val sheet = CssParser.parse("p { color: #ffffff;; display: none; ; }")
        assertTrue(sheet.diagnostics.isEmpty())
        assertEquals(2, sheet.rules.single().declarations.size)
    }

    @Test
    fun `伪类选择器整条规则丢弃有诊断`() {
        val sheet = CssParser.parse("p:hover { color: #ffffff; } p { color: #000000; }")
        // 非法选择器丢弃整条规则；后续合法规则不受影响
        assertEquals(listOf("p"), sheet.rules.map { it.selectors.first().compounds.first().tag })
        assertTrue(sheet.diagnostics.any { it.message.contains("p:hover") })
    }

    @Test
    fun `属性选择器整条规则丢弃有诊断`() {
        val sheet = CssParser.parse("div[data-x] { color: #ffffff; }")
        assertTrue(sheet.rules.isEmpty())
        assertTrue(sheet.diagnostics.isNotEmpty())
    }

    @Test
    fun `组合器选择器丢弃`() {
        val sheet = CssParser.parse("div > p { color: #ffffff; }")
        assertTrue(sheet.rules.isEmpty())
        assertTrue(sheet.diagnostics.isNotEmpty())
    }

    @Test
    fun `at 规则丢弃有诊断`() {
        val block = CssParser.parse("@media screen { p { color: #ffffff; } } p { display: flex; }")
        assertEquals(listOf("p"), block.rules.map { it.selectors.first().compounds.first().tag })
        assertTrue(block.diagnostics.any { it.message.contains("@media") })
        val statement = CssParser.parse("@import url(x.css); p { display: none; }")
        assertEquals(1, statement.rules.size)
        assertTrue(statement.diagnostics.any { it.message.contains("@import") })
    }

    @Test
    fun `注释被剥离`() {
        val sheet = CssParser.parse("/* 头注释 */ p { /* 值间注释 */ color: #ffffff; } /* 尾注 */")
        assertTrue(sheet.diagnostics.isEmpty())
        assertEquals(1, sheet.rules.single().declarations.size)
    }

    @Test
    fun `var 替换为主题 token`() {
        val sheet = CssParser.parse("p { color: var(--mt-primary); }", vars("--mt-primary" to "#112233"))
        assertEquals("#112233", sheet.rules.single().declarations.single().value)
        assertTrue(sheet.diagnostics.isEmpty())
    }

    @Test
    fun `var 回退值生效`() {
        val sheet = CssParser.parse("p { color: var(--mt-missing, #445566); }", vars())
        assertEquals("#445566", sheet.rules.single().declarations.single().value)
        assertTrue(sheet.diagnostics.none { it.code == DiagnosticCodes.UNDEFINED_VAR })
    }

    @Test
    fun `未定义 var 丢弃声明有诊断`() {
        val sheet = CssParser.parse("p { color: var(--mt-missing); display: none; }", vars())
        // 声明被丢弃（规则只剩 display），UNDEFINED_VAR 诊断携带变量名
        assertEquals(listOf("display"), sheet.rules.single().declarations.map { it.property })
        val diag = sheet.diagnostics.first { it.code == DiagnosticCodes.UNDEFINED_VAR }
        assertEquals("--mt-missing", diag.target)
    }

    @Test
    fun `var 嵌套引用解析`() {
        val sheet =
            CssParser.parse(
                "p { color: var(--mt-a); }",
                vars("--mt-a" to "var(--mt-b)", "--mt-b" to "#556677"),
            )
        assertEquals("#556677", sheet.rules.single().declarations.single().value)
    }

    @Test
    fun `inline style 解析为声明序列`() {
        val inline = CssParser.parseInlineStyle("color: #ffffff; margin: 4px 8px")
        assertEquals(2, inline.declarations.size)
        assertEquals("margin", inline.declarations[1].property)
        assertEquals("4px 8px", inline.declarations[1].value)
        assertTrue(inline.diagnostics.isEmpty())
    }

    @Test
    fun `大小写不敏感的属性名与元素名`() {
        val sheet = CssParser.parse("P { COLOR: #ffffff; } DIV.ClassName { opacity: 0.5 }")
        assertEquals("color", sheet.rules[0].declarations.single().property)
        assertEquals("div", sheet.rules[1].selectors.single().compounds.single().tag)
        assertEquals(listOf("ClassName"), sheet.rules[1].selectors.single().compounds.single().classes)
    }

    @Test
    fun `空样式表解析为零规则零诊断`() {
        val sheet = CssParser.parse("   \n  ")
        assertTrue(sheet.rules.isEmpty())
        assertTrue(sheet.diagnostics.isEmpty())
    }

    @Test
    fun `选择器块缺失截断丢弃`() {
        val sheet = CssParser.parse("p")
        assertNotNull(sheet)
        assertTrue(sheet.rules.isEmpty())
        assertTrue(sheet.diagnostics.isNotEmpty())
    }
}
