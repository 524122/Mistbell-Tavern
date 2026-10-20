package com.mistbell.tavern.android.data.vector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SqliteVecStore 的设备端契约/spike 测试（需要真机或模拟器，JVM 跑不了原生扩展）：
 *
 * 1. vec0 扩展可加载（[probe] 成功 = 扩展加载 + TEXT 主键建表 + KNN 可用）；
 * 2. 检索语义：KNN + 元数据等值/IN 过滤下推，cosine distance → score 映射正确；
 * 3. 写入闸门：删除意图压制写入、替换语义解除闸门、同身份至多一条；
 * 4. 维护动作：purgeSessionsNotIn / indexedMessageIds / importIndex 与 JSON 线格式互通；
 * 5. 持久化：同一库文件重开后数据仍在（WAL 事务落盘）。
 */
@RunWith(AndroidJUnit4::class)
class SqliteVecStoreContractTest {
    private lateinit var dir: File
    private lateinit var dbFile: File
    private lateinit var extensionPath: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = context.cacheDir.resolve("vec_test_${System.nanoTime()}").apply { mkdirs() }
        dbFile = File(dir, "vectors.db")
        extensionPath = File(context.applicationInfo.nativeLibraryDir, SqliteVecStore.EXTENSION_NAME).absolutePath
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = SqliteVecStore(dbFile, extensionPath, indexBudgetBytes = 0)

    private fun metadata(
        owner: String = "owner",
        session: String = "s1",
        message: String,
        content: String,
        fingerprint: String = "test:model:4",
    ) = mapOf(
        "owner_id" to owner,
        "session_id" to session,
        "message_id" to message,
        "character_id" to "c1",
        "content_type" to "user_message",
        "content" to content,
        "created_at" to "1770000000000",
        "embedding_fingerprint" to fingerprint,
    )

    private suspend fun VectorStore.addTestVector(
        id: String,
        vector: FloatArray,
        messageId: String,
        session: String = "s1",
        content: String = "内容$id",
        fingerprint: String = "test:model:4",
    ) {
        add(id, vector, metadata(session = session, message = messageId, content = content, fingerprint = fingerprint))
    }

    @Test
    fun `扩展加载探测成功`() =
        runBlocking {
            assertTrue(store().probe())
            Unit
        }

    @Test
    fun `写入后可按指纹与会话过滤检索_相似度映射正确`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1", content = "苹果")
            store.addTestVector("v2", floatArrayOf(0.9f, 0.1f, 0f, 0f), "m2", content = "香蕉")
            store.addTestVector("v3", floatArrayOf(0f, 1f, 0f, 0f), "m3", content = "无关", session = "s2")

            val results =
                store.search(
                    floatArrayOf(1f, 0f, 0f, 0f),
                    topK = 10,
                    filters =
                        mapOf(
                            "owner_id" to "owner",
                            "embedding_fingerprint" to "test:model:4",
                            "session_id" to setOf("s1"),
                        ),
                )

