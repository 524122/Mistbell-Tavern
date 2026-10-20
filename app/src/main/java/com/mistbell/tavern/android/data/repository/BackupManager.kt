package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.clearIndex
import com.mistbell.tavern.android.data.vector.exportIndexJson
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量备份/恢复（SETTINGS.md S4）：单 zip 打包全部 Room 表 + 主题包资产文件 + 向量库文件。
 *
 * 设计要点：
 * - **敏感字段明文进包**：settings 表中 SecureStore 密文（llm_api_key / providers_json 等）
 *   导出时解密为明文并标记 sensitive，恢复时重新 wrap——密文绑设备 Keystore，跨机恢复必失败，
 *   明文进用户自选位置的 zip 是用户显式持有的导出物（与导出角色卡同级）。
 * - **恢复为合并且跳过已存在主键**：不删本地数据，重复恢复/跨机合并不产生重复与丢失。
 * - 恢复全程单事务落库；主题资产在事务提交后铺入，向量索引在事务提交后**原子替换并重载**。
 * - 不导出 Room 的 `vector_memory` 表：它只存元数据、不含向量，无法重建索引，作为"第二份
 *   向量数据"只会与权威索引（`vector_store.json`）不一致。表的定义保留只为 schema 稳定。
 *
 * @param resetGate 记忆链闸门（默认生产闸门）：导出与恢复直接读写记忆库，清零未完成即失败
 *   （异常原样上抛，UI 提示失败），绝不把待清零的旧状态打包出去或恢复进来。
 */
