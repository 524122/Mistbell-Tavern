package com.mistbell.tavern.android.util

/**
 * F3-FTS：词法召回的查询分词器（纯函数，无依赖）。
 *
 * 规则（契约 B）：
 * - 连续 CJK 段：长度为 1 时取该字；长度 ≥2 时取全部相邻 bigram（二元词片）；
 * - ASCII/数字连续段：长度 ≥2 时整段取出并转小写；
 * - 合并去重后按长度降序取前 maxTerms；
 * - 空串/纯符号输入 → 空表。
 */
object TermExtractor {
    fun extract(
        query: String,
        maxTerms: Int = 6,
    ): List<String> {
        if (query.isBlank()) return emptyList()

        val terms = linkedSetOf<String>()

        // 按连续段切分：每段要么全是 CJK，要么全是 ASCII/数字，要么是其它符号
        val segmentRegex = Regex("[\\u4e00-\\u9fff]+|[A-Za-z0-9]+|[^\\u4e00-\\u9fffA-Za-z0-9]+")
        segmentRegex.findAll(query).forEach { match ->
            val seg = match.value
            when {
                seg.first().isCjk() -> {
                    if (seg.length == 1) {
                        terms.add(seg)
                    } else {
                        // 全部相邻 bigram
                        seg.windowed(2).forEach { terms.add(it) }
                    }
                }
                seg[0].isLetterOrDigit() -> {
                    // ASCII/数字段：长度 ≥2 整段小写
                    if (seg.length >= 2) terms.add(seg.lowercase())
                }
                // 符号段：忽略
            }
        }

        return terms
            .sortedWith(compareByDescending<String> { it.length })
            .take(maxTerms.coerceAtLeast(0))
    }

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'
}
