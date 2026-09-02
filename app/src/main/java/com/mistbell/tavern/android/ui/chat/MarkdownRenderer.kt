package com.mistbell.tavern.android.ui.chat

// 注意：本文件不再 import isSystemInDarkTheme —— 深浅色由应用内三态设置（dark/light/system）
// 在 UI 层算出后经 dark 参数透传进来，避免"应用内强制浅色 + 系统深色"时取错变体

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.ui.theme.AccentGreen
import com.mistbell.tavern.android.ui.theme.AccentGreenDark
import com.mistbell.tavern.android.ui.theme.AccentOrange
import com.mistbell.tavern.android.ui.theme.AccentOrangeDark

// —— 正则常量：原先在每行/每段循环内重复编译 Regex，长消息解析开销被成倍放大；
//    提升为顶层常量后整个进程只编译一次 ——

// 块级结构（标题 / 列表）
private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.+)")
private val HEADER_START_REGEX = Regex("^#{1,6}\\s+")
private val BULLET_LIST_REGEX = Regex("^([\\s]*[-*+]\\s+)(.+)")
private val NUMBERED_LIST_REGEX = Regex("^(\\s*\\d+\\.\\s+)(.+)")

// 行内样式
private val BOLD_PATTERN = Regex("\\*\\*(.+?)\\*\\*")
private val ITALIC_PATTERN = Regex("\\*(.+?)\\*")
private val CODE_PATTERN = Regex("`(.+?)`")
private val LINK_PATTERN = Regex("\\[(.+?)\\]\\((.+?)\\)")

// 支持多种引号类型：英文双引号、单引号、中文双引号、单引号、日文引号
private val QUOTE_PATTERN = Regex("""["「『]([^"」』]+?)["」』]|'([^']+?)'|"([^"]+?)"|'([^']+?)'""")

// 支持圆括号和中文圆括号，但需要排除链接语法 [text](url)
private val ACTION_PATTERN = Regex("""(?<!\])\(([^)]+?)\)|（([^）]+?)）""")

// 行内代码背景（半透明黑，深浅色共用，保持原行为）
private val INLINE_CODE_BACKGROUND = Color(0x1A000000)

