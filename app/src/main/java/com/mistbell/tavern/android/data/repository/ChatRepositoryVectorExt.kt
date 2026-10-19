package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.vector.deleteByMessageId
import com.mistbell.tavern.android.data.vector.deleteSessionVectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ChatRepository 扩展 - 向量存储功能
 *
 * 提供消息向量化和语义检索功能
 */

private const val TAG = "ChatRepository"

/** 触发用户消息的持久化向量恢复；正文与角色始终以 Room 当前版本为准。 */
suspend fun storeUserMessageVector(
    @Suppress("UnusedParameter") content: String,
    ownerId: String,
    @Suppress("UnusedParameter") characterId: String,
    sessionId: String,
    messageId: String,
) = withContext(Dispatchers.IO) {
    try {
        TavernApplication.instance.container.vectorSyncRecovery.recoverSource(
            sourceType = VectorSyncOutboxEntity.SOURCE_MESSAGE,
            ownerId = ownerId,
            sessionId = sessionId,
            sourceId = messageId,
        )
        Log.d(TAG, "Scheduled user message vector recovery: $messageId")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to recover user message vector: ${e.message}", e)
    }
}

/**
 * 触发 AI 回复的持久化向量恢复。恢复器统一使用替换语义并核验 Room 当前版本；
 * content/characterId/replacePrevious 仅为兼容既有调用签名保留。
 */
@Suppress("LongParameterList")
suspend fun storeAssistantMessageVector(
    @Suppress("UnusedParameter") content: String,
    ownerId: String,
    @Suppress("UnusedParameter") characterId: String,
    sessionId: String,
    messageId: String,
    @Suppress("UnusedParameter") replacePrevious: Boolean = false,
) = withContext(Dispatchers.IO) {
    try {
        TavernApplication.instance.container.vectorSyncRecovery.recoverSource(
            sourceType = VectorSyncOutboxEntity.SOURCE_MESSAGE,
            ownerId = ownerId,
            sessionId = sessionId,
            sourceId = messageId,
        )
        Log.d(TAG, "Scheduled assistant message vector recovery: $messageId")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to recover assistant message vector: ${e.message}", e)
    }
}

/**
 * 删除某条消息的向量，并登记删除意图。
 *
 * 登记意图是关键：这条消息可能正有一次慢速 embedding 在途，删除时它还没落地；
 * 不登记的话，embedding 完成后的写入会把刚删掉的对话又放回索引（跨会话召回还会把它捞回来）。
 * 需要"消息还在、只是内容变了"的替换型写入，见 [storeAssistantMessageVector] 的 `replacePrevious`。
 */
suspend fun deleteMessageVectors(messageId: String) =
    withContext(Dispatchers.IO) {
        TavernApplication.instance.container.vectorMemoryService.deleteByMessageId(messageId)
    }

/**
 * 删除会话的全部向量（owner + session 维度）。
 *
 * 不再按 characterId 过滤：群聊会话的向量分属多个 NPC，按会话主角色过滤会漏删 NPC 向量。
 *
 * **不再以 available 为前置条件**：索引里可能有 embedding 服务可用时写下的向量（例如用户后来
 * 清掉了 API Key），此时"没有 embedding 服务"不等于"没有向量要删"——早退会让会话删了而向量留着，
 * 下次恢复语义召回时把已删对话捞回来。
 */
suspend fun deleteSessionVectors(
    ownerId: String,
    sessionId: String,
    messageIds: Collection<String> = emptyList(),
) = withContext(Dispatchers.IO) {
    TavernApplication.instance.container.vectorMemoryService.deleteSessionVectors(
        ownerId,
        sessionId,
        messageIds = messageIds,
    )
}
