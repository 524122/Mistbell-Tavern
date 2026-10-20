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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class WorldBookRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database
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
        // 完全本地化：无服务器依赖，此方法保留为空以兼容现有调用点（刷新按钮等）
    }

    suspend fun createWorldBook(name: String): WorldBook? {
        return withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val entity = WorldBookEntity(id = id, name = name, settingsJson = "{}")
            db.worldBookDao().upsertBook(entity)
            WorldBook(id = id, name = name, entries = emptyList())
        }
    }

    suspend fun deleteWorldBook(id: String) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().deleteEntriesByBookId(id)
            db.worldBookDao().deleteBookById(id)
        }
    }

    suspend fun createEntry(
        bookId: String,
        draft: WorldBookEntryDraft,
    ): WorldBookEntry? {
        return withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val stringListSerializer =
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            val entity =
                WorldBookEntryEntity(
                    id = id,
                    bookId = bookId,
                    comment = draft.comment,
                    keysJson = Json.encodeToString(stringListSerializer, draft.keys),
                    content = draft.content,
                    constant = draft.constant,
                    disable = draft.disable,
                    insertPosition = draft.insertPosition,
                    depth = draft.depth,
                    probability = draft.probability,
                    depthRole = draft.depthRole,
                    order = 100,
                    secondaryKeysJson = Json.encodeToString(stringListSerializer, draft.secondaryKeys),
                    selective = draft.selective,
                    selectiveLogic = draft.selectiveLogic,
                    caseSensitive = draft.caseSensitive,
                    matchWholeWords = draft.matchWholeWords,
                    scanDepth = draft.scanDepth,
                )
            db.worldBookDao().upsertEntries(listOf(entity))
            entity.toDomain()
        }
    }

    suspend fun updateEntry(
        entryId: String,
        patch: JsonObject,
    ) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().getEntryById(entryId)?.let { existing ->
                db.worldBookDao().upsertEntries(listOf(existing.applyPatch(patch)))
            }
        }
    }

    suspend fun deleteEntry(entryId: String) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().deleteEntryById(entryId)
        }
    }

    suspend fun exportWorldBook(): JsonElement? {
        return withContext(Dispatchers.IO) {
            try {
                api.exportWorldBook()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 将 JSON patch 应用到实体（本地优先写入时用于更新已有条目）。
     * 只覆盖 patch 中实际提供的字段；insertPosition/depth 自 v19 起已落库，随 patch 更新。
     */
    private fun WorldBookEntryEntity.applyPatch(patch: JsonObject): WorldBookEntryEntity {
        val keysJson = patchStringList(patch["key"], keysJson)
        return copy(
            comment = patch["comment"]?.jsonPrimitive?.content ?: comment,
            content = patch["content"]?.jsonPrimitive?.content ?: content,
            constant = patch["constant"]?.jsonPrimitive?.booleanOrNull ?: constant,
            disable = patch["disable"]?.jsonPrimitive?.booleanOrNull ?: disable,
            order = patch["order"]?.jsonPrimitive?.intOrNull ?: order,
            insertPosition = patch["insertPosition"]?.jsonPrimitive?.content ?: insertPosition,
            depth = patch["depth"]?.jsonPrimitive?.intOrNull ?: depth,
            probability = patch["probability"]?.jsonPrimitive?.doubleOrNull ?: probability,
            depthRole = patch["depthRole"]?.jsonPrimitive?.content ?: depthRole,
            keysJson = keysJson,
        ).applyTriggeringPatch(patch)
    }
}

/** 触发语义字段（v28）的 patch：次级词/四态逻辑/大小写/全字/扫描深度 */
private fun WorldBookEntryEntity.applyTriggeringPatch(patch: JsonObject): WorldBookEntryEntity =
    copy(
        secondaryKeysJson = patchStringList(patch["secondaryKeys"], secondaryKeysJson),
        selective = patch["selective"]?.jsonPrimitive?.booleanOrNull ?: selective,
        selectiveLogic = patch["selectiveLogic"]?.jsonPrimitive?.intOrNull ?: selectiveLogic,
        // 可空字段三态：patch 缺省=保持；JsonNull=清空（跟随全局）；布尔=显式值
        caseSensitive = nullableBooleanPatch(patch["caseSensitive"], caseSensitive),
        matchWholeWords = nullableBooleanPatch(patch["matchWholeWords"], matchWholeWords),
        scanDepth = nullableIntPatch(patch["scanDepth"], scanDepth),
    )

/** patch 中的字符串数组字段 → 实体 keysJson 存储格式；缺省保持原值 */
private fun patchStringList(
    element: JsonElement?,
    currentJson: String,
): String =
    (element as? JsonArray)?.let { arr ->
        Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            arr.map { it.jsonPrimitive.content },
        )
    } ?: currentJson

/** 可空布尔的三态 patch：缺省=保持；JsonNull=清空；布尔=显式值 */
private fun nullableBooleanPatch(
    element: JsonElement?,
    current: Boolean?,
): Boolean? =
    when (element) {
        null -> current
        is JsonNull -> null
        else -> element.jsonPrimitive.booleanOrNull ?: current
    }

/** 可空整数的三态 patch：缺省=保持；JsonNull=清空；整数=显式值 */
private fun nullableIntPatch(
    element: JsonElement?,
    current: Int?,
): Int? =
    when (element) {
        null -> current
        is JsonNull -> null
        else -> element.jsonPrimitive.intOrNull ?: current
    }

/**
 * 新建世界书条目的字段草稿（createEntry 的参数对象，防参数表随字段膨胀）。
 * 默认值与实体/酒馆条目模板一致。
 */
data class WorldBookEntryDraft(
    val comment: String,
    val keys: List<String>,
    val content: String,
    val constant: Boolean = false,
    val disable: Boolean = false,
    val insertPosition: String = "before_prompt",
    val depth: Int = 0,
    val probability: Double = 1.0,
    val depthRole: String = "system",
    val secondaryKeys: List<String> = emptyList(),
    val selective: Boolean = true,
    val selectiveLogic: Int = 0,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val scanDepth: Int? = null,
)
