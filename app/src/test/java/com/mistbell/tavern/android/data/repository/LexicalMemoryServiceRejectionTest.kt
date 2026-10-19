package com.mistbell.tavern.android.data.repository

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约：词法召回的拒绝路径——ownerId / currentSessionId 为 blank 时
 * LexicalMemoryService.searchRelevantHistory **直接返回空结果，绝不退化成 owner 级扫描**，
 * 且在触碰 Room 之前早退（本测试在 JVM 上运行，没有任何数据库替身，能返回即证明未碰 db）。
 *
 * Context 是抽象类、不能动态代理；ContextWrapper 是具体子类，拒绝路径一个 Context 方法
 * 都不会被调用，故 `ContextWrapper(null)` 即可作替身。
 *
 * 正向召回（不按 characterId 过滤、同会话 NPC 可召回）依赖 Room DAO，
 * JVM 无 Robolectric/mock 框架无法构造，由 androidTest 层覆盖，此处不重复。
 */
class LexicalMemoryServiceRejectionTest {
    private fun fakeContext() = ContextWrapper(null)

    private fun window(
        ownerId: String,
        currentSessionId: String,
    ) = RecallWindow.resolve(ownerId, "character", currentSessionId, MemoryScope.SESSION, listOf("s1"))

    @Test
    fun `ownerId 为空拒绝召回`() {
        val service = LexicalMemoryService(fakeContext())

        val result =
            runBlocking {
                service.searchRelevantHistory(window(ownerId = "  ", currentSessionId = "s1"), "关键词")
            }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `currentSessionId 为空拒绝召回`() {
        val service = LexicalMemoryService(fakeContext())

        val result =
            runBlocking {
                service.searchRelevantHistory(window(ownerId = "owner", currentSessionId = ""), "关键词")
            }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `会话上限兜底分支宁可不召回`() {
        // 手工装配一个 sessionLimitExceeded=true 的窗口（生产窗口恒为 false，
        // 此处验证兜底分支语义仍在：即使出现也返回空、不扫描 owner 级）
        val exceededWindow =
            RecallWindow(
                ownerId = "owner",
                currentSessionId = "s1",
                characterId = null,
                sessionIds = setOf("s1"),
                includeUnassigned = false,
                scope = MemoryScope.SESSION,
                sessionLimitExceeded = true,
            )
        val service = LexicalMemoryService(fakeContext())

        val result = runBlocking { service.searchRelevantHistory(exceededWindow, "关键词") }

        assertTrue(result.isEmpty())
    }
}
