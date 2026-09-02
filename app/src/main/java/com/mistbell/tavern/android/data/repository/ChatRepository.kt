package com.mistbell.tavern.android.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.local.entity.*
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.service.MemoryExtractionService
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class ChatRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)
    private val providerRepo = ProviderRepository(context)
    private val settingsRepo = SettingsRepository(context)
    private val structuredMemoryRepo = StructuredMemoryRepository(context)
    private val memoryExtractionService = MemoryExtractionService(context, structuredMemoryRepo)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 消息窗口默认大小（v16 性能修复）：首屏只观察最新 200 条，更旧消息由 loadOlderMessages 按页补加载
        const val DEFAULT_MESSAGE_WINDOW = 200
    }

    // --- Local-first reads ---

    // 域映射与 distinctUntilChanged 的按值比较都可能有开销，统一切到 Default 线程，
    // 避免阻塞 Room 回调线程或收集方（主线程）
    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeSessions(
        ownerId: String,
        characterId: String,
    ): Flow<List<SessionSummary>> {
        return db.sessionDao().getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeRecentSessions(ownerId: String): Flow<List<SessionSummary>> {
        return db.sessionDao().getRecent(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 消息窗口观察：只取最新 limit 条（DESC 查询反转回 ASC 展示），长会话不再全表加载。
    // Message 是 data class，distinctUntilChanged 按值去重，过滤 Room 无效化导致的多余重发。
    fun observeMessages(
        ownerId: String,
        characterId: String,
        sessionId: String,
        limit: Int = DEFAULT_MESSAGE_WINDOW,
    ): Flow<List<Message>> {
        return db.messageDao().getLatestBySession(sessionId, ownerId, characterId, limit).map { entities ->
            entities.map { it.toDomain() }.asReversed()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 上滚加载更旧一页：一次性挂起查询（非流）。修复3：游标为复合游标（窗口最旧一条的
    // created_at + id），与 DAO 的 (created_at DESC, id DESC) 排序构成全序，
    // 同 created_at 的并列消息不会被 LIMIT 切开永久丢失。
    suspend fun loadOlderMessages(
        sessionId: String,
        ownerId: String,
        characterId: String,
        beforeCreatedAt: String,
        beforeId: String,
        limit: Int,
    ): List<Message> {
        return withContext(Dispatchers.IO) {
            db.messageDao()
                .getOlderBySession(sessionId, ownerId, characterId, beforeCreatedAt, beforeId, limit)
                .map { it.toDomain() }
                .asReversed()
        }
    }

    suspend fun getActiveSessionId(
        ownerId: String,
        characterId: String,
    ): String {
        val sessions = db.sessionDao().getByCharacter(ownerId, characterId).first()
        return sessions.firstOrNull()?.id ?: ""
    }

    // --- Write operations (local-first + optional sync) ---

    suspend fun sendMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        message: String,
        worldBookId: String = "",
        onPartial: ((String) -> Unit)? = null,
    ): Message {
        return withContext(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val userMsg =
                Message(
                    id = msgId,
                    role = "user",
                    content = message,
                    thinking = null,
                    createdAt = java.time.Instant.now().toString(),
                    memoryIds = null,
                    swipes = null,
                    swipeIndex = 0,
                )

            // 1. Save user message locally
            db.messageDao().upsert(
                MessageEntity.fromDomain(userMsg, sessionId, ownerId, characterId),
            )

            // 1.5. 向量化用户消息（异步，不阻塞主流程）
            storeUserMessageVector(
                content = message,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = msgId,
            )

            // 2. Update session message count
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            if (session != null) {
                db.sessionDao().upsert(
                    session.copy(
                        messageCount = session.messageCount + 1,
                        updatedAt = userMsg.createdAt,
                        title =
                            if (session.title.isBlank() && session.messageCount == 0) {
                                message.take(26)
                            } else {
                                session.title
                            },
                    ),
                )
            }

            // 3. Try to get AI response via LLM
            var insertedAssistantId: String? = null
            // 流式累计缓冲，作用域覆盖整个 try，取消时据此判断是否已有部分回复
            val sb = StringBuilder()
            try {
                val llmConfig = loadLlmConfig()
                if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                    val promptMessages = PromptBuilder.buildPrompt(db, ownerId, characterId, sessionId, message, currentMessageId = msgId)
                    if (settingsRepo.isStreamingEnabled()) {
                        // 流式开：SSE 真流式，逐增量收集累计全文，onPartial 每次回调累计全文供 UI 渲染
                        LlmClient.chatStream(llmConfig, promptMessages).collect { delta ->
                            sb.append(delta)
                            onPartial?.invoke(sb.toString())
                        }
                    } else {
                        // 流式关：整包返回，不调 onPartial
                        sb.append(LlmClient.chat(llmConfig, promptMessages))
                    }
                    // F2.1 回复清洗：提取全部 <think>…</think> 块为思考内容，正文不含 think（流式/非流式统一走 sb，此处统一处理）
                    val (replyContent, replyThinking) = splitThinking(sb.toString())
                    val assistantMsg =
                        Message(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = replyContent,
                            thinking = replyThinking,
                            createdAt = java.time.Instant.now().toString(),
                            memoryIds = null,
                            swipes = null,
                            swipeIndex = 0,
                        )
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(assistantMsg, sessionId, ownerId, characterId),
                    )
                    insertedAssistantId = assistantMsg.id

                    // 3.5. 向量化 AI 回复（异步，不阻塞主流程）
                    storeAssistantMessageVector(
                        content = replyContent,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        messageId = assistantMsg.id,
                    )

                    // 更新会话消息计数
                    val updatedSession = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (updatedSession != null) {
                        val sessionAfterReply =
                            updatedSession.copy(
                                messageCount = updatedSession.messageCount + 1,
                                updatedAt = assistantMsg.createdAt,
                            )
                        db.sessionDao().upsert(sessionAfterReply)

                        // 每一轮对话都提取记忆（开场白已经在创建会话时插入，不参与这里的逻辑）
                        extractMemoriesInBackground(
                            enabled = sessionAfterReply.enableLongTermMemory,
                            providerId = sessionAfterReply.providerId,
                            userMessage = message,
                            assistantMessage = replyContent,
                            ownerId = ownerId,
                            characterId = characterId,
                            sessionId = sessionId,
                            messageIds = listOf(msgId, assistantMsg.id),
                        )
                    }

                    return@withContext assistantMsg
                } else {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
                }
            } catch (e: CancellationException) {
                // 用户主动停止生成：不同于网络失败，不回滚用户消息。
                // 已收到部分回复则按成功路径格式落库部分回复并回写计数，然后向上抛出取消。
                if (sb.isNotEmpty()) {
                    val partialMsg =
                        Message(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = sb.toString(),
                            thinking = null,
                            createdAt = java.time.Instant.now().toString(),
                            memoryIds = null,
                            swipes = null,
                            swipeIndex = 0,
                        )
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(partialMsg, sessionId, ownerId, characterId),
                    )
                    val sessionAfterPartial = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (sessionAfterPartial != null) {
                        db.sessionDao().upsert(
                            sessionAfterPartial.copy(
                                messageCount = sessionAfterPartial.messageCount + 1,
                                updatedAt = partialMsg.createdAt,
                            ),
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                // LLM 调用或收尾失败：事务内回滚本条消息（用户消息，若已插入还包括回复），
                // 计数按真实行数重算（自愈，不依赖增量加减），再抛出异常让 UI 显示错误。
                // 已知残留：已异步写入的向量无法按消息清理（无对应接口，见 ROADMAP 向量双写一致性）
                db.withTransaction {
                    db.messageDao().deleteById(msgId)
                    insertedAssistantId?.let { db.messageDao().deleteById(it) }
                    val sessionForRollback = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (sessionForRollback != null) {
                        db.sessionDao().upsert(
                            sessionForRollback.copy(
                                messageCount = db.messageDao().getBySession(sessionId, ownerId, characterId).first().size,
                                title = if (sessionForRollback.title == message.take(26)) "" else sessionForRollback.title,
                            ),
                        )
                    }
                }
                throw e
            }
        }
    }

    suspend fun undoLastMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 事务保证删除与计数回写原子完成，避免中途失败导致计数漂移
            db.withTransaction {
                val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
                if (messages.isNotEmpty()) {
                    db.messageDao().deleteById(messages.last().id)
                    val session = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (session != null) {
                        db.sessionDao().upsert(session.copy(messageCount = messages.size - 1))
                    }
                }
            }
        }
    }

    suspend fun backtrackToMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
    ) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                db.messageDao().deleteAfter(sessionId, messageId, ownerId, characterId)
            }
        }
    }

    suspend fun regenerateMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val msg = db.messageDao().getById(messageId, sessionId) ?: return@withContext
            if (msg.role != "assistant") return@withContext

            // 先取上下文与配置，任何删除都在拿到新回复成功之后，避免旧消息丢失而新回复没来
            val userMessages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val lastUserMsg =
                userMessages.lastOrNull { it.role == "user" }
                    ?: throw IllegalStateException("没有可重新生成的用户消息")
            val llmConfig = loadLlmConfig()
            if (llmConfig.baseUrl.isBlank() || llmConfig.apiKey.isBlank()) {
                throw IllegalStateException("LLM 未配置：请在设置中配置 API 密钥")
            }

            // excludeFromMessageId：截断目标消息及其之后的历史，
            // 保证正要被替换的旧回复不进入上下文（否则模型会复述旧答案）
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    lastUserMsg.content,
                    currentMessageId = lastUserMsg.id,
                    excludeFromMessageId = messageId,
                )
            // SSE 真流式：逐增量收集累计全文，onPartial 每次回调累计全文供 UI 渲染
            val sb = StringBuilder()
            try {
                if (settingsRepo.isStreamingEnabled()) {
                    // 流式开：SSE 真流式，逐增量收集累计全文，onPartial 每次回调累计全文供 UI 渲染
                    LlmClient.chatStream(llmConfig, prompt).collect { delta ->
                        sb.append(delta)
                        onPartial?.invoke(sb.toString())
                    }
                } else {
                    // 流式关：整包返回，不调 onPartial
                    sb.append(LlmClient.chat(llmConfig, prompt))
                }
            } catch (e: CancellationException) {
                // 用户主动停止重新生成：不删旧消息、不触发失败回滚。
                // 已收到部分回复则按成功路径的替换事务落库部分回复；空则直接上抛取消。
                if (sb.isNotEmpty()) {
                    val partialMsg =
                        Message(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = sb.toString(),
                            thinking = null,
                            createdAt = java.time.Instant.now().toString(),
                            memoryIds = null,
                            swipes = null,
                            swipeIndex = 0,
                        )
                    db.withTransaction {
                        db.messageDao().deleteAfter(sessionId, messageId, ownerId, characterId)
                        db.messageDao().deleteById(messageId)
                        db.messageDao().upsert(
                            MessageEntity.fromDomain(partialMsg, sessionId, ownerId, characterId),
                        )
                        val session = db.sessionDao().get(sessionId, ownerId, characterId)
                        if (session != null) {
                            db.sessionDao().upsert(
                                session.copy(messageCount = db.messageDao().getBySession(sessionId, ownerId, characterId).first().size),
                            )
                        }
                    }
                }
                throw e
            }
            // F2.1 回复清洗：重新生成路径同样提取 think 块
            val (replyContent, replyThinking) = splitThinking(sb.toString())

            val assistantMsg =
                Message(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = replyContent,
                    thinking = replyThinking,
                    createdAt = java.time.Instant.now().toString(),
                    memoryIds = null,
                    swipes = null,
                    swipeIndex = 0,
                )

            // 成功拿到新回复后，在事务内原子完成替换：先删目标之后的消息与目标本身，再插入新回复并回写计数
            db.withTransaction {
                db.messageDao().deleteAfter(sessionId, messageId, ownerId, characterId)
                db.messageDao().deleteById(messageId)
                db.messageDao().upsert(
                    MessageEntity.fromDomain(assistantMsg, sessionId, ownerId, characterId),
                )
                val session = db.sessionDao().get(sessionId, ownerId, characterId)
                if (session != null) {
                    db.sessionDao().upsert(
                        session.copy(messageCount = db.messageDao().getBySession(sessionId, ownerId, characterId).first().size),
                    )
                }
            }
            // 向量化新回复（异步，不阻塞主流程）。
            // 已知残留：被替换的旧消息向量没有按消息删除的接口，暂无法清理（见 ROADMAP 向量双写一致性问题）
            storeAssistantMessageVector(
                content = replyContent,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = assistantMsg.id,
            )
        }
    }

    suspend fun continueMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // Continue message 功能需要 LLM 实现
            // TODO: 实现本地 LLM 的 continue 功能
        }
    }

    suspend fun swipeMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        direction: String,
    ) {
        withContext(Dispatchers.IO) {
            // Swipe message 功能需要本地实现
            // TODO: 实现本地的 swipe 功能
        }
    }

    suspend fun clearConversation(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            db.messageDao().deleteBySession(sessionId, ownerId, characterId)
        }
    }

    suspend fun createSession(
        ownerId: String,
        characterId: String,
        title: String = "",
        providerId: String = "",
        enableLongTermMemory: Boolean = false,
        worldBookId: String = "",
    ): String {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // 如果没有指定 providerId，尝试获取默认的 provider
            val actualProviderId =
                if (providerId.isBlank()) {
                    providerRepo.observeProviders().first().firstOrNull()?.id ?: ""
                } else {
                    providerId
                }

            val session =
                SessionEntity(
                    id = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    messageCount = 0,
                    providerId = actualProviderId,
                    modelId = "",
                    worldBookId = worldBookId,
                    summaryJson = "",
                    enableLongTermMemory = enableLongTermMemory,
                    participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(listOf(characterId)),
                )
            db.sessionDao().upsert(session)
            sessionId
        }
    }

    suspend fun deleteSession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 删除该会话的所有记忆
            db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
            structuredMemoryRepo.deleteMemoriesBySession(ownerId, sessionId)
            db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
            android.util.Log.d("ChatRepository", "Deleted memories for session: $sessionId")

            // 删除消息和会话
            db.messageDao().deleteBySession(sessionId, ownerId, characterId)
            db.sessionDao().delete(sessionId, ownerId, characterId)
        }
    }

    // --- Helpers ---

    // F2.1 回复清洗：提取全部 <think>…</think> 块，多段以空行合并为思考内容；
    // 正文为去除全部 think 块后 trim 的结果。无有效思考时 thinking 为 null。
    private fun splitThinking(reply: String): Pair<String, String?> {
        val thinking =
            Regex("(?s)<think>([\\s\\S]*?)</think>").findAll(reply)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        val content = reply.replace(Regex("(?s)<think>[\\s\\S]*?</think>"), "").trim()
        return content to thinking.ifBlank { null }
    }

    private suspend fun loadLlmConfig(): LlmConfig {
        val settingsDao = db.settingsDao()
        return LlmConfig(
            baseUrl = settingsDao.getValue("llm_base_url") ?: "",
            apiKey = SecureStore.unwrap(settingsDao.getValue("llm_api_key") ?: ""),
            model = settingsDao.getValue("llm_model") ?: "",
            temperature = settingsDao.getValue("temperature")?.toDoubleOrNull() ?: 0.8,
            maxTokens = settingsDao.getValue("max_tokens")?.toIntOrNull() ?: 1024,
        )
    }

    private fun extractMemoriesInBackground(
        enabled: Boolean,
        providerId: String,
        userMessage: String,
        assistantMessage: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ) {
        if (!enabled) {
            android.util.Log.d("ChatRepository", "Long-term memory disabled for this session")
            return
        }

        backgroundScope.launch {
            try {
                android.util.Log.d("ChatRepository", "Long-term memory enabled, extracting memories...")

                val providers = providerRepo.observeProviders().first()
                android.util.Log.d("ChatRepository", "Available providers: ${providers.map { "${it.id}:${it.name}" }}")
                android.util.Log.d("ChatRepository", "Looking for providerId: '$providerId'")

                var provider = providers.find { it.id == providerId }

                // 如果找不到，尝试使用第一个可用的 provider
                if (provider == null) {
                    android.util.Log.w("ChatRepository", "Provider '$providerId' not found")
                    provider = providers.firstOrNull()

                    if (provider != null) {
                        android.util.Log.i("ChatRepository", "Falling back to first available provider: ${provider.name}")
                    } else {
                        val llmConfig = loadLlmConfig()
                        provider =
                            if (
                                llmConfig.baseUrl.isNotBlank() &&
                                llmConfig.apiKey.isNotBlank() &&
                                llmConfig.model.isNotBlank()
                            ) {
                                android.util.Log.i("ChatRepository", "Using active LLM config for memory extraction")
                                ProviderConfig(
                                    id = "active_llm_config",
                                    name = "Active LLM Config",
                                    endpoint = llmConfig.baseUrl,
                                    apiKey = llmConfig.apiKey,
                                    selectedModel = llmConfig.model,
                                    memoryModel = llmConfig.model,
                                )
                            } else {
                                android.util.Log.w("ChatRepository", "No LLM providers configured, memory extraction disabled")
                                null
                            }
                    }
                }

                val savedCount =
                    memoryExtractionService.extractAndSaveMemories(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        messageIds = messageIds,
                        provider = provider,
                    )
                android.util.Log.d("ChatRepository", "Memory extraction completed, saved $savedCount memories")
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Memory extraction failed: ${e.message}", e)
            }
        }
    }
}
