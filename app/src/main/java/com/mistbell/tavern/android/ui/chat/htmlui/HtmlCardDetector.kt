package com.mistbell.tavern.android.ui.chat.htmlui

import com.mistbell.tavern.android.util.HtmlCardText

/**
 * 楼层 HTML 检测器：把消息文本拆成 Markdown 段与 HTML 文档段。
 *
 * 两种形态（对齐酒馆助手的渲染入口）：
 * 1. ```html 围栏块（围栏内是完整 HTML 文档）
 * 2. 整段消息以 <!DOCTYPE html 或 <html 开头（卡级正则替换出的裸 HTML）
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。
 * containsHtml 的判定集合已下沉到零依赖的 [HtmlCardText]（P3 导入报告与楼层渲染共用同一
 * 口径，docs/UI_MODE.md §5）；本类持有其 internal 正则做楼层拆分，行为与下沉前逐字节一致。
 */
object HtmlCardDetector {
    sealed interface Part {
        data class Markdown(val text: String) : Part

        /** [narrative] = true 表示叙事内容中的行内 HTML（心声魂音/对话玉简等），整段按文档渲染 */
        data class HtmlDoc(val html: String, val narrative: Boolean = false) : Part
    }

    private val TABLE_BLOCK =
        Regex(
            """<table\b.*?</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    /** 消息是否包含可渲染的 HTML 界面（快速预检，避免每条消息全量拆分）。委托下沉的判定，口径不变 */
    fun containsHtml(content: String): Boolean = HtmlCardText.containsHtml(content)

    /**
     * 拆分消息为有序片段列表。无 HTML 时返回单个 Markdown 段（零分配开销路径）。
     */
    fun split(content: String): List<Part> =
        when {
            !containsHtml(content) -> listOf(Part.Markdown(content))
            // 整段裸 HTML（卡级正则产物）：以 doctype/html 开头且无围栏
            HtmlCardText.BARE_HTML_PREFIX.containsMatchIn(content.trimStart()) && !content.contains("```") ->
                listOf(Part.HtmlDoc(content))
            // 叙事内容中的行内 HTML：整段包装为文档（保留全部叙事文本与 <style>）
            HtmlCardText.NARRATIVE_HTML.containsMatchIn(content) && !content.contains("```") -> splitNarrative(content)
            // 行内表格单独成段，前后正文仍走 Markdown
            HtmlCardText.TABLE_HTML.containsMatchIn(content) && !content.contains("```") -> splitTables(content)
            else -> splitByFences(content)
        }

    /**
     * 叙事内容按 <style> 切块：所有样式块合并为共享样式头，正文按块拆成小节。
     * 纯文本小节走 Markdown，含行内组件的小节各自渲染为独立小 HTML 文档——
     * 避免整段超长叙事生成单个巨型 WebView（此前因此 ANR）。
     */
    private fun splitNarrative(content: String): List<Part> {
        val styleRegex =
            Regex(
                "<style\\b[^>]*>.*?</style>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val styleHead = StringBuilder()
        val sections = mutableListOf<String>()
        var lastEnd = 0
        styleRegex.findAll(content).forEach { match ->
            sections += content.substring(lastEnd, match.range.first)
            styleHead.append(match.value)
            lastEnd = match.range.last + 1
        }
        sections += content.substring(lastEnd)
        val sharedHead = styleHead.toString()
        return sections.mapNotNull { section ->
            when {
                // 仅含 <style> 的小节不单独成段——样式已并入共享头，无内容可渲染
                styleRegex.replace(section, "").isBlank() -> null
                HtmlCardText.NARRATIVE_HTML.containsMatchIn(section) ->
                    Part.HtmlDoc(wrapNarrativeSection(sharedHead, section), narrative = true)
                else -> Part.Markdown(section)
            }
        }.ifEmpty { listOf(Part.Markdown(content)) }
    }

    /** 把闭合的 table 抽成 HTML 段。未闭合的表格留在 Markdown 里，不按半截标签渲染。 */
    private fun splitTables(content: String): List<Part> {
        val parts = mutableListOf<Part>()
        var lastEnd = 0
        TABLE_BLOCK.findAll(content).forEach { match ->
            appendPlain(parts, content.substring(lastEnd, match.range.first))
            parts += Part.HtmlDoc(match.value, narrative = true)
            lastEnd = match.range.last + 1
        }
        appendPlain(parts, content.substring(lastEnd))
        return parts.ifEmpty { listOf(Part.Markdown(content)) }
    }

    private fun appendPlain(
        parts: MutableList<Part>,
        text: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) parts += Part.Markdown(trimmed)
    }

    /** 小节包装：共享样式头 + 本节内容，小文档不会撑爆布局。 */
    private fun wrapNarrativeSection(
        styleHead: String,
        section: String,
    ): String = "<!DOCTYPE html><html><head>$styleHead</head><body>$section</body></html>"

    private fun splitByFences(content: String): List<Part> {
        val parts = mutableListOf<Part>()
        val lines = content.lines()
        val markdownBuffer = StringBuilder()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith("```html")) {
                markdownBuffer.appendLine(lines[i])
                i++
                continue
            }
            val (htmlLines, next, closed) = collectFence(lines, i + 1)
            appendFence(parts, markdownBuffer, htmlLines, closed)
            i = next
        }
        flushMarkdown(parts, markdownBuffer)
        return parts.ifEmpty { listOf(Part.Markdown(content)) }
    }

    /** 闭合围栏出 HTML 文档段；未闭合按普通文本还原文本（不当 HTML 渲染）。 */
    private fun appendFence(
        parts: MutableList<Part>,
        markdownBuffer: StringBuilder,
        htmlLines: List<String>,
        closed: Boolean,
    ) {
        if (closed) {
            flushMarkdown(parts, markdownBuffer)
            parts.add(Part.HtmlDoc(htmlLines.joinToString("\n")))
        } else {
            markdownBuffer.appendLine("```html")
            htmlLines.forEach { markdownBuffer.appendLine(it) }
        }
    }

    /** 收集 ```html 围栏内容；返回 (内容行, 围栏后下一行索引, 是否闭合)。 */
    private fun collectFence(
        lines: List<String>,
        start: Int,
    ): Triple<List<String>, Int, Boolean> {
        val htmlLines = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```")) return Triple(htmlLines, i + 1, true)
            htmlLines.add(lines[i])
            i++
        }
        return Triple(htmlLines, i, false)
    }

    private fun flushMarkdown(
        parts: MutableList<Part>,
        buffer: StringBuilder,
    ) {
        val text = buffer.toString().trim()
        if (text.isNotEmpty()) parts.add(Part.Markdown(text))
        buffer.clear()
    }
}
