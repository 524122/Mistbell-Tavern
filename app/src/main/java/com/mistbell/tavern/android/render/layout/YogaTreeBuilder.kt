package com.mistbell.tavern.android.render.layout

import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDisplay
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaMeasureFunction
import com.facebook.yoga.YogaMeasureMode
import com.facebook.yoga.YogaMeasureOutput
import com.facebook.yoga.YogaNode
import com.facebook.yoga.YogaNodeFactory
import com.facebook.yoga.YogaOverflow
import com.facebook.yoga.YogaWrap
import com.mistbell.tavern.android.render.engine.TextMeasurer
import com.mistbell.tavern.android.render.model.AlignItems
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.FlexDirection
import com.mistbell.tavern.android.render.model.FlexWrap
import com.mistbell.tavern.android.render.model.JustifyContent
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.TextBlock

/** 构建产物：YogaNode 与规格树的平行结构（绘制遍历按同构树走，坐标取 Yoga 布局结果） */
data class YogaBuiltNode(
    val yoga: YogaNode,
    val spec: LayoutSpec,
    val children: List<YogaBuiltNode>,
    /** 文本叶子非空：绘制层据此取 Paragraph */
    val textBlock: TextBlock?,
)

/**
 * Yoga 树构建器（M3）：布局规格树 → YogaNode 树。
 *
 * 映射：display/flex-direction/wrap/gap/justify/align、margin/padding（px→set、%→setPercent、
 * auto→setMarginAuto）、width/height/min/max、border（Yoga 只管宽度，颜色/圆角留绘制层）；
 * 文本叶子 setMeasureFunction → [TextMeasurer]（SkParagraph 测量，按 (文本+样式指纹, 约束宽) 缓存）。
 *
 * 本类触碰 Yoga JNI，不可 JVM 单测；逻辑保持薄（换算细节在 LayoutSpecBuilder 的纯 Kotlin 侧）。
 */
class YogaTreeBuilder(private val measurer: TextMeasurer) {
    /** 构建根节点（规格树已在 LayoutSpecBuilder 剪枝，display:none 不会到达这里） */
    fun build(root: LayoutSpec): YogaBuiltNode = buildNode(root)

    private fun buildNode(spec: LayoutSpec): YogaBuiltNode {
        val node = YogaNodeFactory.create()
        applyProps(node, spec.props)
        val children: List<YogaBuiltNode>
        val textBlock: TextBlock?
        when (val leaf = spec.leaf) {
            is LayoutLeaf.Container -> {
                children = leaf.children.map(::buildNode)
                children.forEachIndexed { index, child -> node.addChildAt(child.yoga, index) }
                textBlock = null
            }
            is LayoutLeaf.Text -> {
                textBlock = leaf.block
                children = emptyList()
                node.setMeasureFunction(measureFunction(textBlock))
            }
            is LayoutLeaf.Image -> {
                // 图片叶子：尺寸由样式声明；未声明时给最小占位，绘制层按节点框 fit 居中
                children = emptyList()
                textBlock = null
                if (spec.props.width == null && spec.props.height == null) {
                    node.setWidth(IMAGE_PLACEHOLDER_PX)
                    node.setHeight(IMAGE_PLACEHOLDER_PX)
                }
            }
        }
        return YogaBuiltNode(node, spec, children, textBlock)
    }

    /** SkParagraph 测量函数：EXACTLY/AT_MOST/UNDEFINED 三态约束 → (宽, 高) */
    private fun measureFunction(block: TextBlock): YogaMeasureFunction =
        YogaMeasureFunction { _, width, widthMode, _, _ ->
            val available =
                when (widthMode) {
                    YogaMeasureMode.EXACTLY, YogaMeasureMode.AT_MOST -> width
                    else -> null
                }
            val (measuredWidth, height) = measurer.measure(block, available)
            val resultWidth = if (widthMode == YogaMeasureMode.EXACTLY) width else measuredWidth
            YogaMeasureOutput.make(resultWidth, height)
        }

    /** 属性映射（Length：px→set、%→setPercent、auto→未定义/自动） */
    private fun applyProps(
        node: YogaNode,
        props: LayoutProps,
    ) {
        node.setDisplay(YogaDisplay.FLEX)
        node.setFlexDirection(flexDirections.getValue(props.flexDirection))
        node.setWrap(flexWraps.getValue(props.flexWrap))
        applyLength(props.gap, { node.setGap(YogaGutter.ALL, it) }, { node.setGapPercent(YogaGutter.ALL, it) })
        node.setJustifyContent(justifications.getValue(props.justifyContent))
        props.alignItems?.let { node.setAlignItems(alignments.getValue(it)) }
        applyMargin(node, props)
        applyPadding(node, props)
        if (props.borderWidth > 0f) node.setBorder(YogaEdge.ALL, props.borderWidth)
        applyLength(props.width, node::setWidth, node::setWidthPercent)
        applyLength(props.height, node::setHeight, node::setHeightPercent)
        applyLength(props.minWidth, node::setMinWidth, node::setMinWidthPercent)
        applyLength(props.maxWidth, node::setMaxWidth, node::setMaxWidthPercent)
        applyLength(props.minHeight, node::setMinHeight, node::setMinHeightPercent)
        applyLength(props.maxHeight, node::setMaxHeight, node::setMaxHeightPercent)
        if (props.overflowHidden) node.setOverflow(YogaOverflow.HIDDEN)
    }

