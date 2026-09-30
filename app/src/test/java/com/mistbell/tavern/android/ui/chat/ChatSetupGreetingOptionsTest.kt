package com.mistbell.tavern.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 开场白选项构建纯函数（buildGreetingOptions）的单测：首条缺省、空白过滤、纯备用兜底 */
class ChatSetupGreetingOptionsTest {
    @Test
    fun `首条非空时排在首位_备用依次跟随`() {
        val options = buildGreetingOptions("你好，旅行者。", listOf("嗯？", "第三条"))
        assertEquals(listOf("你好，旅行者。", "嗯？", "第三条"), options)
    }

    @Test
    fun `首条为空时由备用开场白顶上`() {
        val options = buildGreetingOptions("", listOf("备用一", "备用二"))
        assertEquals(listOf("备用一", "备用二"), options)
    }

    @Test
    fun `首条与备用都为空时选项列表为空`() {
        assertEquals(emptyList<String>(), buildGreetingOptions("", emptyList()))
        assertEquals(emptyList<String>(), buildGreetingOptions("  ", listOf("", " ")))
    }

    @Test
    fun `备用列表中的空白项被过滤_不产生空选项`() {
        val options = buildGreetingOptions("首条", listOf("有效", "", "  ", "也有效"))
        assertEquals(listOf("首条", "有效", "也有效"), options)
    }

    @Test
    fun `仅有首条时选项只有一个`() {
        val options = buildGreetingOptions("只有默认", emptyList())
        assertEquals(listOf("只有默认"), options)
    }
}
