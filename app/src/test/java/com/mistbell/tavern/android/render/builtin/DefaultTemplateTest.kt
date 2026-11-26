package com.mistbell.tavern.android.render.builtin

import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.RenderDecision
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.parse.StyleResolver
import com.mistbell.tavern.android.render.parse.TemplateParser
import com.mistbell.tavern.android.render.parse.TemplateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置默认模板 v1 的回归单测：可解析、槽位齐备、版本冻结、默认排版基线。 */
class DefaultTemplateTest {
    @Test
    fun `默认模板可解析且无告警诊断`() {
        val doc = DefaultTemplate.document()
        assertEquals(DefaultTemplate.TEMPLATE_VERSION, doc.templateVersion)
        assertTrue(doc.diagnostics.none { it.severity == DiagnosticSeverity.WARN })
        assertTrue(doc.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `templateVersion 冻结为 1`() {
        assertEquals(1, DefaultTemplate.TEMPLATE_VERSION)
        assertEquals(1, TemplateParser.DEFAULT_TEMPLATE_VERSION)
    }

    @Test
    fun `两个槽位在且顺序正确`() {
        val doc = DefaultTemplate.document()
        assertEquals("div", doc.tree.tag)
        assertEquals(mapOf("class" to "msg"), doc.tree.attrs)
        val slots = doc.tree.children.map { it as TemplateNode.SlotNode }
        assertEquals(2, slots.size)
        assertEquals("narrative", slots[0].name)
        assertEquals("panels", slots[1].name)
    }

    @Test
    fun `默认模板通过 Validator`() {
        assertEquals(RenderDecision.Renderable, TemplateValidator.decide(DefaultTemplate.document()))
    }

    @Test
    fun `默认样式表零诊断`() {
        assertTrue(StyleResolver.DEFAULT_STYLESHEET.diagnostics.isEmpty())
    }

    @Test
    fun `槽位默认块级全宽`() {
        val resolved = StyleResolver.resolve(DefaultTemplate.document())
        val slots = resolved.tree.children
        for (slot in slots) {
            assertEquals(DisplayValue.BLOCK, slot.style.display)
            val width = slot.style.width
            assertEquals(CssUnit.PERCENT, width?.unit)
        }
    }
}
