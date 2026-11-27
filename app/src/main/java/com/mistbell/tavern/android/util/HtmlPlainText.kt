package com.mistbell.tavern.android.util

/**
 * HTML 片段 → 可读纯文本（P3「关掉后的兜底」，docs/UI_MODE.md）。
 *
 * 只在 MessageBubble 的「HTML 界面关闭且片段不是布局 HTML」分支使用：那里原来把原始
 * 标签当纯文本裸露给用户。本函数把标签收掉，让兜底分支给出可读文本；带 `<style>` /
 * `<table>` 且无脚本的片段仍走 litehtml，不经过这里。
 *
 * 零依赖纯函数（不碰 android.text.Html，JVM 可单测）：
 * - `<style>` / `<script>` 整块（含内容）删除；
 * - 块级标签与 `<br>` 转换行，表格单元格以空格分隔；
 * - 其余标签剥掉；
 * - 还原常见命名实体与数字（十进制/十六进制）实体；
 * - 纯文本输入逐字节原样返回（无标签/实体时不做任何改动）。
 */
object HtmlPlainText {
    /** `<script>` / `<style>` 整块删除：未闭合时删到文本末尾（坏片段也不裸露内容） */
    private val BLOCKED_ELEMENTS =
        listOf(
            Regex("<script\\b[^>]*>[\\s\\S]*?(?:</script\\s*>|$)", RegexOption.IGNORE_CASE),
            Regex("<style\\b[^>]*>[\\s\\S]*?(?:</style\\s*>|$)", RegexOption.IGNORE_CASE),
        )

    /** `<br>` 变体 → 换行 */
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** 块级标签（开或闭）→ 换行：p/div/h1-6/li/tr/table 等常见排版标签 */
    private val BLOCK_TAGS =
        Regex(
            "</?(?:address|article|aside|blockquote|caption|dd|div|dl|dt|fieldset|figcaption|figure|" +
                "footer|form|h[1-6]|header|hr|li|main|nav|ol|p|pre|section|table|tbody|" +
                "thead|tfoot|tr|ul)\\b[^>]*/?>",
            RegexOption.IGNORE_CASE,
        )

    /** 表格单元格（开或闭）→ 空格：同一行内的单元格保持可读的分隔 */
    private val CELL_TAGS = Regex("</?(?:td|th)\\b[^>]*/?>", RegexOption.IGNORE_CASE)

    /** 其余一切标签 */
    private val ANY_TAG = Regex("<[^>]*>")

    /** 命名实体（覆盖 HTML 常用集合）；未列出的原样保留 */
    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
        )

    /** `&#NNNN;` 十进制实体 */
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")

    /** 十六进制实体的进制（const：MagicNumber 豁免） */
    private const val HEX_RADIX: Int = 16

    /** `&#xHHHH;` 十六进制实体 */
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    /** `&name;` 命名实体（查表） */
    private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")

    fun toPlainText(html: String): String {
        if (!html.contains('<') && !html.contains('&')) return html
        var text = html
        BLOCKED_ELEMENTS.forEach { text = text.replace(it, "") }
        text = text.replace(BR, "\n")
        text = text.replace(CELL_TAGS, " ")
        text = text.replace(BLOCK_TAGS, "\n")
        text = text.replace(ANY_TAG, "")
        text = decodeEntities(text)
        return normalizeWhitespace(text)
    }

    /** 实体还原：数字实体按码点；命名实体查表，未知的原样保留 */
    private fun decodeEntities(text: String): String =
        text
            .replace(NUMERIC_ENTITY) { match -> String(Character.toChars(match.groupValues[1].toInt())) }
            .replace(HEX_ENTITY) { match -> String(Character.toChars(match.groupValues[1].toInt(HEX_RADIX))) }
            .replace(NAMED_ENTITY) { match -> NAMED_ENTITIES[match.groupValues[1]] ?: match.value }

    /** 逐行去首尾空白、连续空白收敛为单个（单元格/块级标签连排不留双空格或空行）、去首尾换行 */
    private fun normalizeWhitespace(text: String): String =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { it.trim() }
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\n{2,}"), "\n")
            .trim('\n')
}
