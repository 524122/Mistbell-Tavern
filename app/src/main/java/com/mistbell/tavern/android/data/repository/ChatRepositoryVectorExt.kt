package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.VectorMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ChatRepository 扩展 - 向量存储功能
 *
 * 提供消息向量化和语义检索功能
 */

private const val TAG = "ChatRepository"
private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * 存储用户消息到向量数据库
 */
fun storeUserMessageVector(
    content: String,
    ownerId: String,
    characterId: String,
    sessionId: String,
    messageId: String
) {
    // 异步存储，不阻塞主流程
    backgroundScope.launch {
        try {
            val vectorMemoryService = TavernApplication.instance.vectorMemoryService

            vectorMemoryService.storeMessage(
                content = content,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = messageId,
                contentType = "user_message"  // VectorMemory.ContentType.USER_MESSAGE
            )

            Log.d(TAG, "Stored user message vector: $messageId")
        } catch (e: Exception) {
            // 向量存储失败不应影响主流程
            Log.e(TAG, "Failed to store user message vector: ${e.message}", e)
        }
    }
}

/**
 * 存储 AI 回复到向量数据库
 */
fun storeAssistantMessageVector(
    content: String,
    ownerId: String,
    characterId: String,
    sessionId: String,
    messageId: String
) {
    // 异步存储，不阻塞主流程
    backgroundScope.launch {
        try {
            val vectorMemoryService = TavernApplication.instance.vectorMemoryService

            vectorMemoryService.storeMessage(
                content = content,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = messageId,
                contentType = "ai_message"  // VectorMemory.ContentType.AI_MESSAGE
            )

            Log.d(TAG, "Stored assistant message vector: $messageId")
        } catch (e: Exception) {
            // 向量存储失败不应影响主流程
            Log.e(TAG, "Failed to store assistant message vector: ${e.message}", e)
        }
    }
}
