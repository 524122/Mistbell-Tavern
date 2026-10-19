// 删除路径的契约是"失败不掀翻调用方"——一次清理失败最多让召回多留一条孤儿向量，
// 不该让发送链路、会话删除或记忆编辑崩掉，因此统一捕获 Exception 并降级为返回值。
@file:Suppress("TooGenericExceptionCaught")

package com.mistbell.tavern.android.data.vector

import android.util.Log

/*
 * 向量索引的删除动作。
 *
 * 与 [VectorMemoryService] 的写入/检索热路径分开：这里的每一条都是"按某个维度清账"，
 * 共用同一套 filter 形状与同一条**删除意图**纪律。
 *
 * ## 删除意图
 *
 * 删除与写入是并发的：embedding 是慢操作，删除时它可能还没落地。只删"现在存在的向量"会让
 * 在途那次写入稍后把删掉的内容又放回索引（跨会话召回还会把它捞回来）。所以这里的每条删除都带
 * [VectorStore.MARK_MESSAGES_DELETED] 指令：存储在被删的那批行上登记 message_id，
 * 之后针对它们的写入一律被闸门挡住。
 *
 * 需要"同一条消息内容变了"的替换型写入，走 [VectorMemoryService.storeMessage] 的
 * `replacePrevious = true`——它会解除这个标记。
 *
 * 下面是四条按维度清账的入口。
 */

/**
 * 删除会话的所有向量（owner + session 维度，角色为可选收窄条件）。
 *
 * 会话的归属键是 (owner_id, session_id)：群聊里同一条会话的 assistant 消息按真实说话者
 * 落库，character_id 各是不同 NPC。若按会话主角色过滤，NPC 的向量会漏删、长期残留在
 * vector_store.json 中——因此会话删除必须以 session 为准。
 */
suspend fun VectorMemoryService.deleteSessionVectors(
    ownerId: String,
    sessionId: String,
    characterId: String? = null,
    messageIds: Collection<String> = emptyList(),
): Int {
    try {
        if (messageIds.isNotEmpty()) {
            indexStore().markMessagesDeleted(messageIds)
        }
        val filters =
            mutableMapOf<String, Any>(
                "owner_id" to ownerId,
                "session_id" to sessionId,
                // 指令：命中行（该会话的一切向量）的 message_id 登记为已删除，
                // 挡住尚在 embedding 的在途写入
                VectorStore.MARK_MESSAGES_DELETED to true,
            )
        characterId?.let { filters["character_id"] = it }

        val deleted = indexStore().deleteByFilters(filters)
        Log.d(TAG, "Deleted $deleted vectors for session: $sessionId")

        return deleted
    } catch (e: Exception) {
        Log.e(
            TAG,
            "删除会话向量失败：会话 $sessionId 的向量可能残留在索引中，" +
                "下次召回可能捞回已删除的对话。${e.message}",
            e,
        )
        return 0
    }
}

/**
 * 删除角色的所有向量。
 *
 * 角色级记忆（`session_id` 为哨兵值、`character_id` 为哨兵值）一并带走——它本来就属于这个角色。
 * 与 [deleteSessionVectors] 一样带删除意图指令。
 */
suspend fun VectorMemoryService.deleteCharacterVectors(
    ownerId: String,
    characterId: String,
): Int {
    try {
        val deleted =
            indexStore().deleteByFilters(
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                    VectorStore.MARK_MESSAGES_DELETED to true,
                ),
            )
        // 角色级记忆的摘要在向量层用哨兵 character_id 占位，按真实角色过滤删不到它；
        // 这里补一刀，否则删了角色还留着"这个角色是什么样"的摘要
        val deletedCharacterLevel =
            indexStore().deleteByFilters(
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to VectorStore.CHARACTER_LEVEL_CHARACTER,
                    VectorStore.MARK_MESSAGES_DELETED to true,
                ),
            )
        Log.d(TAG, "Deleted $deleted vectors for character: $characterId (+$deletedCharacterLevel 角色级)")

        return deleted + deletedCharacterLevel
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete character vectors: ${e.message}", e)
        return 0
    }
}

/**
 * 按 messageId 删除向量。
 *
 * 结构化记忆删除/更新时清理其同步副本（messageId = "structured_memory_<id>"），
 * 以及消息被回滚/撤销/回退/重抽时的清理。
 *
 * **先登记删除意图再删**：这条消息可能正有一次慢速 embedding 在途，它落地时必须被挡住。
 */
suspend fun VectorMemoryService.deleteByMessageId(messageId: String): Int {
    return try {
        indexStore().markMessagesDeleted(listOf(messageId))
        val deleted = indexStore().deleteByFilters(mapOf("message_id" to messageId))
        Log.d(TAG, "Deleted $deleted vectors by messageId: $messageId")
        deleted
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete vectors by messageId: ${e.message}", e)
        0
    }
}

/** 恢复器专用：只有删除后的完整索引已经可靠落盘才返回 true。 */
suspend fun VectorMemoryService.deleteByMessageIdPersisted(
    messageId: String,
    ownerId: String? = null,
    sessionId: String? = null,
): Boolean {
    return try {
        indexStore().markMessagesDeleted(listOf(messageId))
        val filters = mutableMapOf<String, Any>("message_id" to messageId)
        if (!ownerId.isNullOrBlank()) filters["owner_id"] = ownerId
        if (!sessionId.isNullOrBlank()) filters["session_id"] = sessionId
        indexStore().deleteByFilters(filters)
        indexStore().flush()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to persist vector deletion for $messageId: ${e.message}", e)
        false
    }
}

/**
 * 删除摘要型向量（结构化记忆的同步产物，content_type = summary）。
 *
 * 随记忆批量删除联动，按 owner/角色/会话范围过滤。
 */
suspend fun VectorMemoryService.deleteSummaryVectors(
    ownerId: String,
    characterId: String? = null,
    sessionId: String? = null,
): Int {
    return try {
        val filters =
            mutableMapOf<String, Any>(
                "owner_id" to ownerId,
                "content_type" to VectorStore.SUMMARY_CONTENT_TYPE,
            )
        characterId?.let { filters["character_id"] = it }
        sessionId?.let { filters["session_id"] = it }
        // 指令：这些摘要对应的记忆没了，在途写入同样要挡住
        filters[VectorStore.MARK_MESSAGES_DELETED] = true
        val deleted = indexStore().deleteByFilters(filters)
        Log.d(TAG, "Deleted $deleted summary vectors (owner=$ownerId, character=$characterId, session=$sessionId)")
        deleted
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete summary vectors: ${e.message}", e)
        0
    }
}

private const val TAG = "VectorMemoryService"