@Suppress("TooManyFunctions")
class BackupManager(
    private val context: Context,
    private val resetGate: suspend () -> Unit =
        TavernApplication.instance.container.sessionMemoryResetGate,
) {
    private val db get() = TavernApplication.instance.container.database

    // 结构化记忆合并段（可注入 db 的独立组件，instrumented 测试直接钉合并契约）；
    // 保持与 db 同级的惰性：每次访问现场构造，不在 BackupManager 初始化期触发 container 建库
    private val memoryRestoreMerger get() = StructuredMemoryRestoreMerger(db)

    /** 备份结果摘要（导出与恢复共用，UI 直接拼提示文案） */
    @Serializable
    data class BackupSummary(
        val added: Map<String, Int> = emptyMap(),
        val skipped: Map<String, Int> = emptyMap(),
    ) {
        val totalAdded: Int get() = added.values.sum()
        val totalSkipped: Int get() = skipped.values.sum()
    }

    @Serializable
    data class BackupManifest(
        val format: Int,
        val appVersion: String,
        val createdAt: String,
        val counts: Map<String, Int> = emptyMap(),
    )

    /** settings 表条目：sensitive=true 的值在包内为明文，恢复时重新加密落库 */
    @Serializable
    data class SettingsBackupEntry(
        val key: String,
        val value: String,
        val sensitive: Boolean = false,
    )

    /** 十张业务表的完整快照（导出采集 / 恢复解码共用结构） */
    private data class Tables(
        val characters: List<CharacterEntity> = emptyList(),
        val sessions: List<SessionEntity> = emptyList(),
        val messages: List<MessageEntity> = emptyList(),
        val memories: List<MemoryEntity> = emptyList(),
        val worldBooks: List<WorldBookEntity> = emptyList(),
        val worldBookEntries: List<WorldBookEntryEntity> = emptyList(),
        val settings: List<SettingsBackupEntry> = emptyList(),
        val structuredMemories: List<StructuredMemoryEntity> = emptyList(),
        val themePacks: List<ThemePackEntity> = emptyList(),
    )

    companion object {
        const val FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val CHARACTERS = "characters.json"
        private const val SESSIONS = "sessions.json"
        private const val MESSAGES = "messages.json"
        private const val MEMORIES = "memories.json"
        private const val WORLD_BOOKS = "world_books.json"
        private const val WORLD_BOOK_ENTRIES = "world_book_entries.json"
        private const val SETTINGS = "settings.json"
        private const val STRUCTURED_MEMORY = "structured_memory.json"
        private const val THEME_PACKS = "theme_packs.json"

        /** 与 [InMemoryVectorStore.FILE_NAME] 同源——向量索引只有一个权威文件，禁止两处手写字面量 */
        internal const val VECTOR_STORE_FILE = InMemoryVectorStore.FILE_NAME
        internal const val THEMES_DIR_PREFIX = "themes/"

        internal val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        // ---- 纯函数（JVM 可测）：清单校验与容错列表解码 ----

        /**
         * 校验并解析备份清单；格式不符抛 IllegalArgumentException（中文消息直出 UI）。
         * 广捕异常：解析边界把任何底层异常统一转换为用户可懂的恢复提示，原始异常类型无意义。
         * 注意 kotlinx SerializationException 继承 IllegalArgumentException——解码必须独立 try，
         * 否则解析失败会被"校验分支"的 catch 原样上抛，用户看到的是底层英文报错。
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun parseManifest(text: String): BackupManifest {
            val m =
                try {
                    json.decodeFromString(BackupManifest.serializer(), text)
                } catch (e: Exception) {
                    throw IllegalArgumentException("备份清单解析失败：不是有效的 Mistbell 备份文件（${e.message ?: "格式错误"}）")
                }
            validateFormat(m.format)
            return m
        }

        /** 备份格式版本校验：高于支持版本提示升级，非法值提示文件损坏（require → IllegalArgumentException） */
        private fun validateFormat(format: Int) {
            require(format <= FORMAT_VERSION) { "备份格式（v$format）高于当前应用支持的 v$FORMAT_VERSION，请升级应用后再恢复" }
            require(format >= 1) { "备份清单格式非法（format=$format），文件可能已损坏" }
        }

        /**
         * 容错列表解码：整体按数组逐元素解码，坏元素跳过而不是整表丢弃——
         * 恢复是数据抢救场景，单条损坏不应牺牲其余全部数据。
         * 任何异常都按"该表无可恢复数据"处理（空表是安全缺省，错误本身已在恢复入口校验清单时把关）。
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun <T> decodeList(
            text: String?,
            serializer: KSerializer<T>,
        ): List<T> =
            if (text.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val array = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
                    array.mapNotNull { element ->
                        runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
                    }
                }.getOrDefault(emptyList())
            }

        /** settings 导出条目：密文解为明文并标记 sensitive（明文原样直通） */
        fun toBackupEntry(entity: SettingsEntity): SettingsBackupEntry =
            if (SecureStore.isEncrypted(entity.value)) {
                SettingsBackupEntry(entity.key, SecureStore.unwrap(entity.value), sensitive = true)
            } else {
                SettingsBackupEntry(entity.key, entity.value)
            }

        /** settings 恢复条目：sensitive 值重新加密落库 */
        fun toSettingsEntity(entry: SettingsBackupEntry): SettingsEntity =
            SettingsEntity(
                entry.key,
                if (entry.sensitive) SecureStore.wrap(entry.value) else entry.value,
            )
    }

    // ---- 导出 ----

    suspend fun exportToUri(uri: Uri): BackupSummary =
        withContext(Dispatchers.IO) {
            resetGate()
            check(TavernApplication.instance.container.vectorStore.flush()) {
                "备份失败：向量索引尚未成功落盘，请稍后重试"
            }
            val tables = collectTables()
            val counts = tableCounts(tables)
            val manifest =
                BackupManifest(
                    format = FORMAT_VERSION,
                    appVersion = com.mistbell.tavern.android.BuildConfig.VERSION_NAME,
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                    counts = counts,
                )

            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    zip.putJsonEntry(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
                    zip.putJsonEntry(CHARACTERS, encodeList(tables.characters, CharacterEntity.serializer()))
                    zip.putJsonEntry(SESSIONS, encodeList(tables.sessions, SessionEntity.serializer()))
                    zip.putJsonEntry(MESSAGES, encodeList(tables.messages, MessageEntity.serializer()))
                    zip.putJsonEntry(MEMORIES, encodeList(tables.memories, MemoryEntity.serializer()))
                    zip.putJsonEntry(WORLD_BOOKS, encodeList(tables.worldBooks, WorldBookEntity.serializer()))
                    zip.putJsonEntry(
                        WORLD_BOOK_ENTRIES,
                        encodeList(tables.worldBookEntries, WorldBookEntryEntity.serializer()),
                    )
                    zip.putJsonEntry(SETTINGS, encodeList(tables.settings, SettingsBackupEntry.serializer()))
                    zip.putJsonEntry(
                        STRUCTURED_MEMORY,
                        encodeList(tables.structuredMemories, StructuredMemoryEntity.serializer()),
                    )
                    zip.putJsonEntry(THEME_PACKS, encodeList(tables.themePacks, ThemePackEntity.serializer()))

                    // 主题包资产与向量索引（可选条目；缺失不报错）。
                    // 不再导出 Room 的 vector_memory 表：它只存元数据、不含向量，无法重建索引，
                    // 作为"第二份向量数据"只会与权威索引不一致（见 writeAuxiliaryFiles 说明）。
                    tables.themePacks.forEach { pack -> putThemeAssets(zip, context, pack) }
                    // 向量索引导出走 store API：存储引擎可能是 sqlite-vec（vector_index.db），
                    // 磁盘上不再有 vector_store.json 可读；zip 条目名与线格式不变
                    // （见 VectorIndexJson.kt），新旧引擎的备份可以互相恢复
                    runCatching {
                        TavernApplication.instance.container.vectorMemoryService.exportIndexJson()
                    }.getOrNull()?.let { indexJson ->
                        zip.putJsonEntry(VECTOR_STORE_FILE, indexJson)
                    }
                }
            } ?: throw IllegalArgumentException("备份失败：无法写入所选位置")

            BackupSummary(added = counts)
        }

    private suspend fun collectTables(): Tables {
        val characters = db.characterDao().getAllOnce()
        val sessions = db.sessionDao().getAllOnce()
        val messages = db.messageDao().getAllOnce()
        val memories = db.memoryDao().getAllOnce()
        val worldBooks = db.worldBookDao().getAllBooksOnce()
        val worldBookEntries = worldBooks.flatMap { db.worldBookDao().getEntriesList(it.id) }
        val settings = db.settingsDao().getAll().first().map { toBackupEntry(it) }
        return Tables(
            characters = characters,
            sessions = sessions,
            messages = messages,
            memories = memories,
            worldBooks = worldBooks,
            worldBookEntries = worldBookEntries,
            settings = settings,
            structuredMemories = db.structuredMemoryDao().getAllOnce(),
            themePacks = db.themePackDao().observeAll().first(),
        )
    }

    // ---- 恢复（合并语义：已存在主键跳过，不删本地数据） ----

    suspend fun restoreFromUri(uri: Uri): BackupSummary =
        withContext(Dispatchers.IO) {
            resetGate()
            val files = readBackupZip(uri)
            val manifestText =
                files[MANIFEST]
                    ?: throw IllegalArgumentException("恢复失败：备份中缺少 manifest.json，不是有效的 Mistbell 备份")
            parseManifest(manifestText)

            val tables = decodeTables(files)
            val summary = mergeTables(tables)

            // Room 是合并恢复。若本机存在备份之外的消息/记忆，直接导入备份索引会把两者弄成
            // 两套事实；此时清空旧索引并从合并后的 Room 重建当前消息索引。
            writeAuxiliaryFiles(files, tables.themePacks, shouldRebuildIndex(tables))
            TavernApplication.instance.container.vectorSyncRecovery.recoverDue()
            summary
        }

    private fun decodeTables(files: Map<String, String>): Tables =
        Tables(
            characters = decodeList(files[CHARACTERS], CharacterEntity.serializer()),
            sessions = decodeList(files[SESSIONS], SessionEntity.serializer()),
            messages = decodeList(files[MESSAGES], MessageEntity.serializer()),
            memories = decodeList(files[MEMORIES], MemoryEntity.serializer()),
            worldBooks = decodeList(files[WORLD_BOOKS], WorldBookEntity.serializer()),
            worldBookEntries = decodeList(files[WORLD_BOOK_ENTRIES], WorldBookEntryEntity.serializer()),
            settings = decodeList(files[SETTINGS], SettingsBackupEntry.serializer()),
            structuredMemories = decodeList(files[STRUCTURED_MEMORY], StructuredMemoryEntity.serializer()),
            themePacks = decodeList(files[THEME_PACKS], ThemePackEntity.serializer()),
        )

    /** 全部表在同一事务合并：要么全部成功，要么回到恢复前状态 */
    private suspend fun mergeTables(t: Tables): BackupSummary {
        val added = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()
        db.withTransaction {
            val (newCharacters, dupCharacters) = t.characters.partition { db.characterDao().getById(it.id) == null }
            db.characterDao().upsertAll(newCharacters)
            added[CHARACTERS] = newCharacters.size
            skipped[CHARACTERS] = dupCharacters.size

            val (newSessions, dupSessions) = t.sessions.partition { db.sessionDao().getById(it.id) == null }
            newSessions.forEach { db.sessionDao().upsert(it) }
            added[SESSIONS] = newSessions.size
            skipped[SESSIONS] = dupSessions.size

            val (newMessages, dupMessages) =
                t.messages.partition { db.messageDao().getById(it.id, it.sessionId, it.ownerId) == null }
            db.messageDao().upsertAll(newMessages)
            added[MESSAGES] = newMessages.size
            skipped[MESSAGES] = dupMessages.size

            val (newMemories, dupMemories) = t.memories.partition { db.memoryDao().getById(it.id) == null }
            db.memoryDao().upsertAll(newMemories)
            added[MEMORIES] = newMemories.size
            skipped[MEMORIES] = dupMemories.size

            val (newBooks, dupBooks) = t.worldBooks.partition { db.worldBookDao().getById(it.id) == null }
            newBooks.forEach { db.worldBookDao().upsertBook(it) }
            added[WORLD_BOOKS] = newBooks.size
            skipped[WORLD_BOOKS] = dupBooks.size

            val (newEntries, dupEntries) =
                t.worldBookEntries.partition { db.worldBookDao().getEntryById(it.id) == null }
            db.worldBookDao().upsertEntries(newEntries)
            added[WORLD_BOOK_ENTRIES] = newEntries.size
            skipped[WORLD_BOOK_ENTRIES] = dupEntries.size

            // settings：备份值显式胜出（用户点了"恢复"即表达此意图）；敏感值重加密
            t.settings.forEach { db.settingsDao().upsert(toSettingsEntity(it)) }
            added[SETTINGS] = t.settings.size

            // 结构化记忆：归属校验 + 两阶段冲突插入 + 签名查重，见 StructuredMemoryRestoreMerger
            // 类头契约注释（mergeTables 与 shouldRebuildIndex 共用同组件，判定必须同源）。
            val memoryMerge = memoryRestoreMerger.merge(t.structuredMemories)
            added[STRUCTURED_MEMORY] = memoryMerge.added
            skipped[STRUCTURED_MEMORY] = memoryMerge.duplicated + memoryMerge.unattachable

            val (newPacks, dupPacks) = t.themePacks.partition { db.themePackDao().getById(it.id) == null }
            newPacks.forEach { db.themePackDao().upsert(it) }
            added[THEME_PACKS] = newPacks.size
            skipped[THEME_PACKS] = dupPacks.size
        }
        return BackupSummary(added = added, skipped = skipped.filterValues { it > 0 })
    }

    private suspend fun writeAuxiliaryFiles(
        files: Map<String, String>,
        themePacks: List<ThemePackEntity>,
        rebuildIndex: Boolean,
    ) {
        themePacks.forEach { pack -> writeThemeAssetsIfAbsent(context, pack, files) }
        if (rebuildIndex) {
            rebuildCurrentMessageIndex()
            return
        }
        val vectorStoreEntry = files[VECTOR_STORE_FILE]
        if (vectorStoreEntry == null) {
            // 备份没有索引条目时，不能保留本机旧索引；它可能包含已经不存在的消息。
            rebuildCurrentMessageIndex()
            return
        }
        check(TavernApplication.instance.container.vectorStore.importIndex(vectorStoreEntry)) {
            "备份恢复失败：向量索引无法导入"
        }
    }

    /**
     * 合并恢复后只在业务集合完全一致时直接使用备份索引，否则按当前 Room 重建。
     *
     * "实际 Room 内容偏离备份"（归属过滤丢弃、同 id 冲突重插、内容签名不同）一律强制重建：
     * 恢复完成后的索引必须只来自最终有效 Room 数据——备份自带的索引文件可能仍含被丢弃
     * 记忆的向量（含升级前 "cross_session" 哨兵），importIndex 会让它们复活。
     */
    private suspend fun shouldRebuildIndex(tables: Tables): Boolean {
        val currentMessages = db.messageDao().getAllOnce().associateBy { it.id }
        val backupMessages = tables.messages.associateBy { it.id }
        val currentMemories = db.structuredMemoryDao().getAllOnce().associateBy { it.id }
        val (attachable, unattachable) =
            tables.structuredMemories.partition { memoryRestoreMerger.isAttachableMemory(it) }
        val backupMemories = attachable.associateBy { it.id }
        val backupDeviant = unattachable.isNotEmpty()
        val messageContentChanged =
            currentMessages.keys == backupMessages.keys &&
                currentMessages.any { (id, value) ->
                    messageSignature(value) != messageSignature(backupMessages.getValue(id))
                }
        val memoryContentChanged =
            currentMemories.keys == backupMemories.keys &&
                currentMemories.any { (id, value) ->
                    StructuredMemoryRestoreMerger.signature(value) !=
                        StructuredMemoryRestoreMerger.signature(backupMemories.getValue(id))
                }
        return backupDeviant ||
            currentMessages.keys != backupMessages.keys ||
            messageContentChanged ||
            currentMemories.keys != backupMemories.keys ||
            memoryContentChanged
    }

    private fun messageSignature(message: MessageEntity): String =
        listOf(message.sessionId, message.ownerId, message.characterId, message.role, message.content, message.thinking)
            .joinToString("\u0000")

    private suspend fun rebuildCurrentMessageIndex() {
        val service = TavernApplication.instance.container.vectorMemoryService
        service.clearIndex()
        db.messageDao().getAllOnce().map { it.ownerId }.distinct().forEach { ownerId ->
            VectorIndexRebuilder.forDatabase(db, service).rebuild(
                ownerId = ownerId,
                limit = VectorIndexRebuilder.DEFAULT_LIMIT,
            )
        }
    }

    // ---- zip 工具 ----

    private fun tableCounts(t: Tables): Map<String, Int> =
        mapOf(
            CHARACTERS to t.characters.size,
            SESSIONS to t.sessions.size,
            MESSAGES to t.messages.size,
            MEMORIES to t.memories.size,
            WORLD_BOOKS to t.worldBooks.size,
            WORLD_BOOK_ENTRIES to t.worldBookEntries.size,
            SETTINGS to t.settings.size,
            STRUCTURED_MEMORY to t.structuredMemories.size,
            THEME_PACKS to t.themePacks.size,
        )

    /**
     * 读取备份 zip 全部条目。JSON（包括向量索引）统一按 UTF-8；主题资产按 ISO-8859-1
     * 承载原始字节。Entry 名含 ".." 一律拒绝（防路径穿越，与 ThemePackRepository 同规）。
     */
    private fun readBackupZip(uri: Uri): Map<String, String> {
        val files = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    storeEntry(files, zip, entry!!)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("恢复失败：无法打开所选文件")
        return files
    }
}

