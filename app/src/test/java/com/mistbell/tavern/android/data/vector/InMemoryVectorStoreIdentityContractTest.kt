package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 契约：InMemoryVectorStore 的"同一来源身份至多一条"不变量与加载收敛——
 * 1) 同一 (owner_id, session_id, message_id) 身份再次写入会**替换**全部旧项
 *    （历史随机 vectorId 堆积的多条旧摘要不得残留，无论内存态还是落盘后再读回）；
 * 2) loadFromDisk 对旧格式文件中同身份的多条记录按文件序只保留**最后一条**；
 * 3) loadGate 在任何磁盘读取之前 await（清零先行，禁止读回刚删掉的旧索引）；
 * 4) 逆序提交防护：旧版本写入 commitCheck 通过后被暂停、新版本先提交、
 *    旧版本恢复后其过期 token 必须被闸门拒绝，不得覆盖新版本。
 */
class InMemoryVectorStoreIdentityContractTest {
    private lateinit var dir: File

    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(20)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vector-identity-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun storeAt(
        file: File = File(dir, InMemoryVectorStore.FILE_NAME),
        loadGate: suspend () -> Unit = {},
    ) = InMemoryVectorStore(file, maxVectorsInMemory = 0, flushDelayMs = 60, loadGate = loadGate)

    private fun identityMeta(
        content: String,
        messageId: String = "m1",
    ) = mapOf<String, Any>(
        "owner_id" to "o1",
        "session_id" to "s1",
        "message_id" to messageId,
        "content_type" to "ai_message",
        "content" to content,
    )

    @Test
    fun `同一身份顺序写入后旧内容消失_索引至多一条`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = storeAt(file, loadGate = {})
            store.add("vec_first", FloatArray(4) { 1f }, identityMeta("第一版摘要"))
            store.add("vec_second", FloatArray(4) { 1f }, identityMeta("第二版摘要"))
            store.flush()
        }

        runBlocking {
            val reopened = storeAt(file)
            assertEquals("同一身份在索引中至多一条", 1, reopened.count())
            assertNull("旧 vectorId 的旧项必须被替换掉", reopened.get("vec_first"))
            val only = reopened.get("vec_second")
            assertNotNull(only)
            assertEquals("第二版摘要", only!!.metadata["content"])
        }
    }

    @Test
    fun `loadFromDisk 对旧文件同身份多条按文件序保留最后一条`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        // 手写"旧版本"索引文件：同一身份 (o1,s1,m1) 堆了两条历史随机 vectorId 的副本
        file.writeText(
            """{"vectors":[""" +
                """{"id":"vec_old","vector":[1.0,0.0,0.0,0.0],""" +
                """"metadata":{"owner_id":"o1","session_id":"s1","message_id":"m1",""" +
                """"content_type":"ai_message","content":"最旧的摘要"}},{"id":"vec_new",""" +
                """"vector":[0.0,1.0,0.0,0.0],""" +
                """"metadata":{"owner_id":"o1","session_id":"s1","message_id":"m1",""" +
                """"content_type":"ai_message","content":"最新一条"}}]}""",
        )

        runBlocking {
            val store = storeAt(file)
            assertEquals("加载即收敛：同身份只留一条", 1, store.count())
            assertNull(store.get("vec_old"))
            val kept = store.get("vec_new")
            assertNotNull(kept)
            assertEquals("文件序最后一条才是'最新写入'语义", "最新一条", kept!!.metadata["content"])
        }
    }

    @Test
    fun `loadGate 在读盘前 await`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        file.writeText(
            """{"vectors":[{"id":"v1","vector":[1.0,1.0,1.0,1.0],""" +
                """"metadata":{"content_type":"ai_message","content":"磁盘内容"}}]}""",
        )
        val gateEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store =
            storeAt(file, loadGate = {
                gateEntered.complete(Unit)
                release.await()
            })

        runBlocking {
            val pending = async { store.count() }
            // 等闸门触发并挂起：读盘动作必定还没发生（闸门在读盘之前）
            gateEntered.await()
            assertFalse("闸门未放行前不得完成读盘", pending.isCompleted)

            release.complete(Unit)
            assertEquals("闸门放行后按磁盘内容加载", 1, pending.await())
            assertEquals("磁盘内容", store.get("v1")?.metadata?.get("content"))
        }
    }

    @Test
    fun `逆序提交_旧版本恢复后不得覆盖新版本`() {
        val file = File(dir, InMemoryVectorStore.FILE_NAME)
        runBlocking {
            val store = InMemoryVectorStore(file, maxVectorsInMemory = 0, flushDelayMs = 60_000)
            val service = VectorMemoryService(store, StubEmbeddingService())

            val oldCheckPassed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resumeOld = kotlinx.coroutines.CompletableDeferred<Unit>()

            // 旧版本：commitCheck 通过（确认源版本无误）后被暂停
            val oldWrite =
                async {
                    service.storeMessage(
                        content = "旧版本摘要",
                        ownerId = "o1",
                        characterId = "c1",
                        sessionId = "s1",
                        messageId = "m1",
                        contentType = "ai_message",
                        commitCheck = {
                            oldCheckPassed.complete(Unit)
                            resumeOld.await()
                            true
                        },
                    )
                }
            oldCheckPassed.await()

            // 新版本先提交：旧版本登记删除（generation 前进），replacePrevious 解除并写入新向量
            store.markMessagesDeleted(listOf("m1"))
            val newId =
                service.storeMessage(
                    content = "新版本摘要",
                    ownerId = "o1",
                    characterId = "c1",
                    sessionId = "s1",
                    messageId = "m1",
                    contentType = "ai_message",
                    replacePrevious = true,
                )
            assertNotNull("新版本必须写成功", newId)

            // 旧版本恢复：token 的 generation 已过期，闸门必须拒绝
            resumeOld.complete(Unit)
            assertNull("旧版本不得落地（覆盖新版本）", oldWrite.await())

            assertEquals("索引里只能有新版本一条", 1, store.count())
            val results =
                store.search(
                    FloatArray(4) { 1f },
                    topK = 5,
                    filters = mapOf("message_id" to "m1"),
                )
            assertEquals(1, results.size)
            assertEquals("新版本摘要", results.single().content)
        }
    }
}
