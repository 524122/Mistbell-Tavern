package com.mistbell.tavern.android.render.layout

import com.mistbell.tavern.android.render.model.ColorValue
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FlexDirection
import com.mistbell.tavern.android.render.model.InheritedStyle
import com.mistbell.tavern.android.render.model.JustifyContent
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.NodeBindings
import com.mistbell.tavern.android.render.model.OverflowValue
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.ResolvedStyle
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextSpan
import com.mistbell.tavern.android.render.model.VisibilityValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LayoutSpecBuilder 的 JVM 单测：display:none 剪枝、样式字段映射完整性抽查、叶子类型分派。 */
class LayoutSpecBuilderTest {
    private fun element(
        tag: String,
        style: ResolvedStyle = ResolvedStyle(),
        bindings: NodeBindings? = null,
        children: List<RenderNode> = emptyList(),
        attrs: Map<String, String> = emptyMap(),
    ): RenderNode.Element {
        val node = TemplateNode.ElementNode(tag, attrs, emptyList())
        return RenderNode.Element(StyledNode(node, style, InheritedStyle.ROOT, bindings), children)
    }

    private fun textBlock(text: String) = TextBlock(spans = listOf(TextSpan(text = text)), align = TextAlign.LEFT)

    @Test
    fun `display none 子树剪枝`() {
        val root =
            element(
                "div",
                children =
                    listOf(
                        element("p", children = listOf(textNode("保留"))),
                        element(
                            "div",
                            style = ResolvedStyle(display = DisplayValue.NONE),
                            children = listOf(textNode("丢弃")),
                        ),
                    ),
            )
        val spec = LayoutSpecBuilder.build(root)
        assertNotNull(spec)
        val children = (spec!!.leaf as LayoutLeaf.Container).children
        assertEquals(1, children.size)
    }

    @Test
    fun `绑定不可见子树剪枝`() {
        val root =
            element(
                "div",
                children =
                    listOf(
                        element(
                            "div",
                            bindings = NodeBindings(visiblePath = "隐藏", visible = false),
                            children = listOf(textNode("隐藏行")),
                        ),
                        element("p", children = listOf(textNode("可见行"))),
                    ),
            )
        val spec = LayoutSpecBuilder.build(root)!!
        val children = (spec.leaf as LayoutLeaf.Container).children
        assertEquals(1, children.size)
    }

    @Test
    fun `整树不可见返回 null`() {
        val root = element("div", style = ResolvedStyle(display = DisplayValue.NONE))
        assertNull(LayoutSpecBuilder.build(root))
    }

    @Test
    fun `盒模型字段映射抽查`() {
        val style =
            ResolvedStyle(
                flexDirection = FlexDirection.ROW,
                justifyContent = JustifyContent.SPACE_BETWEEN,
                marginTop = Length.px(4f),
                marginLeft = Length(50f, CssUnit.PERCENT),
                paddingLeft = Length.px(6f),
                width = Length.px(120f),
                maxWidth = Length.AUTO,
                borderWidth = Length.px(2f),
                borderColor = ColorValue(0xFF112233L),
                borderRadius = Length.px(8f),
                backgroundColor = ColorValue(0xFF445566L),
                opacity = 0.5f,
                overflow = OverflowValue.HIDDEN,
            )
        val spec = LayoutSpecBuilder.build(element("div", style = style))!!
        val props = spec.props
        assertEquals(FlexDirection.ROW, props.flexDirection)
        assertEquals(JustifyContent.SPACE_BETWEEN, props.justifyContent)
        assertEquals(4f, props.marginTop?.value)
        assertEquals(CssUnit.PX, props.marginTop?.unit)
        assertEquals(50f, props.marginLeft?.value)
        assertEquals(CssUnit.PERCENT, props.marginLeft?.unit)
        assertEquals(6f, props.paddingLeft?.value)
        assertEquals(120f, props.width?.value)
        assertEquals(CssUnit.AUTO, props.maxWidth?.unit)
        assertEquals(2f, props.borderWidth)
        assertEquals(0xFF112233L, props.borderColor?.argb)
        assertEquals(8f, props.borderRadius)
        assertEquals(0xFF445566L, props.backgroundColor?.argb)
        assertEquals(0.5f, props.opacity)
        assertTrue(props.overflowHidden)
        assertFalse(props.visibilityHidden)
    }

    @Test
    fun `visibility hidden 保留布局但不绘制`() {
        val spec = LayoutSpecBuilder.build(element("div", style = ResolvedStyle(visibility = VisibilityValue.HIDDEN)))!!
        assertTrue(spec.props.visibilityHidden)
        assertNotNull(spec)
    }

    @Test
    fun `img 叶子映射为 ImageSpec`() {
        val spec = LayoutSpecBuilder.build(element("img", attrs = mapOf("src" to "img://face", "alt" to "表情")))!!
        val image = spec.leaf as LayoutLeaf.Image
        assertEquals("img://face", image.image.src)
        assertEquals("表情", image.image.alt)
    }

    @Test
    fun `文本叶子映射为 Text`() {
        val root = element("div", children = listOf(RenderNode.TextLeaf(textBlock("正文"))))
        val spec = LayoutSpecBuilder.build(root)!!
        val child = (spec.leaf as LayoutLeaf.Container).children.single()
        val text = child.leaf as LayoutLeaf.Text
        assertEquals("正文", text.block.text)
    }

    @Test
    fun `未声明字段取默认值`() {
        val spec = LayoutSpecBuilder.build(element("div"))!!
        val props = spec.props
        assertEquals(FlexDirection.COLUMN, props.flexDirection)
        assertEquals(0f, props.borderWidth)
        assertEquals(1f, props.opacity)
        assertFalse(props.overflowHidden)
        assertFalse(props.visibilityHidden)
        assertNull(props.backgroundColor)
        assertNull(props.width)
    }

    private fun textNode(value: String): RenderNode.InlineText {
        val node = TemplateNode.TextNode(value)
        return RenderNode.InlineText(StyledNode(node, ResolvedStyle(), InheritedStyle.ROOT), value)
    }
}
