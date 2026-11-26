package com.mistbell.tavern.android.render.layout

import com.mistbell.tavern.android.render.model.AlignItems
import com.mistbell.tavern.android.render.model.ColorValue
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FlexDirection
import com.mistbell.tavern.android.render.model.FlexWrap
import com.mistbell.tavern.android.render.model.ImageSpec
import com.mistbell.tavern.android.render.model.JustifyContent
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.OverflowValue
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.VisibilityValue

/**
 * 布局规格树（M3，纯 Kotlin 可单测）：实例化树 + 级联样式 → 每节点「布局属性集 + 叶子类型」。
 *
 * 叶子类型：[LayoutLeaf.Container]（HTML 卡段结构）/ [LayoutLeaf.Text]（TextBlock 一等内容）/
 * [LayoutLeaf.Image]（img）。display:none 与 data-bind:visible=false 子树在此剪枝；
 * visibility:hidden 保留布局（占位）但绘制层跳过（CSS 语义）。
 */
data class LayoutSpec(
    val props: LayoutProps,
    val leaf: LayoutLeaf,
    /** 诊断/调试用：来源元素标签（合成容器为 "div"） */
    val sourceTag: String? = null,
)

/**
 * 布局属性全集（ResolvedStyle 的盒模型/弹性布局/视觉子集）。
 * Length 保留 PERCENT/AUTO 原单位（PERCENT → Yoga 百分比、AUTO → 未定义/自动）；
 * 级联产物中的 EM 已折算为 PX。
 */
data class LayoutProps(
    val flexDirection: FlexDirection = FlexDirection.COLUMN,
    val flexWrap: FlexWrap = FlexWrap.NOWRAP,
    val gap: Length? = null,
    val justifyContent: JustifyContent = JustifyContent.FLEX_START,
    /** null → 不设置（Yoga 默认 STRETCH） */
    val alignItems: AlignItems? = null,
    val marginTop: Length? = null,
    val marginRight: Length? = null,
    val marginBottom: Length? = null,
    val marginLeft: Length? = null,
    val paddingTop: Length? = null,
    val paddingRight: Length? = null,
    val paddingBottom: Length? = null,
    val paddingLeft: Length? = null,
    val width: Length? = null,
    val height: Length? = null,
    val minWidth: Length? = null,
    val maxWidth: Length? = null,
    val minHeight: Length? = null,
    val maxHeight: Length? = null,
    /** 边框宽（px；级联已折算） */
    val borderWidth: Float = 0f,
    val borderColor: ColorValue? = null,
    /** 圆角（px，单值统一圆角） */
    val borderRadius: Float = 0f,
    val backgroundColor: ColorValue? = null,
    /** 自身 opacity（未声明取继承有效值），绘制层乘入 */
    val opacity: Float = 1f,
    /** overflow:hidden：子内容裁剪 */
    val overflowHidden: Boolean = false,
    /** visibility:hidden：占位不绘制 */
    val visibilityHidden: Boolean = false,
)

/** 叶子类型：容器（递归）/ 一等内容文本块 / 图片 */
sealed class LayoutLeaf {
    data class Container(val children: List<LayoutSpec>) : LayoutLeaf()

    data class Text(val block: TextBlock) : LayoutLeaf()

    data class Image(val image: ImageSpec) : LayoutLeaf()
}

/**
 * 布局规格构建器：[RenderNode] 树（InlineCollector 产物）→ [LayoutSpec] 树。
 * 纯函数、剪枝在构建期完成（display:none / 不可见子树不进规格树），可 JVM 单测。
 */
object LayoutSpecBuilder {
    /** 根构建：整棵树剪枝后为空（不可见）→ null（宿主按空内容处理） */
    fun build(root: RenderNode.Element): LayoutSpec? = buildNode(root)

    /** 单节点构建；null = 该节点（含整子树）被剪除 */
    private fun buildNode(element: RenderNode.Element): LayoutSpec? {
        if (isPruned(element.styled)) return null
        val source = element.styled.node as? TemplateNode.ElementNode
        val props = propsOf(element.styled)
        val leaf =
            when (source?.tag) {
                IMG_TAG -> {
                    val attrs = requireNotNull(source).attrs
                    LayoutLeaf.Image(ImageSpec(src = attrs[SRC_ATTR].orEmpty(), alt = attrs[ALT_ATTR]))
                }
                else -> LayoutLeaf.Container(element.children.mapNotNull(::visit))
            }
        return LayoutSpec(props, leaf, source?.tag)
    }

    /** 子节点访问：TextLeaf → 合成文本规格节点；Element → 递归构建；裸文本已被收集器消化 */
    private fun visit(node: RenderNode): LayoutSpec? =
        when (node) {
            is RenderNode.Element -> buildNode(node)
            is RenderNode.TextLeaf -> LayoutSpec(LayoutProps(), LayoutLeaf.Text(node.block))
            is RenderNode.InlineText -> null
        }

    /** 剪枝判定：绑定不可见 或 display:none */
    private fun isPruned(styled: StyledNode): Boolean {
        val hiddenByBinding = styled.bindings?.visible == false
        val hiddenByDisplay = styled.style.display == DisplayValue.NONE
        return hiddenByBinding || hiddenByDisplay
    }

    /** 样式 → 布局属性映射（未声明字段取默认值；opacity 取继承有效值） */
    private fun propsOf(styled: StyledNode): LayoutProps {
        val style = styled.style
        return LayoutProps(
            flexDirection = style.flexDirection ?: FlexDirection.COLUMN,
            flexWrap = style.flexWrap ?: FlexWrap.NOWRAP,
            gap = style.gap,
            justifyContent = style.justifyContent ?: JustifyContent.FLEX_START,
            alignItems = style.alignItems,
            marginTop = concrete(style.marginTop),
            marginRight = concrete(style.marginRight),
            marginBottom = concrete(style.marginBottom),
            marginLeft = concrete(style.marginLeft),
            paddingTop = concrete(style.paddingTop),
            paddingRight = concrete(style.paddingRight),
            paddingBottom = concrete(style.paddingBottom),
            paddingLeft = concrete(style.paddingLeft),
            width = concrete(style.width),
            height = concrete(style.height),
            minWidth = concrete(style.minWidth),
            maxWidth = concrete(style.maxWidth),
            minHeight = concrete(style.minHeight),
            maxHeight = concrete(style.maxHeight),
            borderWidth = style.borderWidth?.value ?: 0f,
            borderColor = style.borderColor,
            borderRadius = style.borderRadius?.value ?: 0f,
            backgroundColor = style.backgroundColor,
            opacity = style.opacity ?: styled.inherited.opacity,
            overflowHidden = style.overflow == OverflowValue.HIDDEN,
            visibilityHidden = style.visibility == VisibilityValue.HIDDEN,
        )
    }

    /** EM 已在级联折算；AUTO/PERCENT 保留原单位交 YogaTreeBuilder 决定 Yoga 入参 */
    private fun concrete(length: Length?): Length? =
        when (length?.unit) {
            null, CssUnit.AUTO, CssUnit.PERCENT, CssUnit.PX -> length
            CssUnit.EM -> Length.px(length.value)
        }

    private const val IMG_TAG = "img"
    private const val SRC_ATTR = "src"
    private const val ALT_ATTR = "alt"
}
