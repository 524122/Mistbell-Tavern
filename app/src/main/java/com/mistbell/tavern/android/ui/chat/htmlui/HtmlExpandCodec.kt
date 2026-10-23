package com.mistbell.tavern.android.ui.chat.htmlui

/**
 * HTML 界面展开状态集合的持久化编码：逗号分隔的 messageId 列表。
 * messageId 为 UUID（不含逗号），故无需转义；非法片段解码时直接丢弃。
 */
object HtmlExpandCodec {
    fun encode(expandedIds: Set<String>): String = expandedIds.sorted().joinToString(",")

    fun decode(raw: String?): Set<String> =
        raw
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
