package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemePackDao {
    @Query("SELECT * FROM theme_packs ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ThemePackEntity>>

    @Query("SELECT * FROM theme_packs WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ThemePackEntity?>

    @Query("SELECT * FROM theme_packs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ThemePackEntity?

    @Upsert
    suspend fun upsert(pack: ThemePackEntity)

    @Query("DELETE FROM theme_packs WHERE id = :id")
    suspend fun deleteById(id: String)
}
