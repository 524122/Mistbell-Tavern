package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY updated_at DESC")
    fun getByCharacter(ownerId: String, characterId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestByCharacter(ownerId: String, characterId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE owner_id = :ownerId ORDER BY is_pinned DESC, CASE WHEN is_pinned = 1 THEN pinned_at ELSE updated_at END DESC LIMIT 24")
    fun getRecent(ownerId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId LIMIT 1")
    suspend fun get(sessionId: String, ownerId: String, characterId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun delete(sessionId: String, ownerId: String, characterId: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("UPDATE sessions SET unread_count = :count WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun updateUnreadCount(sessionId: String, ownerId: String, characterId: String, count: Int)

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun updateTitle(sessionId: String, ownerId: String, characterId: String, title: String, updatedAt: String)

    @Query("UPDATE sessions SET is_pinned = :pinned, pinned_at = :pinnedAt WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun updatePinned(sessionId: String, ownerId: String, characterId: String, pinned: Boolean, pinnedAt: String?)

    @Query("UPDATE sessions SET is_muted = :muted WHERE id = :sessionId AND owner_id = :ownerId AND character_id = :characterId")
    suspend fun updateMuted(sessionId: String, ownerId: String, characterId: String, muted: Boolean)
}
