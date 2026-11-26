package com.mistbell.tavern.android.data.repository

import androidx.room.InvalidationTracker
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.ApiConfigDao
import com.mistbell.tavern.android.data.local.dao.CharacterDao
import com.mistbell.tavern.android.data.local.dao.CustomPromptDao
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.GlobalRegexDao
import com.mistbell.tavern.android.data.local.dao.MemoryDao
import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.local.dao.QuickReplyDao
import com.mistbell.tavern.android.data.local.dao.RulePackDao
import com.mistbell.tavern.android.data.local.dao.SessionDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.dao.ThemePackDao
import com.mistbell.tavern.android.data.local.dao.VectorMemoryDao
import com.mistbell.tavern.android.data.local.dao.VectorSyncOutboxDao
import com.mistbell.tavern.android.data.local.dao.WorldBookDao
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 会话写路径事务体的 JVM 契约测试（无 Robolectric——Room 事务无法在替身 db 上运行，
 * 事务边界留在 ChatRepository 成员里、由生产调用方 `db.withTransaction { block() }` 包裹；
 * 本测试直接运行 internal 事务体函数，钉住它们的内部语义，事务原子性由 Room 编译期保证）：
 * 1) sendMessage 事务体：先写消息再读会话（计数回写基于消息已写入后的状态）、计数 +1、
 *    首条用户消息回填标题、会话不存在时静默跳过回写；
 * 2) 桥追加/删除事务体：计数按真实行数增减且不为负、会话不存在时返回 null/0、空列表短路；
 * 3) 桥删除路径必须先取会话锁（持锁期间不得进入删除临界区，与删除/清空互斥）。
 */
