package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.AlignItems
import com.mistbell.tavern.android.render.model.ColorValue
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FlexDirection
import com.mistbell.tavern.android.render.model.FlexWrap
import com.mistbell.tavern.android.render.model.FontStyleValue
import com.mistbell.tavern.android.render.model.InheritedStyle
import com.mistbell.tavern.android.render.model.JustifyContent
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.OverflowValue
import com.mistbell.tavern.android.render.model.ResolvedStyle
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextDecorationValue
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.model.VisibilityValue

/** 级联产物：样式已解析树 + 级联期诊断（值非法/未知属性/inline style 解析告警） */
data class ResolvedTemplate(val tree: StyledNode, val diagnostics: List<Diagnostic>)

/** 祖先链节点签名：后代选择器匹配只需要元素名、class 集合与 id（协议不注入 id，恒 null） */
private data class NodeSignature(
    val tag: String,
    val classes: Set<String>,
    val id: String?,
)

/** 一条候选声明：携带命中选择器特异性与规则源顺序，级联按此排序 */
private data class MatchedDeclaration(
    val specificity: SelectorSpecificity,
    val order: Int,
    val declaration: CssDeclaration,
)

private const val SNIPPET_LIMIT = 40
private val WHITESPACE_RUN = Regex("\\s+")
private val LENGTH_PATTERN = Regex("""(-?(?:\d+(?:\.\d+)?|\.\d+))(px|dp|%|em|auto)?""")
private val UNITLESS_NUMBER = Regex("""-?(?:\d+(?:\.\d+)?|\.\d+)""")

/** 颜色 hex 位数（#rgb/#rgba/#rrggbb/#rrggbbaa）与折算常量 */
private const val HEX_SHORT_RGB = 3
private const val HEX_SHORT_RGBA = 4
private const val HEX_LONG_RGB = 6
private const val HEX_LONG_RGBA = 8
private const val HEX_RADIX = 16
private const val BYTE_SHIFT = 8
private const val ALPHA_BYTE_SHIFT = 24
private const val BYTE_MASK = 0xFFL
private const val OPAQUE_HEX_SUFFIX = "ff"

/** opacity 与 font-weight 的合法范围；百分比折算分母；字重关键字 */
private const val MIN_OPACITY = 0f
private const val MAX_OPACITY = 1f
private const val MIN_FONT_WEIGHT = 100
private const val MAX_FONT_WEIGHT = 900
private const val PERCENT_DENOMINATOR = 100f
private const val WEIGHT_NORMAL = 400
private const val WEIGHT_BOLD = 700

/** 盒简写最多 4 值；第 4 值（left）的取值下标 */
private const val BOX_VALUE_LIMIT = 4
private const val BOX_INDEX_LEFT = 3

private val FONT_WEIGHT_KEYWORDS = mapOf("normal" to WEIGHT_NORMAL, "bold" to WEIGHT_BOLD)
private val FONT_FAMILIES = setOf("sans-serif", "serif", "monospace")
private const val BORDER_STYLE_SOLID = "solid"

private val DISPLAY_VALUES =
    mapOf("block" to DisplayValue.BLOCK, "flex" to DisplayValue.FLEX, "none" to DisplayValue.NONE)
private val FLEX_DIRECTIONS =
    mapOf(
        "row" to FlexDirection.ROW,
        "row-reverse" to FlexDirection.ROW_REVERSE,
        "column" to FlexDirection.COLUMN,
        "column-reverse" to FlexDirection.COLUMN_REVERSE,
    )
private val FLEX_WRAPS =
    mapOf("nowrap" to FlexWrap.NOWRAP, "wrap" to FlexWrap.WRAP, "wrap-reverse" to FlexWrap.WRAP_REVERSE)
private val JUSTIFY_CONTENTS =
    mapOf(
        "flex-start" to JustifyContent.FLEX_START,
        "center" to JustifyContent.CENTER,
        "flex-end" to JustifyContent.FLEX_END,
        "space-between" to JustifyContent.SPACE_BETWEEN,
        "space-around" to JustifyContent.SPACE_AROUND,
        "space-evenly" to JustifyContent.SPACE_EVENLY,
    )
