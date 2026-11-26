// 文件名按规划取 MarkdownParser.kt（文件主体是 parseMarkdown 解析器），与文件里唯一的类
// MdSegment 不同名；ktlint filename 规则不允许，按仓库惯例（同 VectorIndexDeletions.kt）就地豁免。
@file:Suppress("ktlint:standard:filename")

package com.mistbell.tavern.android.render.markdown

// 本文件是从 ui/chat/MarkdownRenderer.kt 原样下沉的纯 Kotlin Markdown 块级解析器
// （纯移动：类型、函数签名、正则与解析行为均未改动）。
// 动机：render 是叶子包，不允许依赖 ui.*；原先 MdSegment / parseMarkdown 定义在
// ui.chat，迫使 render/markdown/MarkdownToNodes 反向 import ui.chat。
// 下沉后 ui.chat 改为引用本文件（ui → render 是允许的方向），
// Compose 相关部分（MarkdownRenderer / appendInlineMarkdown 等）仍留在 ui.chat。

// —— 正则常量：原先在每行/每段循环内重复编译 Regex，长消息解析开销被成倍放大；
//    提升为顶层常量后整个进程只编译一次 ——

// 块级结构（标题 / 列表）
private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.+)")
private val HEADER_START_REGEX = Regex("^#{1,6}\\s+")
private val BULLET_LIST_REGEX = Regex("^([\\s]*[-*+]\\s+)(.+)")
private val NUMBERED_LIST_REGEX = Regex("^(\\s*\\d+\\.\\s+)(.+)")

sealed class MdSegment {
    data class Paragraph(val text: String) : MdSegment()

    data class CodeBlock(val code: String, val language: String) : MdSegment()

    data class Header(val text: String, val level: Int) : MdSegment()

    data class ListItem(val text: String, val depth: Int) : MdSegment()
}

// Simple markdown parser

// 原位置（ui/chat/MarkdownRenderer.kt）这两类问题均由 detekt 基线吸收；纯移动不改结构，
// 新文件不在基线内，按仓库惯例就地抑制。
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
fun parseMarkdown(content: String): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    val lines = content.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // skip closing ```
            segments.add(MdSegment.CodeBlock(codeLines.joinToString("\n"), language))
            continue
        }

        // Header
        val headerMatch = HEADER_REGEX.find(line.trim())
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            segments.add(MdSegment.Header(headerMatch.groupValues[2], level))
            i++
            continue
        }

        // List item
        val listMatch =
            BULLET_LIST_REGEX.find(line)
                ?: NUMBERED_LIST_REGEX.find(line)
        if (listMatch != null) {
            val depth = listMatch.groupValues[1].trimStart().length / 2
            segments.add(MdSegment.ListItem(listMatch.groupValues[2], depth))
            i++
            continue
        }

        // Empty line
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph - collect consecutive non-empty, non-special lines
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size && isParagraphContinuation(lines[i])) {
            paragraphLines.add(lines[i])
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            segments.add(MdSegment.Paragraph(paragraphLines.joinToString(" ")))
        }
    }

    return segments
}

/**
 * 段落收集的继续条件抽为具名谓词：四个条件的复合直接内联在 while 里会触发
 * ComplexCondition，且具名后语义更清晰——空行、代码围栏、标题行都终止段落。
 */
private fun isParagraphContinuation(line: String): Boolean =
    line.isNotBlank() &&
        !line.trimStart().startsWith("```") &&
        !HEADER_START_REGEX.containsMatchIn(line.trim())
