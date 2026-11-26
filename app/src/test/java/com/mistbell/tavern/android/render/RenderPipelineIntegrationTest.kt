package com.mistbell.tavern.android.render

import com.mistbell.tavern.android.render.engine.InlineCollector
import com.mistbell.tavern.android.render.instantiate.SlotFiller
import com.mistbell.tavern.android.render.layout.LayoutLeaf
import com.mistbell.tavern.android.render.layout.LayoutSpec
import com.mistbell.tavern.android.render.layout.LayoutSpecBuilder
import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.parse.CssParser
import com.mistbell.tavern.android.render.parse.TemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染管线纯 JVM 集成冒烟（M3）：模板 → 级联 → 绑定 → slot 填充 → 行内收集 → 布局规格
 * 全链走通（不含 Yoga/skiko JNI 层，Instrumented 层由 RenderPipelineSmokeTest 覆盖）。
 */
class RenderPipelineIntegrationTest {
    private fun containerChildren(spec: LayoutSpec) = (spec.leaf as LayoutLeaf.Container).children

    /** 徽章面板（var() inline style 与 class 样式混合） */
    private val badgePanelHtml =
        """<div class="card"><span class="badge" style="color: var(--mt-primary, #000000)">好感度 88</span></div>"""

    @Test
    fun `模板到布局规格全链走通`() {
        val template =
            TemplateParser.parse(
                """<div class="msg"><p class="head">{{char}} 的状态</p><slot name="narrative"></slot>""" +
                    """<slot name="panels"></slot></div>""",
            )
        val themeCss =
            """
            .msg { padding: 8px; background-color: #ffffff; }
            .head { font-size: 18px; font-weight: 700; }
            .card { border: 1px solid var(--mt-primary, #cccccc); border-radius: 8px; padding: 6px; }
            """.trimIndent()
        val stylesheet = CssParser.parse(themeCss, ThemeVars(mapOf("--mt-primary" to "#7c5cff")))
        val filled =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    narrative = listOf(ContentSegment.Markdown("## 夜色\n\n她**握紧**栏杆。")),
                    panels = listOf(badgePanelHtml),
                    themeStylesheet = stylesheet,
                    themeVars = ThemeVars(mapOf("--mt-primary" to "#7c5cff")),
                    context = BindingContext(macros = mapOf("char" to "艾琳")),
                ),
            )
        val collected = InlineCollector.collect(filled.root)
        val spec = LayoutSpecBuilder.build(collected)
        assertNotNull(spec)
        assertTrue(filled.diagnostics.isEmpty())

        // 顶层 .msg 容器：padding/background 已映射
        val props = spec!!.props
        assertEquals(8f, props.paddingTop?.value)
        assertEquals(0xFFFFFFFFL, props.backgroundColor?.argb)

        val children = containerChildren(spec)
        // head p + 叙述包裹块×2（标题/段落）+ 面板卡片
        assertEquals(4, children.size)

        // 标题：默认样式表 h 无关——p.head 用 .head 的 18px；宏已插值
        val headText = children[0].let { flattenText(it) }
        assertTrue("宏插值应已替换进文本：$headText", headText.contains("艾琳 的状态"))

        // Markdown 标题块 20px（h2 档），段落含 bold span（块经合成容器包裹）
        val h2 = containerChildren(children[1]).single().leaf as LayoutLeaf.Text
        assertEquals(20f, h2.block.fontSizePx)
        val paragraph = containerChildren(children[2]).single().leaf as LayoutLeaf.Text
        assertTrue(paragraph.block.spans.any { it.bold })

        // 卡片：主题表 var() 已替换为 token 值，border/radius 映射进规格
        val card = children[3]
        assertEquals(8f, card.props.borderRadius)
        assertEquals(1f, card.props.borderWidth)
        assertEquals(0xFF7C5CFFL, card.props.borderColor?.argb)
        val badge = containerChildren(card).single()
        val badgeText = badge.leaf as LayoutLeaf.Text
        assertEquals("好感度 88", badgeText.block.text)
        assertEquals(0xFF7C5CFFL, badgeText.block.spans.single().colorArgb)
    }

    @Test
    fun `data-bind visible 全链剪枝`() {
        val template =
            TemplateParser.parse(
                """<div><div data-bind:visible="state.显示隐藏" style="color: #ff0000"><span>隐藏行</span></div>""" +
                    """<p>可见行</p></div>""",
            )
        val filled =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    context = BindingContext(variables = mapOf("显示隐藏" to "0")),
                ),
            )
        // 变量 "0" 为假值 → visible=false → 布局规格剪枝
        val collected = InlineCollector.collect(filled.root)
        val spec = LayoutSpecBuilder.build(collected)
        assertNotNull(spec)
        val texts = mutableListOf<String>()

        fun walk(node: LayoutSpec) {
            when (val leaf = node.leaf) {
                is LayoutLeaf.Text -> texts += leaf.block.text
                is LayoutLeaf.Container -> leaf.children.forEach(::walk)
                is LayoutLeaf.Image -> Unit
            }
        }
        walk(spec!!)
        assertEquals(listOf("可见行"), texts)
    }

    @Test
    fun `变量缺失记诊断不崩`() {
        val template = TemplateParser.parse("<p>{{state.不存在}}</p>")
        val filled =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = template,
                    context = BindingContext(variables = emptyMap()),
                ),
            )
        assertTrue(filled.diagnostics.any { it.code == DiagnosticCodes.MISS_VARIABLE })
        val collected = InlineCollector.collect(filled.root)
        assertNotNull(LayoutSpecBuilder.build(collected))
    }

    /** 汇聚子树全部文本（诊断用） */
    private fun flattenText(spec: LayoutSpec): String {
        val builder = StringBuilder()
        when (val leaf = spec.leaf) {
            is LayoutLeaf.Text -> builder.append(leaf.block.text)
            is LayoutLeaf.Container -> leaf.children.forEach { builder.append(flattenText(it)) }
            is LayoutLeaf.Image -> Unit
        }
        return builder.toString()
    }
}