private val ALIGN_ITEMS =
    mapOf(
        "flex-start" to AlignItems.FLEX_START,
        "center" to AlignItems.CENTER,
        "flex-end" to AlignItems.FLEX_END,
        "stretch" to AlignItems.STRETCH,
        "baseline" to AlignItems.BASELINE,
    )
private val TEXT_ALIGNS = mapOf("left" to TextAlign.LEFT, "center" to TextAlign.CENTER, "right" to TextAlign.RIGHT)
private val FONT_STYLES = mapOf("normal" to FontStyleValue.NORMAL, "italic" to FontStyleValue.ITALIC)
private val TEXT_DECORATIONS =
    mapOf(
        "none" to TextDecorationValue.NONE,
        "underline" to TextDecorationValue.UNDERLINE,
        "line-through" to TextDecorationValue.LINE_THROUGH,
    )
private val OVERFLOW_VALUES = mapOf("hidden" to OverflowValue.HIDDEN, "visible" to OverflowValue.VISIBLE)
private val VISIBILITY_VALUES = mapOf("visible" to VisibilityValue.VISIBLE, "hidden" to VisibilityValue.HIDDEN)

/** 值解析签名：原始串 → 强类型值；null = 值非法（声明丢弃并告警） */
private typealias ValueParser = (String) -> Any?

/** 属性规格：值解析器 + 归槽键（margin/padding/border 简写在归槽时展开为多槽位） */
private class PropSpec(val parse: ValueParser, val slot: String)

private val LENGTH_MARGIN: ValueParser = { StyleValues.length(it, allowNegative = true) }
private val LENGTH_POSITIVE: ValueParser = { StyleValues.length(it, allowNegative = false) }
private val LENGTH_NO_AUTO: ValueParser =
    { StyleValues.length(it, allowNegative = false)?.takeUnless { length -> length.unit == CssUnit.AUTO } }

private const val SLOT_MARGIN = "margin"
private const val SLOT_PADDING = "padding"
private const val SLOT_BORDER = "border"
private const val SLOT_BORDER_STYLE = "borderStyle"

private val SIDE_SUFFIXES = listOf("Top", "Right", "Bottom", "Left")

/** 属性白名单 → 值解析器 + 归槽键（设计文档 §5.2 表格；未知属性在 apply 处丢弃并告警） */
private val PROPERTY_SPECS: Map<String, PropSpec> =
    mapOf(
        "display" to PropSpec({ StyleValues.parseEnum(it, DISPLAY_VALUES) }, "display"),
        "flex-direction" to PropSpec({ StyleValues.parseEnum(it, FLEX_DIRECTIONS) }, "flexDirection"),
        "flex-wrap" to PropSpec({ StyleValues.parseEnum(it, FLEX_WRAPS) }, "flexWrap"),
        "gap" to PropSpec(LENGTH_NO_AUTO, "gap"),
        "justify-content" to PropSpec({ StyleValues.parseEnum(it, JUSTIFY_CONTENTS) }, "justifyContent"),
        "align-items" to PropSpec({ StyleValues.parseEnum(it, ALIGN_ITEMS) }, "alignItems"),
        "margin" to PropSpec({ StyleValues.box(it, allowNegative = true) }, SLOT_MARGIN),
        "margin-top" to PropSpec(LENGTH_MARGIN, "marginTop"),
        "margin-right" to PropSpec(LENGTH_MARGIN, "marginRight"),
        "margin-bottom" to PropSpec(LENGTH_MARGIN, "marginBottom"),
        "margin-left" to PropSpec(LENGTH_MARGIN, "marginLeft"),
        "padding" to PropSpec({ StyleValues.box(it, allowNegative = false) }, SLOT_PADDING),
        "padding-top" to PropSpec(LENGTH_POSITIVE, "paddingTop"),
        "padding-right" to PropSpec(LENGTH_POSITIVE, "paddingRight"),
        "padding-bottom" to PropSpec(LENGTH_POSITIVE, "paddingBottom"),
        "padding-left" to PropSpec(LENGTH_POSITIVE, "paddingLeft"),
        "width" to PropSpec(LENGTH_POSITIVE, "width"),
        "height" to PropSpec(LENGTH_POSITIVE, "height"),
        "min-width" to PropSpec(LENGTH_NO_AUTO, "minWidth"),
        "max-width" to PropSpec(LENGTH_NO_AUTO, "maxWidth"),
        "min-height" to PropSpec(LENGTH_NO_AUTO, "minHeight"),
        "max-height" to PropSpec(LENGTH_NO_AUTO, "maxHeight"),
        "border" to PropSpec(StyleValues::border, SLOT_BORDER),
        "border-width" to PropSpec(LENGTH_NO_AUTO, "borderWidth"),
        "border-color" to PropSpec(StyleValues::color, "borderColor"),
        "border-radius" to PropSpec(LENGTH_NO_AUTO, "borderRadius"),
        "border-style" to PropSpec(::borderStyleOnlySolid, SLOT_BORDER_STYLE),
        "background-color" to PropSpec(StyleValues::color, "backgroundColor"),
        "color" to PropSpec(StyleValues::color, "color"),
        "opacity" to PropSpec(StyleValues::number, "opacity"),
        "font-size" to PropSpec(LENGTH_NO_AUTO, "fontSize"),
        "font-weight" to PropSpec(StyleValues::weight, "fontWeight"),
        "font-family" to PropSpec(StyleValues::fontFamily, "fontFamily"),
        "line-height" to PropSpec(StyleValues::lineHeight, "lineHeight"),
        "text-align" to PropSpec({ StyleValues.parseEnum(it, TEXT_ALIGNS) }, "textAlign"),
        "font-style" to PropSpec({ StyleValues.parseEnum(it, FONT_STYLES) }, "fontStyle"),
        "text-decoration" to PropSpec({ StyleValues.parseEnum(it, TEXT_DECORATIONS) }, "textDecoration"),
        "overflow" to PropSpec({ StyleValues.parseEnum(it, OVERFLOW_VALUES) }, "overflow"),
        "visibility" to PropSpec({ StyleValues.parseEnum(it, VISIBILITY_VALUES) }, "visibility"),
    )