    private fun applyMargin(
        node: YogaNode,
        props: LayoutProps,
    ) {
        margins.forEach { side ->
            val length = side.get(props)
            applyLength(
                length,
                { node.setMargin(side.edge, it) },
                { node.setMarginPercent(side.edge, it) },
            )
            marginAuto(length) { node.setMarginAuto(side.edge) }
        }
    }

    private fun applyPadding(
        node: YogaNode,
        props: LayoutProps,
    ) {
        paddings.forEach { side ->
            applyLength(
                side.get(props),
                { node.setPadding(side.edge, it) },
                { node.setPaddingPercent(side.edge, it) },
            )
        }
    }

    private fun marginAuto(
        length: Length?,
        setter: () -> Unit,
    ) {
        if (length?.unit == CssUnit.AUTO) setter()
    }

    /** px → px setter；% → percent setter；auto 留给 marginAuto（其余属性 auto = Yoga 未定义默认） */
    private fun applyLength(
        length: Length?,
        setPx: (Float) -> Unit,
        setPercent: (Float) -> Unit,
    ) {
        when (length?.unit) {
            CssUnit.PX -> setPx(length.value)
            CssUnit.PERCENT -> setPercent(length.value)
            else -> Unit
        }
    }

    /** 模型枚举 → Yoga 枚举映射（查表降低 applyProps 分支复杂度） */
    private val flexDirections: Map<FlexDirection, YogaFlexDirection> =
        mapOf(
            FlexDirection.ROW to YogaFlexDirection.ROW,
            FlexDirection.ROW_REVERSE to YogaFlexDirection.ROW_REVERSE,
            FlexDirection.COLUMN to YogaFlexDirection.COLUMN,
            FlexDirection.COLUMN_REVERSE to YogaFlexDirection.COLUMN_REVERSE,
        )

    private val flexWraps: Map<FlexWrap, YogaWrap> =
        mapOf(
            FlexWrap.NOWRAP to YogaWrap.NO_WRAP,
            FlexWrap.WRAP to YogaWrap.WRAP,
            FlexWrap.WRAP_REVERSE to YogaWrap.WRAP_REVERSE,
        )

    private val justifications: Map<JustifyContent, YogaJustify> =
        mapOf(
            JustifyContent.FLEX_START to YogaJustify.FLEX_START,
            JustifyContent.CENTER to YogaJustify.CENTER,
            JustifyContent.FLEX_END to YogaJustify.FLEX_END,
            JustifyContent.SPACE_BETWEEN to YogaJustify.SPACE_BETWEEN,
            JustifyContent.SPACE_AROUND to YogaJustify.SPACE_AROUND,
            JustifyContent.SPACE_EVENLY to YogaJustify.SPACE_EVENLY,
        )

    private val alignments: Map<AlignItems, YogaAlign> =
        mapOf(
            AlignItems.FLEX_START to YogaAlign.FLEX_START,
            AlignItems.CENTER to YogaAlign.CENTER,
            AlignItems.FLEX_END to YogaAlign.FLEX_END,
            AlignItems.STRETCH to YogaAlign.STRETCH,
            AlignItems.BASELINE to YogaAlign.BASELINE,
        )

    companion object {
        /** 图片叶子未声明尺寸时的占位（px） */
        private const val IMAGE_PLACEHOLDER_PX = 24f

        /** 四边 margin/padding 取值器与 Yoga 边缘的对（applyMargin/applyPadding 查表用） */
        private data class SideProp(
            val get: (LayoutProps) -> Length?,
            val edge: YogaEdge,
        )

        private val margins: List<SideProp> =
            listOf(
                SideProp({ it.marginTop }, YogaEdge.TOP),
                SideProp({ it.marginRight }, YogaEdge.RIGHT),
                SideProp({ it.marginBottom }, YogaEdge.BOTTOM),
                SideProp({ it.marginLeft }, YogaEdge.LEFT),
            )

        private val paddings: List<SideProp> =
            listOf(
                SideProp({ it.paddingTop }, YogaEdge.TOP),
                SideProp({ it.paddingRight }, YogaEdge.RIGHT),
                SideProp({ it.paddingBottom }, YogaEdge.BOTTOM),
                SideProp({ it.paddingLeft }, YogaEdge.LEFT),
            )
    }
}
