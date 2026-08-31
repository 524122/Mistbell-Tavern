package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TermExtractor 纯函数单元测试（F3-FTS 词法记忆回退）。
 * 覆盖：连续 CJK 段取相邻 bigram、单字 CJK、ASCII/数字段小写化、
 * 混合文本、符号/空输入、maxTerms 截断与长度降序、重复 bigram 去重。
 * 注：同长度词位按提取顺序稳定排列（Kotlin sortedByDescending 为稳定排序）。
 */
class TermExtractorTest {
    @Test
    fun `纯中文连续段取全部相邻bigram`() {
        // "天气真好" → 相邻 bigram：天气、气真、真好
        assertEquals(listOf("天气", "气真", "真好"), TermExtractor.extract("天气真好"))
    }

    @Test
    fun `单字CJK段取该字`() {
        assertEquals(listOf("嗨"), TermExtractor.extract("嗨"))
        // 单字夹在符号中仍是长度 1 的 CJK 段
        assertEquals(listOf("雨"), TermExtractor.extract("，雨！"))
    }

    @Test
    fun `ASCII与数字连续段取全长并小写`() {
        // "hello" 与 "World123" 各成一段，小写化后按长度降序
        assertEquals(listOf("world123", "hello"), TermExtractor.extract("hello World123"))
    }

    @Test
    fun `混合文本CJK与ASCII分段提取`() {
        // CJK 段 "今天"/"真好" 各取 bigram，ASCII 段 "weather" 全长小写；
        // 按长度降序：weather(7) > 今天(2) = 真好(2)
        assertEquals(listOf("weather", "今天", "真好"), TermExtractor.extract("今天weather真好"))
    }

    @Test
    fun `空与纯符号输入返回空表`() {
        assertEquals(emptyList<String>(), TermExtractor.extract(""))
        assertEquals(emptyList<String>(), TermExtractor.extract("   "))
        assertEquals(emptyList<String>(), TermExtractor.extract("！？。，、！?"))
        assertEquals(emptyList<String>(), TermExtractor.extract("!!@@##$$"))
    }

    @Test
    fun `maxTerms截断且保持长度降序`() {
        // 6 个汉字 → 5 个 bigram；截取前 3 仍是长度降序（此处等长，取提取顺序）
        val terms = TermExtractor.extract("一二三四五六", maxTerms = 3)
        assertEquals(3, terms.size)
        assertEquals(listOf("一二", "二三", "三四"), terms)
        // 混合长度时截断后仍严格非递增
        val mixed = TermExtractor.extract("abc 中文词语 query长句测试", maxTerms = 2)
        assertEquals(2, mixed.size)
        assertTrue(mixed[0].length >= mixed[1].length)
    }

    @Test
    fun `重复bigram去重`() {
        // "天天天气" → bigram 序列 天天、天天、天气 → 去重后 [天天, 天气]
        assertEquals(listOf("天天", "天气"), TermExtractor.extract("天天天气"))
        // 跨段重复也去重："天气" 出现在两个 CJK 段
        assertEquals(listOf("天气"), TermExtractor.extract("天气，天气"))
    }

    @Test
    fun `默认maxTerms为6且全部符合提取规则`() {
        // 8 个汉字 → 7 个 bigram，默认只取前 6 个
        val terms = TermExtractor.extract("一二三四五六七八")
        assertEquals(6, terms.size)
        assertEquals(listOf("一二", "二三", "三四", "四五", "五六", "六七"), terms)
    }
}
