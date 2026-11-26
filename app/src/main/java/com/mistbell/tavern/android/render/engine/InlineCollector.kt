package com.mistbell.tavern.android.render.engine

import com.mistbell.tavern.android.render.model.DisplayValue
import com.mistbell.tavern.android.render.model.FontStyleValue
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextDecorationValue
import com.mistbell.tavern.android.render.model.TextSpan

/**
 * 行内收集器（M3）：把块级元素内的行内内容（span/b/i/em/strong/u/s/code/a/br + 文本）
 * 合并为单个 [TextBlock]——行内元素的级联样式与文本装饰合并成 span；块级子元素打断收集
 * （各自成块，递归处理）。whitespace 折叠：连续空白折为单个空格，整块纯空白不产出块。
 *
 * `br` 产出段内换行（"\n"，由 SkParagraph 承担）；`a[href]` 的 href 携带进 span（M5 命中测试）；
 * visibility:hidden / data-bind:visible=false 的元素跳过。纯 Kotlin、可 JVM 单测。
 */
object InlineCollector {
    /** 行内元素集合（设计文档 §4.1 白名单的行内子集；div/p/h1-h6/ul/li/pre/hr/img/slot 为块级） */
    private val INLINE_TAGS =
        setOf("span", "b", "strong", "i", "em", "u", "s", "code", "a", "br")

    /** font-weight 达到该值即视为加粗（CSS 语义 600 为分界） */
    private const val BOLD_WEIGHT_THRESHOLD = 600

    /** 常规字重（块级未声明时参与加粗判定的基线） */
    private const val NORMAL_WEIGHT = 400

    /** 等宽字族（内置默认样式表 code/pre 的 font-family） */
    private const val MONO_FAMILY = "monospace"

    private const val BR_TAG = "br"
    private const val HREF_ATTR = "href"
    private const val LINE_BREAK = "\n"
    private val WHITESPACE_RUN = Regex("\\s+")

    /** 收集入口：返回行内内容已合并的新树（结构与输入一致，块内行内内容折叠为 TextLeaf） */
    fun collect(root: RenderNode.Element): RenderNode.Element {
        val collected = collectChildren(root.styled, root.children)
        return RenderNode.Element(root.styled, collected)
    }

    /** 块内子级：行内内容连续段合并，块级子元素打断 */
    private fun collectChildren(
        blockStyled: StyledNode,
        children: List<RenderNode>,
    ): List<RenderNode> {
        val out = mutableListOf<RenderNode>()
        val base = baseState(blockStyled)
        val run =
            RunBuilder(
                base = base,
                align = blockStyled.style.textAlign ?: blockStyled.inherited.textAlign ?: TextAlign.LEFT,
                lineHeightPx = blockStyled.style.lineHeight?.value ?: blockStyled.inherited.lineHeightPx,
            )
        for (child in children) {
            when (child) {
                is RenderNode.InlineText -> run.appendText(child.text)
                is RenderNode.TextLeaf -> {
                    run.flushInto(out)
                    out += child
                }
                is RenderNode.Element ->
                    when {
                        isNone(child.styled) -> Unit
                        isInline(child.styled) -> run.appendElement(child.styled, child.children)
                        else -> {
                            run.flushInto(out)
                            out += collectElement(child)
                        }
                    }
            }
        }
        run.flushInto(out)
        return out
    }

    private fun collectElement(element: RenderNode.Element): RenderNode.Element =
        RenderNode.Element(element.styled, collectChildren(element.styled, element.children))

    /** inline 判定：标签行内 且 声明 display 未覆盖为块级 */
    private fun isInline(styled: StyledNode): Boolean {
        val tag = (styled.node as? TemplateNode.ElementNode)?.tag
        return tag in INLINE_TAGS && styled.style.display == null
    }

    /** display:none：行内收集跳过（布局层剪枝语义一致） */
    private fun isNone(styled: StyledNode): Boolean = styled.style.display == DisplayValue.NONE

    /** 块基线状态：块元素有效样式（未声明走继承链） */
    private fun baseState(blockStyled: StyledNode): SpanState {
        val style = blockStyled.style
        val inherited = blockStyled.inherited
        return SpanState(
            bold = (style.fontWeight ?: inherited.fontWeight ?: NORMAL_WEIGHT) >= BOLD_WEIGHT_THRESHOLD,
            italic = style.fontStyle == FontStyleValue.ITALIC,
            underline = style.textDecoration == TextDecorationValue.UNDERLINE,
            strikethrough = style.textDecoration == TextDecorationValue.LINE_THROUGH,
            colorArgb = (style.color ?: inherited.color)?.argb,
            mono = style.fontFamily == MONO_FAMILY,
            fontSizePx = style.fontSize?.value ?: inherited.fontSizePx,
            href = null,
        )
    }

