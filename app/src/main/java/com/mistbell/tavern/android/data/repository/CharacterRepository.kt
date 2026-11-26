package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CharacterRepository {
    private val db get() = TavernApplication.instance.container.database

    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
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
