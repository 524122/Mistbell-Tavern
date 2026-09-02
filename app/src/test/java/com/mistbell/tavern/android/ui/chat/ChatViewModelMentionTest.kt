package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.api.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * @提及 解析纯函数 resolveMentionTarget 的 JVM 单测（契约 6 minor 对齐项）。
 *
 * 名字匹配语义与 util/GroupSpeaker.kt 的 parseGroupSpeaker 对齐：
 * - 候选名先 trim 再前缀匹配（名字带首尾空白同样可命中）；
 * - "@名字" 后允许 0..n 空白再跟冒号（半角/全角都认）；
 * - 兼容 @提及 自然书写：紧跟空白分隔符（"@A 你好"）也命中；
 * - 无分隔符（"@A你好"）不命中；多候选前缀重叠时最长名字优先。
 */
class ChatViewModelMentionTest {
    private fun participant(
        id: String,
        name: String,
    ) = Character(id = id, name = name)

    // "@A 你好"：空白分隔符（提及自然书写）命中
    @Test
    fun `空格分隔符命中`() {
        val participants = listOf(participant("a", "A"))
        assertEquals("a", resolveMentionTarget("@A 你好", participants))
    }

    // "@A：你好"：全角冒号命中
    @Test
    fun `全角冒号分隔符命中`() {
        val participants = listOf(participant("a", "A"))
        assertEquals("a", resolveMentionTarget("@A：你好", participants))
    }

    // "@A:你好"：半角冒号命中
    @Test
    fun `半角冒号分隔符命中`() {
        val participants = listOf(participant("a", "A"))
        assertEquals("a", resolveMentionTarget("@A:你好", participants))
    }

    // 名字与冒号之间 0..n 空白（对齐 parseGroupSpeaker 契约 5 的 trim 语义）
    @Test
    fun `名字与冒号之间允许空白`() {
        val participants = listOf(participant("a", "A"))
        assertEquals("a", resolveMentionTarget("@A : 你好", participants))
        assertEquals("a", resolveMentionTarget("@A： 你好", participants))
    }

    // 无分隔符返回 null：排除 "@AliceBot hi" 对 "Alice" 的误匹配
    @Test
    fun `无分隔符返回null`() {
        val participants = listOf(participant("a", "A"))
        assertNull(resolveMentionTarget("@A你好", participants))
        val alice = listOf(participant("alice", "Alice"))
        assertNull(resolveMentionTarget("@AliceBot hi", alice))
        // 只有 @ 无名字、@名字后无任何内容同样不命中
        assertNull(resolveMentionTarget("@", alice))
        assertNull(resolveMentionTarget("@Alice", alice))
        assertNull(resolveMentionTarget("@Alice你好", alice))
    }

    // 多候选前缀重叠时最长名字优先
    @Test
    fun `多候选最长名字优先`() {
        val participants =
            listOf(
                participant("short", "Alice"),
                participant("long", "Alice Chen"),
            )
        assertEquals("long", resolveMentionTarget("@Alice Chen: 你好", participants))
        assertEquals("long", resolveMentionTarget("@Alice Chen 你好", participants))
        // 只匹配到短名时仍返回短名
        assertEquals("short", resolveMentionTarget("@Alice: 你好", participants))
    }

    // 名字带空白：候选名先 trim 再匹配（对齐 parseGroupSpeaker）
    @Test
    fun `名字带首尾空白经trim后命中`() {
        val participants = listOf(participant("a", "  A  "))
        assertEquals("a", resolveMentionTarget("@A: 你好", participants))
        assertEquals("a", resolveMentionTarget("@A 你好", participants))
        // 未 trim 的原名无法被 "@A" 前缀命中——trim 后必须命中
    }

    // 名字含内部空格：按前缀匹配而非按分隔符截断
    @Test
    fun `名字含内部空格完整前缀匹配`() {
        val participants = listOf(participant("chen", "Alice Chen"))
        assertEquals("chen", resolveMentionTarget("@Alice Chen: 你好", participants))
        assertNull(resolveMentionTarget("@Alice 你好", participants))
    }

    // 非 @ 开头、空名单、空白名字候选一律不命中
    @Test
    fun `非提及与非法候选返回null`() {
        val participants = listOf(participant("a", "A"))
        assertNull(resolveMentionTarget("你好 @A:", participants))
        assertNull(resolveMentionTarget("@A 你好", emptyList()))
        assertNull(resolveMentionTarget("@A 你好", listOf(participant("blank", "   "))))
    }
}
