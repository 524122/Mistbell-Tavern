package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.VectorMemory
import com.mistbell.tavern.android.data.local.entity.VectorMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

class VectorMemoryRepository(context: Context) {
    private val db = TavernApplication.instance.database
    private val memoryDao = db.vectorMemoryDao()

    // 获取所有向量记忆
    fun getAllMemories(ownerId: String): Flow<List<VectorMemory>> {
        return memoryDao.getAll(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按角色获取向量记忆
    fun getMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<VectorMemory>> {
        return memoryDao.getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按会话获取向量记忆
    fun getMemoriesBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<VectorMemory>> {
        return memoryDao.getBySession(ownerId, characterId, sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按内容类型获取向量记忆
    fun getMemoriesByContentType(
        ownerId: String,
        contentType: String,
    ): Flow<List<VectorMemory>> {
        return memoryDao.getByContentType(ownerId, contentType).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 获取单个向量记忆
    suspend fun getMemoryById(id: Long): VectorMemory? {
        return withContext(Dispatchers.IO) {
            memoryDao.getById(id)?.toDomain()
        }
    }

    // 通过消息 ID 获取向量记忆
    suspend fun getMemoryByMessageId(messageId: String): VectorMemory? {
        return withContext(Dispatchers.IO) {
            memoryDao.getByMessageId(messageId)?.toDomain()
        }
    }

    // 通过向量 ID 获取向量记忆
    suspend fun getMemoryByVectorId(vectorId: String): VectorMemory? {
        return withContext(Dispatchers.IO) {
            memoryDao.getByVectorId(vectorId)?.toDomain()
        }
    }

    // 创建向量记忆
    suspend fun createMemory(memory: VectorMemory): Long {
        return withContext(Dispatchers.IO) {
            val entity =
                VectorMemoryEntity.fromDomain(
                    memory.copy(createdAt = Instant.now().toString()),
                )
            memoryDao.insert(entity)
        }
    }

    // 更新向量记忆
    suspend fun updateMemory(memory: VectorMemory) {
        withContext(Dispatchers.IO) {
            val entity = VectorMemoryEntity.fromDomain(memory)
            memoryDao.update(entity)
        }
    }

    // 删除向量记忆
    suspend fun deleteMemory(id: Long) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteById(id)
        }
    }

    // 按角色删除向量记忆
    suspend fun deleteMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteByCharacter(ownerId, characterId)
        }
    }

    // 按会话删除向量记忆
    suspend fun deleteMemoriesBySession(
        ownerId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteBySession(ownerId, sessionId)
        }
    }

    // 按消息删除向量记忆
    suspend fun deleteMemoryByMessageId(messageId: String) {
        withContext(Dispatchers.IO) {
            memoryDao.deleteByMessageId(messageId)
        }
    }

    // 获取向量记忆数量
    suspend fun getMemoryCount(ownerId: String): Int {
        return withContext(Dispatchers.IO) {
            memoryDao.getCount(ownerId)
        }
    }

    // 获取角色的向量记忆数量
    suspend fun getMemoryCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int {
        return withContext(Dispatchers.IO) {
            memoryDao.getCountByCharacter(ownerId, characterId)
        }
    }

    // 获取会话的向量记忆数量
    suspend fun getMemoryCountBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Int {
        return withContext(Dispatchers.IO) {
            memoryDao.getCountBySession(ownerId, characterId, sessionId)
        }
    }
}
