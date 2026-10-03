package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
import kotlinx.serialization.Serializable

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
    // depth≥1 为 @D 模式插入历史倒数第 D 条之前，此时本字段不生效
    @ColumnInfo(name = "insert_position", defaultValue = "before_prompt") val insertPosition: String = "before_prompt",
    // @D 模式深度：0=跟随 insertPosition；1-10=插入历史倒数第 depth 条之前
    @ColumnInfo(name = "depth", defaultValue = "0") val depth: Int = 0,
    // 触发概率（v20，对齐酒馆 probability）：0-1，关键词命中后仍按概率掷骰；
    // 1=必触发（缺省），0=永不触发；constant 常驻条目不受概率影响
    @ColumnInfo(name = "probability", defaultValue = "1") val probability: Double = 1.0,
    // @D 插入角色（v21，对齐酒馆 role）：system/user/assistant——仅 depth≥1 时生效，
    // 决定该条目以什么身份插到历史倒数第 depth 条之前
    @ColumnInfo(name = "depth_role", defaultValue = "system") val depthRole: String = "system",
    @ColumnInfo(name = "secondary_keys_json", defaultValue = "") val secondaryKeysJson: String = "",
    @ColumnInfo(name = "sticky", defaultValue = "0") val sticky: Boolean = false,
    @ColumnInfo(name = "cooldown", defaultValue = "0") val cooldown: Int = 0,
    @ColumnInfo(name = "delay", defaultValue = "0") val delay: Int = 0,
    @ColumnInfo(name = "group_name", defaultValue = "") val groupName: String = "",
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
            )
        }
    }
}