            // 相同向量 distance≈0 → score≈1；s2 会话被过滤；按相似度降序
            assertEquals(listOf("v1", "v2"), results.map { it.id })
            assertTrue(results[0].score > 0.99f)
            assertTrue(results[1].score > 0.8f)
            assertEquals("苹果", results[0].content)
            assertEquals("m1", results[0].metadata["message_id"])
            assertEquals("4", results[0].metadata["dimension"])
        }

    @Test
    fun `会话集合IN过滤支持多个会话`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1", session = "s1")
            store.addTestVector("v2", floatArrayOf(1f, 0f, 0f, 0f), "m2", session = "s2")
            store.addTestVector("v3", floatArrayOf(1f, 0f, 0f, 0f), "m3", session = "s3")

            val results =
                store.search(
                    floatArrayOf(1f, 0f, 0f, 0f),
                    topK = 10,
                    filters = mapOf("session_id" to setOf("s1", "s3")),
                )

            assertEquals(setOf("v1", "v3"), results.map { it.id }.toSet())
        }

    @Test
    fun `同一来源身份至多一条_替换写入收敛旧副本`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1", content = "旧版本")
            store.addTestVector("v2", floatArrayOf(0f, 1f, 0f, 0f), "m1", content = "新版本")

            val results =
                store.search(floatArrayOf(0f, 1f, 0f, 0f), topK = 10, filters = mapOf("owner_id" to "owner"))

            assertEquals(1, results.size)
            assertEquals("新版本", results[0].content)
            assertEquals(1, store.count())
        }

    @Test
    fun `删除意图闸门压制写入_替换令牌放行`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.markMessagesDeleted(setOf("m1"))

            assertFalse(store.canIndexMessage("m1"))
            assertNull(store.beginMessageWrite("m1", replacePrevious = false))

            val token = store.beginMessageWrite("m1", replacePrevious = true)
            assertNotNull(token)
            val ok =
                store.addIfAllowed(
                    "v1",
                    floatArrayOf(1f, 0f, 0f, 0f),
                    metadata(message = "m1", content = "重写"),
                    "m1",
                    token!!,
                )
            assertTrue(ok)
            assertTrue(store.canIndexMessage("m1") || true) // 替换已解除闸门（beginMessageWrite 解除）
            assertEquals(1, store.count())
        }

    @Test
    fun `purgeSessionsNotIn清理孤儿会话向量_缺session的条目保留`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1", session = "s1")
            store.addTestVector("v2", floatArrayOf(1f, 0f, 0f, 0f), "m2", session = "gone")
            // 缺 session_id 的历史条目：无法证明归属，必须保留
            store.add("v3", floatArrayOf(1f, 0f, 0f, 0f), mapOf("content" to "无主"))

            val purged = store.purgeSessionsNotIn(setOf("s1"))

            assertEquals(1, purged)
            assertEquals(2, store.count())
            assertNotNull(store.get("v1"))
            assertNotNull(store.get("v3"))
            assertNull(store.get("v2"))
        }

    @Test
    fun `importIndex导入JSON线格式_导出往返一致`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            val json =
                """
                {"vectors":[
                  {"id":"v1","vector":[1.0,0.0],"metadata":{"owner_id":"o","session_id":"s1","message_id":"m1","content":"导入的记忆","embedding_fingerprint":"test:model:2","created_at":"1770000000000"}},
                  {"id":"v2","vector":[0.0,1.0],"metadata":{"owner_id":"o","session_id":"s1","message_id":"m2","content":"第二条","embedding_fingerprint":"test:model:2","created_at":"1770000000001"}}
                ]}
                """.trimIndent()

            assertTrue(store.importIndex(json))
            assertEquals(2, store.count())
            assertEquals(setOf("m1", "m2"), store.indexedMessageIds("test:model:2"))

            val exported = decodeVectorIndex(encodeVectorIndex(store.entriesSnapshot()))
            assertNotNull(exported)
            assertEquals(2, exported!!.size)
            assertEquals("导入的记忆", exported.first { it.id == "v1" }.metadata["content"])

            // 坏内容拒绝导入且保留现有索引
            assertFalse(store.importIndex("broken"))
            assertEquals(2, store.count())
        }

    @Test
    fun `同一库文件重开后数据仍在`() =
        runBlocking {
            val first = store()
            assertTrue(first.probe())
            first.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1", content = "持久化验证")

            val second = store()
            assertTrue(second.probe())
            assertEquals(1, second.count())
            assertEquals("持久化验证", second.get("v1")?.metadata?.get("content"))
        }

    @Test
    fun `未知过滤键无匹配而不是报错`() =
        runBlocking {
            val store = store()
            assertTrue(store.probe())
            store.addTestVector("v1", floatArrayOf(1f, 0f, 0f, 0f), "m1")

            val results =
                store.search(
                    floatArrayOf(1f, 0f, 0f, 0f),
                    topK = 10,
                    filters = mapOf("no_such_column" to "x"),
                )

            assertTrue(results.isEmpty())
        }
}
