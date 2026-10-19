package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

// 本轮为 deleteAllSessions 的孤儿清理新增 deleteByOwner，函数数到达阈值 11。
// DAO 的方法数由 Room 查询面决定，不宜为凑阈值合并查询，豁免。
@Suppress("TooManyFunctions")
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
        "SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND session_id = :sessionId AND status = 'active' ORDER BY importance DESC",
    )
    fun getActiveBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<MemoryEntity>>

    @Query(
        "SELECT * FROM memories WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND session_id = :sessionId ORDER BY importance DESC",
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

    /** 按 owner 全量删除（deleteAllSessions 的孤儿清理用；不带 owner 条件的 deleteAll 会越界） */
    @Query("DELETE FROM memories WHERE owner_id = :ownerId")
    suspend fun deleteByOwner(ownerId: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    /** 备份导出用：一次性全表读取（不限 owner/角色，legacy memories 表） */
    @Query("SELECT * FROM memories")
    suspend fun getAllOnce(): List<com.mistbell.tavern.android.data.local.entity.MemoryEntity>
}
