package com.mistbell.tavern.android.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        "SELECT * FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId ORDER BY created_at ASC",
    )
    fun getBySession(
        sessionId: String,
        ownerId: String,
        characterId: String,
    ): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId ORDER BY created_at ASC",
    )
    fun getBySessionPaged(
        sessionId: String,
        ownerId: String,
        characterId: String,
    ): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId AND session_id = :sessionId LIMIT 1")
    suspend fun getById(
        messageId: String,
        sessionId: String,
    ): MessageEntity?

    @Upsert
    suspend fun upsert(msg: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteBySession(
        sessionId: String,
        ownerId: String,
        characterId: String,
    )

    // 注意：必须先于 deleteById(target) 调用——本查询通过子查询定位目标消息的时间，
    // 若目标行已不存在则整条条件为 NULL，不会删除任何消息（安全边界，调用方依赖此语义）。
    // created_at 为 ISO-8601 字符串，字典序即时间序；同一时间戳的并列消息用 rowid（插入序）决胜，
    // 保证"目标之后"的语义在导入数据等时间重复场景下依然正确。
    @Query(
        """
        DELETE FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND character_id = :characterId
          AND (
              created_at > (SELECT target.created_at FROM messages target WHERE target.id = :afterMessageId)
              OR (
                  created_at = (SELECT target.created_at FROM messages target WHERE target.id = :afterMessageId)
                  AND rowid > (SELECT target.rowid FROM messages target WHERE target.id = :afterMessageId)
              )
          )
    """,
    )
    suspend fun deleteAfter(
        sessionId: String,
        afterMessageId: String,
        ownerId: String,
        characterId: String,
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("UPDATE messages SET is_read = 1 WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun markAsRead(
        sessionId: String,
        ownerId: String,
        characterId: String,
    )

    @Query(
        "SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId AND is_read = 0",
    )
    suspend fun getUnreadCount(
        sessionId: String,
        ownerId: String,
        characterId: String,
    ): Int

    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT session_id, MAX(created_at) as max_created_at
            FROM messages
            WHERE owner_id = :ownerId
            GROUP BY session_id
        ) latest ON m.session_id = latest.session_id AND m.created_at = latest.max_created_at
        WHERE m.owner_id = :ownerId
    """,
    )
    suspend fun getLatestMessagesByOwner(ownerId: String): List<MessageEntity>

    // F3-FTS 词法召回：取会话内最近 N 条消息的 id，作为"近期窗口"排除集——
    // 这些消息本来就在上下文里，历史召回时不应重复出现。
    @Query(
        "SELECT id FROM messages WHERE session_id=:sessionId AND owner_id=:ownerId AND character_id=:characterId ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun latestIdsBySession(
        sessionId: String,
        ownerId: String,
        characterId: String,
        limit: Int,
    ): List<String>

    // F3-FTS 词法召回：按关键词（LIKE）检索历史消息，排除近期窗口内的 id。
    // 未用满的词位由调用方填充 "~~nomatch~~%"（不会匹配任何真实内容），LIKE 对 % 无需转义（模式固定）。
    @Query(
        "SELECT * FROM messages WHERE session_id=:sessionId AND owner_id=:ownerId AND character_id=:characterId AND id NOT IN (:excludedIds) AND (content LIKE :t1 OR content LIKE :t2 OR content LIKE :t3 OR content LIKE :t4 OR content LIKE :t5 OR content LIKE :t6) ORDER BY created_at DESC LIMIT :resultLimit",
    )
    suspend fun searchByContentTerms(
        sessionId: String,
        ownerId: String,
        characterId: String,
        excludedIds: List<String>,
        t1: String,
        t2: String,
        t3: String,
        t4: String,
        t5: String,
        t6: String,
        resultLimit: Int,
    ): List<MessageEntity>
}
