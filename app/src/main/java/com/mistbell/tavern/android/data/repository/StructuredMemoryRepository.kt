package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

class StructuredMemoryRepository(context: Context) {
    private val db = TavernApplication.instance.database
    private val memoryDao = db.structuredMemoryDao()

    companion object {
        private const val TAG = "StructuredMemoryRepo"
        private const val SYNC_THRESHOLD = 7 // 重要性阈值
    }

    // 获取所有记忆
    fun getAllMemories(ownerId: String): Flow<List<StructuredMemory>> {
        return memoryDao.getAll(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按角色获取记忆
    fun getMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按会话获取记忆
    fun getMemoriesBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getBySession(ownerId, characterId, sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getMemoriesBySession(
        ownerId: String,
        sessionId: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getBySession(ownerId, sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按类型获取记忆
    fun getMemoriesByType(
        ownerId: String,
        memoryType: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByType(ownerId, memoryType).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getMemoriesBySessionAndType(
        ownerId: String,
        sessionId: String,
        memoryType: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getBySessionAndType(ownerId, sessionId, memoryType).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按重要性获取记忆
    fun getMemoriesByImportance(
        ownerId: String,
        minImportance: Int,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByImportance(ownerId, minImportance).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 搜索记忆
    fun searchMemories(
        ownerId: String,
        query: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.search(ownerId, query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun searchMemoriesBySession(
        ownerId: String,
        sessionId: String,
        query: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.searchBySession(ownerId, sessionId, query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 获取单个记忆
    suspend fun getMemoryById(id: Long): StructuredMemory? {
        return withContext(Dispatchers.IO) {
            memoryDao.getById(id)?.toDomain()
        }
    }

    // 创建记忆
    suspend fun createMemory(memory: StructuredMemory): Long {
        return withContext(Dispatchers.IO) {
            val entity =
                StructuredMemoryEntity.fromDomain(
                    memory.copy(
                        createdAt = Instant.now().toString(),
                        updatedAt = Instant.now().toString(),
                    ),
                )
            val memoryId = memoryDao.insert(entity)

            // 如果重要性 >= 7，同步到向量数据库
            val createdMemory = memory.copy(id = memoryId)
            if (createdMemory.shouldSyncToVector()) {
                syncToVectorIfNeeded(createdMemory)
            }

            memoryId
        }
    }

    // 更新记忆
    suspend fun updateMemory(memory: StructuredMemory) {
        withContext(Dispatchers.IO) {
            val entity =
                StructuredMemoryEntity.fromDomain(
                    memory.copy(updatedAt = Instant.now().toString()),
                )
            memoryDao.update(entity)

            // 如果重要性 >= 7，重新同步到向量数据库
            if (memory.shouldSyncToVector()) {
                syncToVectorIfNeeded(memory)
            }
        }
    }

    // 删除记忆
    suspend fun deleteMemory(id: Long) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteById(id)
        }
    }

    // 按角色删除记忆
    suspend fun deleteMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteByCharacter(ownerId, characterId)
        }
    }

    // 按会话删除记忆
    suspend fun deleteMemoriesBySession(
        ownerId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteBySession(ownerId, sessionId)
        }
    }

    // 增加访问计数
    suspend fun incrementAccessCount(id: Long) {
        withContext(Dispatchers.IO) {
            memoryDao.incrementAccessCount(id, Instant.now().toString())
        }
    }

    // 获取记忆数量
    suspend fun getMemoryCount(ownerId: String): Int {
        return withContext(Dispatchers.IO) {
            memoryDao.getCount(ownerId)
        }
    }

    // 获取角色的记忆数量
    suspend fun getMemoryCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int {
        return withContext(Dispatchers.IO) {
            memoryDao.getCountByCharacter(ownerId, characterId)
        }
    }

    /**
     * 如果记忆重要性>=7，同步到向量数据库
     */
    private suspend fun syncToVectorIfNeeded(memory: StructuredMemory) {
        if (memory.importance < SYNC_THRESHOLD) {
            return
        }

        try {
            val vectorContent = memory.buildVectorContent()
            val vectorMemoryService = TavernApplication.instance.vectorMemoryService

            vectorMemoryService.storeMessage(
                content = vectorContent,
                ownerId = memory.ownerId,
                characterId = memory.characterId ?: "unknown",
                sessionId = memory.sessionId ?: "cross_session",
                messageId = "structured_memory_${memory.id}",
                contentType = "summary", // VectorMemory.ContentType.SUMMARY
            )

            Log.d(TAG, "Synced memory ${memory.id} to vector store (importance: ${memory.importance})")
        } catch (e: Exception) {
            // 向量同步失败不应影响主流程
            Log.e(TAG, "Failed to sync memory to vector: ${e.message}", e)
        }
    }
}
