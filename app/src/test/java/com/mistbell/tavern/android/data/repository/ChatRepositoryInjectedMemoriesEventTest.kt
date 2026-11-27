package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_GROUP
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.eval.memory.EvalContext
import com.mistbell.tavern.android.eval.memory.EvalRuntime
import com.mistbell.tavern.android.eval.memory.FakeTavernDb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * 「记忆可见 v0」发布通道的 JVM 端到端契约（docs/ROADMAP.md P2 第 4 项）：
 * 五条生成路径（发送 / 群聊推动 / 重新生成 / 继续 / swipe）在**回复成功落库后**发布
 * [InjectedMemoriesEvent]，清单取自**发送时那一次装配**（buildPromptTrace 的注入清单）。
 *
 * 语义钉死：
 * 1. 事件 id 与本次装配实际注入的结构化记忆一致（且正文真的出现在发给假 LLM 的提示词里）；
 * 2. 记忆关闭 / 无注入 / 回复失败 → 没有事件；
 * 3. 重新生成按**新**消息 id 发布；继续与 swipe 按**同一条**消息 id（新内容）发布。
 *
 * 基建沿用记忆评测的替身套路（EvalRuntime 换容器 + FakeTavernDb 内存库），LLM 用 JDK 自带
 * HttpServer 起的本地 OpenAI 兼容端点（非流式），不引新依赖、不碰真网。
 */