/** border-style 仅接受 solid（设计文档 §5.2：style 仅 solid） */
private fun borderStyleOnlySolid(raw: String): String? = raw.trim().lowercase().takeIf { it == BORDER_STYLE_SOLID }

/** 值解析入口统一走 parseOrReject：解析失败记录 UNKNOWN_CSS_VALUE 诊断并返回 null（声明被丢弃） */
private fun <T> parseOrReject(
    diagnostics: MutableList<Diagnostic>,
    property: String,
    rawValue: String,
    parse: (String) -> T?,
): T? {
    val parsed = parse(rawValue)
    if (parsed == null) {
        diagnostics +=
            Diagnostic(
                DiagnosticSeverity.WARN,
                DiagnosticCodes.UNKNOWN_CSS_VALUE,
                "属性「$property」的值「${rawValue.take(SNIPPET_LIMIT)}」不在值语法内，已丢弃",
                property,
            )
    }
    return parsed
}

/** em 相对自身字号折算为 PX（PERCENT/AUTO 保留原单位交 M3 处理） */
private fun concrete(
    length: Length?,
    fontPx: Float,
): Length? =
    when (length?.unit) {
        null -> null
        CssUnit.EM -> Length.px(length.value * fontPx)
        else -> length
    }

/** #rgb/#rgba/#rrggbb/#rrggbbaa 统一展开为 RRGGBBAA 串 */
private fun expandHex(digits: String): String? =
    when (digits.length) {
        HEX_SHORT_RGB -> digits.map { ch -> "$ch$ch" }.joinToString("") + OPAQUE_HEX_SUFFIX
        HEX_SHORT_RGBA -> digits.map { ch -> "$ch$ch" }.joinToString("")
        HEX_LONG_RGB -> digits + OPAQUE_HEX_SUFFIX
        HEX_LONG_RGBA -> digits
        else -> null
    }

/** RRGGBBAA → 0xAARRGGBB */
private fun toColorValue(rrggbbaa: Long): ColorValue {
    val alpha = rrggbbaa and BYTE_MASK
    val rgb = rrggbbaa ushr BYTE_SHIFT
    return ColorValue((alpha shl ALPHA_BYTE_SHIFT) or rgb)
}

