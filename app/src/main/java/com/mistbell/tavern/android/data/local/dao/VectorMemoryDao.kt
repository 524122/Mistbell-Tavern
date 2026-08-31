package com.mistbell.tavern.android.data.local.dao

import androidx.room.*
import com.mistbell.tavern.android.data.local.entity.VectorMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VectorMemoryDao {
    @Query("SELECT * FROM vector_memory WHERE owner_id = :ownerId ORDER BY created_at DESC")
    fun getAll(ownerId: String): Flow<List<VectorMemoryEntity>>

    @Query("SELECT * FROM vector_memory WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY created_at DESC")
    fun getByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<VectorMemoryEntity>>

    @Query(
        "SELECT * FROM vector_memory WHERE owner_id = :ownerId AND character_id = :characterId AND session_id = :sessionId ORDER BY created_at DESC",
    )
    fun getBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<VectorMemoryEntity>>

    @Query("SELECT * FROM vector_memory WHERE id = :id")
    suspend fun getById(id: Long): VectorMemoryEntity?

    @Query("SELECT * FROM vector_memory WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): VectorMemoryEntity?

    @Query("SELECT * FROM vector_memory WHERE vector_id = :vectorId")
    suspend fun getByVectorId(vectorId: String): VectorMemoryEntity?

    @Query("SELECT * FROM vector_memory WHERE owner_id = :ownerId AND content_type = :contentType ORDER BY created_at DESC")
    fun getByContentType(
        ownerId: String,
        contentType: String,
    ): Flow<List<VectorMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: VectorMemoryEntity): Long

    @Update
    suspend fun update(memory: VectorMemoryEntity)

    @Delete
    suspend fun delete(memory: VectorMemoryEntity)

    @Query("DELETE FROM vector_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM vector_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteByCharacter(
        ownerId: String,
        characterId: String,
    )

    @Query("DELETE FROM vector_memory WHERE owner_id = :ownerId AND session_id = :sessionId")
    suspend fun deleteBySession(
        ownerId: String,
        sessionId: String,
    )

    @Query("DELETE FROM vector_memory WHERE message_id = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("DELETE FROM vector_memory")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM vector_memory WHERE owner_id = :ownerId")
    suspend fun getCount(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM vector_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun getCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int

    @Query("SELECT COUNT(*) FROM vector_memory WHERE owner_id = :ownerId AND character_id = :characterId AND session_id = :sessionId")
    suspend fun getCountBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Int
}
