package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.local.entity.*
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.service.MemoryExtractionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class ChatRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)
    private val providerRepo = ProviderRepository(context)
    private val structuredMemoryRepo = StructuredMemoryRepository(context)
    private val memoryExtractionService = MemoryExtractionService(context, structuredMemoryRepo)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Local-first reads ---

    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeSessions(ownerId: String, characterId: String): Flow<List<SessionSummary>> {
        return db.sessionDao().getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeRecentSessions(ownerId: String): Flow<List<SessionSummary>> {
        return db.sessionDao().getRecent(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeMessages(ownerId: String, characterId: String, sessionId: String): Flow<List<Message>> {
        return db.messageDao().getBySession(sessionId, ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeMemories(ownerId: String, characterId: String): Flow<List<Memory>> {
        return db.memoryDao().getAll(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getActiveSessionId(ownerId: String, characterId: String): String {
        val sessions = db.sessionDao().getByCharacter(ownerId, characterId).first()
        return sessions.firstOrNull()?.id ?: ""
    }

    // --- Write operations (local-first + optional sync) ---

    suspend fun sendMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        message: String,
        worldBookId: String = ""
    ): Message {
        return withContext(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val userMsg = Message(
                id = msgId,
                role = "user",
                content = message,
                thinking = null,
                createdAt = java.time.Instant.now().toString(),
                memoryIds = null,
                swipes = null,
                swipeIndex = 0
            )

            // 1. Save user message locally
            db.messageDao().upsert(
                MessageEntity.fromDomain(userMsg, sessionId, ownerId, characterId)
            )

            // 1.5. 向量化用户消息（异步，不阻塞主流程）
            storeUserMessageVector(
                content = message,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = msgId
            )

            // 2. Update session message count
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            if (session != null) {
                db.sessionDao().upsert(
                    session.copy(
                        messageCount = session.messageCount + 1,
                        updatedAt = userMsg.createdAt,
                        title = if (session.title.isBlank() && session.messageCount == 0) {
                            message.take(26)
                        } else session.title
                    )
                )
            }

            // 3. Try to get AI response via LLM
            try {
                val llmConfig = loadLlmConfig()
                if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                    val promptMessages = PromptBuilder.buildPrompt(db, ownerId, characterId, sessionId, message)
                    val reply = LlmClient.chat(llmConfig, promptMessages)
                    val assistantMsg = Message(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        content = reply,
                        thinking = null,
                        createdAt = java.time.Instant.now().toString(),
                        memoryIds = null,
                        swipes = null,
                        swipeIndex = 0
                    )
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(assistantMsg, sessionId, ownerId, characterId)
                    )

                    // 3.5. 向量化 AI 回复（异步，不阻塞主流程）
                    storeAssistantMessageVector(
                        content = reply,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        messageId = assistantMsg.id
                    )

                    // 更新会话消息计数
                    val updatedSession = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (updatedSession != null) {
                        val sessionAfterReply = updatedSession.copy(
                            messageCount = updatedSession.messageCount + 1,
                            updatedAt = assistantMsg.createdAt
                        )
                        db.sessionDao().upsert(sessionAfterReply)

                        // 每一轮对话都提取记忆（开场白已经在创建会话时插入，不参与这里的逻辑）
                        extractMemoriesInBackground(
                            enabled = sessionAfterReply.enableLongTermMemory,
                            providerId = sessionAfterReply.providerId,
                            userMessage = message,
                            assistantMessage = reply,
                            ownerId = ownerId,
                            characterId = characterId,
                            sessionId = sessionId,
                            messageIds = listOf(msgId, assistantMsg.id)
                        )
                    }

                    return@withContext assistantMsg
                } else {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
                }
            } catch (e: Exception) {
                // LLM 调用失败，抛出异常让 UI 显示错误
                throw e
            }
        }
    }

    suspend fun undoLastMessage(ownerId: String, characterId: String, sessionId: String) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            if (messages.isNotEmpty()) {
                db.messageDao().deleteBySession(sessionId, ownerId, characterId)
                if (messages.size > 1) {
                    db.messageDao().upsertAll(messages.dropLast(1))
                }
            }
        }
    }

    suspend fun backtrackToMessage(ownerId: String, characterId: String, sessionId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                db.messageDao().deleteAfter(sessionId, messageId, ownerId, characterId)
            }
        }
    }

    suspend fun regenerateMessage(ownerId: String, characterId: String, sessionId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val msg = db.messageDao().getById(messageId, sessionId) ?: return@withContext
            if (msg.role == "assistant") {
                // Delete the old assistant message
                db.messageDao().deleteAfter(sessionId, messageId, ownerId, characterId)
            }
            // Try to regenerate via LLM
            val userMessages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val lastUserMsg = userMessages.lastOrNull { it.role == "user" }
            if (lastUserMsg != null) {
                try {
                    val llmConfig = loadLlmConfig()
                    if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                        val prompt = PromptBuilder.buildPrompt(db, ownerId, characterId, sessionId, lastUserMsg.content)
                        val reply = LlmClient.chat(llmConfig, prompt)
                        val assistantMsg = Message(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = reply,
                            thinking = null,
                            createdAt = java.time.Instant.now().toString(),
                            memoryIds = null,
                            swipes = null,
                            swipeIndex = 0
                        )
                        db.messageDao().upsert(
                            MessageEntity.fromDomain(assistantMsg, sessionId, ownerId, characterId)
                        )
                        return@withContext
                    }
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun continueMessage(ownerId: String, characterId: String, sessionId: String) {
        withContext(Dispatchers.IO) {
            // Continue message 功能需要 LLM 实现
            // TODO: 实现本地 LLM 的 continue 功能
        }
    }

    suspend fun swipeMessage(ownerId: String, characterId: String, sessionId: String, messageId: String, direction: String) {
        withContext(Dispatchers.IO) {
            // Swipe message 功能需要本地实现
            // TODO: 实现本地的 swipe 功能
        }
    }

    suspend fun clearConversation(ownerId: String, characterId: String, sessionId: String) {
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
        worldBookId: String = ""
    ): String {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // 如果没有指定 providerId，尝试获取默认的 provider
            val actualProviderId = if (providerId.isBlank()) {
                providerRepo.observeProviders().first().firstOrNull()?.id ?: ""
            } else {
                providerId
            }

            val session = SessionEntity(
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
                participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(listOf(characterId))
            )
            db.sessionDao().upsert(session)
            sessionId
        }
    }

    suspend fun deleteSession(ownerId: String, characterId: String, sessionId: String) {
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

    private suspend fun loadLlmConfig(): LlmConfig {
        val settingsDao = db.settingsDao()
        return LlmConfig(
            baseUrl = settingsDao.getValue("llm_base_url") ?: "",
            apiKey = settingsDao.getValue("llm_api_key") ?: "",
            model = settingsDao.getValue("llm_model") ?: "",
            temperature = settingsDao.getValue("temperature")?.toDoubleOrNull() ?: 0.8,
            maxTokens = settingsDao.getValue("max_tokens")?.toIntOrNull() ?: 1024
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
        messageIds: List<String>
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
                        provider = if (
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
                                memoryModel = llmConfig.model
                            )
                        } else {
                            android.util.Log.w("ChatRepository", "No LLM providers configured, memory extraction disabled")
                            null
                        }
                    }
                }

                val savedCount = memoryExtractionService.extractAndSaveMemories(
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = sessionId,
                    messageIds = messageIds,
                    provider = provider
                )
                android.util.Log.d("ChatRepository", "Memory extraction completed, saved $savedCount memories")
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Memory extraction failed: ${e.message}", e)
            }
        }
    }
}
