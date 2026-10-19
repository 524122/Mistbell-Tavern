package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 向量索引的落盘与淘汰行为。
 *
 * 背景：`add()` 曾每写一条就整文件重写一次（满 1000 条 512 维时单次约 8 MB），
 * 而淘汰只按插入顺序丢队首。这两条都只能靠"真跑一遍"来锁住。
 */
class InMemoryVectorStoreTest {
    private lateinit var dir: File

    /**
     * 每个用例 20 秒硬上限：这些用例全是内存操作 + 几 KB 临时文件，正常都在百毫秒内。
     * 没有它，一旦出现"持锁期间跨线程再取锁"这类死锁，测试会永久挂住（Gradle 不报错也不超时），
     * CI 只会看到一个卡死的 worker——这正是加上它的原因。
     */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(20)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vector-store-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 测试用短窗口：默认 1.5s 会让用例白等 */
    private fun storeAt(
        file: File = File(dir, InMemoryVectorStore.FILE_NAME),
        cap: Int = InMemoryVectorStore.DEFAULT_MAX_VECTORS,
        flushDelayMs: Long = 60,
    ) = InMemoryVectorStore(file, maxVectorsInMemory = cap, flushDelayMs = flushDelayMs)

    private fun meta(
        contentType: String = "ai_message",
        createdAt: String = "2026-01-01T00:00:00Z",
    ) = mapOf<String, Any>(
        "content_type" to contentType,
        "created_at" to createdAt,
        "content" to "内容",
    )

    @Test
    fun `写入不立即落盘_由合并窗口收敛`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        val store = storeAt(file, flushDelayMs = 60_000) // 窗口长到测试期间绝不会触发

        runBlocking {
            store.add("v1", FloatArray(4) { 1f }, meta())
            assertFalse("add 不该同步写盘——那正是每条消息重写整份索引的成因", file.exists())

            store.flush()
        }