class ChatRepositoryRoomWriteContractTest {
    private companion object {
        const val OWNER = "local-user"
        const val CHARACTER = "c1"
        const val SESSION = "s1"
        const val BASE_TIME = "2026-01-01T00:00:00Z"

        /** 代理未处理的方法时直接失败（代码路径变了要显式更新测试，不静默返回默认值） */
        private fun <T : Any> daoProxy(
            iface: Class<T>,
            onCall: (name: String, args: Array<Any?>) -> Any?,
        ): T =
            Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
            ) { _, method, args -> onCall(method.name, args ?: emptyArray()) } as T
    }

    /** 内存库状态 + DAO 调用顺序记录（callLog 即事务体内的执行序） */
    private class MemoryDb {
        val messages = LinkedHashMap<String, MessageEntity>()
        val sessions = LinkedHashMap<String, SessionEntity>()
        val callLog = mutableListOf<String>()

        fun seedSession(
            id: String,
            messageCount: Int,
            title: String = "",
            updatedAt: String = BASE_TIME,
        ) {
            sessions[id] =
                SessionEntity(
                    id = id,
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    title = title,
                    createdAt = BASE_TIME,
                    updatedAt = updatedAt,
                    messageCount = messageCount,
                    providerId = "",
                    modelId = "",
                    worldBookId = "",
                    summaryJson = "",
                )
        }

        fun seedMessage(
            id: String,
            sessionId: String,
            createdAt: String,
        ) {
            messages[id] =
                MessageEntity(
                    id = id,
                    sessionId = sessionId,
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    role = "user",
                    content = "content-$id",
                    thinking = null,
                    createdAt = createdAt,
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                )
        }

        /** 替身 AppDatabase：只实现测试涉及的两个 DAO，其余未触即败 */
        fun db(): AppDatabase =
            object : AppDatabase() {
                override fun characterDao(): CharacterDao = throw UnsupportedOperationException()

                override fun sessionDao(): SessionDao = sessionDaoProxy()

                override fun messageDao(): MessageDao = messageDaoProxy()

                override fun worldBookDao(): WorldBookDao = throw UnsupportedOperationException()

                override fun settingsDao(): SettingsDao = throw UnsupportedOperationException()

                override fun memoryDao(): MemoryDao = throw UnsupportedOperationException()

                override fun structuredMemoryDao(): StructuredMemoryDao = throw UnsupportedOperationException()

                override fun vectorMemoryDao(): VectorMemoryDao = throw UnsupportedOperationException()

                override fun vectorSyncOutboxDao(): VectorSyncOutboxDao = throw UnsupportedOperationException()

                override fun themePackDao(): ThemePackDao = throw UnsupportedOperationException()

                override fun apiConfigDao(): ApiConfigDao = throw UnsupportedOperationException()

                override fun customPromptDao(): CustomPromptDao = throw UnsupportedOperationException()

                override fun rulePackDao(): RulePackDao = throw UnsupportedOperationException()

                override fun quickReplyDao(): QuickReplyDao = throw UnsupportedOperationException()

                override fun globalRegexDao(): GlobalRegexDao = throw UnsupportedOperationException()

                override fun extensionPackDao(): ExtensionPackDao = throw UnsupportedOperationException()

                override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()

                override fun clearAllTables() = throw UnsupportedOperationException()
            }

        private fun messageDaoProxy(): MessageDao =
            daoProxy(MessageDao::class.java) { name, args ->
                when (name) {
                    "upsert" -> {
                        val message = args[0] as MessageEntity
                        callLog += "message.upsert:${message.id}"
                        messages[message.id] = message
                        null
                    }
                    "deleteByIds" -> {
                        @Suppress("UNCHECKED_CAST")
                        val ids = args[0] as Collection<String>
                        var deleted = 0
                        ids.forEach { if (messages.remove(it) != null) deleted++ }
                        callLog += "message.deleteByIds($deleted)"
                        deleted
                    }
                    "getAnyById" -> messages[args[0] as String]
                    else -> throw UnsupportedOperationException("MessageDao.$name")
                }
            }

        private fun sessionDaoProxy(): SessionDao =
            daoProxy(SessionDao::class.java) { name, args ->
                when (name) {
                    "get" -> {
                        callLog += "session.get:${args[0]}"
                        sessions[args[0] as String]
                            ?.takeIf { it.ownerId == args[1] && it.characterId == args[2] }
                    }
                    "upsert" -> {
                        val session = args[0] as SessionEntity
                        callLog += "session.upsert:${session.id}(count=${session.messageCount})"
                        sessions[session.id] = session
                        null
                    }
                    else -> throw UnsupportedOperationException("SessionDao.$name")
                }
            }
    }

    // ---- sendMessage 事务体（修复1） ----

    @Test
    fun `sendMessage事务体_先写消息再读会话_计数加一并回填首条标题`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 0, title = "")
            val userMessage = buildUserMessage("m1", createdAt = "2026-01-02T00:00:00Z")

            saveUserMessageAndSyncSessionCount(
                db = db.db(),
                ownerId = OWNER,
                characterId = CHARACTER,
                sessionId = SESSION,
                userMessage = userMessage,
                titleSeed = "这是一段首条用户消息用来当标题",
            )

            assertTrue("用户消息必须已写入", db.messages.containsKey("m1"))
            val session = db.sessions.getValue(SESSION)
            assertEquals("计数必须在原值上加一", 1, session.messageCount)
            assertEquals("updatedAt 必须随用户消息刷新", userMessage.createdAt, session.updatedAt)
            assertEquals("首条用户消息回填标题", "这是一段首条用户消息用来当标题".take(26), session.title)
            // 事务体内顺序契约：先写消息，再读会话（计数回写基于写入后的库内状态），最后回写
            assertEquals(
                listOf("message.upsert:m1", "session.get:$SESSION", "session.upsert:$SESSION(count=1)"),
                db.callLog,
            )
        }

    @Test
    fun `sendMessage事务体_已有标题时不覆盖且计数继续累加`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 5, title = "已有标题")
            db.seedMessage("old", SESSION, createdAt = BASE_TIME)

            saveUserMessageAndSyncSessionCount(
                db = db.db(),
                ownerId = OWNER,
                characterId = CHARACTER,
                sessionId = SESSION,
                userMessage = buildUserMessage("m2", createdAt = "2026-01-03T00:00:00Z"),
                titleSeed = "不该成为标题的新消息",
            )

            val session = db.sessions.getValue(SESSION)
            assertEquals(6, session.messageCount)
            assertEquals("已有标题", session.title)
        }

    @Test
    fun `sendMessage事务体_会话不存在时只落消息不回写`() =
        runBlocking {
            val db = MemoryDb()

            saveUserMessageAndSyncSessionCount(
                db = db.db(),
                ownerId = OWNER,
                characterId = CHARACTER,
                sessionId = SESSION,
                userMessage = buildUserMessage("m3", createdAt = BASE_TIME),
                titleSeed = "标题",
            )

            assertTrue("消息本身仍要落库", db.messages.containsKey("m3"))
            // 读会话会发生（返回 null 后跳过），但不得有任何会话回写
            assertEquals(
                "会话不存在时只落消息、不回写会话",
                listOf("message.upsert:m3", "session.get:$SESSION"),
                db.callLog,
            )
        }

    // ---- 桥追加事务体（修复2） ----

    @Test
    fun `桥追加事务体_消息落库且计数加一返回新id`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 2)

            val id =
                appendBridgeMessageWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    role = "user",
                    content = "桥消息",
                )

            assertNotNull("会话存在时必须返回新消息 id", id)
            assertEquals("追加的消息必须落库", "桥消息", db.messages.getValue(id!!).content)
            val session = db.sessions.getValue(SESSION)
            assertEquals("计数 +1", 3, session.messageCount)
            assertTrue("updatedAt 必须刷新", session.updatedAt != BASE_TIME)
        }

    @Test
    fun `桥追加事务体_会话不存在返回null且不落消息`() =
        runBlocking {
            val db = MemoryDb()

            val id =
                appendBridgeMessageWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    role = "user",
                    content = "孤儿消息",
                )

            assertNull("会话不存在时返回 null", id)
            assertTrue("不得落任何消息", db.messages.isEmpty())
        }

    // ---- 桥删除事务体（修复2） ----

    @Test
    fun `桥删除事务体_按真实行数扣减计数且先删后回写`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 3)
            db.seedMessage("a", SESSION, createdAt = BASE_TIME)
            db.seedMessage("b", SESSION, createdAt = "2026-01-01T00:01:00Z")
            db.seedMessage("c", SESSION, createdAt = "2026-01-01T00:02:00Z")

            val deleted =
                deleteBridgeMessagesWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    messageIds = listOf("a", "c", "不存在的id"),
                )

            assertEquals("返回真实删除行数（不存在的 id 不计）", 2, deleted)
            assertEquals(1, db.messages.size)
            assertEquals("计数扣减真实行数且不为负", 1, db.sessions.getValue(SESSION).messageCount)
            // 事务体内顺序契约：先删除（拿到真实行数），再回写计数
            assertEquals(
                listOf("message.deleteByIds(2)", "session.get:$SESSION", "session.upsert:$SESSION(count=1)"),
                db.callLog,
            )
        }

    @Test
    fun `桥删除事务体_计数漂移时扣减不为负`() =
        runBlocking {
            val db = MemoryDb()
            // 计数与实际行数漂移（历史脏数据）：删 3 行但计数只有 2
            db.seedSession(SESSION, messageCount = 2)
            db.seedMessage("a", SESSION, createdAt = BASE_TIME)
            db.seedMessage("b", SESSION, createdAt = "2026-01-01T00:01:00Z")
            db.seedMessage("c", SESSION, createdAt = "2026-01-01T00:02:00Z")

            val deleted =
                deleteBridgeMessagesWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    messageIds = listOf("a", "b", "c"),
                )

            assertEquals(3, deleted)
            assertEquals("扣减到 0 为止，不得为负", 0, db.sessions.getValue(SESSION).messageCount)
        }

    @Test
    fun `桥删除事务体_会话不存在返回0`() =
        runBlocking {
            val db = MemoryDb()

            val deleted =
                deleteBridgeMessagesWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    messageIds = listOf("a", "b"),
                )

            assertEquals("无会话无消息时返回 0", 0, deleted)
            assertTrue("不得有任何会话回写", db.callLog.none { it.startsWith("session.") })
        }

    @Test
    fun `桥删除事务体_空列表短路返回0`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 1)

            val deleted =
                deleteBridgeMessagesWithSessionLock(
                    db = db.db(),
                    ownerId = OWNER,
                    characterId = CHARACTER,
                    sessionId = SESSION,
                    messageIds = emptyList(),
                )

            assertEquals(0, deleted)
            assertTrue("空列表不得触碰消息表与会话表", db.callLog.isEmpty())
        }

    // ---- 桥删除取会话锁（修复2 的锁纪律） ----

    @Test
    fun `桥删除路径先取会话锁_持锁期间不得进入删除临界区`() =
        runBlocking {
            val db = MemoryDb()
            db.seedSession(SESSION, messageCount = 1)
            db.seedMessage("a", SESSION, createdAt = BASE_TIME)
            // 缓冲为 1 的信号道：进入删除临界区即发（缓冲保证"未持锁就进入"的信号不丢）
            val entered = Channel<Unit>(1)

            val deleteJob: Job =
                SessionMutationLocks.withSessionLock(SESSION) {
                    val job =
                        launch {
                            deleteBridgeMessagesWithSessionLock(
                                db = db.db(),
                                ownerId = OWNER,
                                characterId = CHARACTER,
                                sessionId = SESSION,
                                messageIds = listOf("a"),
                                transact = { block ->
                                    entered.trySend(Unit)
                                    block()
                                },
                            )
                        }
                    // 若删除路径没取会话锁，它会立刻进入临界区发出信号（毫秒级到达）；
                    // 取了锁则信号在持锁期间永远等不到。宽超时只用于区分这两种时序。
                    val signal = withTimeoutOrNull(2_000) { entered.receive() }
                    assertNull("持锁期间不得进入删除临界区（说明路径先取了会话锁）", signal)
                    job
                }
            // 释放锁后删除得以完成（join 掉竞态，再断言删除真实发生）
            deleteJob.join()
            assertEquals(0, db.messages.size)
            assertEquals(0, db.sessions.getValue(SESSION).messageCount)
        }

    private fun buildUserMessage(
        id: String,
        createdAt: String,
    ): MessageEntity =
        MessageEntity(
            id = id,
            sessionId = SESSION,
            ownerId = OWNER,
            characterId = CHARACTER,
            role = "user",
            content = "用户消息内容",
            thinking = null,
            createdAt = createdAt,
            memoryIdsJson = "",
            swipesJson = "",
            swipeIndex = 0,
            thinkingSwipesJson = "",
        )
}
