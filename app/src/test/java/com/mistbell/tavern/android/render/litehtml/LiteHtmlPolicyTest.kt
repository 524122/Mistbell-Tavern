package com.mistbell.tavern.android.render.litehtml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteHtmlPolicyTest {
    @Test
    fun `style 和 table 走 litehtml`() {
        assertTrue(LiteHtmlPolicy.isLayoutHtml("<style>p{color:red}</style><p>hello</p>"))
        assertTrue(LiteHtmlPolicy.isLayoutHtml("<table><tr><td>cell-a</td></tr></table>"))
    }

    @Test
    fun `脚本、iframe 和事件处理不走 litehtml`() {
        assertTrue(LiteHtmlPolicy.requiresScriptRuntime("<script>alert(1)</script><p>x</p>"))
        assertTrue(LiteHtmlPolicy.requiresScriptRuntime("<iframe src=\"https://example.com\"></iframe>"))
        assertTrue(LiteHtmlPolicy.requiresScriptRuntime("<div onclick=\"go()\">按钮</div>"))
        assertFalse(LiteHtmlPolicy.isLayoutHtml("<style>p{color:red}</style><script>x</script>"))
        assertFalse(LiteHtmlPolicy.isLayoutHtml("<div class=\"panel\">面板</div>"))
    }

    @Test
    fun `默认文字色插在 head 内，作者样式仍在后面`() {
        val html = "<!DOCTYPE html><html><head><style>p{color:red}</style></head><body><p>hi</p></body></html>"
        val colored = LiteHtmlPolicy.withBubbleTextColor(html, "#112233")
        assertTrue(colored.indexOf("body{color:#112233}") < colored.indexOf("p{color:red}"))
    }

    @Test
    fun `没有 head 时默认文字色放在文档前面`() {
        val colored = LiteHtmlPolicy.withBubbleTextColor("<p>hi</p>", "#ABCDEF")
        assertTrue(colored.startsWith("<style>body{color:#ABCDEF}</style>"))
    }

    @Test
    fun `颜色转成不带透明度的 CSS`() {
        assertEquals("#112233", LiteHtmlPolicy.colorToCss(0xFF112233.toInt()))
    }
}
