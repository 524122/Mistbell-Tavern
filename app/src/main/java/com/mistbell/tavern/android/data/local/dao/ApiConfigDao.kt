package com.mistbell.tavern.android.data.local.dao

import androidx.room.*
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * API 配置 DAO
 */
@Dao
interface ApiConfigDao {
    @Query("SELECT * FROM api_configs ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<ApiConfigEntity>>

    @Query("SELECT * FROM api_configs ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAll(): List<ApiConfigEntity>

    @Query("SELECT * FROM api_configs WHERE id = :id")
    suspend fun getById(id: String): ApiConfigEntity?

    @Query("SELECT * FROM api_configs WHERE id = :id")
    fun observeById(id: String): Flow<ApiConfigEntity?>

    @Query("SELECT * FROM api_configs WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ApiConfigEntity?

    @Query("SELECT * FROM api_configs WHERE isDefault = 1 LIMIT 1")
    fun observeDefault(): Flow<ApiConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apiConfig: ApiConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apiConfigs: List<ApiConfigEntity>)

    @Update
    suspend fun update(apiConfig: ApiConfigEntity)

    @Query("DELETE FROM api_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM api_configs")
    suspend fun deleteAll()

    @Query("UPDATE api_configs SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Transaction
    suspend fun setDefault(id: String) {
        clearAllDefaults()
        val config = getById(id)
        config?.let {
            update(it.copy(isDefault = true, updatedAt = System.currentTimeMillis()))
        }
    }

    @Query("UPDATE api_configs SET lastTestStatus = :status, lastTestTime = :time, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTestStatus(
        id: String,
        status: String,
        time: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COUNT(*) FROM api_configs")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM api_configs")
    fun observeCount(): Flow<Int>
}
