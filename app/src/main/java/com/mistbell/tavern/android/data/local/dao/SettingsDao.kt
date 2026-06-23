package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun getAll(): Flow<List<SettingsEntity>>

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun upsert(setting: SettingsEntity)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}
