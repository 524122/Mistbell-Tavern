package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.api.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息窗口分页纯函数的 JVM 单测（v16 性能修复）。
 *
 * 覆盖 mergeMessageWindow 的关键语义：空窗口直通、空 prepend 直通、
 * 复合边界过滤（修复3：createdAt 相同时按 id 决胜，与 getOlderBySession 的复合游标一致）、
 * 按 id 与窗口去重兜底，以及流式发射的时间窗节流判定。
 * 这些函数被设计为顶层纯函数，就是为了脱离 Android/协程环境直接验证合并正确性。
 */
class ChatViewModelLogicTest {
    private fun msg(
        id: String,
        createdAt: String,
        content: String = id,
    ) = Message(id = id, role = "user", content = content, createdAt = createdAt)

    // ---- mergeMessageWindow ----

    @Test
    fun `空窗口时直接返回 prepend 列表`() {
        val prepended = listOf(msg("a", "2026-01-01T00:00:00Z"))
        assertEquals(prepended, mergeMessageWindow(prepended, emptyList()))
    }

    @Test
    fun `空 prepend 时返回窗口本身`() {
        val window = listOf(msg("a", "2026-01-01T00:00:00Z"), msg("b", "2026-01-01T00:01:00Z"))
        assertEquals(window, mergeMessageWindow(emptyList(), window))
    }

    @Test
    fun `两者皆空时返回空列表`() {
        assertTrue(mergeMessageWindow(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `prepend 接在窗口前面且顺序保持升序`() {
        val prepended = listOf(msg("a", "00:00"), msg("b", "00:01"))
        val window = listOf(msg("c", "00:05"), msg("d", "00:06"))
        val merged = mergeMessageWindow(prepended, window)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun `复合边界同 createdAt 时按 id 决胜`() {
        // 修复3：getOlderBySession 改为 (created_at, id) 复合游标取数——导入会话保留原时间戳时，
        // 同 createdAt 且 id 小于窗口首条的 prepend 消息会被合法取到，合并侧必须保留；
        // id 大于窗口首条的不应出现（与游标取数语义互为镜像），避免同刻消息重复插入
        val prepended = listOf(msg("aaa", "00:05"), msg("zzz", "00:05"))
        val window = listOf(msg("mmm", "00:05"), msg("newer", "00:06"))
        val merged = mergeMessageWindow(prepended, window)
        assertEquals(listOf("aaa", "mmm", "newer"), merged.map { it.id })
    }

    @Test
    fun `晚于窗口边界的 prepend 消息被过滤`() {
        val prepended = listOf(msg("late", "00:09"))
        val window = listOf(msg("a", "00:05"), msg("b", "00:06"))
        val merged = mergeMessageWindow(prepended, window)
        assertEquals(window, merged)
    }

    @Test
    fun `与窗口 id 重复的 prepend 消息被兜底丢弃`() {
        // 防御并发/重放场景：窗口里已有的消息不允许因 prepend 再次出现
        val prepended = listOf(msg("dup", "00:00"), msg("old", "00:01"))
        val window = listOf(msg("dup", "00:05"), msg("b", "00:06"))
        val merged = mergeMessageWindow(prepended, window)
        assertEquals(listOf("old", "dup", "b"), merged.map { it.id })
        assertEquals(3, merged.size)
    }

    // ---- shouldEmitStreamText（流式时间窗节流） ----

    @Test
    fun `首次发射必然放行`() {
        // lastEmitAtNanos = 0 表示尚未发射过
        assertTrue(shouldEmitStreamText(0L, 1L))
    }

    @Test
    fun `间隔未满 80ms 的帧被节流丢弃`() {
        val now = 1_000_000_000L
        assertFalse(shouldEmitStreamText(now, now + STREAM_EMIT_INTERVAL_NS - 1))
    }

    @Test
    fun `间隔恰好达到 80ms 的帧放行`() {
        val now = 1_000_000_000L
        assertTrue(shouldEmitStreamText(now, now + STREAM_EMIT_INTERVAL_NS))
        assertTrue(shouldEmitStreamText(now, now + STREAM_EMIT_INTERVAL_NS + 1))
    }
}
