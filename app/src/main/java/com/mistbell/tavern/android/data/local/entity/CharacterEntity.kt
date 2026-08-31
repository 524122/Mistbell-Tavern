package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val description: String,
    val personality: String,
    val scenario: String,
    @ColumnInfo(name = "first_mes") val firstMes: String,
    @ColumnInfo(name = "mes_example") val mesExample: String,
    val color: String,
    @ColumnInfo(name = "avatar_data") val avatarData: String,
    @ColumnInfo(name = "world_book_id") val worldBookId: String,
    // defaultValue 必须与 MIGRATION_9_10 的 SQL DEFAULT '' 一致（Room TableInfo 校验列默认值）
    @ColumnInfo(name = "theme_id", defaultValue = "") val themeId: String = "",
    @ColumnInfo(name = "data_json") val dataJson: String
) {
    fun toDomain(): Character {
        val charData = try {
            if (dataJson.isNotBlank()) {
                kotlinx.serialization.json.Json.decodeFromString<CharacterData>(dataJson)
            } else null
        } catch (_: Exception) { null }

        return Character(
            id = id,
            name = name,
            role = role,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            mesExample = mesExample,
            color = color,
            avatarData = avatarData,
            worldBookId = worldBookId,
            themeId = themeId,
            data = charData
        )
    }

    companion object {
        fun fromDomain(c: Character): CharacterEntity {
            val dataStr = c.data?.let {
                kotlinx.serialization.json.Json.encodeToString(CharacterData.serializer(), it)
            } ?: ""
            return CharacterEntity(
                id = c.id,
                name = c.name,
                role = c.role,
                description = c.description,
                personality = c.personality,
                scenario = c.scenario,
                firstMes = c.firstMes,
                mesExample = c.mesExample,
                color = c.color,
                avatarData = c.avatarData,
                worldBookId = c.worldBookId,
                themeId = c.themeId,
                dataJson = dataStr
            )
        }
    }
}
