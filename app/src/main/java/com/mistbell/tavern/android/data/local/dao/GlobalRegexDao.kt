package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalRegexDao {
    @Query("SELECT * FROM global_regex_rules ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<GlobalRegexRuleEntity>>

    @Query("SELECT * FROM global_regex_rules ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<GlobalRegexRuleEntity>

    @Upsert
    suspend fun upsertAll(items: List<GlobalRegexRuleEntity>)

    @Query("DELETE FROM global_regex_rules")
    suspend fun deleteAll()

    /** 保存即整表替换：与卡级管理页"保存整个文档"的交互一致，列表顺序即落库顺序 */
    @Transaction
    suspend fun replaceAll(items: List<GlobalRegexRuleEntity>) {
        deleteAll()
        if (items.isNotEmpty()) upsertAll(items)
    }
}