/** CSS 值类型解析（纯函数集合）：Length/颜色/枚举/字重/字族/行高/盒展开/opacity/border 简写 */
private object StyleValues {
    /** Length：px 与 dp 等价；[allowNegative] 仅 margin 场景为真 */
    fun length(
        raw: String,
        allowNegative: Boolean,
    ): Length? {
        val match = LENGTH_PATTERN.matchEntire(raw.trim().lowercase()) ?: return null
        val (number, unit) = match.destructured
        val rejected = !allowNegative && number.startsWith("-")
        val value = if (rejected) null else number.toFloatOrNull()
        return when {
            value == null -> null
            unit == "auto" -> Length.AUTO
            unit == "%" -> Length(value, CssUnit.PERCENT)
            unit == "em" -> Length(value, CssUnit.EM)
            // "", px, dp（其余单位已被正则限定排除）
            else -> Length(value, CssUnit.PX)
        }
    }

    /** 颜色：#rgb/#rgba/#rrggbb/#rrggbbaa → 0xAARRGGBB；具名颜色不支持 */
    fun color(raw: String): ColorValue? =
        raw.trim()
            .lowercase()
            .takeIf { it.startsWith("#") }
            ?.let { it.substring(1) }
            ?.takeIf { digits -> digits.isNotEmpty() && digits.toLongOrNull(HEX_RADIX) != null }
            ?.let(::expandHex)
            ?.toLongOrNull(HEX_RADIX)
            ?.let(::toColorValue)

    /** 枚举值查表（大小写不敏感） */
    fun <T : Enum<T>> parseEnum(
        raw: String,
        values: Map<String, T>,
    ): T? = values[raw.trim().lowercase()]

    /** 字重：normal/bold 关键字或 100-900 数字 */
    fun weight(raw: String): Int? {
        val value = FONT_WEIGHT_KEYWORDS[raw.trim().lowercase()] ?: raw.trim().toIntOrNull()
        return value?.takeIf { it in MIN_FONT_WEIGHT..MAX_FONT_WEIGHT }
    }

    /** 字族：仅内置字库白名单（引用字符串/其他字族不支持） */
    fun fontFamily(raw: String): String? = raw.trim().lowercase().takeIf { it in FONT_FAMILIES }

    /** 行高：px/%/em 或无单位倍数（倍数按 EM 因子携带，相对自身字号折算） */
    fun lineHeight(raw: String): Length? {
        val trimmed = raw.trim().lowercase()
        if (UNITLESS_NUMBER.matchEntire(trimmed) != null) {
            return trimmed.toFloatOrNull()?.let { Length(it, CssUnit.EM) }
        }
        return length(trimmed, allowNegative = false)?.takeUnless { it.unit == CssUnit.AUTO }
    }

    /** margin/padding 简写：1-4 值展开为 [top, right, bottom, left] */
    fun box(
        raw: String,
        allowNegative: Boolean,
    ): List<Length>? {
        val values =
            raw.trim().lowercase().split(WHITESPACE_RUN).filter { it.isNotEmpty() }.map { length(it, allowNegative) }
        val invalid = values.isEmpty() || values.size > BOX_VALUE_LIMIT || values.any { it == null }
        if (invalid) return null
        val sides = values.map { requireNotNull(it) }
        val top = sides.first()
        val right = sides.getOrElse(1) { top }
        val bottom = sides.getOrElse(2) { top }
        val left = sides.getOrElse(BOX_INDEX_LEFT) { right }
        return listOf(top, right, bottom, left)
    }

    /** opacity：0..1 浮点数 */
    fun number(raw: String): Float? = raw.trim().toFloatOrNull()?.takeIf { it in MIN_OPACITY..MAX_OPACITY }

    /** border 简写：width / solid / color 任意顺序组合（style 仅 solid；none 按 0 宽处理） */
    fun border(raw: String): Pair<Length?, ColorValue?>? =
        raw.trim()
            .lowercase()
            .split(WHITESPACE_RUN)
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.fold(Pair<Length?, ColorValue?>(null, null)) { acc, part ->
                val parsed = parseBorderPart(part) ?: return null
                Pair(parsed.first ?: acc.first, parsed.second ?: acc.second)
            }

