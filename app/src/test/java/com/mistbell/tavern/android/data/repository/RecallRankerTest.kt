package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.repository.RecallRanker.RecallCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecallRanker 融合排序契约：RRF 归并、阈值政策、时间/类型权重、内容去重、topK 截断。
 * 纯 JVM 测试（RecallRanker 无 Android/Room 依赖）。
 */
class RecallRankerTest {
    private val now: Long = 1_700_000_000_000L

    private fun candidate(
        id: String,
        score: Float? = null,
        ageDays: Float? = null,
        isSummary: Boolean = false,
        content: String = "内容$id",
    ) = RecallCandidate(
        messageId = id,
        content = content,
        vectorScore = score,
        createdAtEpochMs = ageDays?.let { now - (it * 24 * 60 * 60 * 1000).toLong() },
        isSummary = isSummary,
    )

    private fun fuse(
        vector: List<RecallCandidate>,
        lexical: List<RecallCandidate> = emptyList(),
        topK: Int = 10,
        threshold: Float = 0.5f,
    ) = RecallRanker.fuse(vector, lexical, topK, threshold, now)

    @Test
    fun `两路都中的候选排在单路候选之前`() {
        val a = candidate("a", score = 0.9f)
        val b = candidate("b")

        val ranked = fuse(vector = listOf(a), lexical = listOf(a, b))

        assertEquals(listOf("a", "b"), ranked.map { it.candidate.messageId })
        assertTrue(ranked[0].fromVector && ranked[0].fromLexical)
        assertFalse(ranked[1].fromVector)
        assertTrue(ranked[1].fromLexical)
    }

    @Test
    fun `低于阈值且有词法命中的候选保留`() {
        val lowScore = candidate("low", score = 0.3f)

        val ranked = fuse(vector = listOf(lowScore), lexical = listOf(lowScore), threshold = 0.5f)

        assertEquals(listOf("low"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `低于阈值且无词法命中的候选丢弃`() {
        val high = candidate("high", score = 0.6f)
        val low = candidate("low", score = 0.3f)

        val ranked = fuse(vector = listOf(high, low), threshold = 0.5f)

        assertEquals(listOf("high"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `分数恰好等于阈值的候选保留`() {
        val edge = candidate("edge", score = 0.5f)

        val ranked = fuse(vector = listOf(edge), threshold = 0.5f)

        assertEquals(listOf("edge"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `纯词法候选保留且不标记向量来源`() {
        val lexicalOnly = candidate("lex")

        val ranked = fuse(vector = emptyList(), lexical = listOf(lexicalOnly))

        assertEquals(listOf("lex"), ranked.map { it.candidate.messageId })
        assertFalse(ranked[0].fromVector)
        assertTrue(ranked[0].fromLexical)
    }

    @Test
    fun `融合分相同时新消息排在旧消息之前`() {
        // 向量路头名与词法路头名的 RRF 相同（都是 1/(60+0)），此时时间因子裁决
        val fresh = candidate("fresh", score = 0.9f, ageDays = 0f)
        val stale = candidate("stale", ageDays = 30f)

        val ranked = fuse(vector = listOf(fresh), lexical = listOf(stale))

        assertEquals(listOf("fresh", "stale"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `巨大名次差距不被时间因子翻案`() {
        // 一年前的向量头名 vs 此刻的向量第 11 名：RRF 差距远超 15% 的权重上限
        val oldTop = candidate("oldTop", score = 0.9f, ageDays = 365f)
        val freshTail = candidate("freshTail", score = 0.8f, ageDays = 0f)
        val fillers = (1..9).map { candidate("f$it", score = 0.85f, ageDays = 100f) }

        val ranked = fuse(vector = listOf(oldTop) + fillers + listOf(freshTail))

        assertEquals("oldTop", ranked.first().candidate.messageId)
    }

    @Test
    fun `summary 加成让事实副本反超相邻名次的普通消息`() {
        val plain = candidate("plain", score = 0.9f)
        val summary = candidate("summary", score = 0.9f, isSummary = true)

        val ranked = fuse(vector = listOf(plain, summary))

        assertEquals(listOf("summary", "plain"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `归一化内容去重保留名次最高的副本`() {
        val top = candidate("top", score = 0.9f, content = "User 讨厌被叫主人")
        val dup = candidate("dup", score = 0.9f, content = "user  讨厌被叫主人\n")

        val ranked = fuse(vector = listOf(top, dup))

        assertEquals(listOf("top"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `topK 截断只保留名次最高者`() {
        val vector = (1..5).map { candidate("v$it", score = 0.9f) }

        val ranked = fuse(vector = vector, topK = 2)

        assertEquals(listOf("v1", "v2"), ranked.map { it.candidate.messageId })
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(fuse(vector = emptyList(), lexical = emptyList()).isEmpty())
    }

    @Test
    fun `时间戳未知时按中性时间因子参与排序`() {
        // 无时间戳不该被当成"无限老"而必输：中性因子 0.5 下，名次在前的无戳候选
        // 仍能胜过相邻名次的一年旧候选（1/60×1.075 > 1/61×1.0）
        val noTs = candidate("noTs", score = 0.9f, ageDays = null)
        val old = candidate("old", score = 0.9f, ageDays = 365f)

        val ranked = fuse(vector = listOf(noTs, old))

        assertEquals(listOf("noTs", "old"), ranked.map { it.candidate.messageId })
    }
}
