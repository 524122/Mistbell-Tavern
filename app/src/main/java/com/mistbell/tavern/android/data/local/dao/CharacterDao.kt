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

    @Upsert
    suspend fun upsertAll(characters: List<CharacterEntity>)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM characters")
    suspend fun deleteAll()
}
