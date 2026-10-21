package com.mistbell.tavern.android.data.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RecallDiagnostics.summaryLine 各分支的呈现契约（预览/复制文本共用同一行） */
class RecallDiagnosticsTest {
    @Test
    fun `混合召回注入时展示条数构成与耗时缓存`() {
        val diag =
            RecallDiagnostics(
                structuredCount = 4,
                vectorCandidates = 7,
                lexicalCandidates = 3,
                fusedCount = 5,
                injectedRecallLines = 5,
                vectorCacheHit = true,
                vectorLatencyMs = 12,
                lexicalLatencyMs = 3,
            )

        val line = diag.summaryLine()

        assertEquals("结构化 4 条；召回 5 条（向量 7 ∩ 词法 3 → 融合 5）；向量 12ms·缓存命中 / 词法 3ms", line)
    }

    @Test
    fun `缓存未命中与命中标注不同`() {
        val diag = RecallDiagnostics(injectedRecallLines = 1, fusedCount = 1, vectorCacheHit = false, vectorLatencyMs = 20)

        val line = diag.summaryLine()

        assertTrue(line.contains("缓存未中"))
        assertFalse(line.contains("缓存命中"))
    }

    @Test
    fun `词法回退模式不展示向量耗时`() {
        val diag =
            RecallDiagnostics(
                vectorAvailable = false,
                lexicalOnly = true,
                lexicalCandidates = 2,
                fusedCount = 2,
                injectedRecallLines = 2,
                lexicalLatencyMs = 5,
            )

        val line = diag.summaryLine()

        assertTrue(line.contains("词法召回 2 条"))
        assertTrue(line.contains("词法 5ms"))
        assertFalse(line.contains("向量"))
    }

    @Test
    fun `召回块丢弃时展示原因而不是条数`() {
        val diag =
            RecallDiagnostics(
                structuredCount = 3,
                vectorCandidates = 6,
                lexicalCandidates = 2,
                fusedCount = 5,
                injectedRecallLines = 5,
                vectorLatencyMs = 10,
                droppedReason = RecallDiagnostics.DROP_HISTORY_INTACT,
            )

        val line = diag.summaryLine()

        assertTrue(line.contains("召回未注入：历史未裁剪（召回内容已在上下文中）"))
        assertFalse(line.contains("融合"))
    }

    @Test
    fun `无命中时展示无命中原因`() {
        val diag = RecallDiagnostics(droppedReason = RecallDiagnostics.DROP_NO_HITS)

        assertEquals("召回未注入：向量与词法均无命中", diag.summaryLine())
    }

    @Test
    fun `本轮完全无记忆时如实说明`() {
        assertEquals("本轮无长期记忆注入", RecallDiagnostics().summaryLine())
    }
}
