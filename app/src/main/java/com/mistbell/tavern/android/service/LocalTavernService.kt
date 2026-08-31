package com.mistbell.tavern.android.service

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.service.models.LocalChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.*

/**
 * 本地 Tavern 主服务
 *
 * 移植自后端 TavernService.java
 * 负责完整的对话流程编排：
 * 1. 加载历史消息
 * 2. 激活世界书
 * 3. 检索记忆
 * 4. 构建提示词
 * 5. 调用 LLM
 * 6. 保存消息
 */
class LocalTavernService(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val promptService = LocalPromptService(context)
    private val worldBookService = LocalWorldBookService()
    private val memoryService = LocalMemoryService(context)
    private val providerService = LocalProviderService(context)

    companion object {
        private const val MAX_RECENT_MESSAGES = 20
        private const val WORLD_BOOK_SCAN_MESSAGES = 5
    }

    /**
     * 发送消息并获取 AI 回复（流式）
     *
     * @param ownerId 所有者 ID
     * @param sessionId 会话 ID
     * @param characterId 角色 ID
     * @param userMessage 用户消息
     * @return 流式返回 AI 回复
     */
    suspend fun chat(
        ownerId: String,
        sessionId: String,
        characterId: String,
        userMessage: String,
    ): Flow<ChatResult> =
        flow {
            try {
                // 1. 保存用户消息
                val userMessageEntity =
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        ownerId = ownerId,
                        characterId = characterId,
                        role = "user",
                        content = userMessage,
                        thinking = null,
                        createdAt = System.currentTimeMillis().toString(),
                        memoryIdsJson = "[]",
                        swipesJson = """["$userMessage"]""",
                        swipeIndex = 0,
                        thinkingSwipesJson = "[]",
                    )
                db.messageDao().upsert(userMessageEntity)

                // 2. 加载会话和角色
                val session =
                    db.sessionDao().get(sessionId, ownerId, characterId)
                        ?: throw IllegalStateException("Session not found: $sessionId")

                val character =
                    db.characterDao().getById(characterId)?.toDomain()
                        ?: throw IllegalStateException("Character not found: $characterId")

                // 3. 加载历史消息
                val recentMessages =
                    db.messageDao()
                        .getBySession(sessionId, ownerId, characterId)
                        .first()
                        .takeLast(MAX_RECENT_MESSAGES)
                        .map { it.toDomain() }

                // 4. 激活世界书
                val worldBookEntries =
                    if (character.worldBookId != null) {
                        db.worldBookDao().getEntriesList(character.worldBookId)
                    } else {
                        emptyList()
                    }

                val scanText =
                    worldBookService.buildScanText(
                        userMessage,
                        recentMessages.takeLast(WORLD_BOOK_SCAN_MESSAGES).map { it.content },
                    )
                val activatedEntries = worldBookService.activateEntries(worldBookEntries, scanText)

                // 5. 检索记忆
                val memories =
                    memoryService.searchMemories(
                        query = userMessage,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        limit = 10,
                    )

                // 6. 构建提示词
                val promptSections =
                    promptService.buildPromptSections(
                        character = character,
                        recentMessages = recentMessages,
                        memories = memories,
                        activatedEntries = activatedEntries,
                        sessionSummary = null,
                    )

                val chatMessages = promptService.buildChatMessages(promptSections)

                // 发送构建完成事件
                emit(ChatResult.PromptBuilt(promptSections, chatMessages))

                // 7. 调用 LLM（流式）
                val assistantMessageId = UUID.randomUUID().toString()
                val responseBuilder = StringBuilder()

                providerService.chatStream(chatMessages).collect { chunk ->
                    responseBuilder.append(chunk)
                    emit(ChatResult.StreamChunk(chunk))
                }

                val finalResponse = responseBuilder.toString()

                // 8. 保存 AI 回复
                val assistantMessage =
                    MessageEntity(
                        id = assistantMessageId,
                        sessionId = sessionId,
                        ownerId = ownerId,
                        characterId = characterId,
                        role = "assistant",
                        content = finalResponse,
                        thinking = null,
                        createdAt = System.currentTimeMillis().toString(),
                        memoryIdsJson = "[]",
                        swipesJson = """["$finalResponse"]""",
                        swipeIndex = 0,
                        thinkingSwipesJson = "[]",
                    )
                db.messageDao().upsert(assistantMessage)

                // 更新会话（通过重新获取并更新）
                val updatedSession =
                    session.copy(
                        updatedAt = System.currentTimeMillis().toString(),
                    )
                db.sessionDao().upsert(updatedSession)

                // 发送完成事件
                emit(ChatResult.Complete(assistantMessage.toDomain()))
            } catch (e: Exception) {
                emit(ChatResult.Error(e.message ?: "Unknown error"))
            }
        }

    /**
     * 撤销最后一条消息
     */
    suspend fun undoLastMessage(
        sessionId: String,
        ownerId: String,
        characterId: String,
    ): Boolean {
        return try {
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            if (messages.isEmpty()) return false

            val lastMessage = messages.last()
            db.messageDao().deleteBySession(sessionId, ownerId, characterId)

            // 重新插入除最后一条外的所有消息
            if (messages.size > 1) {
                db.messageDao().upsertAll(messages.dropLast(1))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 回溯到指定消息
     */
    suspend fun backtrackToMessage(
        sessionId: String,
        ownerId: String,
        characterId: String,
        messageId: String,
    ): Boolean {
        return try {
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val targetIndex = messages.indexOfFirst { it.id == messageId }

            if (targetIndex == -1) return false

            // 删除所有消息
            db.messageDao().deleteBySession(sessionId, ownerId, characterId)

            // 重新插入到目标消息为止
            val messagesToKeep = messages.take(targetIndex + 1)
            db.messageDao().upsertAll(messagesToKeep)

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 重新生成最后一条 AI 回复
     */
    suspend fun regenerateLastMessage(
        ownerId: String,
        sessionId: String,
        characterId: String,
    ): Flow<ChatResult> =
        flow {
            try {
                val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
                if (messages.size < 2) {
                    emit(ChatResult.Error("No messages to regenerate"))
                    return@flow
                }

                // 删除最后一条 AI 消息
                val lastMessage = messages.last()
                if (lastMessage.role != "assistant") {
                    emit(ChatResult.Error("Last message is not from assistant"))
                    return@flow
                }

                // 删除并重新插入（删除最后一条）
                db.messageDao().deleteBySession(sessionId, ownerId, characterId)
                db.messageDao().upsertAll(messages.dropLast(1))

                // 获取用户消息
                val userMessage = messages[messages.size - 2]

                // 重新生成
                chat(ownerId, sessionId, characterId, userMessage.content).collect { result ->
                    emit(result)
                }
            } catch (e: Exception) {
                emit(ChatResult.Error(e.message ?: "Unknown error"))
            }
        }

    /**
     * 继续生成（追加到最后一条消息）
     */
    suspend fun continueGeneration(
        ownerId: String,
        sessionId: String,
        characterId: String,
    ): Flow<ChatResult> =
        flow {
            try {
                val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
                if (messages.isEmpty()) {
                    emit(ChatResult.Error("No messages to continue"))
                    return@flow
                }

                val lastMessage = messages.last()
                if (lastMessage.role != "assistant") {
                    emit(ChatResult.Error("Last message is not from assistant"))
                    return@flow
                }

                // 构建继续提示
                val continuePrompt = "[继续上文]"

                // 临时添加继续提示
                val tempUserMessage =
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        ownerId = ownerId,
                        characterId = characterId,
                        role = "user",
                        content = continuePrompt,
                        thinking = null,
                        createdAt = System.currentTimeMillis().toString(),
                        memoryIdsJson = "[]",
                        swipesJson = """["$continuePrompt"]""",
                        swipeIndex = 0,
                        thinkingSwipesJson = "[]",
                    )
                db.messageDao().upsert(tempUserMessage)

                // 生成续写
                val responseBuilder = StringBuilder(lastMessage.content)

                chat(ownerId, sessionId, characterId, continuePrompt).collect { result ->
                    when (result) {
                        is ChatResult.StreamChunk -> {
                            responseBuilder.append(result.chunk)
                            emit(result)
                        }
                        is ChatResult.Complete -> {
                            // 删除临时用户消息
                            db.messageDao().deleteBySession(sessionId, ownerId, characterId)
                            db.messageDao().upsertAll(messages) // 恢复原消息

                            // 更新原消息
                            val json = kotlinx.serialization.json.Json
                            val stringListSerializer =
                                kotlinx.serialization.builtins.ListSerializer(
                                    kotlinx.serialization.serializer<String>(),
                                )
                            val currentSwipes =
                                try {
                                    json.decodeFromString(stringListSerializer, lastMessage.swipesJson)
                                } catch (e: Exception) {
                                    listOf(lastMessage.content)
                                }

                            val updatedSwipes = currentSwipes.toMutableList()
                            if (updatedSwipes.size > lastMessage.swipeIndex) {
                                updatedSwipes[lastMessage.swipeIndex] = responseBuilder.toString()
                            } else {
                                updatedSwipes.add(responseBuilder.toString())
                            }

                            val updatedMessage =
                                lastMessage.copy(
                                    content = responseBuilder.toString(),
                                    swipesJson = json.encodeToString(stringListSerializer, updatedSwipes),
                                )
                            db.messageDao().upsert(updatedMessage)

                            emit(ChatResult.Complete(updatedMessage.toDomain()))
                        }
                        else -> emit(result)
                    }
                }
            } catch (e: Exception) {
                emit(ChatResult.Error(e.message ?: "Unknown error"))
            }
        }

    /**
     * Swipe 切换
     */
    suspend fun swipeMessage(
        sessionId: String,
        ownerId: String,
        messageId: String,
        direction: SwipeDirection,
    ): Message? {
        return try {
            val message = db.messageDao().getById(messageId, sessionId) ?: return null

            // 解析 swipes JSON
            val json = kotlinx.serialization.json.Json
            val stringListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            val swipes =
                try {
                    json.decodeFromString(stringListSerializer, message.swipesJson)
                } catch (e: Exception) {
                    listOf(message.content)
                }

            val newIndex =
                when (direction) {
                    SwipeDirection.LEFT -> (message.swipeIndex - 1).coerceAtLeast(0)
                    SwipeDirection.RIGHT -> (message.swipeIndex + 1).coerceAtMost(swipes.size - 1)
                }

            if (newIndex == message.swipeIndex) return null

            val updatedMessage =
                message.copy(
                    swipeIndex = newIndex,
                    content = swipes[newIndex],
                )

            db.messageDao().upsert(updatedMessage)
            updatedMessage.toDomain()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清空会话消息
     */
    suspend fun clearSession(
        sessionId: String,
        ownerId: String,
        characterId: String,
    ): Boolean {
        return try {
            db.messageDao().deleteBySession(sessionId, ownerId, characterId)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 聊天结果
 */
sealed class ChatResult {
    data class PromptBuilt(
        val sections: com.mistbell.tavern.android.service.models.PromptSections,
        val messages: List<LocalChatMessage>,
    ) : ChatResult()

    data class StreamChunk(val chunk: String) : ChatResult()

    data class Complete(val message: Message) : ChatResult()

    data class Error(val error: String) : ChatResult()
}

/**
 * Swipe 方向
 */
enum class SwipeDirection {
    LEFT,
    RIGHT,
}
