package com.mistbell.tavern.android.data.local.dao

import androidx.room.*
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StructuredMemoryDao {
    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId ORDER BY created_at DESC")
    fun getAll(ownerId: String): Flow<List<StructuredMemoryEntity>>

    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY created_at DESC")
    fun getByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<StructuredMemoryEntity>>

    // 提示词召回候选 —— 会话内长期记忆（2026-10 产品决策）：结构化 / 向量 / 词法三条通道统一
    // 按 owner + 当前会话过滤（窗口见 RecallWindow），群聊不按主角色过滤，缺失 sessionId 时
    // 调用方拒绝召回。owner 硬隔离不变：绝不把其它用户的记忆注入本提示词。

    /**
     * 会话窗召回：只认**会话集合**，不按角色收窄（生产路径唯一入口）。
     *
     * 会话内长期记忆：群聊里 NPC 与主角色的记忆同属一次对话，按 character_id 切分只会把
     * 同一次对话切碎；角色隔离由会话本身提供（owner + session）。
     *
     * @param sessionIds 窗口允许的会话集合；调用方保证非空（缺失会话边界时调用方直接拒绝召回）
     */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id IN (:sessionIds) " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesInSessions(
        ownerId: String,
        sessionIds: List<String>,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 一次性查询：某会话内的全部记忆（抽取去重用）。归属硬约束 = owner + session */
    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId")
    suspend fun getByOwnerAndSession(
        ownerId: String,
        sessionId: String,
    ): List<StructuredMemoryEntity>

    // ---- 以下为遗留查询：生产召回路径已不再调用，仅供旧测试引用保留 ----

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id IS NULL " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesUnassigned(
        ownerId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 遗留，仅供旧测试引用：按角色 + 会话集合召回（跨会话口径已废弃） */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND session_id IN (:sessionIds) ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesForCharacterInSessions(
        ownerId: String,
        characterId: String,
        sessionIds: List<String>,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 遗留，仅供旧测试引用 */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND session_id IS NULL ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesForCharacterUnassigned(
        ownerId: String,
        characterId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 遗留，仅供旧测试引用 */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesForCharacter(
        ownerId: String,
        characterId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 遗留，仅供旧测试引用：owner 级召回（跨会话口径已废弃，生产路径拒绝无会话边界的召回） */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidatesForOwner(
        ownerId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /**
     * 记忆管理页的列表：**仅当前会话的记忆**（owner + session）。
     *
     * 会话内长期记忆：页面可见集合与召回口径逐字一致——只看得到本会话的，召回也只召回本会话的。
     * [characterId] 仅为兼容旧签名保留，不再参与过滤（Room 要求查询参数被引用，此处以恒真
     * 条件占位）。
     */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId " +
            "AND (:characterId IS NULL OR 1 = 1) ORDER BY created_at DESC",
    )
    fun getVisibleInSession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query("SELECT * FROM structured_memory WHERE id = :id")
    suspend fun getById(id: Long): StructuredMemoryEntity?

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND memory_type = :memoryType " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun getByType(
        ownerId: String,
        memoryType: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND importance >= :minImportance " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun getByImportance(
        ownerId: String,
        minImportance: Int,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND " +
            "(title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun search(
        ownerId: String,
        query: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: StructuredMemoryEntity): Long

    @Update
    suspend fun update(memory: StructuredMemoryEntity)

    @Query(
        "UPDATE structured_memory SET vector_revision = lower(hex(randomblob(16))) " +
            "WHERE id = :id AND vector_revision = ''",
    )
    suspend fun ensureVectorRevision(id: Long)

    @Delete
    suspend fun delete(memory: StructuredMemoryEntity)

    @Query("DELETE FROM structured_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteByCharacter(
        ownerId: String,
        characterId: String,
    )

    /**
     * 删会话时清理它的记忆。
     *
     * **角色级记忆（`session_id` 为空）会存活**，这是刻意的：它不属于任何会话，
     * 删一个会话不该带走"这个角色是什么样"这种跨会话事实。要删它们走按角色/按条删除。
     */
    @Query("DELETE FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId")
    suspend fun deleteBySession(
        ownerId: String,
        sessionId: String,
    )

    @Transaction
    suspend fun deleteAll() {
        deleteAllStructuredMemory()
        deleteAllVectorSyncOutbox()
    }

    @Query("DELETE FROM structured_memory")
    suspend fun deleteAllStructuredMemory()

    @Query("DELETE FROM vector_sync_outbox")
    suspend fun deleteAllVectorSyncOutbox()

    /** 按 owner 全量删除（deleteAllSessions 的孤儿清理用；不带 owner 条件的 deleteAll 会越界） */
    @Query("DELETE FROM structured_memory WHERE owner_id = :ownerId")
    suspend fun deleteByOwner(ownerId: String)

    @Query("UPDATE structured_memory SET access_count = access_count + 1, last_accessed_at = :accessedAt WHERE id = :id")
    suspend fun incrementAccessCount(
        id: Long,
        accessedAt: String,
    )

    @Query(
        "UPDATE structured_memory SET access_count = access_count + 1, last_accessed_at = :accessedAt " +
            "WHERE id IN (:ids)",
    )
    suspend fun incrementAccessCount(
        ids: List<Long>,
        accessedAt: String,
    )

    @Query("SELECT COUNT(*) FROM structured_memory WHERE owner_id = :ownerId")
    suspend fun getCount(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun getCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND importance >= 7 " +
            "AND session_id IS NOT NULL ORDER BY updated_at DESC LIMIT :limit",
    )
    suspend fun getSummariesForReindex(
        ownerId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    /** 备份导出用：一次性全表读取（不限 owner） */
    @Query("SELECT * FROM structured_memory")
    suspend fun getAllOnce(): List<com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity>
}