    /** 行内样式合并：行内元素声明覆盖，未声明字段透传父级状态 */
    private fun mergeState(
        parent: SpanState,
        styled: StyledNode,
    ): SpanState {
        val style = styled.style
        val attrs = (styled.node as? TemplateNode.ElementNode)?.attrs
        return SpanState(
            bold = style.fontWeight?.let { it >= BOLD_WEIGHT_THRESHOLD } ?: parent.bold,
            italic = style.fontStyle?.let { it == FontStyleValue.ITALIC } ?: parent.italic,
            underline =
                style.textDecoration?.let { it == TextDecorationValue.UNDERLINE } ?: parent.underline,
            strikethrough =
                style.textDecoration?.let { it == TextDecorationValue.LINE_THROUGH } ?: parent.strikethrough,
            colorArgb = style.color?.argb ?: parent.colorArgb,
            mono = style.fontFamily?.let { it == MONO_FAMILY } ?: parent.mono,
            fontSizePx = style.fontSize?.value ?: parent.fontSizePx,
            href = attrs?.get(HREF_ATTR) ?: parent.href,
        )
    }

    /** 行内样式状态（span 合并与判等的全部字段） */
    private data class SpanState(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strikethrough: Boolean,
        val colorArgb: Long?,
        val mono: Boolean,
        val fontSizePx: Float?,
        val href: String?,
    ) {
        fun toSpan(text: String) =
            TextSpan(
                text = text,
                bold = bold,
                italic = italic,
                underline = underline,
                strikethrough = strikethrough,
                colorArgb = colorArgb,
                mono = mono,
                fontSizePx = fontSizePx,
                href = href,
            )
    }

    /** 一个块内连续行内内容的累积器：状态相同相邻片段合并、br → 换行、纯空白不产出块 */
    private class RunBuilder(
        private val base: SpanState,
        private val align: TextAlign,
        private val lineHeightPx: Float?,
    ) {
        private var state = base
        private var buffer = StringBuilder()
        private val spans = mutableListOf<TextSpan>()

        /** 文本按当前状态并入缓冲（TextNode 的观感由外层元素合并后的状态决定） */
        fun appendText(text: String) {
            buffer.append(collapseWhitespace(text))
        }

        fun appendElement(
            styled: StyledNode,
            children: List<RenderNode>,
        ) {
            val tag = (styled.node as? TemplateNode.ElementNode)?.tag
            if (tag == BR_TAG) {
                buffer.append(LINE_BREAK)
                return
            }
            // 状态切换前冲刷缓冲，保证缓冲文本以进入时的状态成 span；
            // 子级收集后再冲刷一次（子级可能又切过状态），最后恢复外层状态
            pushBuffer()
            val previous = state
            state = mergeState(state, styled)
            appendInlineChildren(children)
            pushBuffer()
            state = previous
        }

        private fun appendInlineChildren(children: List<RenderNode>) {
            for (child in children) {
                when (child) {
                    is RenderNode.InlineText -> appendText(child.text)
                    is RenderNode.Element -> appendElement(child.styled, child.children)
                    // 一等内容块不会出现在行内运行里（SlotFiller 已包裹为块级）；兜底按纯文本并入
                    is RenderNode.TextLeaf -> appendText(child.block.text)
                }
            }
        }

        /** 冲刷当前运行：样式一致的相邻文本合并为单 span，产出一个 TextBlock */
        fun flushInto(out: MutableList<RenderNode>) {
            pushBuffer()
            state = base
            val merged = mergeAdjacent(spans.toList())
            spans.clear()
            val trimmed = trimEdgeSpaces(merged)
            val text = trimmed.joinToString(separator = "") { it.text }
            if (text.isBlank()) return
            out +=
                RenderNode.TextLeaf(
                    TextBlock(
                        spans = trimmed,
                        align = align,
                        fontSizePx = base.fontSizePx,
                        lineHeightPx = lineHeightPx,
                        colorArgb = base.colorArgb,
                    ),
                )
        }

        private fun pushBuffer() {
            if (buffer.isNotEmpty()) {
                spans += state.toSpan(buffer.toString())
                buffer = StringBuilder()
            }
        }

        private fun mergeAdjacent(input: List<TextSpan>): List<TextSpan> {
            val result = mutableListOf<TextSpan>()
            for (span in input) {
                val last = result.lastOrNull()
                if (last != null && last.styleFingerprint == span.styleFingerprint) {
                    result[result.size - 1] = last.copy(text = last.text + span.text)
                } else {
                    result += span
                }
            }
            return result
        }

        /** 修剪首尾普通空格与换行（段内换行保留）；修剪后为空的 span 丢弃 */
        private fun trimEdgeSpaces(input: List<TextSpan>): List<TextSpan> {
            if (input.isEmpty()) return input
            val trimmed = input.toMutableList()
            trimmed[0] = trimmed[0].copy(text = trimmed[0].text.trimStart(' ', '\n'))
            val lastIndex = trimmed.size - 1
            trimmed[lastIndex] = trimmed[lastIndex].copy(text = trimmed[lastIndex].text.trimEnd(' ', '\n'))
            return trimmed.filter { it.text.isNotEmpty() }
        }

        private fun collapseWhitespace(text: String): String = WHITESPACE_RUN.replace(text, " ")
    }
}
