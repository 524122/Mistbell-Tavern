package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.repository.BackupManager
import com.mistbell.tavern.android.data.repository.StructuredMemoryRestoreMerger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份恢复中"会话内长期记忆"的合并契约测试（instrumented，需要真机/模拟器）。
 *
 * 钉住的产品契约（2026-10 会话内长期记忆改造）：
 * - **归属校验**（null/blank sessionId 丢弃、会话不存在丢弃、ownerId 不一致丢弃，均计数）；
 * - **两阶段 id 冲突插入**：先落全部显式 id 的 fresh 记录，冲突记录再以新自增 id 重插——
 *   本机 1(A)、备份 [2(B),1(C)]（C 与 A 同 id 不同内容）时，C 以新 id 存活且**不被 B 覆盖**，
 *   且与备份 JSON 顺序无关；
 * - **重复恢复同一备份不制造副本**：第二次合并 added=0，同 id 同内容与同内容换 id 均按重复计数；
 * - **同 id 同内容跳过**。
 *
 * 合并逻辑本体在 `StructuredMemoryRestoreMerger`（internal，构造函数注入 AppDatabase），
 * BackupManager.mergeTables 委托同组件；本类用 in-memory Room 库直接实例化合并器钉契约。
 * 类内另保留 BackupManager 公共纯函数（decodeList）的解码层边界测试。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreMemoryContractTest {
    private lateinit var db: AppDatabase
    private lateinit var merger: StructuredMemoryRestoreMerger

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db =
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        merger = StructuredMemoryRestoreMerger(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 合并器契约 ----

    private fun memory(
        content: String,
        owner: String = "u1",
        character: String? = "c1",
        session: String? = "s1",
    ) = StructuredMemoryEntity(
        ownerId = owner,
        characterId = character,
        sessionId = session,
        memoryType = "fact",
        title = null,
        content = content,
        structuredData = null,
        importance = 5,
        tags = null,
        keywords = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        lastAccessedAt = null,
        accessCount = 0,
        relatedMessageIds = null,
        sourceType = "auto_extract",
    )

    /** 归属锚点：u1 的 s1 会话（合并后的 sessions 表） */
    private fun seedSessionU1S1() {
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
    }

    @Test
    fun `null与blankSessionId的记忆_丢弃并计数`() {
        seedSessionU1S1()
        val restored =
            listOf(
                memory(content = "null会话", session = null),
                memory(content = "空串会话", session = ""),
                memory(content = "正常记忆", session = "s1"),
            )

        val result = runBlocking { merger.merge(restored) }

        assertEquals(1, result.added)
        assertEquals(2, result.unattachable)
        assertEquals(0, result.duplicated)
        val all = runBlocking { db.structuredMemoryDao().getAllOnce() }
        assertEquals(listOf("正常记忆"), all.map { it.content })
    }

    @Test
    fun `sessionId指向不存在的会话_丢弃并计数`() {
        // sessions 表为空：任何记忆都没有归属锚点
        val result =
            runBlocking { merger.merge(listOf(memory(content = "孤儿记忆", session = "ghost"))) }

        assertEquals(0, result.added)
        assertEquals(1, result.unattachable)
        assertTrue(runBlocking { db.structuredMemoryDao().getAllOnce() }.isEmpty())
    }

    @Test
    fun `会话存在但ownerId不一致_丢弃并计数`() {
        // 合并后的 sessions 表已有 u1 的 s1；备份里 u2 的记忆指向同名字符串会话——
        // ownerId 不一致即归属无效，恢复进来就是召不回/串线的孤儿记忆
        seedSessionU1S1()
        val restored = listOf(memory(content = "u2的记忆", owner = "u2", session = "s1"))

        val result = runBlocking { merger.merge(restored) }

        assertEquals(0, result.added)
        assertEquals(1, result.unattachable)
        assertTrue(runBlocking { db.structuredMemoryDao().getAllOnce() }.isEmpty())
    }

    @Test
    fun `本地已有会话算合法归属_不重复造会话`() {
        seedSessionU1S1()
        val result =
            runBlocking { merger.merge(listOf(memory(content = "挂到本地会话", session = "s1"))) }

        assertEquals(1, result.added)
        assertEquals(0, result.unattachable)
        assertEquals(listOf("挂到本地会话"), runBlocking { db.structuredMemoryDao().getAllOnce() }.map { it.content })
    }

    @Test
    fun `id冲突顺序反例_本机1A_备份2B和1C_C以新id存活不被B覆盖`() {
        seedSessionU1S1()
        // 本机：id=1 内容 A
        runBlocking { db.structuredMemoryDao().insert(memory(content = "A")) }
        // 备份：B 显式 id=2；C 与本机 A 撞 id=1 但内容不同
        val b = memory(content = "B").copy(id = 2)
        val c = memory(content = "C").copy(id = 1)

        val result = runBlocking { merger.merge(listOf(b, c)) }

        // B 是 fresh（id=2 本机不存在）显式落库；C 冲突后以新自增 id 重插
        assertEquals(2, result.added)
        assertEquals(1, result.freshCount)
        assertEquals(1, result.reinsertedCount)
        assertEquals(0, result.duplicated)
        assertEquals(0, result.unattachable)

        // A 保持原内容不被覆盖；B 在显式 id=2；C 以全新自增 id（> 2）存在且内容完整
        assertEquals("A", runBlocking { db.structuredMemoryDao().getById(1) }?.content)
        assertEquals("B", runBlocking { db.structuredMemoryDao().getById(2) }?.content)
        val all = runBlocking { db.structuredMemoryDao().getAllOnce() }
        assertEquals(3, all.size)
        val cRow = all.first { it.content == "C" }
        assertNotEquals("冲突重插不得占用本机 id", 1L, cRow.id)
        assertNotEquals("冲突重插不得占用 B 的显式 id", 2L, cRow.id)
        assertTrue("冲突重插的自增 id 应大于一切现存 id", cRow.id > 2)
    }

    @Test
    fun `id冲突顺序反例_备份JSON顺序颠倒_结果不变`() {
        seedSessionU1S1()
        runBlocking { db.structuredMemoryDao().insert(memory(content = "A")) }
        val b = memory(content = "B").copy(id = 2)
        val c = memory(content = "C").copy(id = 1)

        // 旧实现（冲突项立即 id=0 插入、fresh 末尾 REPLACE）会被此顺序击穿：
        // C 先拿到自增 id=3，B 的显式 id=2 不冲突——真正的击穿场景是 [1(C), 2(B)] 且
        // 本机存在 id=2：C 的自增 id 被 REPLACE 覆盖。这里用 [C, B] 验证两阶段与顺序无关。
        val result = runBlocking { merger.merge(listOf(c, b)) }

        assertEquals(2, result.added)
        assertEquals("A", runBlocking { db.structuredMemoryDao().getById(1) }?.content)
        assertEquals("B", runBlocking { db.structuredMemoryDao().getById(2) }?.content)
        val all = runBlocking { db.structuredMemoryDao().getAllOnce() }
        assertEquals(3, all.size)
        assertTrue(all.any { it.content == "C" && it.id > 2 })
    }

    @Test
    fun `重复恢复同一备份_不制造副本`() {
        seedSessionU1S1()
        runBlocking { db.structuredMemoryDao().insert(memory(content = "A")) }
        val backup = listOf(memory(content = "B").copy(id = 2), memory(content = "C").copy(id = 1))

        val first = runBlocking { merger.merge(backup) }
        assertEquals(2, first.added)
        assertEquals(3, runBlocking { db.structuredMemoryDao().getAllOnce() }.size)

        // 第二次恢复同一备份：B 同 id 同内容跳过；C 的 id 对不上但内容签名已存在（首次以新 id 落库）→ 重复跳过
        val second = runBlocking { merger.merge(backup) }
        assertEquals("第二次恢复不得再落库", 0, second.added)
        assertEquals(2, second.duplicated)
        assertEquals(0, second.unattachable)
        assertEquals("库内仍只有 3 条，无副本", 3, runBlocking { db.structuredMemoryDao().getAllOnce() }.size)
    }

    @Test
    fun `同id同内容_跳过不计added`() {
        seedSessionU1S1()
        val local = memory(content = "同内容")
        runBlocking { db.structuredMemoryDao().insert(local) }
        val id = runBlocking { db.structuredMemoryDao().getAllOnce() }.single().id

        val result = runBlocking { merger.merge(listOf(memory(content = "同内容").copy(id = id))) }

        assertEquals(0, result.added)
        assertEquals(1, result.duplicated)
        assertEquals(1, runBlocking { db.structuredMemoryDao().getAllOnce() }.size)
    }

    @Test
    fun `同id不同内容_本机旧内容不被备份静默跳过当成功`() {
        seedSessionU1S1()
        runBlocking { db.structuredMemoryDao().insert(memory(content = "旧内容")) }
        val id = runBlocking { db.structuredMemoryDao().getAllOnce() }.single().id

        val result = runBlocking { merger.merge(listOf(memory(content = "新内容").copy(id = id))) }

        assertEquals("冲突重插算 added，绝不静默跳过", 1, result.added)
        assertEquals(1, result.reinsertedCount)
        val all = runBlocking { db.structuredMemoryDao().getAllOnce() }
        assertEquals(2, all.size)
        assertTrue(all.any { it.content == "旧内容" && it.id == id })
        assertTrue(all.any { it.content == "新内容" && it.id != id })
    }

    // ---- BackupManager 公共纯函数：解码层边界（合并层的上游） ----

    /**
     * 解码夹具必须用真实序列化产物：@ColumnInfo 不改 JSON 字段名，serializer 用的是
     * ownerId/sessionId 等 camelCase 属性名——手写 SQL 列名（owner_id）会被容错解码当坏行
     * 丢弃，测试在不知情中断言空表。直接用生产备份同源的 [BackupManager.json] 编码真实实体。
     */
    private fun memoryJson(
        id: Int,
        content: String,
        sessionId: String?,
    ): String =
        BackupManager.json.encodeToString(
            StructuredMemoryEntity.serializer(),
            memory(content = content, session = sessionId).copy(id = id.toLong()),
        )

    @Test
    fun `解码层保留null与正常sessionId的记忆_丢弃决策专属merge层`() {
        val payload =
            """
            [
              ${memoryJson(1, "正常会话记忆", "s1")},
              ${memoryJson(2, "null会话记忆", null)}
            ]
            """.trimIndent()

        val decoded =
            BackupManager.decodeList(
                payload,
                StructuredMemoryEntity.serializer(),
            )

        // 解码层不做归属过滤：null sessionId 的记录原样解码。
        // "恢复结果里没有它"是 StructuredMemoryRestoreMerger.isAttachableMemory 的职责。
        assertEquals(2, decoded.size)
        assertEquals("正常会话记忆", decoded[0].content)
        assertEquals("s1", decoded[0].sessionId)
        assertEquals("null会话记忆", decoded[1].content)
        assertNull(decoded[1].sessionId)
    }

    @Test
    fun `解码层对坏元素容错_单条损坏不牺牲整表`() {
        val payload =
            """
            [
              ${memoryJson(1, "完好记忆", "s1")},
              {"id": "not-a-number", "broken": true},
              ${memoryJson(3, "第二条完好", "s2")}
            ]
            """.trimIndent()

        val decoded =
            BackupManager.decodeList(
                payload,
                StructuredMemoryEntity.serializer(),
            )

        assertEquals(2, decoded.size)
        assertEquals(setOf("完好记忆", "第二条完好"), decoded.map { it.content }.toSet())
    }

    @Test
    fun `解码层对空与缺失表按空表处理`() {
        assertTrue(
            BackupManager.decodeList(null, StructuredMemoryEntity.serializer()).isEmpty(),
        )
        assertTrue(
            BackupManager.decodeList("", StructuredMemoryEntity.serializer()).isEmpty(),
        )
        assertTrue(
            BackupManager.decodeList("   ", StructuredMemoryEntity.serializer()).isEmpty(),
        )
        // 非数组内容（如整表损坏）按"该表无可恢复数据"兜底，不抛异常
        assertTrue(
            BackupManager.decodeList("{\"oops\":1}", StructuredMemoryEntity.serializer()).isEmpty(),
        )
    }
}