    /** border 简写的一段：solid→无值、none/hidden→0 宽、颜色或长度；两者皆非 → null */
    private fun parseBorderPart(part: String): Pair<Length?, ColorValue?>? =
        when {
            part == BORDER_STYLE_SOLID -> Pair(null, null)
            part == "none" || part == "hidden" -> Pair(Length.px(0f), null)
            else -> {
                val color = color(part)
                val width = length(part, allowNegative = false)?.takeUnless { it.unit == CssUnit.AUTO }
                Pair(width, color).takeIf { width != null || color != null }
            }
        }
}

/**
 * 样式累积器：命中声明按 (特异性, 源顺序) 排序后依次经 [PROPERTY_SPECS] 解析归槽，后写胜出；
 * inline style 最后应用。finalize 时做单位折算：font-size 的 em/% 相对父字号、其余长度 em 相对
 * 自身字号，全部产出 PX；PERCENT/AUTO 保留原单位交 M3 布局消费。
 */
private class StyleAccumulator(private val diagnostics: MutableList<Diagnostic>) {
    private val slots = HashMap<String, Any>()
    private var effectiveFontSizePx = 0f
    private var effectiveLineHeightPx: Float? = null

    /** 应用一条声明；返回 false = 属性不在白名单（未知属性），true = 已消费（值非法也已记诊断） */
    fun apply(
        property: String,
        rawValue: String,
    ): Boolean {
        val spec = PROPERTY_SPECS[property] ?: return false
        val parsed = parseOrReject(diagnostics, property, rawValue, spec.parse)
        if (parsed != null) store(spec.slot, parsed)
        return true
    }

    /** 归槽：margin/padding 简写展开四边，border 简写展开宽/色，其余单值直存 */
    private fun store(
        slot: String,
        parsed: Any,
    ) {
        when (slot) {
            SLOT_MARGIN, SLOT_PADDING -> storeSides(slot, parsed as List<Length>)
            SLOT_BORDER -> {
                val (width, color) = parsed as Pair<Length?, ColorValue?>
                width?.let { slots["borderWidth"] = it }
                color?.let { slots["borderColor"] = it }
            }
            else -> slots[slot] = parsed
        }
    }

    private fun storeSides(
        slot: String,
        sides: List<Length>,
    ) {
        SIDE_SUFFIXES.forEachIndexed { index, suffix -> slots["$slot$suffix"] = sides[index] }
    }

    /**
     * 产出不可变样式。单位折算：font-size 的 em/% 相对 [parentFontSizePx]（根节点基线 16px），
     * 其余长度 em 相对折算后的自身字号；line-height 的 %/em/无单位倍数同样按自身字号折算，auto 视为未指定。
     */
    fun finalize(parentFontSizePx: Float): ResolvedStyle {
        val sizeSpec = slots["fontSize"] as? Length
        effectiveFontSizePx =
            when (sizeSpec?.unit) {
                null, CssUnit.AUTO -> parentFontSizePx
                CssUnit.EM -> parentFontSizePx * sizeSpec.value
                CssUnit.PERCENT -> parentFontSizePx * sizeSpec.value / PERCENT_DENOMINATOR
                CssUnit.PX -> sizeSpec.value
            }
        effectiveLineHeightPx =
            (slots["lineHeight"] as? Length)?.let { spec ->
                when (spec.unit) {
                    CssUnit.PX -> spec.value
                    CssUnit.EM -> effectiveFontSizePx * spec.value
                    CssUnit.PERCENT -> effectiveFontSizePx * spec.value / PERCENT_DENOMINATOR
                    CssUnit.AUTO -> null
                }
            }
        return build()
    }

