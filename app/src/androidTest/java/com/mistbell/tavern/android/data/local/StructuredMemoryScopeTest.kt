package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 结构化记忆会话内召回的作用域回归测试（instrumented，需要真机/模拟器）。
 *
 * 钉住的产品契约（2026-10 会话内长期记忆改造）：
 * - 召回唯一入口是 [StructuredMemoryDao.getPromptCandidatesInSessions]：只认 owner + 会话集合，
 *   **不按 character_id 收窄**（群聊里 NPC 与主角色的记忆同属一次对话）；调用方在缺失
 *   sessionId 时拒绝召回，DAO 层不做兜底。
 * - 记忆管理页列表 [StructuredMemoryDao.getVisibleInSession] 与召回口径逐字一致：
 *   仅 owner + 当前 session；`session_id IS NULL` 的角色级记忆**不再列出**（旧契约会列出）。
 * - 一次性查询 [StructuredMemoryDao.getByOwnerAndSession]（抽取去重用）同口径。
 * - `getPromptCandidatesUnassigned` / `getPromptCandidatesForCharacter*` / `getPromptCandidatesForOwner`
 *   为遗留查询，生产不再调用，本类不再为其钉语义。
 * - owner 硬隔离不变：其它用户的记忆绝不进入本用户任何查询结果。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class StructuredMemoryScopeTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db =
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun memory(
        content: String,
        owner: String = "u1",
        character: String? = "c1",
        session: String? = "s1",
        importance: Int = 5,
    ) = StructuredMemoryEntity(
        ownerId = owner,
        characterId = character,
        sessionId = session,
        memoryType = "fact",
        title = null,
        content = content,
        structuredData = null,
        importance = importance,
        tags = null,
        keywords = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        lastAccessedAt = null,
        accessCount = 0,
        relatedMessageIds = null,
        sourceType = "auto_extract",
    )

    private fun seedAll() {
        val dao = db.structuredMemoryDao()
        runBlocking {
            dao.insert(memory(content = "本会话记忆", session = "s1"))
            dao.insert(memory(content = "同角色其它会话记忆", session = "s2"))
            dao.insert(memory(content = "角色级记忆", session = null))
            dao.insert(memory(content = "同会话其它角色记忆", character = "c2", session = "s1"))
            dao.insert(memory(content = "其它用户记忆", owner = "u2", session = "s1"))
        }
    }

    @Test
    fun `会话召回只认会话集合_不按角色收窄_隔离其它会话其它用户`() {
        seedAll()

        val got =
            runBlocking {
                db.structuredMemoryDao().getPromptCandidatesInSessions("u1", listOf("s1"), 100)
            }

        // 不对角色设限：群聊里 NPC 的记忆同属这一会话，应当一起召回；
        // 但别的会话、无会话（角色级）、别的用户的记忆都不能进来
        assertEquals(setOf("本会话记忆", "同会话其它角色记忆"), got.map { it.content }.toSet())
    }

    @Test
    fun `会话召回的窗口可多会话_但绝不跨owner`() {
        seedAll()

        val got =
            runBlocking {
                db.structuredMemoryDao().getPromptCandidatesInSessions("u1", listOf("s1", "s2"), 100)
            }

        // s1、s2 都属于 u1 窗口时可一起召回；u2 的 s1 记忆不得混入
        assertEquals(
            setOf("本会话记忆", "同会话其它角色记忆", "同角色其它会话记忆"),
            got.map { it.content }.toSet(),
        )
    }

    @Test
    fun `getByOwnerAndSession_只命中本会话_去重抽取口径`() {
        seedAll()

        val got =
            runBlocking {
                db.structuredMemoryDao().getByOwnerAndSession("u1", "s1")
            }

        // 抽取去重的一次性查询与召回同口径：owner + 当前会话，不按角色、不含角色级、不含其它用户
        assertEquals(setOf("本会话记忆", "同会话其它角色记忆"), got.map { it.content }.toSet())
    }

    @Test
    fun `管理页列表只含本会话_角色级记忆不再列出`() {
        seedAll()

        val got = runBlocking { db.structuredMemoryDao().getVisibleInSession("u1", "c1", "s1").first() }

        // 会话内长期记忆：页面可见集合与召回口径逐字一致——只看得到本会话的。
        // 旧契约会列出 session_id IS NULL 的角色级记忆，现已废弃（这类记录生产路径不再产生，
        // 且不再出现在管理页，也进不了召回）。
        assertEquals(setOf("本会话记忆", "同会话其它角色记忆"), got.map { it.content }.toSet())
    }

    @Test
    fun `管理页列表对其它owner隔离`() {
        seedAll()

        // 同一会话 id 字符串、不同 owner：u2 的管理页只列 u2 的记忆
        val got = runBlocking { db.structuredMemoryDao().getVisibleInSession("u2", "c1", "s1").first() }
        assertEquals(setOf("其它用户记忆"), got.map { it.content }.toSet())
    }

    @Test
    fun `null会话记录不被任何生产查询命中`() {
        seedAll()

        val dao = db.structuredMemoryDao()
        val recalled =
            runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s1", "s2"), 100) }
        val listed =
            runBlocking { dao.getVisibleInSession("u1", "c1", "s1").first() }
        val bySession =
            runBlocking { dao.getByOwnerAndSession("u1", "s1") }

        // "角色级记忆"（session_id IS NULL）在三处生产查询中都不出现
        assertFalse(recalled.any { it.content == "角色级记忆" })
        assertFalse(listed.any { it.content == "角色级记忆" })
        assertFalse(bySession.any { it.content == "角色级记忆" })
    }

    @Test
    fun `删会话只删该会话_其它会话与无会话记录存活`() {
        seedAll()

        runBlocking { db.structuredMemoryDao().deleteBySession("u1", "s1") }

        val remaining = runBlocking { db.structuredMemoryDao().getAllOnce() }
        val contents = remaining.map { it.content }.toSet()
        assertFalse(contents.contains("本会话记忆"))
        assertFalse(contents.contains("同会话其它角色记忆"))
        // 同角色其它会话的记忆不受影响
        assertTrue(contents.contains("同角色其它会话记忆"))
        // session_id IS NULL 的记录不属于任何会话，删除条件 session_id = 's1' 不命中
        assertTrue(contents.contains("角色级记忆"))
        // 其它 owner 的同 id 会话不受影响
        assertTrue(contents.contains("其它用户记忆"))
    }

    @Test
    fun `候选按重要度降序_且受limit约束`() {
        val dao = db.structuredMemoryDao()
        runBlocking {
            dao.insert(memory(content = "低", importance = 3))
            dao.insert(memory(content = "高", importance = 9))
            dao.insert(memory(content = "中", importance = 6))
        }

        val got = runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s1"), 2) }

        assertEquals(listOf("高", "中"), got.map { it.content })
    }
}
