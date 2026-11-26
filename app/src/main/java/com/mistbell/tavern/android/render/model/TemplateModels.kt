package com.mistbell.tavern.android.render.model

/*
 * 主题框架 v0 协议解析层的数据模型（M2）。
 *
 * 全部不可变、纯 Kotlin、无 Android/Compose 依赖——jsoup 解析产物（TemplateDocument）经
 * 样式级联（StyleResolver）成为 StyledNode 树，绑定解析（BindingResolver）就地替换文本，
 * 最终供 M3 布局/绘制消费。诊断一律走返回值，引擎永不上抛。
 */

/** 诊断级别 */
enum class DiagnosticSeverity {
    INFO,

    /** 容错丢弃类：内容按协议被丢弃，渲染继续 */
    WARN,

    /** 致命类：文档结构无法成立（v0 极少使用，容错解析几乎总是产出 WARN） */
    ERROR,
}

/** 稳定诊断码（字符串常量，跨版本稳定，宿主/测试按码断言而非按 message 断言） */
object DiagnosticCodes {
    /** 白名单外元素被处理（整树丢弃或 unwrap），[Diagnostic.target] 为元素名 */
    const val DROP_ELEMENT = "DROP_ELEMENT"

    /** 白名单外属性被丢弃，[Diagnostic.target] 为属性名 */
    const val DROP_ATTRIBUTE = "DROP_ATTRIBUTE"

    /** 白名单外 CSS 属性被丢弃，[Diagnostic.target] 为属性名 */
    const val UNKNOWN_CSS_PROP = "UNKNOWN_CSS_PROP"

    /** CSS 结构解析失败（畸形块/不支持的选择器/@ 规则等），该声明或规则被丢弃 */
    const val PARSE_CSS_FAILED = "PARSE_CSS_FAILED"

    /** 已知属性但值非法（类型不符/超范围），该声明被丢弃 */
    const val UNKNOWN_CSS_VALUE = "UNKNOWN_CSS_VALUE"

    /** var() 引用的变量未定义且无回退值，该声明被丢弃 */
    const val UNDEFINED_VAR = "UNDEFINED_VAR"

    /** slot 元素 name 缺失或不在合法集合内，整子树丢弃 */
    const val INVALID_SLOT = "INVALID_SLOT"

    /** {{state.路径}} 未命中变量，插值为空串 */
    const val MISS_VARIABLE = "MISS_VARIABLE"

    /** data-bind 属性存在但值缺路径（空串），绑定按失败语义处理 */
    const val INVALID_BIND = "INVALID_BIND"

    /** 渲染引擎内部异常（M3）：请求按 fallback 标志回落 WebView/legacy，引擎绝不上抛 */
    const val RENDER_FAILED = "RENDER_FAILED"

    /** 图片加载/解码失败，该图占位跳过（渲染继续） */
    const val IMAGE_FAILED = "IMAGE_FAILED"
}

/**
 * 一条容错诊断。引擎不抛异常、不打日志，所有协议外构造以 [Diagnostic] 返回；
 * [target] 携带被丢弃对象名（元素/属性/变量路径），供 TemplateValidator 与测试按码+对象定位。
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val target: String? = null,
)

/** 模板树节点（HTML 子集 + slot），解析产物，全部不可变 */
sealed class TemplateNode {
    /**
     * 元素节点。[attrs] 只含白名单属性（class/style/data-bind:text/data-bind:visible/href/src/alt），
     * key 一律小写。
     */
    data class ElementNode(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val children: List<TemplateNode> = emptyList(),
    ) : TemplateNode()

    /** 文本节点，文本保持原样（宏/状态插值由 BindingResolver 处理） */
    data class TextNode(val text: String) : TemplateNode()

    /** 槽位节点，[name] ∈ narrative/panels/thinking/sender/timestamp（解析期已校验） */
    data class SlotNode(val name: String) : TemplateNode()
}

/** 模板解析产物：一棵以 [tree] 为根的白名单树 + 模板版本 + 全部诊断 */
data class TemplateDocument(
    val tree: TemplateNode.ElementNode,
    val templateVersion: Int = 1,
    val diagnostics: List<Diagnostic> = emptyList(),
)

// ---- 值类型 ----

/** 长度单位。PX 与 dp 等价（引擎统一按 dp 光栅）；PERCENT 相对父容器；EM 相对字号（级联期已折算） */
enum class CssUnit { PX, PERCENT, EM, AUTO }

/**
 * CSS 长度值。[unit] 为 [CssUnit.AUTO] 时 [value] 无意义（恒 0）。
 * CssParser 产物可含 EM/PERCENT；StyleResolver 级联后 EM 一律折算为 PX
 *（em 相对元素自身 font-size，font-size 的 em/% 相对父级，根基线 16px），仅盒模型 PERCENT/AUTO 保留原单位。
 */
data class Length(val value: Float, val unit: CssUnit) {
    companion object {
        fun px(value: Float) = Length(value, CssUnit.PX)

        /** AUTO 长度 */
        val AUTO = Length(0f, CssUnit.AUTO)
    }
}

/** CSS 颜色值，#rgb/#rgba/#rrggbb/#rrggbbaa 解析产物，0xAARRGGBB */
data class ColorValue(val argb: Long)

/** display 值（Yoga 节点类型，无 inline——行内元素由 M3 文本层处理） */
enum class DisplayValue { BLOCK, FLEX, NONE }

enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }

enum class FlexWrap { NOWRAP, WRAP, WRAP_REVERSE }

enum class JustifyContent { FLEX_START, CENTER, FLEX_END, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }

enum class AlignItems { FLEX_START, CENTER, FLEX_END, STRETCH, BASELINE }

enum class TextAlign { LEFT, CENTER, RIGHT }

enum class OverflowValue { HIDDEN, VISIBLE }

enum class VisibilityValue { VISIBLE, HIDDEN }

/** font-style 值（i/em 斜体需要；设计文档 §5 表格未列，为默认样式表补齐——见 M2 报告偏差） */
enum class FontStyleValue { NORMAL, ITALIC }

/** text-decoration 值（u/s/a 下划线需要；同上为默认样式表补齐） */
enum class TextDecorationValue { NONE, UNDERLINE, LINE_THROUGH }

/**
 * 强类型样式（v0 属性白名单全集，设计文档 §5）。字段为空 = 未指定（继承/默认值由消费方决定）。
 * 级联产物中 EM 已折算为 PX（见 [Length]）。
 */
data class ResolvedStyle(
    val display: DisplayValue? = null,
    val flexDirection: FlexDirection? = null,
    val flexWrap: FlexWrap? = null,
    val gap: Length? = null,
    val justifyContent: JustifyContent? = null,
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
    val borderWidth: Length? = null,
    val borderColor: ColorValue? = null,
    val borderRadius: Length? = null,
    val backgroundColor: ColorValue? = null,
    val color: ColorValue? = null,
    val opacity: Float? = null,
    val fontSize: Length? = null,
    val fontWeight: Int? = null,
    val fontFamily: String? = null,
    val lineHeight: Length? = null,
    val textAlign: TextAlign? = null,
    val fontStyle: FontStyleValue? = null,
    val textDecoration: TextDecorationValue? = null,
    val overflow: OverflowValue? = null,
    val visibility: VisibilityValue? = null,
)

/**
 * 可继承样式的有效值链（沿树自上而下传递）。fontSize/lineHeight 以折算后的 px 数值携带，
 * 供子节点 em 折算与布局使用；未指定的可继承属性透传父级有效值。
 */
data class InheritedStyle(
    val color: ColorValue? = null,
    val fontSizePx: Float = DEFAULT_FONT_SIZE_PX,
    val fontFamily: String? = null,
    val fontWeight: Int? = null,
    val lineHeightPx: Float? = null,
    val textAlign: TextAlign? = null,
    val opacity: Float = DEFAULT_OPACITY,
) {
    companion object {
        /** 根节点基线字号：em/% 折算与默认排版均以此为基准（设计文档 §5.3） */
        const val DEFAULT_FONT_SIZE_PX = 16f

        /** 根节点基线透明度 */
        const val DEFAULT_OPACITY = 1f

        /** 根节点入参（无父级可继承） */
        val ROOT = InheritedStyle()
    }
}

/** 节点绑定（只读两条 + 解析后的可见性），由 BindingResolver 从 data-bind 属性解析挂载 */
data class NodeBindings(
    /** data-bind:text 的状态路径（缺路径/空值时为 null） */
    val textPath: String? = null,
    /** data-bind:visible 的状态路径（缺路径/空值时为 null） */
    val visiblePath: String? = null,
    /**
     * 可见性结论：visiblePath 命中且值为真 → true；缺路径、变量缺失、值为假 → false。
     * 就地解析成布尔值，M3 布局直接消费，无需再携带绑定上下文。
     */
    val visible: Boolean = true,
)

/**
 * 样式已解析树（M3 消费入口）。[style] 为该节点自身声明（级联后），[inherited] 为沿树传递的
 * 可继承有效值；TextNode 的 [style] 恒为空样式、样式观感看 [inherited]。
 */
data class StyledNode(
    val node: TemplateNode,
    val style: ResolvedStyle,
    val inherited: InheritedStyle,
    val bindings: NodeBindings? = null,
    val children: List<StyledNode> = emptyList(),
)

/** 主题 token 桥：--mt-primary 等 10 色的注入值（深浅模式由宿主选择注入哪套，引擎不感知） */
data class ThemeVars(val vars: Map<String, String> = emptyMap()) {
    /** 取变量原始字符串（未注入即未定义） */
    operator fun get(name: String): String? = vars[name]

    companion object {
        /** 设计文档 §5.4 的 10 个规范变量名（宿主按此清单注入） */
        val CANONICAL_NAMES =
            listOf(
                "--mt-primary",
                "--mt-background",
                "--mt-surface",
                "--mt-surface-variant",
                "--mt-user-bubble",
                "--mt-assistant-bubble",
                "--mt-on-user-bubble",
                "--mt-on-assistant-bubble",
                "--mt-on-background",
                "--mt-on-surface",
            )

        val EMPTY = ThemeVars()
    }
}

/** 绑定数据源：variables 来自宿主 MVU KV 扁平化，macros 来自宿主 {{char}}/{{user}} 宏 */
data class BindingContext(
    val variables: Map<String, String> = emptyMap(),
    val macros: Map<String, String> = emptyMap(),
)

/** TemplateValidator 产物：可渲染，或携带原因列表回落 WebView/legacy */
sealed class RenderDecision {
    object Renderable : RenderDecision() {
        override fun toString() = "Renderable"
    }

    data class NeedsFallback(val reasons: List<String>) : RenderDecision()
}