@Composable
fun MarkdownRenderer(
    content: String,
    // 深浅色必须由调用方显式传入（应用内三态 darkModeSetting 算出的结果），
    // 本组件不再自行读取 isSystemInDarkTheme()：系统深色 ≠ 应用内选择的深色
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    // 记忆化：同一 content 只解析一次，重组/流式增量时避免重复解析整篇
    val segments = remember(content) { parseMarkdown(content) }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.forEach { segment ->
                when (segment) {
                    is MdSegment.CodeBlock -> CodeBlock(segment.code, segment.language)
                    is MdSegment.Header ->
                        Text(
                            text = segment.text,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize =
                                        if (segment.level == 1) {
                                            18.sp
                                        } else if (segment.level == 2) {
                                            16.sp
                                        } else {
                                            14.sp
                                        },
                                ),
                        )
                    is MdSegment.ListItem ->
                        Text(
                            text = "• ${segment.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = (segment.depth * 12).dp),
                        )
                    is MdSegment.Paragraph -> {
                        // 主题颜色在组合期读取后作为参数传入 remember 块，
                        // 避免 remember 内读取 snapshot state 把首次颜色固化
                        val linkColor = MaterialTheme.colorScheme.primary
                        // 记忆化：同一文本 + 同一主题状态只构建一次 AnnotatedString
                        val annotated =
                            remember(segment.text, dark, linkColor) {
                                buildAnnotatedString {
                                    appendInlineMarkdown(segment.text, dark, linkColor)
                                }
                            }
                        Text(text = annotated, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: String,
    language: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (language.isNotBlank()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            Text(
                text = code.trimEnd(),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                modifier =
                    Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

// Simple markdown parser

sealed class MdSegment {
    data class Paragraph(val text: String) : MdSegment()

    data class CodeBlock(val code: String, val language: String) : MdSegment()

    data class Header(val text: String, val level: Int) : MdSegment()

    data class ListItem(val text: String, val depth: Int) : MdSegment()
}

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

/**
 * 行内 Markdown 解析：加粗、斜体、行内代码、链接、引号、动作括号。
 *
 * 本函数不读取任何 snapshot state（isSystemInDarkTheme()/MaterialTheme 均不读）：
 * 它被 remember 块包裹，内部读取 snapshot state 不会订阅更新，会把首次颜色固化；
 * 深浅色（darkTheme）与链接颜色（linkColor）由组合期调用方读取后作为参数传入。
 */
fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    darkTheme: Boolean,
    linkColor: Color,
) {
    // 获取主题感知的引号颜色
    val quoteColor =
        if (darkTheme) {
            AccentGreenDark
        } else {
            AccentGreen
        }

    // 获取主题感知的动作颜色
    val actionColor =
        if (darkTheme) {
            AccentOrangeDark
        } else {
            AccentOrange
        }

    // Handle bold, italic, inline code, links, quotes, and actions
    var remaining = text

    // Simple sequential approach: find earliest match and process
    while (remaining.isNotEmpty()) {
        val bold = BOLD_PATTERN.find(remaining)
        val italic = ITALIC_PATTERN.find(remaining)
        val code = CODE_PATTERN.find(remaining)
        val link = LINK_PATTERN.find(remaining)
        val quote = QUOTE_PATTERN.find(remaining)
        val action = ACTION_PATTERN.find(remaining)

        // Find the earliest match
        data class MatchInfo(val range: IntRange, val priority: Int, val startIndex: Int)

        val matches =
            listOfNotNull(
                bold?.let { MatchInfo(it.range, 0, it.range.first) },
                code?.let { MatchInfo(it.range, 1, it.range.first) },
                quote?.let { MatchInfo(it.range, 2, it.range.first) },
                link?.let { MatchInfo(it.range, 3, it.range.first) },
                italic?.let { MatchInfo(it.range, 4, it.range.first) },
                action?.let { MatchInfo(it.range, 5, it.range.first) },
            ).filter { it.startIndex >= 0 }.sortedWith(compareBy<MatchInfo> { it.startIndex }.thenBy { it.priority })

        val firstMatch = matches.firstOrNull()

        if (firstMatch == null) {
            append(remaining)
            break
        }

        // Append text before match
        if (firstMatch.startIndex > 0) {
            append(remaining.substring(0, firstMatch.startIndex))
        }

        when {
            bold != null && firstMatch.range == bold.range -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(bold.groupValues[1])
                }
                remaining = remaining.substring(bold.range.last + 1)
            }
            code != null && firstMatch.range == code.range -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = INLINE_CODE_BACKGROUND,
                    ),
                ) {
                    append(code.groupValues[1])
                }
                remaining = remaining.substring(code.range.last + 1)
            }
            quote != null && firstMatch.range == quote.range -> {
                // 引号本身也应用颜色
                withStyle(SpanStyle(color = quoteColor)) {
                    append(quote.value)
                }
                remaining = remaining.substring(quote.range.last + 1)
            }
            link != null && firstMatch.range == link.range -> {
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(link.groupValues[1])
                }
                remaining = remaining.substring(link.range.last + 1)
            }
            italic != null && firstMatch.range == italic.range -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italic.groupValues[1])
                }
                remaining = remaining.substring(italic.range.last + 1)
            }
            action != null && firstMatch.range == action.range -> {
                // 括号本身也应用样式：斜体 + 橙色
                withStyle(
                    SpanStyle(
                        color = actionColor,
                        fontStyle = FontStyle.Italic,
                    ),
                ) {
                    append(action.value)
                }
                remaining = remaining.substring(action.range.last + 1)
            }
            else -> {
                append(remaining)
                break
            }
        }
    }
}
