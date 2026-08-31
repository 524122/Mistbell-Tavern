package com.mistbell.tavern.android.data.repository.example

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.repository.retrieveForConversation
import com.mistbell.tavern.android.data.repository.storeAssistantMessageVector
import com.mistbell.tavern.android.data.repository.storeUserMessageVector
import com.mistbell.tavern.android.service.buildStructuredMemoryContext
import com.mistbell.tavern.android.service.buildVectorMemoryContext

/**
 * ChatRepository 集成示例
 *
 * 展示如何在 sendMessage 方法中集成向量存储和记忆检索
 */

/**
 * 示例：发送消息并集成向量记忆
 *
 * 在你的 ChatRepository.sendMessage() 方法中添加以下代码
 */
suspend fun sendMessageWithVectorMemory(
    ownerId: String,
    characterId: String,
    sessionId: String,
    message: String,
): Message {
    // ========== 步骤 1: 保存用户消息 ==========
    val userMessage =
        Message(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = message,
            thinking = null,
            createdAt = java.time.Instant.now().toString(),
            memoryIds = null,
            swipes = null,
            swipeIndex = 0,
        )

    // 保存到数据库
    // db.messageDao().upsert(MessageEntity.fromDomain(userMessage, sessionId, ownerId, characterId))

    // ========== 步骤 2: 向量化用户消息（异步） ==========
    storeUserMessageVector(
        content = message,
        ownerId = ownerId,
        characterId = characterId,
        sessionId = sessionId,
        messageId = userMessage.id,
    )

    // ========== 步骤 3: 检索记忆（用于生成 AI 回复） ==========
    // 3.1 检索结构化记忆（仅当前会话）
    val structuredMemoryRepo =
        com.mistbell.tavern.android.data.repository.StructuredMemoryRepository(
            TavernApplication.instance,
        )
    val structuredMemories =
        structuredMemoryRepo.retrieveForConversation(
            ownerId = ownerId,
            characterId = characterId,
            userMessage = message,
        )

    // 3.2 检索向量记忆（仅当前会话 - 限定 sessionId）
    val vectorMemoryService = TavernApplication.instance.vectorMemoryService
    val vectorResults =
        vectorMemoryService.searchRelevantMemories(
            query = message,
            ownerId = ownerId,
            characterId = characterId,
            sessionId = sessionId, // 限定当前会话，不跨会话检索
            topK = 5,
        )

    // 3.3 构建记忆上下文
    val structuredMemoryContext = buildStructuredMemoryContext(structuredMemories)
    val vectorMemoryContext = buildVectorMemoryContext(vectorResults)

    // ========== 步骤 4: 构建 Prompt 并调用 LLM ==========
    // 将记忆上下文注入到系统提示词中
    // val prompt = buildPromptWithMemories(
    //     character = character,
    //     userMessage = message,
    //     structuredMemoryContext = structuredMemoryContext,
    //     vectorMemoryContext = vectorMemoryContext
    // )

    // val aiReply = LlmClient.chat(llmConfig, prompt)

    val aiReply = "AI 回复示例" // 实际调用 LLM

    // ========== 步骤 5: 保存 AI 回复 ==========
    val assistantMessage =
        Message(
            id = java.util.UUID.randomUUID().toString(),
            role = "assistant",
            content = aiReply,
            thinking = null,
            createdAt = java.time.Instant.now().toString(),
            memoryIds = null,
            swipes = null,
            swipeIndex = 0,
        )

    // 保存到数据库
    // db.messageDao().upsert(MessageEntity.fromDomain(assistantMessage, sessionId, ownerId, characterId))

    // ========== 步骤 6: 向量化 AI 回复（异步） ==========
    storeAssistantMessageVector(
        content = aiReply,
        ownerId = ownerId,
        characterId = characterId,
        sessionId = sessionId,
        messageId = assistantMessage.id,
    )

    // ========== 步骤 7: 后台提取记忆（可选） ==========
    // 如果启用了自动记忆提取
    // MemoryExtractionService.extractAndSaveMemories(...)

    return assistantMessage
}

/**
 * 完整的集成伪代码
 *
 * 在你的 ChatRepository.kt 中修改 sendMessage 方法：
 */
/*
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

        // 2. 向量化用户消息（新增）
        storeUserMessageVector(message, ownerId, characterId, sessionId, msgId)

        // 3. Update session message count
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

        // 4. 检索记忆（新增）
        val structuredMemoryRepo = StructuredMemoryRepository(context)
        val structuredMemories = structuredMemoryRepo.retrieveForConversation(
            ownerId, characterId, message
        )
        val vectorMemoryService = TavernApplication.instance.vectorMemoryService
        val vectorResults = vectorMemoryService.searchRelevantMemories(
            message, ownerId, characterId, sessionId, 5  // 限定当前会话
        )

        // 5. Try to get AI response via LLM
        try {
            val llmConfig = loadLlmConfig()
            if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                // 构建 Prompt（记忆注入由 PromptBuilder 内部完成；
                // 此前示例使用了不存在的 structuredMemories/vectorResults 命名参数，已按当前签名修正）
                val promptMessages = PromptBuilder.buildPrompt(
                    db, ownerId, characterId, sessionId, message
                )

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

                // 6. 向量化 AI 回复（新增）
                storeAssistantMessageVector(
                    reply, ownerId, characterId, sessionId, assistantMsg.id
                )

                // 7. Update session
                db.sessionDao().upsert(
                    session!!.copy(
                        messageCount = session.messageCount + 1,
                        updatedAt = assistantMsg.createdAt
                    )
                )

                // 8. Background memory extraction（可选）
                backgroundScope.launch {
                    val memoryService = MemoryExtractionService(context)
                    memoryService.extractAndSaveMemories(
                        userMessage = message,
                        assistantMessage = reply,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        messageIds = listOf(msgId, assistantMsg.id),
                        provider = null
                    )
                }

                return@withContext assistantMsg
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "LLM call failed: ${e.message}", e)
        }

        userMsg
    }
}
*/
