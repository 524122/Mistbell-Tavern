package com.mistbell.tavern.android.render.markdown

import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextSpan
import com.mistbell.tavern.android.ui.chat.MdSegment
import com.mistbell.tavern.android.ui.chat.parseMarkdown

/**
 * Markdown 叙述段 → [TextBlock] 列表（M3，设计文档 §9 M3 行）。
 *
 * 块级结构复用 `MarkdownRenderer.parseMarkdown` 纯函数（Paragraph/CodeBlock/Header/ListItem）；
 * 行内高亮按 ui/chat/MarkdownRenderer 的正则与优先级移植为 [TextSpan] 字段：
 * 引号→颜色、动作括号→斜体+颜色、链接→下划线+颜色+href、粗/斜体、行内代码→等宽小号。
 *
 * 列表前缀：parseMarkdown 会剥掉原始标记（项目符号与序号），本类用同语义正则扫描原文行
 * 恢复标记（项目符号 → "• "，有序 → "序号. "）。游标同步规则：ListItem 顺序取一个标记；
 * Paragraph 吸收规则与 parseMarkdown 一致（空行/围栏/标题行终止），其吸收掉的列表行标记
 * 从队列丢弃（与原渲染器"吸收行作为纯文本渲染"的观感对齐）。
 * <br>/<br/> → 段内换行；标题 → 字号档加粗；围栏代码块 → 等宽块 + 浅背景。
 *
 * 纯 Kotlin、无副作用，可 JVM 单测。
 */
object MarkdownToNodes {
    /**
     * 行内高亮调色板：颜色全部由参数传入（默认值取 ui/chat MarkdownRenderer 现用色），
     * 深浅色适配由 M4 宿主按主题传入。
     */
    data class Palette(
        /** 引号对话颜色（默认取 AccentGreen 浅色变体） */
        val quoteColorArgb: Long = DEFAULT_QUOTE_COLOR,
        /** 动作括号颜色（默认取 AccentOrange 浅色变体） */
        val actionColorArgb: Long = DEFAULT_ACTION_COLOR,
        /** 链接颜色（默认取内置默认样式表的 a 色） */
        val linkColorArgb: Long = DEFAULT_LINK_COLOR,
        /** 围栏代码块浅背景（默认半透明黑，深色主题可传覆盖值） */
        val codeBackgroundArgb: Long = DEFAULT_CODE_BACKGROUND,
    )

    /** 单个 Markdown 串 → 文本块列表（每个块一个 TextBlock；块间距由 SlotFiller 包裹容器补 margin） */
    fun convert(
        markdown: String,
        palette: Palette = Palette(),
        baseFontSizePx: Float = DEFAULT_FONT_SIZE_PX,
    ): List<TextBlock> {
        val markers = ListMarkers(markdown)
        return parseMarkdown(markdown).map { segment ->
            when (segment) {
                is MdSegment.CodeBlock -> codeBlock(segment.code, palette)
                is MdSegment.Header -> header(segment.text, segment.level, palette)
                is MdSegment.ListItem ->
                    listItem(
                        text = segment.text,
                        marker = markers.take(),
                        palette = palette,
                        baseFontSizePx = baseFontSizePx,
                    )
                is MdSegment.Paragraph -> {
                    markers.dropAbsorbed()
                    paragraph(segment.text, palette, baseFontSizePx)
                }
            }
        }
    }

    // ---- 块级转换 ----

    /** 标题字号档（任务拍板：h1=24/h2=20/h3=18/h4=16/h5=14/h6=13，整体加粗） */
    private val HEADER_SIZES = floatArrayOf(H1_SIZE_PX, H2_SIZE_PX, H3_SIZE_PX, H4_SIZE_PX, H5_SIZE_PX, H6_SIZE_PX)

    private fun header(
        text: String,
        level: Int,
        palette: Palette,
    ): TextBlock {
        val size = HEADER_SIZES[(level - 1).coerceIn(0, HEADER_SIZES.lastIndex)]
        val block = inlineBlock(text, size, palette)
        return block.copy(spans = block.spans.map { it.copy(bold = true) })
    }

    private fun listItem(
        text: String,
        marker: String?,
        palette: Palette,
        baseFontSizePx: Float,
    ): TextBlock {
        val prefix = marker ?: BULLET_MARKER
        return inlineBlock(prefix + text, baseFontSizePx, palette)
    }

    private fun paragraph(
        text: String,
        palette: Palette,
        baseFontSizePx: Float,
    ): TextBlock = inlineBlock(text, baseFontSizePx, palette)

    private fun codeBlock(
        code: String,
        palette: Palette,
    ): TextBlock =
        TextBlock(
            spans = listOf(TextSpan(text = code.trimEnd(), mono = true)),
            fontSizePx = CODE_FONT_SIZE_PX,
            lineHeightPx = CODE_LINE_HEIGHT_PX,
            codeBackgroundArgb = palette.codeBackgroundArgb,
        )

