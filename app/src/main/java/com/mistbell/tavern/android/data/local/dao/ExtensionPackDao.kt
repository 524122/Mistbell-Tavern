package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionPackDao {
    @Query("SELECT * FROM extension_packs ORDER BY loading_order ASC, installed_at ASC")
    fun observeAll(): Flow<List<ExtensionPackEntity>>

    @Query("SELECT * FROM extension_packs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExtensionPackEntity?

    /** 启用中的扩展包（P2 注入串/宿主钩子按此消费；P1 仅供后续接线预留） */
    @Query("SELECT * FROM extension_packs WHERE enabled = 1 ORDER BY loading_order ASC, installed_at ASC")
    fun observeEnabled(): Flow<List<ExtensionPackEntity>>

    /** 启用中的扩展包（一次性查询）：提供者互斥在同一事务内计算待停用名单时使用 */
    @Query("SELECT * FROM extension_packs WHERE enabled = 1 ORDER BY loading_order ASC, installed_at ASC")
    suspend fun getEnabled(): List<ExtensionPackEntity>

    @Upsert
    suspend fun upsert(pack: ExtensionPackEntity)

    @Query("UPDATE extension_packs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM extension_packs WHERE id = :id")
    suspend fun deleteById(id: String)
}
