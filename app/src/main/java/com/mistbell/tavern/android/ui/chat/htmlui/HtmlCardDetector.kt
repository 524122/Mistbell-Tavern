package com.mistbell.tavern.android.ui.chat.htmlui

/**
 * 楼层 HTML 检测器：把消息文本拆成 Markdown 段与 HTML 文档段。
 *
 * 两种形态（对齐酒馆助手的渲染入口）：
 * 1. ```html 围栏块（围栏内是完整 HTML 文档）
 * 2. 整段消息以 <!DOCTYPE html 或 <html 开头（卡级正则替换出的裸 HTML）
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。
 */
object HtmlCardDetector {
    sealed interface Part {
        data class Markdown(val text: String) : Part

        data class HtmlDoc(val html: String) : Part
    }

    private val BARE_HTML_PREFIX = Regex("""^\s*(<!DOCTYPE\s+html|<html[\s>])""", RegexOption.IGNORE_CASE)

    /** 消息是否包含可渲染的 HTML 界面（快速预检，避免每条消息全量拆分）。 */
    fun containsHtml(content: String): Boolean = content.contains("```html", ignoreCase = true) || BARE_HTML_PREFIX.containsMatchIn(content)

    /**
     * 拆分消息为有序片段列表。无 HTML 时返回单个 Markdown 段（零分配开销路径）。
     */
    fun split(content: String): List<Part> =
        when {
            !containsHtml(content) -> listOf(Part.Markdown(content))
            // 整段裸 HTML（卡级正则产物）：以 doctype/html 开头且无围栏
            BARE_HTML_PREFIX.containsMatchIn(content.trimStart()) && !content.contains("```") ->
                listOf(Part.HtmlDoc(content))
            else -> splitByFences(content)
        }

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
