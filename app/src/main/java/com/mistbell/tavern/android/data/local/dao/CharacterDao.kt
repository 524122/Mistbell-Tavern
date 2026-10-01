package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY name")
    fun getAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): CharacterEntity?

    /** 批量读取参与者，避免提示词装配按角色逐条查询。 */
    @Query("SELECT * FROM characters WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<CharacterEntity>

    @Upsert
    suspend fun upsertAll(characters: List<CharacterEntity>)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM characters")
    suspend fun deleteAll()

    /** 备份导出用：一次性全表读取（Flow 版本仅作观察用） */
    @Query("SELECT * FROM characters")
    suspend fun getAllOnce(): List<com.mistbell.tavern.android.data.local.entity.CharacterEntity>

    @Upsert
    suspend fun upsert(character: com.mistbell.tavern.android.data.local.entity.CharacterEntity)
}