class ChatRepositoryInjectedMemoriesEventTest {
    private val fakeDb = FakeTavernDb()
    private val fakeLlm = FakeChatLlm()
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        runBlocking {
            EvalRuntime.installSession(fakeDb)
            installTestPendingExtractionStore()
            database = fakeDb.db()
            fakeLlm.start()
            repository = ChatRepository(EvalContext())
            seedLlmSettings()
            seedScene(enableMemory = true)
        }
    }

    @After
    fun tearDown() {
        fakeLlm.stop()
        EvalRuntime.restore()
    }

    // ---- 正向：清单与本次装配一致 ----

    @Test
    fun `发送路径发布事件_注入清单与本次装配一致`() =
        runBlocking {
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")
            seedMemory(id = MEMORY_B, content = "用户住在朝阳区的老胡同")
            seedMessage(id = "u-old", role = "user", content = "早上好", createdAt = time(0))
            seedMessage(id = "a-old", role = "assistant", content = "早上好呀", createdAt = time(1))

            subscribeAndCollect { events ->
                repository.sendMessage(OWNER, CHARACTER, SESSION, "今晚我们去吃北京烤鸭吗")
                val event = events.receiveWithin()

                assertNotNull("记忆开启且有注入时必须发布事件", event)
                assertEquals(SESSION, event?.sessionId)
                assertEquals(OWNER, event?.ownerId)
                assertEquals(
                    "事件 id 必须与本次装配的注入清单一致",
                    setOf(MEMORY_A, MEMORY_B),
                    event?.structuredMemoryIds?.mapNotNull { it.toLongOrNull() }?.toSet(),
                )
                // 注入清单与发给模型的提示词逐条对上（溯源字段与实际注入一致）
                val promptBody = fakeLlm.lastRequestBody()
                assertNotNull(promptBody)
                assertTrue("结构化记忆块应出现在提示词里", promptBody?.contains("## Known Information") == true)
                for (content in listOf("用户最爱吃北京烤鸭", "用户住在朝阳区的老胡同")) {
                    assertTrue("注入的记忆正文应出现在提示词里：$content", promptBody?.contains(content) == true)
                }
                // 回顾的历史行（若有）同样要与提示词一致
                event?.recallMessageIds?.forEach { id ->
                    val line = database.messageDao().getAnyById(id)
                    assertTrue(promptBody?.contains(line?.content.orEmpty().take(RECALL_INJECT_MAX_CHARS)) == true)
                }
                // 事件挂在新落库的 assistant 消息上
                val assistant = latestAssistantMessage()
                assertNotNull(assistant)
                assertEquals(assistant?.id, event?.assistantMessageId)
                assertEquals("好的，我记得了。", assistant?.content)
            }
        }

    // ---- 负向：不该有事件的情形 ----

    @Test
    fun `记忆关闭时回复照常落库但没有事件`() =
        runBlocking {
            seedScene(enableMemory = false)
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")

            subscribeAndCollect { events ->
                repository.sendMessage(OWNER, CHARACTER, SESSION, "随便聊聊")
                val event = events.drainAfterAction()

                assertNull("记忆关闭时不得发布事件", event)
                assertEquals("回复仍然要落库", "好的，我记得了。", latestAssistantMessage()?.content)
            }
        }

    @Test
    fun `本轮没有注入时没有事件`() =
        runBlocking {
            subscribeAndCollect { events ->
                repository.sendMessage(OWNER, CHARACTER, SESSION, "第一次对话")
                val event = events.drainAfterAction()

                assertNull("没有结构化记忆注入时不得发布事件", event)
                assertNotNull(latestAssistantMessage())
            }
        }

    @Test
    fun `回复失败时没有事件且不落库`() =
        runBlocking {
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")
            fakeLlm.respondWithError = true

            subscribeAndCollect { events ->
                val error = runCatching { repository.sendMessage(OWNER, CHARACTER, SESSION, "你好") }
                assertTrue("远端失败应向上抛出", error.isFailure)
                val event = events.drainAfterAction()

                assertNull("回复失败（未落库）不得发布事件", event)
                val messages = database.messageDao().getBySession(SESSION, OWNER).first()
                assertFalse("不得落任何 assistant 消息", messages.any { it.role == "assistant" })
            }
        }

    // ---- 其余路径：重新生成 / 继续 / swipe / 群聊推动 ----

    @Test
    fun `重新生成按新消息发布事件`() =
        runBlocking {
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")
            seedMessage(id = "u-old", role = "user", content = "讲个笑话", createdAt = time(0))
            seedMessage(id = "a-old", role = "assistant", content = "旧回复", createdAt = time(1))

            subscribeAndCollect { events ->
                repository.regenerateMessage(OWNER, CHARACTER, SESSION, "a-old")
                val event = events.receiveWithin()

                assertNotNull(event)
                assertEquals(
                    "重新生成必须按替换后的新消息发布",
                    setOf(MEMORY_A),
                    event?.structuredMemoryIds?.mapNotNull { it.toLongOrNull() }?.toSet(),
                )
                val newAssistantId = event?.assistantMessageId
                assertFalse("不得沿用被替换的旧消息 id", newAssistantId == "a-old")
                assertEquals(newAssistantId, latestAssistantMessage()?.id)
                assertTrue("新消息已落库", database.messageDao().getAnyById(newAssistantId.orEmpty()) != null)
                assertNull("旧消息已被替换删除", database.messageDao().getAnyById("a-old"))
            }
        }

    @Test
    fun `继续与swipe按同一条消息的新内容发布事件`() =
        runBlocking {
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")
            seedMessage(id = "u-old", role = "user", content = "再多说点", createdAt = time(0))
            seedMessage(id = "a-old", role = "assistant", content = "旧回复", createdAt = time(1))

            subscribeAndCollect { events ->
                repository.continueMessage(OWNER, CHARACTER, SESSION)
                val continueEvent = events.receiveWithin()

                assertNotNull(continueEvent)
                assertEquals("继续生成挂在同一条消息上", "a-old", continueEvent?.assistantMessageId)

                repository.swipeMessage(OWNER, CHARACTER, SESSION, "a-old", "right")
                val swipeEvent = events.receiveWithin()

                assertNotNull(swipeEvent)
                assertEquals("swipe 生成同样挂在同一条消息上", "a-old", swipeEvent?.assistantMessageId)
                assertEquals(
                    setOf(MEMORY_A),
                    swipeEvent?.structuredMemoryIds?.mapNotNull { it.toLongOrNull() }?.toSet(),
                )
            }
        }

    @Test
    fun `群聊推动按新消息发布事件`() =
        runBlocking {
            seedMemory(id = MEMORY_A, content = "用户最爱吃北京烤鸭")
            seedScene(
                enableMemory = true,
                mode = SESSION_MODE_GROUP,
                participants = listOf(CHARACTER),
            )

            subscribeAndCollect { events ->
                repository.continueGroupChat(OWNER, CHARACTER, SESSION)
                val event = events.receiveWithin()

                assertNotNull("群聊推动也要发布事件", event)
                assertEquals(setOf(MEMORY_A), event?.structuredMemoryIds?.mapNotNull { it.toLongOrNull() }?.toSet())
                val assistant = latestAssistantMessage()
                assertEquals(assistant?.id, event?.assistantMessageId)
            }
        }

    // ---- 基建 ----

    /** 已订阅状态下执行动作；[EventRecorder] 的接收语义见各自方法 */
    private suspend fun subscribeAndCollect(block: suspend (EventRecorder) -> Unit) {
        coroutineScope {
            val events = EventRecorder()
            // UNDISPATCHED：订阅先于动作建立，杜绝"事件发出时还没有订阅者"的竞态
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.injectedMemoriesEvents.collect { events.channel.trySend(it) }
                }
            try {
                block(events)
            } finally {
                job.cancel()
            }
        }
    }

    private class EventRecorder {
        val channel = Channel<InjectedMemoriesEvent>(Channel.UNLIMITED)

        /** 正向用例：等事件真正送达（真超时，只兜底不计时） */
        suspend fun receiveWithin(): InjectedMemoriesEvent? {
            return withTimeoutOrNull(EVENT_DELIVERY_TIMEOUT_MS) { channel.receive() }
        }

        /** 负向用例：动作已同步返回，让订阅者把缓冲清完再确认一条都没有 */
        suspend fun drainAfterAction(): InjectedMemoriesEvent? {
            repeat(YIELD_ROUNDS_FOR_DRAIN) { yield() }
            return channel.tryReceive().getOrNull()
        }
    }

    private suspend fun latestAssistantMessage(): MessageEntity? =
        database
            .messageDao()
            .getBySession(SESSION, OWNER)
            .first()
            .filter { it.role == "assistant" }
            .maxByOrNull { it.createdAt }

    private suspend fun seedLlmSettings() {
        database.settingsDao().upsert(SettingsEntity("llm_base_url", fakeLlm.baseUrl))
        database.settingsDao().upsert(SettingsEntity("llm_api_key", "test-key"))
        database.settingsDao().upsert(SettingsEntity("llm_model", "test-model"))
        database.settingsDao().upsert(SettingsEntity("streaming_enabled", "0"))
    }

    /** 角色 + 会话（classic / group）；群聊参与者即主角色自己 */
    private suspend fun seedScene(
        enableMemory: Boolean,
        mode: String = "classic",
        participants: List<String> = emptyList(),
    ) {
        database.characterDao().upsert(
            CharacterEntity(
                id = CHARACTER,
                name = "测试角色",
                role = "assistant",
                description = "一个测试角色",
                personality = "",
                scenario = "",
                firstMes = "",
                mesExample = "",
                color = "",
                avatarData = "",
                worldBookId = "",
                dataJson = "",
            ),
        )
        database.sessionDao().upsert(
            SessionEntity(
                id = SESSION,
                ownerId = OWNER,
                characterId = CHARACTER,
                title = "事件契约测试会话",
                createdAt = BASE_TIME.toString(),
                updatedAt = BASE_TIME.toString(),
                messageCount = 0,
                providerId = "",
                modelId = "",
                worldBookId = "",
                summaryJson = "",
                enableLongTermMemory = enableMemory,
                contextTokenLimit = CONTEXT_TOKEN_LIMIT,
                participantCharacterIdsJson =
                    if (participants.isEmpty()) "" else "[" + participants.joinToString(",") { "\"$it\"" } + "]",
                mode = mode,
            ),
        )
    }

    private suspend fun seedMemory(
        id: Long,
        content: String,
    ) {
        database.structuredMemoryDao().insert(
            StructuredMemoryEntity(
                id = id,
                ownerId = OWNER,
                characterId = CHARACTER,
                sessionId = SESSION,
                memoryType = "event",
                title = null,
                content = content,
                structuredData = null,
                importance = 9,
                tags = null,
                keywords = null,
                createdAt = BASE_TIME.toString(),
                updatedAt = BASE_TIME.toString(),
                lastAccessedAt = null,
                relatedMessageIds = null,
            ),
        )
    }

    private suspend fun seedMessage(
        id: String,
        role: String,
        content: String,
        createdAt: String,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                id = id,
                sessionId = SESSION,
                ownerId = OWNER,
                characterId = CHARACTER,
                role = role,
                content = content,
                thinking = null,
                createdAt = createdAt,
                memoryIdsJson = "",
                swipesJson = "",
                swipeIndex = 0,
                thinkingSwipesJson = "",
            ),
        )
    }

    /**
     * EvalRuntime 换掉了 database/vector 栈，但没有换 pendingExtractionStore——它的默认实现
     * 要读 ContextWrapper(null).filesDir，JVM 上必炸。这里反射换成临时目录的替身 store
     * （空闸门），让"记忆开启"路径的攒批逻辑在替身容器上继续可用。
     */
    private fun installTestPendingExtractionStore() {
        tempDir = Files.createTempDirectory("chat-event-contract").toFile()
        val container: AppContainer = TavernApplication.instance.container
        val field =
            AppContainer::class.java.declaredFields.firstOrNull { it.name == "pendingExtractionStore\$delegate" }
                ?: error("AppContainer.pendingExtractionStore 的 delegate 字段不存在")
        field.isAccessible = true
        field.set(
            container,
            lazyOf(PendingExtractionStore(File(tempDir, "pending-extraction.json"), loadGate = {})),
        )
    }

    private fun time(secondsFromBase: Long): String = BASE_TIME.plusSeconds(secondsFromBase).toString()

    private companion object {
        const val OWNER = "local-user"
        const val CHARACTER = "c1"
        const val SESSION = "s1"
        const val MEMORY_A = 11L
        const val MEMORY_B = 12L
        const val CONTEXT_TOKEN_LIMIT = 4096
        const val EVENT_DELIVERY_TIMEOUT_MS = 10_000L
        const val YIELD_ROUNDS_FOR_DRAIN = 8
        const val RECALL_INJECT_MAX_CHARS = 150
        val BASE_TIME: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
    }

    /**
     * 手写的本地 OpenAI 兼容假 LLM（java.net.ServerSocket；android.jar 没有 JDK 的
     * com.sun.net.httpserver，测试也不引新依赖）。只实现 OkHttp 非流式请求需要的最小 HTTP/1.1：
     * 收 POST /chat/completions → 记录请求正文 → 回一条固定的 chat completion。
     * respondWithError=true 时回 500（LlmClient 对非 IOException 的 5xx 直接抛出、不重试）。
     */
    private class FakeChatLlm {
        private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        private val lock = Any()
        private val requestBodies = mutableListOf<String>()

        @Volatile
        private var closed = false

        @Volatile
        var respondWithError: Boolean = false

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}/v1"

        private val acceptThread =
            Thread {
                acceptLoop()
            }.apply {
                isDaemon = true
                name = "fake-chat-llm-accept"
            }

        fun start() {
            acceptThread.start()
        }

        fun stop() {
            closed = true
            serverSocket.close()
        }

        fun lastRequestBody(): String? = synchronized(lock) { requestBodies.lastOrNull() }

        private fun acceptLoop() {
            while (!closed) {
                val socket =
                    try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        return
                    }
                Thread {
                    serve(socket)
                }.apply {
                    isDaemon = true
                    name = "fake-chat-llm-serve"
                }.start()
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection -> handleConnection(connection) }
            } catch (_: Exception) {
                // 客户端超时/提前断开：测试假端点静默即可，不影响用例断言
            }
        }

        /** 读请求头（含 Content-Length）→ 读请求体并记录 → 回一条固定 chat completion */
        private fun handleConnection(connection: Socket) {
            val input = connection.getInputStream()
            val headerText = readHeaders(input)
            val contentLength = CONTENT_LENGTH_REGEX.find(headerText)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            synchronized(lock) { requestBodies += body.toString(Charsets.UTF_8) }
            val payload =
                if (respondWithError) {
                    ByteArray(0)
                } else {
                    (
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\"," +
                            "\"content\":\"好的，我记得了。\"},\"finish_reason\":\"stop\"}]," +
                            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
                    ).toByteArray(Charsets.UTF_8)
                }
            val statusLine = if (respondWithError) "HTTP/1.1 500 Internal Error" else "HTTP/1.1 200 OK"
            val response =
                statusLine + "\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + payload.size + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            val output = connection.getOutputStream()
            output.write(response.toByteArray(Charsets.UTF_8))
            output.write(payload)
            output.flush()
        }

        /** 逐字节读到空行（\r\n\r\n）为止；请求头是 ASCII，逐字符拼接安全 */
        private fun readHeaders(input: java.io.InputStream): String {
            val headers = StringBuilder()
            while (headers.length < MAX_HEADER_BYTES && !headers.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) break
                headers.append(byte.toChar())
            }
            return headers.toString()
        }

        private companion object {
            const val BACKLOG = 50
            const val MAX_HEADER_BYTES = 16 * 1024
            val CONTENT_LENGTH_REGEX = Regex("(?i)content-length:\\s*(\\d+)")
        }
    }
}
