package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query(
        "SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId AND status = 'active' ORDER BY importance DESC",
    )
    fun getActive(
        ownerId: String,
        characterId: String,
    ): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY importance DESC")
    fun getAll(
        ownerId: String,
        characterId: String,
    ): Flow<List<MemoryEntity>>

    @Query(
        "SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId AND session_id = :sessionId AND status = 'active' ORDER BY importance DESC",
    )
    fun getActiveBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<MemoryEntity>>

    @Query(
        "SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId AND session_id = :sessionId ORDER BY importance DESC",
    )
    fun getBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemoryEntity?

    @Upsert
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Query("DELETE FROM memories WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteByCharacter(
        ownerId: String,
        characterId: String,
    )

    @Query("DELETE FROM memories WHERE owner_id = :ownerId AND character_id = :characterId AND session_id = :sessionId")
    suspend fun deleteBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    )

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