    private fun build(): ResolvedStyle {
        val fontPx = effectiveFontSizePx
        return ResolvedStyle(
            display = slots["display"] as? DisplayValue,
            flexDirection = slots["flexDirection"] as? FlexDirection,
            flexWrap = slots["flexWrap"] as? FlexWrap,
            gap = concrete(slots["gap"] as? Length, fontPx),
            justifyContent = slots["justifyContent"] as? JustifyContent,
            alignItems = slots["alignItems"] as? AlignItems,
            marginTop = concrete(slots["marginTop"] as? Length, fontPx),
            marginRight = concrete(slots["marginRight"] as? Length, fontPx),
            marginBottom = concrete(slots["marginBottom"] as? Length, fontPx),
            marginLeft = concrete(slots["marginLeft"] as? Length, fontPx),
            paddingTop = concrete(slots["paddingTop"] as? Length, fontPx),
            paddingRight = concrete(slots["paddingRight"] as? Length, fontPx),
            paddingBottom = concrete(slots["paddingBottom"] as? Length, fontPx),
            paddingLeft = concrete(slots["paddingLeft"] as? Length, fontPx),
            width = concrete(slots["width"] as? Length, fontPx),
            height = concrete(slots["height"] as? Length, fontPx),
            minWidth = concrete(slots["minWidth"] as? Length, fontPx),
            maxWidth = concrete(slots["maxWidth"] as? Length, fontPx),
            minHeight = concrete(slots["minHeight"] as? Length, fontPx),
            maxHeight = concrete(slots["maxHeight"] as? Length, fontPx),
            borderWidth = concrete(slots["borderWidth"] as? Length, fontPx),
            borderColor = slots["borderColor"] as? ColorValue,
            borderRadius = concrete(slots["borderRadius"] as? Length, fontPx),
            backgroundColor = slots["backgroundColor"] as? ColorValue,
            color = slots["color"] as? ColorValue,
            opacity = slots["opacity"] as? Float,
            fontSize = Length(fontPx, CssUnit.PX),
            fontWeight = slots["fontWeight"] as? Int,
            fontFamily = slots["fontFamily"] as? String,
            lineHeight = effectiveLineHeightPx?.let { Length(it, CssUnit.PX) },
            textAlign = slots["textAlign"] as? TextAlign,
            fontStyle = slots["fontStyle"] as? FontStyleValue,
            textDecoration = slots["textDecoration"] as? TextDecorationValue,
            overflow = slots["overflow"] as? OverflowValue,
            visibility = slots["visibility"] as? VisibilityValue,
        )
    }

    /** 子节点继承链：可继承属性 自身声明 ?: 父级有效值（盒模型/背景/边框等不继承） */
    fun childInherited(parent: InheritedStyle): InheritedStyle =
        InheritedStyle(
            color = slots["color"] as? ColorValue ?: parent.color,
            fontSizePx = effectiveFontSizePx,
            fontFamily = slots["fontFamily"] as? String ?: parent.fontFamily,
            fontWeight = slots["fontWeight"] as? Int ?: parent.fontWeight,
            lineHeightPx = effectiveLineHeightPx ?: parent.lineHeightPx,
            textAlign = slots["textAlign"] as? TextAlign ?: parent.textAlign,
            opacity = slots["opacity"] as? Float ?: parent.opacity,
        )
}

/** 内置默认样式表（设计文档 §5）：p/div 基线排版、h1-h6 递减加粗、行内强调、列表缩进、hr/img/slot 基线 */
private const val DEFAULT_CSS = """
    div { display: block; font-size: 16px; line-height: 1.5; }
    p { display: block; font-size: 16px; line-height: 1.5; margin: 0 0 12px 0; }
    h1 { display: block; font-size: 32px; font-weight: 700; line-height: 1.2; margin: 16px 0 8px 0; }
    h2 { display: block; font-size: 28px; font-weight: 700; line-height: 1.2; margin: 16px 0 8px 0; }
    h3 { display: block; font-size: 24px; font-weight: 700; line-height: 1.2; margin: 16px 0 8px 0; }
    h4 { display: block; font-size: 20px; font-weight: 700; line-height: 1.3; margin: 12px 0 6px 0; }
    h5 { display: block; font-size: 18px; font-weight: 700; line-height: 1.3; margin: 12px 0 6px 0; }
    h6 { display: block; font-size: 16px; font-weight: 700; line-height: 1.3; margin: 12px 0 6px 0; }
    b, strong { font-weight: 700; }
    i, em { font-style: italic; }
    u { text-decoration: underline; }
    s { text-decoration: line-through; }
    code, pre { font-family: monospace; }
    pre { display: block; margin: 8px 0; }
    a { color: #2563eb; text-decoration: underline; }
    ul, ol { display: block; padding-left: 22px; margin: 8px 0; }
    li { display: block; margin: 2px 0; }
    hr { display: block; border-width: 1px; border-color: #9ca3af; margin: 12px 0; }
    img { display: block; }
    slot { display: block; width: 100%; }
    .msg { font-size: 16px; line-height: 1.5; }
"""

