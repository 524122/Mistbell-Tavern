package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SseParser.contentDelta 纯函数单元测试（F1 SSE 真流式）。
 * 覆盖契约边界：[DONE]、坏 JSON、空 choices、空 content、多 choices、finish_reason 尾包。
 */
class SseParserTest {

    @Test
    fun `DONE哨兵返回null`() {
        assertNull(SseParser.contentDelta("[DONE]"))
    }

    @Test
    fun `正常chunk提取content增量`() {
        val json = """{"id":"chatcmpl-1","choices":[{"delta":{"role":"assistant","content":"你好"}}]}"""
        assertEquals("你好", SseParser.contentDelta(json))
    }

    @Test
    fun `content为null返回null`() {
        val json = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        assertNull(SseParser.contentDelta(json))
    }

    @Test
    fun `content为空白返回null`() {
        val json = """{"choices":[{"delta":{"content":"  "}}]}"""
        assertNull(SseParser.contentDelta(json))
    }

    @Test
    fun `choices为空数组返回null`() {
        val json = """{"id":"chatcmpl-1","choices":[]}"""
        assertNull(SseParser.contentDelta(json))
    }

    @Test
    fun `坏JSON返回null`() {
        assertNull(SseParser.contentDelta("{not valid json"))
        assertNull(SseParser.contentDelta(""))
    }

    @Test
    fun `多choices取第一个非空content`() {
        val json = """{"choices":[{"delta":{"content":"第一"},"index":0},{"delta":{"content":"第二"},"index":1}]}"""
        assertEquals("第一", SseParser.contentDelta(json))
    }

    @Test
    fun `含finish_reason的收尾包content为null返回null`() {
        val json = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertNull(SseParser.contentDelta(json))
    }
}
