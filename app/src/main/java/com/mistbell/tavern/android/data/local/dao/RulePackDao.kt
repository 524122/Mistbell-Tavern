package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.RulePackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RulePackDao {
    @Query("SELECT * FROM rule_packs ORDER BY loading_order ASC, created_at ASC")
    fun observeAll(): Flow<List<RulePackEntity>>

    @Query("SELECT * FROM rule_packs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RulePackEntity?

    @Query("SELECT * FROM rule_packs WHERE enabled = 1 ORDER BY loading_order ASC, created_at ASC")
    fun observeEnabled(): Flow<List<RulePackEntity>>

    @Upsert
    suspend fun upsert(pack: RulePackEntity)

    @Query("UPDATE rule_packs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM rule_packs WHERE id = :id")
    suspend fun deleteById(id: String)
}
