package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 群聊说话方解析纯函数测试（JVM，无 Android 依赖）。
 *
 * 覆盖：半角/全角冒号、最长名字优先、无匹配、前缀后空白剥离、名字与冒号间空白容错（契约 5）、
 * 空 map、内容为空、名字本身含冒号的边界、名字带首尾空白的 trim 对齐、前导空白剥离。
 */
class GroupSpeakerTest {
    private val speakers = mapOf("npc-1" to "艾莉丝", "npc-2" to "Bob", "npc-3" to "Alice Chen")

    @Test
    fun `半角冒号前缀命中并返回对应 id`() {
        val result = parseGroupSpeaker("艾莉丝: 你们好呀。", speakers)
        assertNotNull(result)
        assertEquals("npc-1", result!!.speakerId)
        assertEquals("你们好呀。", result.strippedContent)
    }

    @Test
    fun `全角冒号前缀同样命中`() {
        val result = parseGroupSpeaker("Bob：这是一段全角冒号的发言。", speakers)
        assertNotNull(result)
        assertEquals("npc-2", result!!.speakerId)
        assertEquals("这是一段全角冒号的发言。", result.strippedContent)
    }

    @Test
    fun `多名字同时前缀匹配时取名字最长者`() {
        // "Alice" 不在表内，但 "Alice Chen" 命中；构造两个互为前缀的候选验证最长优先
        val candidates = mapOf("npc-a" to "Alice", "npc-b" to "Alice Chen")
        val result = parseGroupSpeaker("Alice Chen: 长名字优先。", candidates)
        assertNotNull(result)
        assertEquals("npc-b", result!!.speakerId)
        assertEquals("长名字优先。", result.strippedContent)
    }

    @Test
    fun `名字后面没有冒号不视为命中`() {
        // 开头是名字但紧跟的不是冒号（如正文以名字起句），必须返回 null
        assertNull(parseGroupSpeaker("Bob 今天心情不错", speakers))
    }

    @Test
    fun `前缀后多余空白被剥离`() {
        val result = parseGroupSpeaker("艾莉丝:    前面有一段空白。", speakers)
        assertNotNull(result)
        assertEquals("前面有一段空白。", result!!.strippedContent)
    }

    @Test
    fun `名字与冒号之间的空白被容错命中`() {
        // 跨代理契约 5：名字与冒号之间允许 0..n 空白，"Bob : hi" 也命中
        val result = parseGroupSpeaker("Bob : hi", speakers)
        assertNotNull(result)
        assertEquals("npc-2", result!!.speakerId)
        assertEquals("hi", result.strippedContent)
    }

    @Test
    fun `名字与全角冒号之间多个空白同样命中`() {
        val result = parseGroupSpeaker("艾莉丝   ：   内容在冒号后。", speakers)
        assertNotNull(result)
        assertEquals("npc-1", result!!.speakerId)
        assertEquals("内容在冒号后。", result.strippedContent)
    }

    @Test
    fun `名字与冒号间有空白且冒号后内容为空仍命中`() {
        val result = parseGroupSpeaker("Bob  :", speakers)
        assertNotNull(result)
        assertEquals("npc-2", result!!.speakerId)
        assertEquals("", result.strippedContent)
    }

    @Test
    fun `名字后是正文再接冒号不命中`() {
        // 空白容错不吞正文：名字后除空白外还有其他字符时不得把正文首词当冒号跳过
        assertNull(parseGroupSpeaker("Bob x: 这里不是 Bob 的发言前缀", speakers))
    }

    @Test
    fun `空 speakerNames 返回 null`() {
        assertNull(parseGroupSpeaker("艾莉丝: 内容", emptyMap()))
    }

    @Test
    fun `冒号后内容为空仍视为命中`() {
        val result = parseGroupSpeaker("Bob:", speakers)
        assertNotNull(result)
        assertEquals("npc-2", result!!.speakerId)
        assertEquals("", result.strippedContent)
    }

    @Test
    fun `名字本身含冒号时最长优先可正确切分`() {
        // 边界：角色名里带冒号（"Bot:X"），短候选 "Bot" 会在名字内冒号处错切；
        // 长名字整体匹配后剩余部分才是正文
        val candidates = mapOf("npc-x" to "Bot", "npc-y" to "Bot:X")
        val result = parseGroupSpeaker("Bot:X: 真正的发言。", candidates)
        assertNotNull(result)
        assertEquals("npc-y", result!!.speakerId)
        assertEquals("真正的发言。", result.strippedContent)
    }

    @Test
    fun `名字表中的首尾空白被 trim 后对齐`() {
        val sloppy = mapOf("npc-1" to " 艾莉丝 ")
        val result = parseGroupSpeaker("艾莉丝: 内容", sloppy)
        assertNotNull(result)
        assertEquals("npc-1", result!!.speakerId)
        assertEquals("内容", result.strippedContent)
    }

    @Test
    fun `回复开头的前导空白被 trimStart 后参与匹配`() {
        val result = parseGroupSpeaker("  \n Bob: 缩进开头的回复", speakers)
        assertNotNull(result)
        assertEquals("npc-2", result!!.speakerId)
        assertEquals("缩进开头的回复", result.strippedContent)
    }

    @Test
    fun `内容整体为空白时返回 null`() {
        assertNull(parseGroupSpeaker("   ", speakers))
    }
}
