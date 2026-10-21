package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** sticky 的容错序列化器：新格式是数值（保持 N 轮），旧备份的布尔值 true/false 读作 1/0 */
object StickyFieldSerializer : kotlinx.serialization.KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("sticky", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        val element =
            (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("sticky 需要 JSON 原始值")
        return element.intOrNull ?: (if (element.booleanOrNull == true) 1 else 0)
    }
}

@Entity(tableName = "world_books")
@Serializable
data class WorldBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
) {
    fun toDomain(entries: List<WorldBookEntryEntity>): WorldBook =
        WorldBook(
            id = id,
            name = name,
            entries = entries.map { it.toDomain() },
        )
}

@Entity(
    tableName = "world_book_entries",
    primaryKeys = ["id", "book_id"],
)
@Serializable
data class WorldBookEntryEntity(
    val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val comment: String,
    @ColumnInfo(name = "keys_json") val keysJson: String,
    val content: String,
    val constant: Boolean,
    val disable: Boolean,
    val order: Int,
    // 插入位置（v19）：depth=0 时锚定提示词区——before_prompt=角色定义前，其余=角色定义后；
    // depth≥1 为 @D 模式（对齐酒馆 atDepth，深度越小越贴近生成点），此时本字段不生效
    @ColumnInfo(name = "insert_position", defaultValue = "before_prompt") val insertPosition: String = "before_prompt",
    // @D 模式深度：0=跟随 insertPosition；1-10=@D 深度（D=1 最贴近生成点；
    // 默认插进历史内部（酒馆一致），world_info_in_history=0 时后移到历史之后——见 PromptBuilder）
    @ColumnInfo(name = "depth", defaultValue = "0") val depth: Int = 0,
    // 触发概率（v20，对齐酒馆 probability）：0-1，关键词命中后仍按概率掷骰；
    // 1=必触发（缺省），0=永不触发；constant 常驻条目不受概率影响
    @ColumnInfo(name = "probability", defaultValue = "1") val probability: Double = 1.0,
    // @D 插入角色（v21，对齐酒馆 role）：system/user/assistant——仅 depth≥1 时生效，
    // 决定该条目以什么身份注入 @D 深度块
    @ColumnInfo(name = "depth_role", defaultValue = "system") val depthRole: String = "system",
    @ColumnInfo(name = "secondary_keys_json", defaultValue = "") val secondaryKeysJson: String = "",
    // 粘性轮数（v28 起对齐酒馆数值语义）：0=关闭；激活后保持 N 轮（过期后 cooldown 接力）。
    // 序列化走容错器：旧备份的布尔值 true/false 读作 1/0
    @Serializable(with = StickyFieldSerializer::class)
    @ColumnInfo(name = "sticky", defaultValue = "0") val sticky: Int = 0,
    @ColumnInfo(name = "cooldown", defaultValue = "0") val cooldown: Int = 0,
    @ColumnInfo(name = "delay", defaultValue = "0") val delay: Int = 0,
    @ColumnInfo(name = "group_name", defaultValue = "") val groupName: String = "",
    // 预算豁免（v29，对齐酒馆 ignoreBudget）：true 时不占 WI token 预算、溢出也不被裁
    @ColumnInfo(name = "ignore_budget", defaultValue = "0") val ignoreBudget: Boolean = false,
    // 分组权重（v30，对齐酒馆 groupWeight，默认 100）：同组竞争时加权随机
    @ColumnInfo(name = "group_weight", defaultValue = "100") val groupWeight: Int = 100,
    // 分组覆盖（v30，对齐酒馆 groupOverride）：同组竞争时覆盖条目直接按最高 order 胜出
    @ColumnInfo(name = "group_override", defaultValue = "0") val groupOverride: Boolean = false,
    // 递归控制（v31，对齐酒馆）：excludeRecursion=内容不进递归缓冲；preventRecursion=只能首轮
    // 直接激活（递归轮不命中）；delayUntilRecursion=第 N 递归层级起才允许激活（0=不限）
    @ColumnInfo(name = "exclude_recursion", defaultValue = "0") val excludeRecursion: Boolean = false,
    @ColumnInfo(name = "prevent_recursion", defaultValue = "0") val preventRecursion: Boolean = false,
    @ColumnInfo(name = "delay_until_recursion", defaultValue = "0") val delayUntilRecursion: Int = 0,
    // 扫描面扩展（v31，对齐酒馆 matchXxx）：触发时把人设/角色描述/场景并条目入扫描文本
    @ColumnInfo(name = "match_persona_description", defaultValue = "0")
    val matchPersonaDescription: Boolean = false,

    @ColumnInfo(name = "match_character_description", defaultValue = "0")
    val matchCharacterDescription: Boolean = false,

    @ColumnInfo(name = "match_scenario", defaultValue = "0")
    val matchScenario: Boolean = false,
    // 次键门控开关（v28，对齐酒馆 selective）：true 且次键非空时才启用 selectiveLogic 评估
    @ColumnInfo(name = "selective", defaultValue = "1") val selective: Boolean = true,
    // 次关键词四态逻辑（v28，对齐酒馆 selectiveLogic）：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
    @ColumnInfo(name = "selective_logic", defaultValue = "0") val selectiveLogic: Int = 0,
    // 大小写敏感（v28，对齐酒馆 caseSensitive）：null=关（酒馆 null=跟随全局；应用内不设全局开关）
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean? = null,
    // 全字匹配（v28，对齐酒馆 matchWholeWords）：null=关；单词 key 用非词边界，多词 key 普通包含
    @ColumnInfo(name = "match_whole_words") val matchWholeWords: Boolean? = null,
    // 扫描深度（v28，对齐酒馆 scanDepth）：null=跟随全局设置 world_info_scan_depth（默认 2）
    @ColumnInfo(name = "scan_depth") val scanDepth: Int? = null,
) {
    fun toDomain(): WorldBookEntry {
        val keyList =
            try {
                if (keysJson.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(keysJson)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        val secondaryKeyList =
            try {
                if (secondaryKeysJson.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(secondaryKeysJson)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

        return WorldBookEntry(
            id = id,
            comment = comment,
            key = keyList,
            content = content,
            constant = constant,
            disable = disable,
            insertPosition = insertPosition,
            depth = depth,
            probability = probability,
            depthRole = depthRole,
            order = order,
            secondaryKeys = secondaryKeyList,
            sticky = sticky,
            cooldown = cooldown,
            delay = delay,
            groupName = groupName,
            selective = selective,
            selectiveLogic = selectiveLogic,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            scanDepth = scanDepth,
            ignoreBudget = ignoreBudget,
            groupWeight = groupWeight,
            groupOverride = groupOverride,
            excludeRecursion = excludeRecursion,
            preventRecursion = preventRecursion,
            delayUntilRecursion = delayUntilRecursion,
            matchPersonaDescription = matchPersonaDescription,
            matchCharacterDescription = matchCharacterDescription,
            matchScenario = matchScenario,
        )
    }

    companion object {
        fun fromDomain(
            e: WorldBookEntry,
            bookId: String,
        ): WorldBookEntryEntity {
            val json = kotlinx.serialization.json.Json
            val stringListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            val keysStr = json.encodeToString(stringListSerializer, e.key)
            val secondaryKeysStr = json.encodeToString(stringListSerializer, e.secondaryKeys)
            return WorldBookEntryEntity(
                id = e.id,
                bookId = bookId,
                comment = e.comment,
                keysJson = keysStr,
                content = e.content,
                constant = e.constant,
                disable = e.disable,
                insertPosition = e.insertPosition,
                depth = e.depth,
                probability = e.probability,
                depthRole = e.depthRole,
                order = e.order,
                secondaryKeysJson = secondaryKeysStr,
                sticky = e.sticky,
                cooldown = e.cooldown,
                delay = e.delay,
                groupName = e.groupName,
                selective = e.selective,
                selectiveLogic = e.selectiveLogic,
                caseSensitive = e.caseSensitive,
                matchWholeWords = e.matchWholeWords,
                scanDepth = e.scanDepth,
                ignoreBudget = e.ignoreBudget,
                groupWeight = e.groupWeight,
                groupOverride = e.groupOverride,
                excludeRecursion = e.excludeRecursion,
                preventRecursion = e.preventRecursion,
                delayUntilRecursion = e.delayUntilRecursion,
                matchPersonaDescription = e.matchPersonaDescription,
                matchCharacterDescription = e.matchCharacterDescription,
                matchScenario = e.matchScenario,
            )
        }
    }
}