/**
 * 样式级联（设计文档 §5.1）：
 * 1. 内置 [DEFAULT_STYLESHEET] 打底，主题样式表叠加其上（同特异性时主题按源顺序胜出）；
 * 2. 每个元素节点收集命中声明，按 特异性 (id, class, element) → 源顺序 排序依次应用，后写胜出；
 * 3. inline style 属性最后应用，胜过一切样式表规则；
 * 4. 可继承属性（color/fontSize/fontFamily/fontWeight/lineHeight/textAlign/opacity）沿树传递有效值，
 *    其余不继承；em/% 单位在级联期折算为 PX（相对父级/自身字号），根节点基线 16px。
 *
 * #id 选择器可解析、参与特异性排序，且按 CSS 语义匹配节点 id；但模板协议不注入 id 属性
 *（白名单外，见 §4.1），解析产物恒无 id，主题作者请用 class 选择器。
 */
object StyleResolver {
    /** 跨样式表合并时给规则源顺序留出的号段宽度，保证主题表规则恒大于默认表 */
    private const val RULE_ORDER_SPAN = 10_000

    /** 内置默认样式表（随引擎发布的基线排版；解析应零诊断，测试有回归保护） */
    val DEFAULT_STYLESHEET: ParsedStylesheet by lazy { CssParser.parse(DEFAULT_CSS) }

    /**
     * 级联入口：TemplateDocument → 样式已解析树。
     * [themeStylesheet] 为主题包 style.css 的解析产物；[themeVars] 为宿主按当前深浅模式注入的 token。
     */
    fun resolve(
        document: TemplateDocument,
        themeStylesheet: ParsedStylesheet? = null,
        themeVars: ThemeVars = ThemeVars.EMPTY,
    ): ResolvedTemplate {
        val diagnostics = mutableListOf<Diagnostic>()
        // 主题样式表解析诊断并入级联产物（默认表随引擎发布，其诊断由测试兜底而非运行时告警）
        themeStylesheet?.let { diagnostics += it.diagnostics }
        val orderedRules =
            (listOf(DEFAULT_STYLESHEET) + listOfNotNull(themeStylesheet))
                .flatMapIndexed { sheetIndex, sheet ->
                    sheet.rules.map { rule -> rule.copy(sourceOrder = sheetIndex * RULE_ORDER_SPAN + rule.sourceOrder) }
                }
        val context = CascadeContext(orderedRules, themeVars, diagnostics)
        val tree = context.buildStyledTree(document.tree, emptyList(), InheritedStyle.ROOT, depth = 1)
        return ResolvedTemplate(tree, diagnostics.toList())
    }
}

