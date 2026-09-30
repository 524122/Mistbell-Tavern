package com.mistbell.tavern.android.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆抽取响应清洗/截断抢救的纯函数单测（真实失败形态见 salvage 用例） */
class MemoryExtractionJsonTest {
    // ---- cleanup：围栏、夹带说明文字、尾随逗号 ----

    @Test
    fun `剥掉 json 代码围栏`() {
        val raw = "```json\n{\"triplets\": []}\n```"
        assertEquals("{\"triplets\": []}", MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `剥掉裸代码围栏`() {
        val raw = "```\n{\"triplets\": []}\n```"
        assertEquals("{\"triplets\": []}", MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `模型夹带前置说明文字时截取 JSON 主体`() {
        val raw = "我们分析一下这段对话：\n{\"triplets\": [{\"subject\": \"user\"}]}"
        assertEquals("{\"triplets\": [{\"subject\": \"user\"}]}", MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `JSON 之后跟说明文字时截到末个收口字符`() {
        val raw = "{\"triplets\": []}\n以上就是提取结果。"
        assertEquals("{\"triplets\": []}", MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `裸数组响应不被误剪`() {
        val raw = "[{\"subject\": \"user\"}, {\"subject\": \"艾琳\"}]"
        assertEquals(raw, MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `去掉对象与数组的尾随逗号`() {
        val raw = "{\"triplets\": [{\"subject\": \"a\",},]}"
        assertEquals("{\"triplets\": [{\"subject\": \"a\"}]}", MemoryExtractionJson.cleanup(raw))
    }

    @Test
    fun `合法 JSON 原样通过`() {
        val raw = "{\"triplets\": [{\"subject\": \"user\", \"rawText\": \"user 的名字是墨轩\"}]}"
        assertEquals(raw, MemoryExtractionJson.cleanup(raw))
    }

    // ---- salvageTruncated：截断抢救 ----

    @Test
    fun `triplets 包装被截断时抢救出已完成条目`() {
        // 真实失败形态：输出在第三条中途被 finish_reason=length 砍断
        val truncated =
            "{\"triplets\": [" +
                "{\"subject\":\"user\",\"rawText\":\"user 的名字是墨轩\"}," +
                "{\"subject\":\"艾琳\",\"rawText\":\"艾琳欠 user 一次人情\"}," +
                "{\"subject\":\"凌月璃\",\"rawText\":\"凌月璃是玉珠宗门"

        val salvaged = MemoryExtractionJson.salvageTruncated(truncated)
        assertNotNull(salvaged)
        val triplets = Json.parseToJsonElement(salvaged!!).jsonObject["triplets"]!!.jsonArray
        assertEquals(2, triplets.size)
        assertEquals("user", triplets[0].jsonObject["subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun `裸数组被截断时补右括号`() {
        val truncated = "[{\"subject\":\"user\"},{\"subject\":\"艾琳\"},{\"subject\":\"凌"
        val salvaged = MemoryExtractionJson.salvageTruncated(truncated)
        assertEquals("[{\"subject\":\"user\"},{\"subject\":\"艾琳\"}]", salvaged)
        assertTrue(Json.parseToJsonElement(salvaged!!).jsonArray.size == 2)
    }

    @Test
    fun `没有可收口的完整对象时放弃抢救`() {
        // 数组开头就断了：第一个对象都没写完，抢救不出任何条目
        assertNull(MemoryExtractionJson.salvageTruncated("{\"triplets\": [{\"subject\":\"us"))
    }

    @Test
    fun `非数组结构不抢救`() {
        assertNull(MemoryExtractionJson.salvageTruncated("{\"error\": \"模型拒绝\"}"))
    }
}
