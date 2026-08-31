package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.api.model.CharacterData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.UUID

data class CharacterImportResult(
    val character: CharacterEntity,
    val worldBook: WorldBookEntity?,
    val worldBookEntries: List<WorldBookEntryEntity>,
    // 导入诊断：非阻断性提示（如未映射字段、v1 老格式等），错误不阻断导入
    val warnings: List<String> = emptyList()
)

/**
 * 角色卡 JSON 解析纯核心（无 Android 依赖，可直接单元测试）。
 * 支持 SillyTavern 卡片 v2（data/root 双位读取）与 v1 老键名兜底。
 */
object CardParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析卡片 JSON 字符串为导入结果；结构非法返回 null。
     */
    fun parse(jsonString: String): CharacterImportResult? {
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject
            val dataObject = jsonObject["data"]?.jsonObject

            // 导入诊断收集（去重、保持插入顺序；错误不阻断导入）
            val warnings = linkedSetOf<String>()

            // v1 老键名来源检测（char_name 等仅存在于 v1 卡片根上）
            val v1Keys = listOf("char_name", "char_persona", "world_scenario", "char_greeting", "example_dialogue")
            if (v1Keys.any { jsonObject.containsKey(it) }) {
                warnings.add("检测到 v1 老格式卡片")
            }

            // 辅助函数：v2 优先 data 位，其次 root 位（v1 卡片字段在根上）
            fun getText(vararg keys: String): String {
                for (key in keys) {
                    dataObject?.get(key)?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                    jsonObject[key]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                }
                return ""
            }

            // v1 老键名兜底：char_name/char_persona/world_scenario/char_greeting/example_dialogue/world
            val name = getText("name", "char_name").ifBlank { "未命名角色" }
            val description = getText("description", "desc", "char_persona")
            val personality = getText("personality")
            val scenario = getText("scenario", "world_scenario", "world")
            val firstMes = getText("first_mes", "greeting", "char_greeting")
            val mesExample = getText("mes_example", "example_dialogue", "example")

            // 颜色 / 头像数据（app 私有字段，存在则保留）
            val color = getText("color").ifBlank { "#007AFF" }
            val avatarData = getText("avatarData", "avatar")

            // extensions 透传保真：优先 data 位，其次 root 位
            val extensions = dataObject?.get("extensions")?.jsonObject
                ?: jsonObject["extensions"]?.jsonObject
            // extensions 非空时提示已透传的扩展命名空间数量
            if (extensions != null && extensions.isNotEmpty()) {
                warnings.add("已透传 ${extensions.size} 个扩展命名空间")
            }

            // alternate_greetings / tags 提取进 CharacterData
            val alternateGreetings = buildList {
                val arr = dataObject?.get("alternate_greetings")?.jsonArray
                    ?: jsonObject["alternate_greetings"]?.jsonArray
                arr?.forEach { add(it.jsonPrimitive.contentOrNull ?: "") }
            }
            val tags = buildList {
                val arr = dataObject?.get("tags")?.jsonArray
                    ?: jsonObject["tags"]?.jsonArray
                arr?.forEach { add(it.jsonPrimitive.contentOrNull ?: "") }
            }

            // 解析内嵌世界书
            var worldBookEntity: WorldBookEntity? = null
            var worldBookEntries = emptyList<WorldBookEntryEntity>()
            var worldBookId = ""

            // V2 格式：data.character_book；V1 格式：character_book 在根上
            val characterBook = dataObject?.get("character_book")?.jsonObject
                ?: jsonObject["character_book"]?.jsonObject

            if (characterBook != null) {
                worldBookId = UUID.randomUUID().toString()
                val bookName = characterBook["name"]?.jsonPrimitive?.content ?: "$name 的世界书"

                worldBookEntity = WorldBookEntity(
                    id = worldBookId,
                    name = bookName,
                    settingsJson = "{}"
                )

                worldBookEntries = parseBookEntries(
                    entriesElement = characterBook["entries"],
                    bookId = worldBookId,
                    warnings = warnings
                )
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

            // 构建 CharacterData（带生态互通字段）
            val characterData = CharacterData(
                systemPrompt = systemPrompt,
                postHistoryInstructions = postHistoryInstructions,
                creatorNotes = creatorNotes,
                creator = creator,
                characterVersion = characterVersion,
                alternateGreetings = alternateGreetings,
                tags = tags,
                extensions = extensions
            )
            val dataJson = json.encodeToString(CharacterData.serializer(), characterData)

            val characterEntity = CharacterEntity(
                id = UUID.randomUUID().toString(),
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
                worldBookEntries = worldBookEntries,
                warnings = warnings.toList()
            )
        } catch (e: Exception) {
            // 纯函数不依赖 android.util.Log（JVM 单测环境不可用）；薄壳层负责日志
            null
        }
    }

    /**
     * 解析世界书条目，兼容【数组】与【按 uid 的 map】两种形态。
     * 字段映射规则：enabled 存在时 disable = !enabled；insertion_order 优先于 order；
     * key/keysecondary 单字符串包成数组（keysecondary 暂不映射，收集进 warnings 诊断）。
     */
    internal fun parseBookEntries(
        entriesElement: kotlinx.serialization.json.JsonElement?,
        bookId: String,
        warnings: MutableSet<String> = mutableSetOf()
    ): List<WorldBookEntryEntity> {
        if (entriesElement == null) return emptyList()
        val entryObjects: List<Pair<String?, JsonObject>> = when (entriesElement) {
            is JsonArray -> entriesElement.mapNotNull { el ->
                (el as? JsonObject)?.let { null to it }
            }
            is JsonObject -> entriesElement.map { (uid, el) ->
                (el as? JsonObject)?.let { uid to it } ?: (uid to JsonObject(emptyMap()))
            }.filter { it.second.isNotEmpty() }
            else -> return emptyList()
        }
        return entryObjects.mapNotNull { (uid, entry) ->
            try {
                // 条目 id：优先 uid（map 形态的键），其次条目内 id/uid 字段，最后 UUID
                val entryId = uid
                    ?: entry["uid"]?.jsonPrimitive?.contentOrNull
                    ?: entry["id"]?.jsonPrimitive?.contentOrNull
                    ?: UUID.randomUUID().toString()
                val comment = entry["comment"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = entry["content"]?.jsonPrimitive?.contentOrNull ?: ""
                val constant = entry["constant"]?.jsonPrimitive?.booleanOrNull ?: false
                // enabled(布尔)存在时反相为 disable（生态主流字段）；否则退回旧 disable 字段
                val enabled = entry["enabled"]?.jsonPrimitive?.booleanOrNull
                val disable = enabled?.not()
                    ?: entry["disable"]?.jsonPrimitive?.booleanOrNull
                    ?: false
                // insertion_order 优先于 order
                val order = entry["insertion_order"]?.jsonPrimitive?.intOrNull
                    ?: entry["order"]?.jsonPrimitive?.intOrNull
                    ?: 0

                // keys 单字符串包成数组
                val keys = when (val keysElement = entry["key"] ?: entry["keys"]) {
                    is JsonArray -> keysElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(keysElement.contentOrNull)
                    else -> emptyList()
                }
                // TODO: keysecondary（次要关键字）暂不映射，待后续支持
                // 诊断：条目含 keysecondary / position / probability / depth 等未映射字段时收集提示（不阻断导入）
                if (entry.containsKey("keysecondary")) {
                    warnings.add("次级关键词暂未映射，已忽略")
                }
                val unmappedFields = listOf("position", "probability", "depth").filter { entry.containsKey(it) }
                if (unmappedFields.isNotEmpty()) {
                    warnings.add("世界书条目包含未映射字段：${unmappedFields.joinToString("/")}")
                }
                val keysJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                    keys
                )

                WorldBookEntryEntity(
                    id = entryId,
                    bookId = bookId,
                    comment = comment,
                    keysJson = keysJson,
                    content = content,
                    constant = constant,
                    disable = disable,
                    order = order
                )
            } catch (_: Exception) {
                // 纯函数：跳过坏条目（与 ST 生态容错导入约定一致）
                null
            }
        }
    }
}

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
            CardParser.parse(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "读取 JSON 文件失败", e)
            null
        }
    }

    /**
     * 从 PNG 埋卡导入角色：读取 tEXt("chara") chunk → base64 解码 → 解析卡片 JSON。
     * 无埋卡（或非 PNG）返回 null。
     */
    fun importFromPng(context: Context, uri: Uri): CharacterImportResult? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val base64Text = PngCard.readTextChunk(bytes) ?: return null
            val jsonString = PngCard.decodeCardJson(base64Text) ?: return null
            CardParser.parse(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "读取 PNG 埋卡失败", e)
            null
        }
    }
}