/** 级联上下文：选择器匹配 + 声明收集 + 树构建 */
private class CascadeContext(
    private val rules: List<CssRule>,
    private val themeVars: ThemeVars,
    private val diagnostics: MutableList<Diagnostic>,
) {
    /** 构建样式已解析树；TextNode 直接继承父级有效值，自身无声明 */
    fun buildStyledTree(
        node: TemplateNode,
        ancestors: List<NodeSignature>,
        inherited: InheritedStyle,
        depth: Int,
    ): StyledNode =
        when (node) {
            is TemplateNode.TextNode -> StyledNode(node, ResolvedStyle(), inherited)
            is TemplateNode.ElementNode -> {
                val classes = classesOf(node.attrs)
                val signature = NodeSignature(node.tag, classes, node.attrs[ID_ATTR])
                val accumulator = resolveNode(node.tag, classes, node.attrs[ID_ATTR], node.attrs, ancestors)
                // finalize 先折算有效字号（em 相对自身字号），子节点继承链随后构建
                val style = accumulator.finalize(inherited.fontSizePx)
                val childInherited = accumulator.childInherited(inherited)
                val children =
                    if (depth >= MAX_RESOLVE_DEPTH) {
                        emptyList()
                    } else {
                        node.children.map { child ->
                            buildStyledTree(child, ancestors + signature, childInherited, depth + 1)
                        }
                    }
                StyledNode(node, style, inherited, children = children)
            }
            is TemplateNode.SlotNode -> {
                val accumulator = resolveNode(SLOT_TAG, emptySet(), null, emptyMap(), ancestors)
                StyledNode(node, accumulator.finalize(inherited.fontSizePx), inherited)
            }
        }

    /** 单节点样式：样式表命中声明 → inline style（最后应用，胜过一切样式表规则） */
    private fun resolveNode(
        tag: String,
        classes: Set<String>,
        id: String?,
        attrs: Map<String, String>,
        ancestors: List<NodeSignature>,
    ): StyleAccumulator {
        val accumulator = StyleAccumulator(diagnostics)
        for (declaration in collectDeclarations(tag, classes, id, ancestors)) {
            applyDeclaration(accumulator, declaration)
        }
        attrs[INLINE_STYLE_ATTR]?.let { styleAttr ->
            val inline = CssParser.parseInlineStyle(styleAttr, themeVars)
            diagnostics += inline.diagnostics
            for (declaration in inline.declarations) applyDeclaration(accumulator, declaration)
        }
        return accumulator
    }

    private fun applyDeclaration(
        accumulator: StyleAccumulator,
        declaration: CssDeclaration,
    ) {
        if (!accumulator.apply(declaration.property, declaration.value)) {
            diagnostics +=
                Diagnostic(
                    DiagnosticSeverity.WARN,
                    DiagnosticCodes.UNKNOWN_CSS_PROP,
                    "未知 CSS 属性「${declaration.property}」，已丢弃",
                    declaration.property,
                )
        }
    }

    /** 收集命中当前节点的全部声明，按 特异性 → 源顺序 排序（后写胜出） */
    private fun collectDeclarations(
        tag: String,
        classes: Set<String>,
        id: String?,
        ancestors: List<NodeSignature>,
    ): List<CssDeclaration> {
        val matched = mutableListOf<MatchedDeclaration>()
        for (rule in rules) {
            for (selector in rule.selectors) {
                if (!matches(selector, tag, classes, id, ancestors)) continue
                for (declaration in rule.declarations) {
                    matched += MatchedDeclaration(selector.specificity, rule.sourceOrder, declaration)
                }
            }
        }
        matched.sortWith(compareBy({ it.specificity }, { it.order }))
        return matched.map { it.declaration }
    }

    /** 后代选择器匹配：末段命中节点自身，余下段从最近祖先向外贪心匹配 */
    private fun matches(
        selector: CssSelector,
        tag: String,
        classes: Set<String>,
        id: String?,
        ancestors: List<NodeSignature>,
    ): Boolean {
        val compounds = selector.compounds
        if (!matchesCompound(compounds.last(), tag, classes, id)) return false
        var compoundIndex = compounds.size - 2
        var ancestorIndex = ancestors.size - 1
        while (compoundIndex >= 0 && ancestorIndex >= 0) {
            val ancestor = ancestors[ancestorIndex]
            if (matchesCompound(compounds[compoundIndex], ancestor.tag, ancestor.classes, ancestor.id)) compoundIndex--
            ancestorIndex--
        }
        return compoundIndex < 0
    }

    private fun matchesCompound(
        compound: SelectorCompound,
        tag: String,
        classes: Set<String>,
        id: String?,
    ): Boolean {
        val tagMatches = compound.tag == null || compound.tag == tag
        // 协议白名单不注入 id 属性（解析器丢弃），故主题模板里 #id 选择器实际不命中；
        // 匹配逻辑仍按 CSS 语义支持（手构树/未来协议放宽 id 属性时自动生效）
        val idsMatch = compound.ids.isEmpty() || (id != null && compound.ids.all { it == id })
        val classesMatch = classes.containsAll(compound.classes)
        return tagMatches && idsMatch && classesMatch
    }

    private fun classesOf(attrs: Map<String, String>): Set<String> =
        attrs[CLASS_ATTR]
            ?.split(WHITESPACE_RUN)
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}

/** 样式级联递归深度上限（与 TemplateParser 的解析深度上限对齐，护住调用栈） */
private const val MAX_RESOLVE_DEPTH = 100
private const val SLOT_TAG = "slot"
private const val CLASS_ATTR = "class"
private const val ID_ATTR = "id"
private const val INLINE_STYLE_ATTR = "style"
