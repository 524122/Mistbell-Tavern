package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry

@Entity(tableName = "world_books")
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
data class WorldBookEntryEntity(
    val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val comment: String,
    @ColumnInfo(name = "keys_json") val keysJson: String,
    val content: String,
    val constant: Boolean,
    val disable: Boolean,
    val order: Int,
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

        return WorldBookEntry(
            id = id,
            comment = comment,
            key = keyList,
            content = content,
            constant = constant,
            disable = disable,
            order = order,
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
            return WorldBookEntryEntity(
                id = e.id,
                bookId = bookId,
                comment = e.comment,
                keysJson = keysStr,
                content = e.content,
                constant = e.constant,
                disable = e.disable,
                order = e.order,
            )
        }
    }
}
