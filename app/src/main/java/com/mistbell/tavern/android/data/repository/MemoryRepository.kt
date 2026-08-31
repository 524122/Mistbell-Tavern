package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.Memory
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class MemoryRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)

    fun observeMemories(
        ownerId: String,
        characterId: String,
        activeOnly: Boolean = false,
    ): Flow<List<Memory>> {
        val flow =
            if (activeOnly) {
                db.memoryDao().getActive(ownerId, characterId)
            } else {
                db.memoryDao().getAll(ownerId, characterId)
            }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    suspend fun loadFromServer(
        ownerId: String,
        characterId: String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val result = api.getMemories(ownerId, characterId)
                if (result is JsonArray) {
                    val memories =
                        result.mapNotNull { el ->
                            try {
                                Json.decodeFromJsonElement<Memory>(el)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    val entities =
                        memories.map {
                            MemoryEntity.fromDomain(it, ownerId, characterId)
                        }
                    db.memoryDao().deleteByCharacter(ownerId, characterId)
                    db.memoryDao().upsertAll(entities)
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun createMemory(
        ownerId: String,
        characterId: String,
        content: String,
        layer: String = "episodic",
        type: String = "note",
        importance: Double = 0.5,
        emotionalAtmosphere: String = "",
    ): Memory? {
        return withContext(Dispatchers.IO) {
            try {
                val body =
                    buildJsonObject {
                        put("ownerId", ownerId)
                        put("characterId", characterId)
                        put("content", content)
                        put("layer", layer)
                        put("type", type)
                        put("importance", importance)
                        if (emotionalAtmosphere.isNotBlank()) {
                            put("emotionalAtmosphere", emotionalAtmosphere)
                        }
                    }
                val result = api.createMemory(body)
                val obj = result as? JsonObject
                val id = obj?.get("id")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                loadFromServer(ownerId, characterId)
                Memory(id = id, content = content, layer = layer, type = type, importance = importance)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun createMemory(body: JsonObject): Memory? {
        return withContext(Dispatchers.IO) {
            try {
                val result = api.createMemory(body)
                val obj = result as? JsonObject
                val id = obj?.get("id")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                val ownerId = body["ownerId"]?.jsonPrimitive?.content ?: ""
                val characterId = body["characterId"]?.jsonPrimitive?.content ?: ""
                loadFromServer(ownerId, characterId)
                Memory(
                    id = id,
                    content = body["content"]?.jsonPrimitive?.content ?: "",
                    layer = body["layer"]?.jsonPrimitive?.content ?: "episodic",
                    type = body["type"]?.jsonPrimitive?.content ?: "note",
                    importance = body["importance"]?.jsonPrimitive?.doubleOrNull ?: 0.5,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun updateMemory(
        memoryId: String,
        patch: JsonObject,
    ) {
        withContext(Dispatchers.IO) {
            try {
                api.updateMemory(memoryId, patch)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun deleteMemory(
        memoryId: String,
        ownerId: String,
        characterId: String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                api.deleteMemory(memoryId)
                loadFromServer(ownerId, characterId)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun backfillMemories(
        ownerId: String,
        characterId: String,
        sessionId: String? = null,
    ): JsonElement? {
        return withContext(Dispatchers.IO) {
            try {
                val body =
                    buildJsonObject {
                        put("ownerId", ownerId)
                        put("characterId", characterId)
                        sessionId?.let { put("sessionId", it) }
                    }
                val result = api.backfillMemories(body)
                loadFromServer(ownerId, characterId)
                result
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun exportMemories(
        ownerId: String,
        characterId: String? = null,
    ): JsonElement? {
        return withContext(Dispatchers.IO) {
            try {
                api.exportMemories(ownerId, characterId)
            } catch (_: Exception) {
                null
            }
        }
    }
}
