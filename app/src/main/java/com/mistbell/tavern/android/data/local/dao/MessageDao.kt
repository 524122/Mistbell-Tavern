package com.mistbell.tavern.android.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId ORDER BY created_at ASC")
    fun getBySession(sessionId: String, ownerId: String, characterId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId ORDER BY created_at ASC")
    fun getBySessionPaged(sessionId: String, ownerId: String, characterId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId AND session_id = :sessionId LIMIT 1")
    suspend fun getById(messageId: String, sessionId: String): MessageEntity?

    @Upsert
    suspend fun upsert(msg: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteBySession(sessionId: String, ownerId: String, characterId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND id > :afterMessageId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteAfter(sessionId: String, afterMessageId: String, ownerId: String, characterId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("UPDATE messages SET is_read = 1 WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun markAsRead(sessionId: String, ownerId: String, characterId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId AND character_id = :characterId AND is_read = 0")
    suspend fun getUnreadCount(sessionId: String, ownerId: String, characterId: String): Int

    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT session_id, MAX(created_at) as max_created_at
            FROM messages
            WHERE owner_id = :ownerId
            GROUP BY session_id
        ) latest ON m.session_id = latest.session_id AND m.created_at = latest.max_created_at
        WHERE m.owner_id = :ownerId
    """)
    suspend fun getLatestMessagesByOwner(ownerId: String): List<MessageEntity>
}