    /** 一个块 → 行内解析为 spans（含 <br> 换行）+ 基线排版 */
    private fun inlineBlock(
        text: String,
        baseFontSizePx: Float,
        palette: Palette = Palette(),
    ): TextBlock =
        TextBlock(
            spans = parseInline(text, palette),
            align = TextAlign.LEFT,
            fontSizePx = baseFontSizePx,
            lineHeightPx = baseFontSizePx * DEFAULT_LINE_HEIGHT_RATIO,
        )

    // ---- 行内解析（移植 ui/chat MarkdownRenderer.appendInlineMarkdown 的优先级模型）----

    /**
     * 行内装饰：找最早命中（同位置按优先级 bold > code > quote > link > italic > action），
     * 命中区间映射为对应样式的 span，其余为普通文本 span。
     */
    private fun parseInline(
        text: String,
        palette: Palette,
    ): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        var remaining = replaceLineBreaks(text)
        while (remaining.isNotEmpty()) {
            val firstMatch =
                listOfNotNull(
                    BOLD_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_BOLD, it) },
                    CODE_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_CODE, it) },
                    QUOTE_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_QUOTE, it) },
                    LINK_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_LINK, it) },
                    ITALIC_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_ITALIC, it) },
                    ACTION_PATTERN.find(remaining)?.let { Candidate(it.range, PRIORITY_ACTION, it) },
                ).filter { it.range.first >= 0 }
                    .minWithOrNull(compareBy({ it.range.first }, { it.priority }))

            if (firstMatch == null) {
                spans += TextSpan(text = remaining)
                break
            }
            if (firstMatch.range.first > 0) {
                spans += TextSpan(text = remaining.substring(0, firstMatch.range.first))
            }
            spans += spanOf(firstMatch.match, palette)
            remaining = remaining.substring(firstMatch.match.range.last + 1)
        }
        return spans.ifEmpty { listOf(TextSpan(text = "")) }
    }

    /** 单个命中 → 装饰 span（bold/code/link 语法剥壳；引号/动作连定界符一起着色，观感同原渲染器） */
    private fun spanOf(
        match: MatchResult,
        palette: Palette,
    ): TextSpan =
        when {
            match.value.startsWith(BOLD_MARK) -> TextSpan(text = match.groupValues[1], bold = true)
            match.value.startsWith(CODE_MARK) ->
                TextSpan(text = match.groupValues[1], mono = true, fontSizePx = INLINE_CODE_FONT_SIZE_PX)
            match.value.startsWith(LINK_MARK) ->
                TextSpan(
                    text = match.groupValues[1],
                    underline = true,
                    colorArgb = palette.linkColorArgb,
                    href = match.groupValues[2],
                )
            else -> decoratedQuoted(match, palette)
        }

    /** 引号（含引号字符）→ 颜色 span；动作括号（含括号字符）→ 斜体+颜色 span；余下为斜体 */
    private fun decoratedQuoted(
        match: MatchResult,
        palette: Palette,
    ): TextSpan =
        when (match.value.first()) {
            in QUOTE_OPENERS -> TextSpan(text = match.value, colorArgb = palette.quoteColorArgb)
            in ACTION_OPENERS -> TextSpan(text = match.value, italic = true, colorArgb = palette.actionColorArgb)
            else -> TextSpan(text = match.groupValues.getOrElse(1) { match.value }, italic = true)
        }

    /** <br>/<br/>/<br /> → 段内换行（换行由 SkParagraph 的 \n 语义承担） */
    private fun replaceLineBreaks(text: String): String = LINE_BREAK_PATTERN.replace(text, "\n")

    // ---- 列表标记恢复游标 ----

    /**
     * 原文行的列表标记队列：构造时逐行扫描（与 parseMarkdown 同语义的正则）。
     * [take] 供 ListItem 消费；[dropAbsorbed] 供 Paragraph 丢弃其吸收行（含吸收掉的列表行）的标记。
     */
    private class ListMarkers(source: String) {
        private class LineMarker(
            val isList: Boolean,
            /** 列表标记前缀（缩进 + 符号/序号）；非列表行为 null */
            val prefix: String?,
            /** 该行是否会被段落吸收（isParagraphContinuation 同语义：非空行、非围栏、非标题行） */
            val isParagraphLine: Boolean,
        )

        private val markers: List<LineMarker> = source.lines().map(::classify)
        private var cursor = 0

        /** 取下一列表标记（含缩进）；队列耗尽 → null（调用方退回项目符号前缀） */
        fun take(): String? {
            while (cursor < markers.size && !markers[cursor].isList) cursor++
            if (cursor >= markers.size) return null
            return markers[cursor].prefix.also { cursor++ }
        }

        /** 段落吸收：跳过自游标起全部连续可续行（其标记视为被段落文本吸收，不入前缀队列） */
        fun dropAbsorbed() {
            while (cursor < markers.size && markers[cursor].isParagraphLine) cursor++
        }

        private fun classify(line: String): LineMarker {
            val trimmed = line.trim()
            val isParagraphLine =
                line.isNotBlank() &&
                    !line.trimStart().startsWith(FENCE_PREFIX) &&
                    !HEADER_START_REGEX.containsMatchIn(trimmed)
            val bullet = BULLET_LIST_REGEX.find(line)
            val numbered = NUMBERED_LIST_REGEX.find(line)
            // 缩进按行首空白宽度折算（每 2 空格一档）；原始渲染器的 depth 语义与之不同（含标记符），不沿用
            val indent = INDENT_UNIT.repeat(line.takeWhile { it == ' ' || it == '\t' }.length / 2)
            val marker =
                when {
                    bullet != null -> BULLET_MARKER
                    numbered != null -> numbered.groupValues[1].trimStart()
                    else -> ""
                }
            return LineMarker(
                isList = bullet != null || numbered != null,
                prefix = indent + marker,
                isParagraphLine = isParagraphLine,
            )
        }
    }

    // ---- 常量（块级正则与 ui/chat MarkdownRenderer 同语义；行内正则原样移植）----

    private const val DEFAULT_FONT_SIZE_PX = 16f
    private const val DEFAULT_LINE_HEIGHT_RATIO = 1.5f
    private const val CODE_FONT_SIZE_PX = 12f
    private const val CODE_LINE_HEIGHT_PX = 18f
    private const val INLINE_CODE_FONT_SIZE_PX = 13f

    /** 标题字号档（px，任务拍板） */
    private const val H1_SIZE_PX = 24f
    private const val H2_SIZE_PX = 20f
    private const val H3_SIZE_PX = 18f
    private const val H4_SIZE_PX = 16f
    private const val H5_SIZE_PX = 14f
    private const val H6_SIZE_PX = 13f
    private const val INDENT_UNIT = "  "
    private const val BULLET_MARKER = "• "
    private const val FENCE_PREFIX = "```"
    private const val BOLD_MARK = "**"
    private const val CODE_MARK = "`"
    private const val LINK_MARK = "["

    private const val PRIORITY_BOLD = 0
    private const val PRIORITY_CODE = 1
    private const val PRIORITY_QUOTE = 2
    private const val PRIORITY_LINK = 3
    private const val PRIORITY_ITALIC = 4
    private const val PRIORITY_ACTION = 5

    /** 引号默认色：ui/theme Color.kt AccentGreen（浅色变体） */
    const val DEFAULT_QUOTE_COLOR = 0xFF4D8064L

    /** 动作括号默认色：ui/theme Color.kt AccentOrange（浅色变体） */
    const val DEFAULT_ACTION_COLOR = 0xFFC1843BL

    /** 链接默认色：内置默认样式表 a { color: #2563eb } */
    const val DEFAULT_LINK_COLOR = 0xFF2563EBL

    /** 代码块浅背景默认：半透明黑（深浅色共用，与行内代码背景同基调） */
    const val DEFAULT_CODE_BACKGROUND = 0x14000000L

    private val QUOTE_OPENERS = charArrayOf('"', '“', '「', '『', '\'', '‘')
    private val ACTION_OPENERS = charArrayOf('(', '（')

    private val HEADER_START_REGEX = Regex("^#{1,6}\\s+")
    private val BULLET_LIST_REGEX = Regex("^([\\s]*[-*+]\\s+)(.+)")
    private val NUMBERED_LIST_REGEX = Regex("^(\\s*\\d+\\.\\s+)(.+)")
    private val LINE_BREAK_PATTERN = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    private val BOLD_PATTERN = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC_PATTERN = Regex("\\*(.+?)\\*")
    private val CODE_PATTERN = Regex("`(.+?)`")
    private val LINK_PATTERN = Regex("\\[(.+?)\\]\\((.+?)\\)")

    /** 与 ui/chat MarkdownRenderer 同款：多引号类型（拆行拼接以守 140 列） */
    private val QUOTE_PATTERN =
        Regex("""["「『]([^"」』]+?)["」』]|'([^']+?)'|""" + """"([^"]+?)"|'([^']+?)'""")

    /** 圆/中文圆括号（排除链接语法，lookbehind 同原渲染器） */
    private val ACTION_PATTERN = Regex("""(?<!\])\(([^)]+?)\)|（([^）]+?)）""")

    private data class Candidate(
        val range: IntRange,
        val priority: Int,
        val match: MatchResult,
    )
}
