package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class WorldBookRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)

    fun observeWorldBooks(): Flow<List<WorldBook>> {
        return db.worldBookDao().getAll().map { bookEntities ->
            bookEntities.map { book ->
                val entries = db.worldBookDao().getEntriesList(book.id)
                book.toDomain(entries)
            }
        }
    }

    fun observeEntries(bookId: String): Flow<List<WorldBookEntry>> {
        return db.worldBookDao().getEntries(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun loadFromServer() {
        withContext(Dispatchers.IO) {
            try {
                val result = api.getWorldBook()
                if (result is JsonObject) {
                    val booksArray = result["books"] as? JsonArray ?: return@withContext
                    val bookEntities = mutableListOf<WorldBookEntity>()
                    val allEntries = mutableMapOf<String, List<WorldBookEntryEntity>>()

                    for (bookEl in booksArray) {
                        val bookObj = bookEl as? JsonObject ?: continue
                        val bookId = bookObj["id"]?.jsonPrimitive?.content ?: continue
                        val bookName = bookObj["name"]?.jsonPrimitive?.content ?: ""
                        bookEntities.add(WorldBookEntity(bookId, bookName, "{}"))

                        val entriesArray = bookObj["entries"] as? JsonArray ?: emptyList()
                        val entries = entriesArray.mapNotNull { entryEl ->
                            val entryObj = entryEl as? JsonObject ?: return@mapNotNull null
                            val entryId = entryObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val keysStr = try {
                                val keysArr = entryObj["key"] as? JsonArray ?: emptyList()
                                Json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                                    keysArr.map { it.jsonPrimitive.content }
                                )
                            } catch (_: Exception) { "[]" }

                            WorldBookEntryEntity(
                                id = entryId,
                                bookId = bookId,
                                comment = entryObj["comment"]?.jsonPrimitive?.content ?: "",
                                keysJson = keysStr,
                                content = entryObj["content"]?.jsonPrimitive?.content ?: "",
                                constant = entryObj["constant"]?.jsonPrimitive?.booleanOrNull ?: false,
                                disable = entryObj["disable"]?.jsonPrimitive?.booleanOrNull ?: false,
                                order = entryObj["order"]?.jsonPrimitive?.intOrNull ?: 100
                            )
                        }
                        allEntries[bookId] = entries
                    }
                    db.worldBookDao().replaceAll(bookEntities, allEntries)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun createWorldBook(name: String): WorldBook? {
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject { put("name", name) }
                val result = api.createWorldBook(body)
                val obj = result as? JsonObject
                val id = obj?.get("id")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                loadFromServer()
                WorldBook(id = id, name = name)
            } catch (_: Exception) { null }
        }
    }

    suspend fun deleteWorldBook(id: String) {
        withContext(Dispatchers.IO) {
            try {
                api.deleteWorldBook(id)
                loadFromServer()
            } catch (_: Exception) {}
        }
    }

    suspend fun createEntry(
        bookId: String,
        comment: String,
        keys: List<String>,
        content: String,
        constant: Boolean = false,
        disable: Boolean = false,
        insertPosition: String = "before_prompt",
        depth: Int = 1
    ): WorldBookEntry? {
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("bookId", bookId)
                    put("comment", comment)
                    put("key", Json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        keys
                    ))
                    put("content", content)
                    put("constant", constant)
                    put("disable", disable)
                    put("insertPosition", insertPosition)
                    put("depth", depth)
                }
                api.createWorldEntry(body)
                loadFromServer()
                WorldBookEntry(
                    id = UUID.randomUUID().toString(),
                    comment = comment,
                    key = keys,
                    content = content,
                    constant = constant,
                    disable = disable,
                    insertPosition = insertPosition,
                    depth = depth
                )
            } catch (_: Exception) { null }
        }
    }

    suspend fun updateEntry(entryId: String, patch: JsonObject) {
        withContext(Dispatchers.IO) {
            try {
                api.updateWorldEntry(entryId, patch)
                loadFromServer()
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteEntry(entryId: String) {
        withContext(Dispatchers.IO) {
            try {
                api.deleteWorldEntry(entryId)
                loadFromServer()
            } catch (_: Exception) {}
        }
    }

    suspend fun exportWorldBook(): JsonElement? {
        return withContext(Dispatchers.IO) {
            try {
                api.exportWorldBook()
            } catch (_: Exception) { null }
        }
    }
}
