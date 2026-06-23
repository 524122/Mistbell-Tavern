package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.api.model.CharacterData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream
import java.util.UUID

data class CharacterImportResult(
    val character: CharacterEntity,
    val worldBook: WorldBookEntity?,
    val worldBookEntries: List<WorldBookEntryEntity>
)

object CharacterImporter {
    /**
     * 从 JSON 文件导入角色（包括可能的世界书）
     * @param context Android Context
     * @param uri 文件 URI
     * @return CharacterImportResult 或 null（如果解析失败）
     */
    fun importFromJson(context: Context, uri: Uri): CharacterImportResult? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: return null

            val json = Json { ignoreUnknownKeys = true }
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject

            // 辅助函数：从多个位置获取字段值
            fun getText(vararg keys: String): String {
                for (key in keys) {
                    // 优先从 data 对象中读取
                    jsonObject["data"]?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                    // 然后从根对象读取
                    jsonObject[key]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                }
                return ""
            }

            // 解析基础字段 - 支持多种字段名
            // 始终生成新的 ID，避免与已有角色冲突
            val id = UUID.randomUUID().toString()
            val name = getText("name").ifBlank { "未命名角色" }
            val description = getText("description", "desc")
            val personality = getText("personality")
            val scenario = getText("scenario")
            val firstMes = getText("first_mes", "greeting")
            val mesExample = getText("mes_example", "example")

            android.util.Log.d("CharacterImporter", "Raw JSON keys: ${jsonObject.keys.joinToString()}")
            android.util.Log.d("CharacterImporter", "Parsed name: $name")
            android.util.Log.d("CharacterImporter", "Parsed description: $description")
            android.util.Log.d("CharacterImporter", "Parsed personality: $personality")
            android.util.Log.d("CharacterImporter", "Parsed firstMes: $firstMes")

            // 颜色
            val color = getText("color").ifBlank { "#007AFF" }

            // 头像数据（Base64）
            val avatarData = getText("avatarData", "avatar")

            // 解析世界书
            var worldBookEntity: WorldBookEntity? = null
            var worldBookEntries = emptyList<WorldBookEntryEntity>()
            var worldBookId = ""

            android.util.Log.d("CharacterImporter", "Checking for character_book field...")
            // V2 格式：data.character_book
            // V1 格式：character_book
            val dataObject = jsonObject["data"]?.jsonObject
            val characterBook = dataObject?.get("character_book")?.jsonObject
                ?: jsonObject["character_book"]?.jsonObject
            android.util.Log.d("CharacterImporter", "character_book found: ${characterBook != null}")

            if (characterBook != null) {
                worldBookId = UUID.randomUUID().toString()
                val bookName = characterBook["name"]?.jsonPrimitive?.content ?: "$name 的世界书"

                android.util.Log.d("CharacterImporter", "Creating world book: $bookName with id: $worldBookId")

                worldBookEntity = WorldBookEntity(
                    id = worldBookId,
                    name = bookName,
                    settingsJson = "{}"
                )

                // 解析世界书条目
                val entriesArray = characterBook["entries"]?.jsonArray
                android.util.Log.d("CharacterImporter", "entries array found: ${entriesArray != null}, size: ${entriesArray?.size ?: 0}")

                if (entriesArray != null) {
                    worldBookEntries = entriesArray.mapNotNull { entryElement ->
                        try {
                            val entry = entryElement.jsonObject
                            val entryId = entry["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                            val comment = entry["comment"]?.jsonPrimitive?.content ?: ""
                            val content = entry["content"]?.jsonPrimitive?.content ?: ""
                            val constant = entry["constant"]?.jsonPrimitive?.booleanOrNull ?: false
                            val disable = entry["disable"]?.jsonPrimitive?.booleanOrNull ?: false
                            val order = entry["order"]?.jsonPrimitive?.intOrNull ?: 0

                            // 解析 keys
                            val keys = when (val keysElement = entry["keys"]) {
                                is JsonArray -> keysElement.map { it.jsonPrimitive.content }
                                is JsonPrimitive -> listOf(keysElement.content)
                                else -> emptyList()
                            }
                            val keysJson = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                                keys
                            )

                            WorldBookEntryEntity(
                                id = entryId,
                                bookId = worldBookId,
                                comment = comment,
                                keysJson = keysJson,
                                content = content,
                                constant = constant,
                                disable = disable,
                                order = order
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("CharacterImporter", "Failed to parse entry", e)
                            null
                        }
                    }
                }
            } else {
                // 兼容旧格式
                worldBookId = jsonObject["worldBookId"]?.jsonPrimitive?.content ?: ""
            }

            // 高级字段（CharacterData）
            val systemPrompt = getText("system_prompt")
            val postHistoryInstructions = getText("post_history_instructions")
            val creatorNotes = getText("creator_notes")
            val creator = getText("creator")
            val characterVersion = getText("character_version").ifBlank { "1.0" }

            // 构建 CharacterData JSON
            val characterData = CharacterData(
                systemPrompt = systemPrompt,
                postHistoryInstructions = postHistoryInstructions,
                creatorNotes = creatorNotes,
                creator = creator,
                characterVersion = characterVersion
            )
            val dataJson = json.encodeToString(CharacterData.serializer(), characterData)

            val characterEntity = CharacterEntity(
                id = id,
                name = name,
                role = "assistant",
                description = description,
                personality = personality,
                scenario = scenario,
                firstMes = firstMes,
                mesExample = mesExample,
                color = color,
                avatarData = avatarData,
                worldBookId = worldBookId,
                dataJson = dataJson
            )

            CharacterImportResult(
                character = characterEntity,
                worldBook = worldBookEntity,
                worldBookEntries = worldBookEntries
            )
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "Import failed", e)
            e.printStackTrace()
            null
        }
    }
}
