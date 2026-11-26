package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.RenderDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TemplateValidator 的 JVM 单测：危险元素/slot 非法/空树回落，白名单卡段放行。 */
class TemplateValidatorTest {
    private fun assertNeedsFallback(
        html: String,
        expectReasonContains: String,
    ) {
        val decision = TemplateValidator.decideHtml(html)
        assertTrue("「$html」应 NeedsFallback，实际 $decision", decision is RenderDecision.NeedsFallback)
        val reasons = (decision as RenderDecision.NeedsFallback).reasons
        assertTrue("原因应包含「$expectReasonContains」，实际 $reasons", reasons.any { it.contains(expectReasonContains) })
    }

    @Test
    fun `含 table 的卡段回落`() {
        assertNeedsFallback("<div><table><tr><td>表</td></tr></table></div>", "table")
    }

    @Test
    fun `含 svg 或 form 的卡段回落`() {
        assertNeedsFallback("<svg><circle/></svg>", "svg")
        assertNeedsFallback("<div><form><input></input></form></div>", "form")
    }

    @Test
    fun `含 video 的卡段回落`() {
        assertNeedsFallback("<video src=\"x.mp4\"></video>", "video")
    }

    @Test
    fun `纯 div span 卡段可渲染`() {
        val decision = TemplateValidator.decideHtml("<div><span class=\"t\">文本</span><p>段</p></div>")
        assertEquals(RenderDecision.Renderable, decision)
    }

    @Test
    fun `纯文本卡段可渲染`() {
        assertEquals(RenderDecision.Renderable, TemplateValidator.decideHtml("今天天气不错"))
    }

    @Test
    fun `空模板回落`() {
        assertNeedsFallback("<div></div>", "模板树为空")
        assertNeedsFallback("", "模板树为空")
        assertNeedsFallback("<div>   </div>", "模板树为空")
    }

    @Test
    fun `slot 非法回落`() {
        assertNeedsFallback("<slot name=\"bogus\"><p>备选</p></slot>", "slot")
    }

    @Test
    fun `br 算有效内容不回落`() {
        assertEquals(RenderDecision.Renderable, TemplateValidator.decideHtml("<div><br></div>"))
    }
}
