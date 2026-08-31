package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.Character
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class CharacterRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)

    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun loadCharactersFromServer() {
        try {
            val result = api.getCharacters()
            if (result is JsonArray) {
                val characters =
                    result.mapNotNull { element ->
                        try {
                            kotlinx.serialization.json.Json.decodeFromJsonElement<Character>(element)
                        } catch (_: Exception) {
                            null
                        }
                    }
                val entities =
                    characters.map {
                        com.mistbell.tavern.android.data.local.entity.CharacterEntity.fromDomain(it)
                    }
                db.characterDao().upsertAll(entities)
            }
        } catch (_: Exception) {
        }
    }

    suspend fun createCharacter(
        name: String,
        description: String = "",
        color: String = "",
    ) {
        withContext(Dispatchers.IO) {
            try {
                val data =
                    buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put("color", color)
                    }
                val body = buildJsonObject { put("data", data) }
                api.createCharacter(body)
                // Refresh from server
                loadCharactersFromServer()
            } catch (e: Exception) {
                // 异常上抛，让 UI 能感知保存失败
                throw e
            }
        }
    }

    suspend fun createCharacter(character: Character) {
        withContext(Dispatchers.IO) {
            try {
                val entity = com.mistbell.tavern.android.data.local.entity.CharacterEntity.fromDomain(character)
                db.characterDao().upsertAll(listOf(entity))
            } catch (e: Exception) {
                // 异常上抛，让 UI 能感知保存失败
                throw e
            }
        }
    }

    suspend fun updateCharacter(
        id: String,
        patch: JsonObject,
    ) {
        withContext(Dispatchers.IO) {
            try {
                api.updateCharacter(id, patch)
                loadCharactersFromServer()
            } catch (e: Exception) {
                // 异常上抛，让 UI 能感知保存失败
                throw e
            }
        }
    }

    suspend fun deleteCharacter(id: String) {
        withContext(Dispatchers.IO) {
            try {
                // 直接删除本地数据库
                db.characterDao().deleteById(id)
            } catch (e: Exception) {
                android.util.Log.e("CharacterRepository", "Delete character error", e)
            }
        }
    }
}