        assertTrue("flush 之后必须已落盘", file.exists())
    }

    @Test
    fun `flush 之后新实例能读回全部向量`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file)
            repeat(3) { i -> store.add("v$i", FloatArray(4) { 1f }, meta()) }
            store.flush()
        }

        val reopened = runBlocking { storeAt(file).count() }
        assertEquals(3, reopened)
    }

    @Test
    fun `删除立即落盘_不会停在窗口里等`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            store.add("keep", FloatArray(4) { 1f }, meta())
            store.add("drop", FloatArray(4) { 1f }, meta())
            store.flush()

            // 不 flush：删除若是合并写入，重启后已删向量会复活
            store.deleteByFilters(mapOf("content_type" to "ai_message"))
        }

        val remaining = runBlocking { storeAt(file).count() }
        assertEquals("删除必须已经落到磁盘上", 0, remaining)
    }

    @Test
    fun `超限淘汰按价值与新旧_结构化记忆不被普通消息挤掉`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, cap = 2)
            // summary 最旧——若按时间无差别淘汰，先死的会是它
            store.add("mem", FloatArray(4) { 1f }, meta(VectorEviction.CONTENT_TYPE_SUMMARY, "2020-01-01T00:00:00Z"))
            store.add("msg_old", FloatArray(4) { 1f }, meta(createdAt = "2024-01-01T00:00:00Z"))
            store.add("msg_new", FloatArray(4) { 1f }, meta(createdAt = "2025-01-01T00:00:00Z"))
            store.flush()
        }

        val survivors = runBlocking { storeAt(file) }
        assertTrue("summary 必须活下来", runBlocking { survivors.get("mem") != null })
        assertEquals(2, runBlocking { survivors.count() })
    }

    @Test
    fun `reload 之后窗口内的待写不会把文件覆盖回旧内容`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 50)
            store.add("stale", FloatArray(4) { 1f }, meta())

            // 模拟备份恢复：外部把索引文件整体换掉
            file.writeText(
                """{"vectors":[{"id":"restored","vector":[1.0,1.0,1.0,1.0],""" +
                    """"metadata":{"content_type":"ai_message","content":"恢复的"}}]}""",
            )
            store.reload()

            // 等过窗口：待写任务若还活着，会把 restored 覆盖回 stale
            delay(300)

            assertEquals(1, store.count())
            assertTrue(store.get("restored") != null)
            assertFalse("旧内存态不得把恢复的索引写回去", store.get("stale") != null)
        }
    }

    @Test
    fun `容量为零表示生产持久化索引不做静默淘汰`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, cap = 0)
            repeat(3) { i -> store.add("v$i", FloatArray(4) { 1f }, meta()) }
            store.flush()
            assertEquals(3, store.count())
        }
    }

    // ---- 写入闸门：删除之后落地的那条向量不能复活 ----

    @Test
    fun `被登记删除的消息_之后的写入直接落不进去`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file)
            store.markMessagesDeleted(listOf("m1"))

            assertFalse("删除后不得再写入", store.canIndexMessage("m1"))
            assertTrue("没被删的消息不受影响", store.canIndexMessage("m2"))
        }
    }

    @Test
    fun `删除意图能解除_替换型写入不会被永久压住`() {
        runBlocking {
            val store = storeAt(File(dir, InMemoryVectorStore.FILE_NAME))
            store.markMessagesDeleted(listOf("m1"))
            assertFalse(store.canIndexMessage("m1"))

            store.clearMessageDeleted("m1")

            assertTrue("重抽/继续生成是替换语义，解除后必须能再写", store.canIndexMessage("m1"))
        }
    }

    @Test
    fun `已登记删除的消息不会随文件重载复活`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            store.add("vec_old", FloatArray(4) { 1f }, meta() + ("message_id" to "m1"))
            store.flush()
        }

        val reopened = runBlocking { storeAt(file, flushDelayMs = 60_000) }
        runBlocking {
            // 模拟：进程内先登记删除（Room 已删），随后磁盘内容被重新加载
            reopened.markMessagesDeleted(listOf("m1"))
            reopened.reload()

            assertEquals("已删除的消息不得从旧文件里复活", 0, reopened.count())
        }
    }

    @Test
    fun `按过滤条件删除时_命中行的删除意图会被登记`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            store.add(
                "v1",
                FloatArray(4) { 1f },
                mapOf("session_id" to "s1", "message_id" to "m1", "content_type" to "ai_message"),
            )
            store.add(
                "v2",
                FloatArray(4) { 1f },
                mapOf("session_id" to "s2", "message_id" to "m2", "content_type" to "ai_message"),
            )

            val deleted =
                store.deleteByFilters(
                    mapOf("session_id" to "s1", VectorStore.MARK_MESSAGES_DELETED to true),
                )

            assertEquals(1, deleted)
            assertFalse("被删会话的消息不得再写入", store.canIndexMessage("m1"))
            assertTrue("别的会话不受影响", store.canIndexMessage("m2"))
        }
    }

    @Test
    fun `锁外快照落盘_不会在同一协程嵌套Mutex`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            store.add("v1", FloatArray(4) { 1f }, meta())
            store.deleteByFilters(mapOf("content_type" to "ai_message"))
            assertEquals(0, store.count())
        }
    }

    @Test
    fun `导入索引保留中文正文`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        val content =
            """{"vectors":[{"id":"v1","vector":[1.0,1.0,1.0,1.0],"metadata":{"content":"恢复的中文记忆"}}]}"""
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            assertTrue(store.importIndex(content))
            assertEquals("恢复的中文记忆", store.get("v1")?.metadata?.get("content"))
        }
    }

    // ---- 磁盘预算：不设条数上限之后，真正兜住体积的是字节预算 ----

    @Test
    fun `落盘预算超限时按最旧淘汰_文件不超过预算`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        val budget = 1_500L
        runBlocking {
            val store = InMemoryVectorStore(file, maxVectorsInMemory = 0, flushDelayMs = 60, indexBudgetBytes = budget)
            repeat(20) { i ->
                store.add(
                    "v$i",
                    FloatArray(64) { 1f },
                    mapOf(
                        "content_type" to "ai_message",
                        "created_at" to "2026-01-${(i % 9) + 1}T00:00:00Z",
                        "content" to "第 $i 条内容",
                    ),
                )
            }
            store.flush()

            val stats = store.indexStats()
            assertTrue("必须真的淘汰掉了东西", stats.count < 20)
            assertTrue(
                "淘汰后必须落回预算内（否则整文件重写仍会失败）",
                stats.bytes <= budget,
            )
        }

        val onDisk = file.length()
        assertTrue(
            "实测文件大小也要在预算内：估算与真实序列化必须一致到可判定（实测 ${onDisk}B）",
            onDisk <= budget,
        )
    }

    @Test
    fun `预算内不淘汰_统计如实反映条数与字节`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file)
            repeat(3) { i -> store.add("v$i", FloatArray(4) { 1f }, meta()) }
            store.flush()

            val stats = store.indexStats()
            assertEquals(3, stats.count)
            assertTrue("字节数必须为正，设置页据此显示占用", stats.bytes > 0)
            assertEquals(store.indexBytes(), stats.bytes)
        }
    }

    @Test
    fun `启动对账只删所属会话已不存在的向量`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            store.add("keep", FloatArray(4) { 1f }, mapOf("session_id" to "s1", "message_id" to "m1"))
            store.add("orphan", FloatArray(4) { 1f }, mapOf("session_id" to "gone", "message_id" to "m2"))
            store.add("no_session", FloatArray(4) { 1f }, mapOf("message_id" to "m3"))

            val deleted = store.purgeSessionsNotIn(setOf("s1"))

            assertEquals(1, deleted)
            assertTrue(store.get("keep") != null)
            assertTrue("没有会话归属的条目不该被对账误删（无法判定）", store.get("no_session") != null)
            assertTrue(store.get("orphan") == null)
        }
    }

    @Test
    fun `embedding寮€濮嬪悗鍒犻櫎_鍐欏叆闂ㄧ墝浼氭嫤浣忔櫤鎬佽繖涓€绔炴€`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, flushDelayMs = 60_000)
            val token = requireNotNull(store.beginMessageWrite("m1"))
            store.markMessagesDeleted(listOf("m1"))
            val added =
                store.addIfAllowed(
                    id = "v1",
                    vector = FloatArray(4) { 1f },
                    metadata = meta() + ("message_id" to "m1"),
                    messageId = "m1",
                    token = token,
                )
            assertFalse("删除发生在 embedding 完成后，旧 token 不得再落索引", added)
            assertEquals(0, store.count())
        }
    }
}
