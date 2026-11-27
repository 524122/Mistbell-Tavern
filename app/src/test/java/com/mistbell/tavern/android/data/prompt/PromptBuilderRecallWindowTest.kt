package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.repository.LexicalMemoryService
import com.mistbell.tavern.android.data.repository.RecallWindow
import com.mistbell.tavern.android.data.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 混合召回的近期窗口剔除（PromptBuilder.prepareVectorHits）回归测试：
 * 向量路与词法路必须用同一套「当前会话最近 N 条 + 当前消息」排除（修复前向量路零排除，
 * 探针自己与最近几楼闲聊占掉召回名额）。JVM 上用 java.lang.reflect.Proxy 代理 MessageDao，
 * 未注册的方法直接抛——生产查询路径变了会立刻失败，而不是静默返回默认值。
 */
class PromptBuilderRecallWindowTest {
    /** 只注册 latestIdsBySession 的 DAO 替身：记录实参（验证排除窗口口径），按序返回 id */
    private class RecordingMessageDao(
        private val latestIds: List<String>,
    ) {
        val sessionIds = mutableListOf<String>()
        val ownerIds = mutableListOf<String>()
        val limits = mutableListOf<Int>()

        val proxy: MessageDao =
            Proxy.newProxyInstance(
                MessageDao::class.java.classLoader,
                arrayOf(MessageDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "latestIdsBySession" -> {
                        sessionIds += args[0] as String
                        ownerIds += args[1] as String
                        limits += args[2] as Int
                        latestIds
                    }
                    else -> throw UnsupportedOperationException("MessageDao.${method.name} 未在本测试注册")
                }
            } as MessageDao
    }

    /** 最近窗口消息 id：recent-0 最新，recent-39 第 40 新（与 DAO 的 created_at DESC LIMIT 同形） */
    private val recentIds = (0 until LexicalMemoryService.RECENT_WINDOW).map { "recent-$it" }

    private val window =
        RecallWindow(
            ownerId = "owner",
            currentSessionId = "session-1",
            characterId = null,
            sessionIds = setOf("session-1"),
            includeUnassigned = false,
        )

    private fun hit(
        messageId: String,
        score: Float = 0.8f,
        summary: Boolean = false,
    ) = VectorStore.SearchResult(
        id = "vec_$messageId",
        score = score,
        content = "内容 $messageId",
        metadata =
            buildMap {
                put("message_id", messageId)
                put("content_type", if (summary) VectorStore.SUMMARY_CONTENT_TYPE else "message")
            },
    )

    @Test
    fun `向量路剔除当前消息与最近窗口消息_保留更早的消息`() {
        val dao = RecordingMessageDao(recentIds)
        val results =
            listOf(
                // 当前用户消息自己（恰好在最近 N 条里）
                hit("probe-msg"),
                hit("recent-0"),
                hit("recent-17"),
                // 更早的消息：保留
                hit("old-100"),
                hit("old-200"),
            )

        val filter =
            runBlocking {
                PromptBuilder.prepareVectorHits(dao.proxy, window, "probe-msg", results)
            }

        assertEquals(listOf("old-100", "old-200"), filter.kept.map { it.messageId })
        assertEquals(3, filter.droppedCount)
        // 排除窗口按当前会话 + owner 查（与词法路同一套 DAO 查询）
        assertEquals(listOf("session-1"), dao.sessionIds)
        assertEquals(listOf("owner"), dao.ownerIds)
    }

    @Test
    fun `当前消息不在DAO窗口内也被显式剔除`() {
        val dao = RecordingMessageDao(recentIds) // DAO 返回的 id 不含 current-msg
        val results = listOf(hit("current-msg"), hit("old-100"))

        val filter =
            runBlocking {
                PromptBuilder.prepareVectorHits(dao.proxy, window, "current-msg", results)
            }

        assertEquals(listOf("old-100"), filter.kept.map { it.messageId })
        assertEquals(1, filter.droppedCount)
    }

    @Test
    fun `摘要副本不被误删`() {
        val dao = RecordingMessageDao(recentIds)
        val results =
            listOf(
                // 摘要副本：messageId 不在消息排除集里
                hit("structured_memory_12", summary = true),
                // 同一会话的近期消息：剔除
                hit("recent-3"),
                hit("structured_memory_999", summary = true),
            )

        val filter =
            runBlocking {
                PromptBuilder.prepareVectorHits(dao.proxy, window, "current-msg", results)
            }

        assertEquals(
            listOf("structured_memory_12", "structured_memory_999"),
            filter.kept.map { it.messageId },
        )
        assertEquals(1, filter.droppedCount)
        assertTrue(filter.kept.all { it.isSummary })
    }

    @Test
    fun `词法路与向量路用同一个N`() {
        val dao = RecordingMessageDao(recentIds)

        runBlocking { PromptBuilder.prepareVectorHits(dao.proxy, window, "probe-msg", listOf(hit("old-1"))) }

        // 向量路查排除集时传的 LIMIT 必须就是词法路的那个常量（两边不再各写一个数字）
        assertEquals(listOf(LexicalMemoryService.RECENT_WINDOW), dao.limits)
        assertEquals(40, LexicalMemoryService.RECENT_WINDOW)
    }

    @Test
    fun `空窗口与空当前消息时候选全保留`() {
        val dao = RecordingMessageDao(emptyList())

        val filter =
            runBlocking {
                PromptBuilder.prepareVectorHits(dao.proxy, window, null, listOf(hit("old-1"), hit("old-2")))
            }

        assertEquals(2, filter.kept.size)
        assertEquals(0, filter.droppedCount)
    }

    // ---- 诊断：因近期窗口被剔除的向量候选数（纯增量字段，默认 0） ----

    @Test
    fun `诊断记录近窗剔除条数`() {
        val diag =
            RecallDiagnostics(
                vectorCandidates = 15,
                lexicalCandidates = 3,
                fusedCount = 5,
                injectedRecallLines = 5,
                recentWindowDropped = 7,
            )

        val line = diag.summaryLine()

        assertTrue(line.contains("近窗剔除 7 条"))
    }

    @Test
    fun `无剔除时诊断不出现近窗字样`() {
        val diag =
            RecallDiagnostics(
                vectorCandidates = 15,
                lexicalCandidates = 3,
                fusedCount = 5,
                injectedRecallLines = 5,
            )

        assertFalse(diag.summaryLine().contains("近窗"))
        assertEquals(0, diag.recentWindowDropped)
    }

    @Test
    fun `召回块整体未注入时剔除数仍然可见`() {
        val diag =
            RecallDiagnostics(
                vectorCandidates = 15,
                fusedCount = 0,
                droppedReason = RecallDiagnostics.DROP_NO_HITS,
                recentWindowDropped = 9,
            )

        assertTrue(diag.summaryLine().contains("近窗剔除 9 条"))
        assertTrue(diag.summaryLine().contains(RecallDiagnostics.DROP_NO_HITS))
    }
}
