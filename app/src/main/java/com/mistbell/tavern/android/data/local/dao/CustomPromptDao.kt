package com.mistbell.tavern.android.data.local.dao

import androidx.room.*
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import kotlinx.coroutines.flow.Flow

/**
 * 自定义提示词 DAO
 */
@Dao
interface CustomPromptDao {
    @Query("SELECT * FROM custom_prompts ORDER BY priority DESC, createdAt ASC")
    fun observeAll(): Flow<List<CustomPromptEntity>>

    @Query("SELECT * FROM custom_prompts ORDER BY priority DESC, createdAt ASC")
    suspend fun getAll(): List<CustomPromptEntity>

    @Query("SELECT * FROM custom_prompts WHERE id = :id")
    suspend fun getById(id: String): CustomPromptEntity?

    @Query("SELECT * FROM custom_prompts WHERE id = :id")
    fun observeById(id: String): Flow<CustomPromptEntity?>

    @Query("SELECT * FROM custom_prompts WHERE isEnabled = 1 ORDER BY priority DESC, createdAt ASC")
    suspend fun getEnabled(): List<CustomPromptEntity>

    @Query("SELECT * FROM custom_prompts WHERE isEnabled = 1 ORDER BY priority DESC, createdAt ASC")
    fun observeEnabled(): Flow<List<CustomPromptEntity>>

    @Query("SELECT * FROM custom_prompts WHERE type = :type ORDER BY priority DESC, createdAt ASC")
    suspend fun getByType(type: String): List<CustomPromptEntity>

    @Query("SELECT * FROM custom_prompts WHERE type = :type ORDER BY priority DESC, createdAt ASC")
    fun observeByType(type: String): Flow<List<CustomPromptEntity>>

    @Query("SELECT * FROM custom_prompts WHERE position = :position AND isEnabled = 1 ORDER BY priority DESC")
    suspend fun getByPosition(position: String): List<CustomPromptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prompt: CustomPromptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prompts: List<CustomPromptEntity>)

    @Update
    suspend fun update(prompt: CustomPromptEntity)

    @Query("DELETE FROM custom_prompts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM custom_prompts")
    suspend fun deleteAll()

    @Query("UPDATE custom_prompts SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COUNT(*) FROM custom_prompts")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM custom_prompts WHERE isEnabled = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM custom_prompts WHERE isEnabled = 1")
    fun observeEnabledCount(): Flow<Int>
}
