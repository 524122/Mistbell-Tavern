package com.mistbell.tavern.android.render.bind

import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.parse.StyleResolver
import com.mistbell.tavern.android.render.parse.TemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BindingResolver 的 JVM 单测：宏替换、状态插值、data-bind 两条绑定与失败语义。 */
class BindingResolverTest {
    private fun bound(
        html: String,
        variables: Map<String, String> = emptyMap(),
        macros: Map<String, String> = emptyMap(),
    ): BoundTemplate {
        val styled = StyleResolver.resolve(TemplateParser.parse(html))
        return BindingResolver.bind(styled.tree, BindingContext(variables, macros))
    }

    private fun firstText(root: StyledNode): String =
        when (val node = root.node) {
            is TemplateNode.TextNode -> node.text
            else -> root.children.firstNotNullOfOrNull { firstText(it) } ?: ""
        }

    @Test
    fun `宏替换命中`() {
        val result = bound("<p>{{char}} 对 {{user}} 说</p>", macros = mapOf("char" to "艾琳", "user" to "旅人"))
        assertEquals("艾琳 对 旅人 说", firstText(result.tree))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `宏未命中保留原文`() {
        val result = bound("<p>{{unknown}} 保留</p>", macros = mapOf("char" to "艾琳"))
        assertEquals("{{unknown}} 保留", firstText(result.tree))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `state 插值命中替换`() {
        val result = bound("<p>好感度：{{state.好感度}}</p>", variables = mapOf("好感度" to "88"))
        assertEquals("好感度：88", firstText(result.tree))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `state 未命中替换为空串且有诊断`() {
        val result = bound("<p>前{{state.不存在}}后</p>", variables = mapOf("好感度" to "88"))
        assertEquals("前后", firstText(result.tree))
        val diag = result.diagnostics.single()
        assertEquals(DiagnosticCodes.MISS_VARIABLE, diag.code)
        assertEquals("state.不存在", diag.target)
    }

    @Test
    fun `非匹配键的花括号文本保留`() {
        val result = bound("<p>花费 {{ 100 }} 金币</p>")
        assertEquals("花费 {{ 100 }} 金币", firstText(result.tree))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `未闭合的花括号保留`() {
        val result = bound("<p>打开 {{state.好感度 但没关</p>", variables = mapOf("好感度" to "88"))
        assertEquals("打开 {{state.好感度 但没关", firstText(result.tree))
    }

    @Test
    fun `data-bind text 解析为变量路径`() {
        val result = bound("<div data-bind:text=\"state.名字\">占位</div>", variables = mapOf("名字" to "艾琳"))
        val bindings = result.tree.bindings
        assertNotNull(bindings)
        assertEquals("名字", bindings?.textPath)
        assertNull(bindings?.visiblePath)
        assertTrue(bindings?.visible == true)
    }

    @Test
    fun `data-bind visible 命中假值不可见`() {
        val result = bound("<div data-bind:visible=\"state.旗标\">块</div>", variables = mapOf("旗标" to "false"))
        assertFalse(result.tree.bindings?.visible == true)
        // "0" 同为假值
        val zero = bound("<div data-bind:visible=\"state.旗标\">块</div>", variables = mapOf("旗标" to "0"))
        assertFalse(zero.tree.bindings?.visible == true)
    }

    @Test
    fun `data-bind visible 命中真值可见`() {
        val result = bound("<div data-bind:visible=\"state.旗标\">块</div>", variables = mapOf("旗标" to "88"))
        assertTrue(result.tree.bindings?.visible == true)
        val truthyText = bound("<div data-bind:visible=\"state.旗标\">块</div>", variables = mapOf("旗标" to "TRUE "))
        assertTrue(truthyText.tree.bindings?.visible == true)
    }

    @Test
    fun `data-bind visible 缺路径按 false 且有诊断`() {
        val result = bound("<div data-bind:visible=\"  \">块</div>")
        assertFalse(result.tree.bindings?.visible == true)
        assertEquals(DiagnosticCodes.INVALID_BIND, result.diagnostics.single().code)
    }

    @Test
    fun `data-bind visible 指向缺失变量按 false 且有诊断`() {
        val result = bound("<div data-bind:visible=\"state.不存在\">块</div>", variables = mapOf("x" to "1"))
        assertFalse(result.tree.bindings?.visible == true)
        val diag = result.diagnostics.single()
        assertEquals(DiagnosticCodes.MISS_VARIABLE, diag.code)
        assertEquals("data-bind:visible", diag.target)
    }

    @Test
    fun `无 data-bind 属性不建绑定`() {
        val result = bound("<div>块</div>")
        assertNull(result.tree.bindings)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `绑定解析产出新树且输入树不变`() {
        val styled = StyleResolver.resolve(TemplateParser.parse("<p>{{char}} 你好</p>"))
        BindingResolver.bind(styled.tree, BindingContext(macros = mapOf("char" to "艾琳")))
        // 输入树保持原样（纯函数，无副作用）
        val original = styled.tree.children.first().node as TemplateNode.TextNode
        assertEquals("{{char}} 你好", original.text)
        val boundTree = BindingResolver.bind(styled.tree, BindingContext(macros = mapOf("char" to "艾琳")))
        val replaced = boundTree.tree.children.first().node as TemplateNode.TextNode
        assertEquals("艾琳 你好", replaced.text)
    }

    @Test
    fun `元素子树的文本被递归替换`() {
        val result =
            bound(
                "<div><p>{{state.名}}</p><span>{{user}}</span></div>",
                variables = mapOf("名" to "艾琳"),
                macros = mapOf("user" to "旅人"),
            )
        assertEquals("艾琳", firstText(result.tree.children[0]))
        assertEquals("旅人", firstText(result.tree.children[1]))
    }
}
