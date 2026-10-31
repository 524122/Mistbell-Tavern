package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆助手消息写 API 负载契约测试：批量/正文/id 三重上限、role 白名单、
 * 坏 JSON 整单拒绝（部分写入会让页面侧状态与预期错位，宁可整单失败让脚本重试）、响应 JSON 形态。
 */
class BridgeMessageContractTest {
    // ---- parsePatches：[{id, content, thinking?}] ----

    @Test
    fun `parsePatches 单条正常解析`() {
        val parsed = BridgeMessageContract.parsePatches("""[{"id":"m1","content":"新正文"}]""")
        val patches = (parsed as BridgeMessageContract.Parsed.Ok).value
        assertEquals(listOf(BridgeMessageContract.MessagePatch(id = "m1", content = "新正文")), patches)
    }

    @Test
    fun `parsePatches 批量解析 thinking 有值与显式 null`() {
        val parsed =
            BridgeMessageContract.parsePatches(
                """[{"id":"a","content":"一","thinking":"思维链"},{"id":"b","content":"二","thinking":null}]""",
            )
        val patches = (parsed as BridgeMessageContract.Parsed.Ok).value
        assertEquals(2, patches.size)
        assertEquals("思维链", patches[0].thinking)
        assertEquals(null, patches[1].thinking)
    }

    @Test
    fun `parsePatches 批量超过 50 条整单拒绝`() {
        val payload = (1..51).joinToString(",", prefix = "[", postfix = "]") { """{"id":"m$it","content":"x"}""" }
        val parsed = BridgeMessageContract.parsePatches(payload)
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("50"))
    }

    @Test
    fun `parsePatches content 缺失拒绝`() {
        val parsed = BridgeMessageContract.parsePatches("""[{"id":"m1"}]""")
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("content"))
    }

    @Test
    fun `parsePatches content 上限 20000 边界内通过超限拒绝`() {
        val ok = BridgeMessageContract.parsePatches("""[{"id":"m1","content":"${"长".repeat(20_000)}"}]""")
        assertTrue(ok is BridgeMessageContract.Parsed.Ok)
        val over = BridgeMessageContract.parsePatches("""[{"id":"m1","content":"${"长".repeat(20_001)}"}]""")
        assertTrue(over is BridgeMessageContract.Parsed.Err)
        assertTrue((over as BridgeMessageContract.Parsed.Err).reason.contains("20000"))
    }

    @Test
    fun `parsePatches id 为空或超 64 字符拒绝`() {
        val blank = BridgeMessageContract.parsePatches("""[{"id":"","content":"x"}]""")
        assertTrue(blank is BridgeMessageContract.Parsed.Err)
        assertTrue((blank as BridgeMessageContract.Parsed.Err).reason.contains("id"))
        val tooLong = BridgeMessageContract.parsePatches("""[{"id":"${"i".repeat(65)}","content":"x"}]""")
        assertTrue(tooLong is BridgeMessageContract.Parsed.Err)
        // 边界内 64 字符合法
        assertTrue(
            BridgeMessageContract.parsePatches("""[{"id":"${"i".repeat(64)}","content":"x"}]""")
                is BridgeMessageContract.Parsed.Ok,
        )
    }

    @Test
    fun `parsePatches 坏 JSON 整单拒绝且原因含 JSON`() {
        val parsed = BridgeMessageContract.parsePatches("not-json")
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("JSON"))
    }

    // ---- parseNewMessage：{role, content} ----

    @Test
    fun `parseNewMessage 正常解析 role 与 content`() {
        val parsed = BridgeMessageContract.parseNewMessage("""{"role":"assistant","content":"回复"}""")
        val message = (parsed as BridgeMessageContract.Parsed.Ok).value
        assertEquals("assistant", message.role)
        assertEquals("回复", message.content)
    }

    @Test
    fun `parseNewMessage role 不在白名单拒绝`() {
        val parsed = BridgeMessageContract.parseNewMessage("""{"role":"system","content":"x"}""")
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("role"))
    }

    @Test
    fun `parseNewMessage content 超长拒绝`() {
        val payload = """{"role":"user","content":"${"c".repeat(20_001)}"}"""
        val parsed = BridgeMessageContract.parseNewMessage(payload)
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("20000"))
    }

    // ---- parseIds：[id, ...] ----

    @Test
    fun `parseIds 正常解析`() {
        val parsed = BridgeMessageContract.parseIds("""["a","b"]""")
        assertEquals(listOf("a", "b"), (parsed as BridgeMessageContract.Parsed.Ok).value)
    }

    @Test
    fun `parseIds 含非法元素整单拒绝`() {
        // 非字符串元素（对象）与空串 id 都非法
        val notPrimitive = BridgeMessageContract.parseIds("""["a",{}]""")
        assertTrue(notPrimitive is BridgeMessageContract.Parsed.Err)
        val blank = BridgeMessageContract.parseIds("""["a",""]""")
        assertTrue(blank is BridgeMessageContract.Parsed.Err)
        assertTrue((blank as BridgeMessageContract.Parsed.Err).reason.contains("id"))
    }

    @Test
    fun `parseIds 批量超过 50 条拒绝`() {
        val payload = (1..51).joinToString(",", prefix = "[", postfix = "]") { """"id-$it"""" }
        val parsed = BridgeMessageContract.parseIds(payload)
        assertTrue(parsed is BridgeMessageContract.Parsed.Err)
        assertTrue((parsed as BridgeMessageContract.Parsed.Err).reason.contains("50"))
    }

    // ---- 响应构造 ----

    @Test
    fun `okUpdated okCreated okDeleted 精确 JSON 串`() {
        assertEquals("""{"updated":2}""", BridgeMessageContract.okUpdated(2))
        assertEquals("""{"id":"m1"}""", BridgeMessageContract.okCreated("m1"))
        assertEquals("""{"deleted":3}""", BridgeMessageContract.okDeleted(3))
    }

    @Test
    fun `error 响应格式`() {
        assertEquals("""{"error":"x"}""", BridgeMessageContract.error("x"))
    }
}
