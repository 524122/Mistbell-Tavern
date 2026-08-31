package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer(modifier = modifier) {
        val segments = parseMarkdown(content)
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
                        val annotated =
                            buildAnnotatedString {
                                appendInlineMarkdown(segment.text)
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
        val headerMatch = Regex("^(#{1,6})\\s+(.+)").find(line.trim())
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            segments.add(MdSegment.Header(headerMatch.groupValues[2], level))
            i++
            continue
        }

        // List item
        val listMatch =
            Regex("^([\\s]*[-*+]\\s+)(.+)").find(line)
                ?: Regex("^(\\s*\\d+\\.\\s+)(.+)").find(line)
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
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !Regex("^#{1,6}\\s+").containsMatchIn(lines[i].trim())
        ) {
            paragraphLines.add(lines[i])
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            segments.add(MdSegment.Paragraph(paragraphLines.joinToString(" ")))
        }
    }

    return segments
}

@Composable
fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    // 获取主题感知的引号颜色
    val quoteColor =
        if (isSystemInDarkTheme()) {
            AccentGreenDark
        } else {
            AccentGreen
        }

    // 获取主题感知的动作颜色
    val actionColor =
        if (isSystemInDarkTheme()) {
            AccentOrangeDark
        } else {
            AccentOrange
        }

    // Handle bold, italic, inline code, links, quotes, and actions
    var remaining = text
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    val italicPattern = Regex("\\*(.+?)\\*")
    val codePattern = Regex("`(.+?)`")
    val linkPattern = Regex("\\[(.+?)\\]\\((.+?)\\)")
    // 支持多种引号类型：英文双引号、单引号、中文双引号、单引号、日文引号
    val quotePattern = Regex("""["「『]([^"」』]+?)["」』]|'([^']+?)'|"([^"]+?)"|'([^']+?)'""")
    // 支持圆括号和中文圆括号，但需要排除链接语法 [text](url)
    val actionPattern = Regex("""(?<!\])\(([^)]+?)\)|（([^）]+?)）""")

    // Simple sequential approach: find earliest match and process
    while (remaining.isNotEmpty()) {
        val bold = boldPattern.find(remaining)
        val italic = italicPattern.find(remaining)
        val code = codePattern.find(remaining)
        val link = linkPattern.find(remaining)
        val quote = quotePattern.find(remaining)
        val action = actionPattern.find(remaining)

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
                        background = androidx.compose.ui.graphics.Color(0x1A000000),
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
                        color = MaterialTheme.colorScheme.primary,
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
