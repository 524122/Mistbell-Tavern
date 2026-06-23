package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.PendingSyncEntity

@Dao
interface PendingSyncDao {
    @Query("SELECT * FROM pending_sync ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingSyncEntity>

    @Upsert
    suspend fun insert(item: PendingSyncEntity)

    @Delete
    suspend fun delete(item: PendingSyncEntity)

    @Query("DELETE FROM pending_sync")
    suspend fun deleteAll()
}
