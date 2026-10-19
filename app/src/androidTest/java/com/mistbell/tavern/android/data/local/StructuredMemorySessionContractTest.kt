package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.entity.SessionEntity
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
 * 会话内长期记忆的 Room 层契约测试（instrumented，需要真机/模拟器）。
 *
 * 钉住的产品契约（2026-10 会话内长期记忆改造，Room 版本 26 无新迁移）：
 * 1. **同角色不同会话隔离**：`getPromptCandidatesInSessions` / `getByOwnerAndSession` /
 *    `getVisibleInSession` 只命中当前会话集合，同角色其它会话的记忆不得串线。
 * 2. **owner 硬隔离**：sessionId 是普字符串，不同 owner 的同 id 会话互不命中——
 *    任何查询都以 owner_id 为第一谓词。
 * 3. **空/null sessionId 记录不被任何生产查询命中**：生产路径不再产生这类记录（召回在缺失
 *    sessionId 时拒绝），存量记录也进不了召回与管理页。
 * 4. **`deleteByOwner` 不越界**：只清本 owner 的结构化记忆，其它 owner 数据原样保留
 *    （区别于无 owner 条件的 `deleteAll`，后者是越界操作）。
 * 5. **重启保留**：写入后关闭并重建数据库实例（临时文件库，模拟进程重启），
 *    数据必须完整可读——持久化是 Room 层契约，不是内存缓存行为。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class StructuredMemorySessionContractTest {
    private lateinit var db: AppDatabase
    private var persistedDb: AppDatabase? = null
    private val persistedName = "sm-contract-test.db"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(persistedName)
        db =
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
        persistedDb?.close()
        persistedDb = null
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(persistedName)
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

    /** 同角色两条会话（s1/s2），各挂一条记忆 */
    private fun seedSameCharacterTwoSessions() {
        runBlocking {
            db.structuredMemoryDao().insert(memory(content = "会话一记忆", session = "s1"))
            db.structuredMemoryDao().insert(memory(content = "会话二记忆", session = "s2"))
        }
    }

    @Test
    fun `同角色不同会话_三条生产查询都只命中当前会话`() {
        seedSameCharacterTwoSessions()
        val dao = db.structuredMemoryDao()

        val recalled =
            runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s1"), 100) }
        val listed =
            runBlocking { dao.getVisibleInSession("u1", "c1", "s1").first() }
        val bySession =
            runBlocking { dao.getByOwnerAndSession("u1", "s1") }

        // 历史缺陷：召回只有 (owner, character) 两个维度，同角色其它会话的记忆会被本会话召回。
        // 会话内契约下，三条口径互相一致，都只含本会话
        assertEquals(setOf("会话一记忆"), recalled.map { it.content }.toSet())
        assertEquals(setOf("会话一记忆"), listed.map { it.content }.toSet())
        assertEquals(setOf("会话一记忆"), bySession.map { it.content }.toSet())
    }

    @Test
    fun `同角色不同会话_以s2为当前会话时命中s2`() {
        seedSameCharacterTwoSessions()
        val dao = db.structuredMemoryDao()

        val recalled =
            runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s2"), 100) }
        val bySession =
            runBlocking { dao.getByOwnerAndSession("u1", "s2") }

        assertEquals(setOf("会话二记忆"), recalled.map { it.content }.toSet())
        assertEquals(setOf("会话二记忆"), bySession.map { it.content }.toSet())
    }

    @Test
    fun `不同owner同sessionId字符串_互不命中`() {
        runBlocking {
            db.structuredMemoryDao().insert(memory(content = "u1的s1记忆", owner = "u1", session = "s1"))
            db.structuredMemoryDao().insert(memory(content = "u2的s1记忆", owner = "u2", session = "s1"))
        }
        val dao = db.structuredMemoryDao()

        val recalledU1 =
            runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s1"), 100) }
        val recalledU2 =
            runBlocking { dao.getPromptCandidatesInSessions("u2", listOf("s1"), 100) }
        val listedU1 =
            runBlocking { dao.getVisibleInSession("u1", "c1", "s1").first() }
        val bySessionU1 =
            runBlocking { dao.getByOwnerAndSession("u1", "s1") }

        // session_id 是无命名空间的普通字符串，owner_id 是第一谓词——u1 的任何查询都不得带出 u2 的记忆
        assertEquals(setOf("u1的s1记忆"), recalledU1.map { it.content }.toSet())
        assertEquals(setOf("u2的s1记忆"), recalledU2.map { it.content }.toSet())
        assertEquals(setOf("u1的s1记忆"), listedU1.map { it.content }.toSet())
        assertEquals(setOf("u1的s1记忆"), bySessionU1.map { it.content }.toSet())
    }

    @Test
    fun `空串与null的sessionId_不被任何生产查询命中`() {
        runBlocking {
            db.structuredMemoryDao().insert(memory(content = "null会话", session = null))
            db.structuredMemoryDao().insert(memory(content = "空串会话", session = ""))
            db.structuredMemoryDao().insert(memory(content = "正常会话", session = "s1"))
        }
        val dao = db.structuredMemoryDao()

        // DAO 层契约：IN 谓词按传入集合匹配——调用方不会把空串传进来（生产召回入口
        // 对 blank owner/session 一律拒绝或收窄，该行为由单元测试覆盖）；本测试只断言
        // DAO 的明确约束：用正常会话集合查询时，null/空串 sessionId 的记录不出现。
        val recalled =
            runBlocking { dao.getPromptCandidatesInSessions("u1", listOf("s1"), 100) }
        val listed =
            runBlocking { dao.getVisibleInSession("u1", "c1", "s1").first() }
        val bySession =
            runBlocking { dao.getByOwnerAndSession("u1", "s1") }
        val all = runBlocking { dao.getAllOnce() }

        // 生产查询全部落空；记录本身仍在库里（备份/清理路径的粒度，不由查询层隐藏）
        assertFalse(recalled.any { it.content == "null会话" || it.content == "空串会话" })
        assertFalse(listed.any { it.content == "null会话" || it.content == "空串会话" })
        assertFalse(bySession.any { it.content == "null会话" || it.content == "空串会话" })
        assertEquals(3, all.size)
    }

    @Test
    fun `deleteByOwner只删本owner_其它owner保留`() {
        runBlocking {
            db.structuredMemoryDao().insert(memory(content = "u1会话记忆", owner = "u1", session = "s1"))
            db.structuredMemoryDao().insert(memory(content = "u1另一会话", owner = "u1", session = "s2"))
            db.structuredMemoryDao().insert(memory(content = "u2会话记忆", owner = "u2", session = "s1"))
        }

        runBlocking { db.structuredMemoryDao().deleteByOwner("u1") }

        val remaining = runBlocking { db.structuredMemoryDao().getAllOnce() }
        assertEquals(setOf("u2会话记忆"), remaining.map { it.content }.toSet())
    }

    @Test
    fun `deleteByOwner删尽本owner后_其它owner的getCount不受影响`() {
        runBlocking {
            db.structuredMemoryDao().insert(memory(content = "u1记忆", owner = "u1", session = "s1"))
            db.structuredMemoryDao().insert(memory(content = "u2记忆A", owner = "u2", session = "s1"))
            db.structuredMemoryDao().insert(memory(content = "u2记忆B", owner = "u2", session = "s2"))
        }

        runBlocking { db.structuredMemoryDao().deleteByOwner("u1") }

        val u1Count = runBlocking { db.structuredMemoryDao().getCount("u1") }
        val u2Count = runBlocking { db.structuredMemoryDao().getCount("u2") }
        assertEquals(0, u1Count)
        assertEquals(2, u2Count)
    }

    @Test
    fun `写入后重建数据库实例_数据仍可读出`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 用临时文件库而非 in-memory：in-memory 随实例关闭即销毁，无法验证"重启保留"
        persistedDb =
            Room.databaseBuilder(ctx, AppDatabase::class.java, persistedName)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            persistedDb!!.structuredMemoryDao().insert(memory(content = "重启后要还在", session = "s1"))
        }
        persistedDb!!.close()
        persistedDb = null

        // 模拟进程重启：全新实例打开同一文件
        persistedDb =
            Room.databaseBuilder(ctx, AppDatabase::class.java, persistedName)
                .allowMainThreadQueries()
                .build()

        val got =
            runBlocking {
                persistedDb!!.structuredMemoryDao().getPromptCandidatesInSessions("u1", listOf("s1"), 100)
            }
        assertEquals(listOf("重启后要还在"), got.map { it.content })

        val byId = runBlocking { persistedDb!!.structuredMemoryDao().getById(got[0].id) }
        assertEquals("重启后要还在", byId?.content)
    }

    @Test
    fun `备份归属校验的前置条件_合并后sessions表可见且ownerId一致`() {
        // BackupManager.isAttachableMemory 的判定依赖"合并后的 sessions 表"（本地会话同样算合法
        // 归属——合并且跳过已存在主键是既定语义）。此处用生产 SessionDao 钉住该前置条件：
        // 恢复侧按 sessionId 查得到的会话，其 ownerId 必须与原记忆的 ownerId 一致才可挂。
        runBlocking {
            db.sessionDao().upsert(
                SessionEntity(
                    id = "s1",
                    ownerId = "u1",
                    characterId = "c1",
                    title = "会话一",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    messageCount = 0,
                    providerId = "",
                    modelId = "",
                    worldBookId = "",
                    summaryJson = "",
                ),
            )
        }

        val session = runBlocking { db.sessionDao().getById("s1") }
        assertTrue("合并后的 sessions 表必须能按 id 查到会话", session != null)
        assertEquals("u1", session!!.ownerId)

        // 归属一致 → 可挂；归属不一致（备份来自另一设备同名会话）→ 丢弃。
        // 判定本身在 BackupManager.isAttachableMemory（private），此处钉的是它依赖的 DAO 事实。
        assertTrue(session.ownerId == "u1")
        assertFalse("ownerId 不一致的会话不得作为记忆的归属锚点", session.ownerId == "u2")
    }
}
