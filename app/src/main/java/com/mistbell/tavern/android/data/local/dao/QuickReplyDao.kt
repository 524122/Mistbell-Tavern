package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.QuickReplyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickReplyDao {
    @Query("SELECT * FROM quick_replies ORDER BY order_index ASC, created_at ASC")
    fun observeAll(): Flow<List<QuickReplyEntity>>

    @Upsert
    suspend fun upsert(item: QuickReplyEntity)

    @Query("DELETE FROM quick_replies WHERE id = :id")
    suspend fun deleteById(id: String)
}