// ---- 文件级私有工具（置类外避免触发 TooManyFunctions，与 SettingsRepository 先例一致） ----

private fun <T> encodeList(
    list: List<T>,
    serializer: KSerializer<T>,
): String = BackupManager.json.encodeToString(ListSerializer(serializer), list)

private fun ZipOutputStream.putJsonEntry(
    name: String,
    content: String,
) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun putThemeAssets(
    zip: ZipOutputStream,
    context: Context,
    pack: ThemePackEntity,
) {
    val dir = File(context.filesDir, "themes/${pack.id}")
    dir.listFiles()?.forEach { f ->
        zip.putNextEntry(ZipEntry("${BackupManager.THEMES_DIR_PREFIX}${pack.id}/${f.name}"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

private fun writeThemeAssetsIfAbsent(
    context: Context,
    pack: ThemePackEntity,
    files: Map<String, String>,
) {
    val dir = File(context.filesDir, "themes/${pack.id}")
    if (dir.exists()) return
    dir.mkdirs()
    files.forEach { (name, content) ->
        if (name.startsWith("${BackupManager.THEMES_DIR_PREFIX}${pack.id}/")) {
            val fileName = name.substringAfterLast('/')
            if (fileName.isNotBlank()) {
                File(dir, fileName).writeBytes(content.toByteArray(Charsets.ISO_8859_1))
            }
        }
    }
}

private fun storeEntry(
    files: MutableMap<String, String>,
    zip: ZipInputStream,
    entry: ZipEntry,
) {
    val name = entry.name
    require(name.split('/').none { it == ".." }) { "备份文件非法：条目路径包含 \"..\"（疑似路径穿越）" }
    if (entry.isDirectory) return
    val bytes = zip.readBytes()
    files[name] =
        if (name.startsWith(BackupManager.THEMES_DIR_PREFIX)) {
            String(bytes, Charsets.ISO_8859_1)
        } else {
            String(bytes, Charsets.UTF_8)
        }
}
