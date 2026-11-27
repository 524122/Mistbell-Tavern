package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.repository.InjectedMemoriesEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「记忆可见 v0」内存账本的 JVM 单测：事件入账（N 的口径）、忘记同步到**所有**消息、
 * 会话切换清空与跨会话事件过滤。全部走手写状态容器，不需要 Android/协程环境。
 */
class InjectedMemoriesStoreTest {
    private companion object {
        const val SESSION = "s1"
        const val OTHER_SESSION = "s2"

        fun event(
            messageId: String,
            sessionId: String = SESSION,
            structured: List<String> = emptyList(),
            recall: List<String> = emptyList(),
        ) = InjectedMemoriesEvent(
            ownerId = "local-user",
            sessionId = sessionId,
            assistantMessageId = messageId,
            structuredMemoryIds = structured,
            recallMessageIds = recall,
        )
    }

    @Test
    fun `事件按消息id入账_N取结构化记忆条数_召回行单算`() {
        val store = InjectedMemoriesStore()
        store.onSessionChanged(SESSION)
        store.onEvent(
            event(
                messageId = "a1",
                structured = listOf("11", "12"),
                recall = listOf("m1", "m2", "m3"),
            ),
        )

        val injected = store.state.value["a1"]
        assertEquals(2, injectedMemoryBadgeCount(injected))
        assertEquals(listOf("11", "12"), injected?.structuredMemoryIds)
        assertEquals(listOf("m1", "m2", "m3"), injected?.recallMessageIds)
    }

    @Test
    fun `没有结构化记忆时标记为null_召回行再多也不显示`() {
        val store = InjectedMemoriesStore()
        store.onSessionChanged(SESSION)
        store.onEvent(event(messageId = "a2", recall = listOf("m1")))
        store.onEvent(event(messageId = "a3"))

        assertNull(injectedMemoryBadgeCount(store.state.value["a2"]))
        assertNull(injectedMemoryBadgeCount(store.state.value["a3"]))
        assertNull(injectedMemoryBadgeCount(null))
    }

    @Test
    fun `忘记后记忆id从所有消息的清单移除`() {
        val store = InjectedMemoriesStore()
        store.onSessionChanged(SESSION)
        store.onEvent(event(messageId = "a1", structured = listOf("11", "12")))
        store.onEvent(event(messageId = "a2", structured = listOf("12"), recall = listOf("m1")))

        store.forgetMemory("12")

        assertEquals(listOf("11"), store.state.value["a1"]?.structuredMemoryIds)
        assertEquals(listOf("m1"), store.state.value["a2"]?.recallMessageIds)
        assertEquals(1, injectedMemoryBadgeCount(store.state.value["a1"]))
        assertNull(injectedMemoryBadgeCount(store.state.value["a2"]))
    }

    @Test
    fun `切换会话即清空_其他会话的事件不入账`() {
        val store = InjectedMemoriesStore()
        store.onSessionChanged(SESSION)
        store.onEvent(event(messageId = "a1", structured = listOf("11")))
        assertEquals(1, store.state.value.size)

        // 未切会话时：别的会话（或空会话）的迟到事件直接丢弃，防串染
        store.onEvent(event(messageId = "a-other", sessionId = OTHER_SESSION, structured = listOf("99")))
        store.onEvent(event(messageId = "a-blank", sessionId = "", structured = listOf("99")))
        assertEquals(1, store.state.value.size)

        // 切换会话：账本清空，旧会话条目不再可达；随后只认新会话的事件
        store.onSessionChanged(OTHER_SESSION)
        assertEquals(0, store.state.value.size)
        store.onEvent(event(messageId = "a2", sessionId = OTHER_SESSION, structured = listOf("21")))
        assertEquals(1, store.state.value.size)
        assertEquals(listOf("21"), store.state.value["a2"]?.structuredMemoryIds)
    }

    @Test
    fun `同一条消息重发事件按新内容覆盖`() {
        val store = InjectedMemoriesStore()
        store.onSessionChanged(SESSION)
        store.onEvent(event(messageId = "a1", structured = listOf("11"), recall = listOf("m1")))
        // 续写/swipe：同一条消息的新内容又注入了一批，覆盖旧清单
        store.onEvent(event(messageId = "a1", structured = listOf("12")))

        assertEquals(listOf("12"), store.state.value["a1"]?.structuredMemoryIds)
        assertEquals(emptyList<String>(), store.state.value["a1"]?.recallMessageIds)
    }
}
