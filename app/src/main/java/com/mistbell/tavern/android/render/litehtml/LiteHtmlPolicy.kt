package com.mistbell.tavern.android.render.litehtml

/**
 * 聊天气泡里哪些 HTML 可以交给 litehtml。
 * style / table 只做排版；script、iframe、事件处理和 javascript: 链接仍留给现有 HTML 气泡。
 */
object LiteHtmlPolicy {
    private val SCRIPT_SURFACE =
        Regex(
            """<(?:script|iframe|object|embed)\b|\son[a-z]+\s*=|javascript:""",
            RegexOption.IGNORE_CASE,
        )

    private val LAYOUT_SURFACE =
        Regex(
            """<(?:style|table|thead|tbody|tfoot|tr|td|th)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val HEAD_OPEN = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)

    /** 含脚本运行面。这类片段不进 litehtml。 */
    fun requiresScriptRuntime(html: String): Boolean = SCRIPT_SURFACE.containsMatchIn(html)

    /** 有 style 或表格，且没有脚本运行面。 */
    fun isLayoutHtml(html: String): Boolean = !requiresScriptRuntime(html) && LAYOUT_SURFACE.containsMatchIn(html)

    /**
     * 给未指定颜色的正文一个气泡文字色。插在作者样式之前，作者的 `p{color:...}` 仍能覆盖。
     * [colorCss] 形如 `#112233`。
     */
    fun withBubbleTextColor(
        html: String,
        colorCss: String,
    ): String {
        val rule = "<style>body{color:$colorCss}</style>"
        val head = HEAD_OPEN.find(html) ?: return rule + html
        val insertAt = head.range.last + 1
        return html.replaceRange(insertAt, insertAt, rule)
    }

    /** ARGB 整数转 CSS `#RRGGBB`，忽略透明度。 */
    fun colorToCss(argb: Int): String = "#%06X".format(argb and RGB_MASK)

    private const val RGB_MASK = 0x00FFFFFF
}
