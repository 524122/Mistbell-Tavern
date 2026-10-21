package com.mistbell.tavern.android.data.vector

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * sqlite-vec 向量存储：vec0 虚拟表（C 层精确 KNN）+ 独立 SQLite 库文件。
 *
 * 与 [InMemoryVectorStore] 的差异只在于存储引擎，契约语义逐条对齐：
 *
 * - **增量持久化**：SQLite 事务提交即落盘（WAL），不再有整文件 JSON 重写——
 *   [flush] 恒为 true、[reload] 是 no-op（库文件永远是权威态）；
 * - **离堆存储**：向量不占 JVM 堆，[indexBudgetBytes] 治理的是 db 文件字节数而不是内存；
 * - **维度分表**：vec0 的维度建表即固定，按向量长度路由到 `vec_vectors_N`（懒创建）；
 *   换 embedding 模型后旧维度表仍在，但检索按指纹过滤，不会跨向量空间混搜；
 * - **删除意图闸门保持内存态**（[deletedMessageIds]）：进程结束丢失由启动对账兜底——
 *   与 JSON 实现同一设计决策，不改变故障模型；
 * - **备份兼容**：[importIndex] 读写的是与 JSON 实现相同的线格式（见 VectorIndexJson.kt），
 *   旧备份可以直接恢复进本实现。
 *
 * Android 系统 SQLite 不支持加载扩展，库文件经由 requery sqlite-android 打开，
 * vec0.so 在装配期以文件系统路径传入（要求 manifest `extractNativeLibs="true"`）。
 *
 * 锁纪律与 [InMemoryVectorStore] 相同：[mutex] 不可重入，公开方法取锁、私有方法不取锁；
 * SQLite 连接本身线程安全，这里取锁是为了"闸门判定 + 删除 + 写入"这类复合操作的逻辑原子性。
 */
class SqliteVecStore(
    private val dbFile: File,
    private val extensionPath: String,
    private val indexBudgetBytes: Long = InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES,
    private val loadGate: suspend () -> Unit = {},
) : VectorStore {
    /** 生产装配用：索引库固定放在 `filesDir` 下，扩展来自应用原生库目录 */
    constructor(
        context: Context,
        indexBudgetBytes: Long = InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES,
        loadGate: suspend () -> Unit = {},
    ) : this(
        File(context.filesDir, FILE_NAME),
        File(context.applicationInfo.nativeLibraryDir, EXTENSION_NAME).absolutePath,
        indexBudgetBytes,
        loadGate,
    )

    private val mutex = Mutex()

    /** 见 [InMemoryVectorStore] 同名字段：已删除、不得再写入索引的 message id（有界 LRU） */
    private val deletedMessageIds: MutableSet<String> =
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(DELETED_IDS_INITIAL_CAPACITY, LRU_LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > DELETED_IDS_MAX_CAPACITY
            },
        )

    /** 删除代次：写入令牌据此判定"embedding 期间是否发生过删除"（见 [beginMessageWrite]） */
    private var deletionGeneration = 0L

    private var db: SQLiteDatabase? = null

    /** 已创建的 vec0 表（按维度一张）；打开库时从 sqlite_master 恢复 */
    private val knownTables = mutableSetOf<Int>()

    /**
     * 装配期探测：打开库并确认 vec0 扩展可用（`SELECT vec_version()`）。
     *
     * 构造本身不做 IO，扩展加载/建库失败只会在首次打开时暴露——DI 装配需要一次主动探测
     * 来决定用本实现还是回退 JSON 内存索引（见 AppContainer.assembleVectorStore）。
     */
    suspend fun probe(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val db = openLocked()
                    db.rawQuery("SELECT vec_version()", null).use { cursor ->
                        cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "vec0 扩展探测失败：${e.message}", e)
                    false
                }
            }
        }

    /**
     * 打开库并加载 vec0 扩展（幂等）。加载闸门（升级清零 SessionMemoryReset）必须先于
     * 任何磁盘读取完成——与 [InMemoryVectorStore.ensureLoaded] 同一纪律。
     */
    private suspend fun openLocked(): SQLiteDatabase {
        db?.let { return it }
        loadGate()
        val config =
            SQLiteDatabaseConfiguration(
                dbFile.absolutePath,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE,
                emptyList(),
                emptyList(),
                listOf(SQLiteCustomExtension(extensionPath, null)),
            )
        val opened = SQLiteDatabase.openDatabase(config, null, null)
        // WAL：崩溃只丢最后一个事务；增量清理：淘汰删除后页能被回收，文件不单调膨胀
        opened.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        opened.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        opened.rawQuery("PRAGMA auto_vacuum=INCREMENTAL", null).use { it.moveToFirst() }
        // 恢复已有维度表（进程重启后 knownTables 是空的）
        opened.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '$TABLE_PREFIX%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0).removePrefix(TABLE_PREFIX).toIntOrNull()?.let { knownTables += it }
            }
        }
        db = opened
        return opened
    }

    // ---- 写入 ----

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                // 与内存实现同一不变量：同来源身份至多一条
                deleteByIdentityLocked(db, metadata)
                upsertRow(db, id, vector, metadata)
                evictForBudgetLocked(db)
            }
        }
    }

    override suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean,
    ): VectorStore.MessageWriteToken? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                openLocked()
                if (!replacePrevious && messageId in deletedMessageIds) return@withLock null
                if (replacePrevious) deletedMessageIds.remove(messageId)
                VectorStore.MessageWriteToken(messageId, deletionGeneration, replacePrevious)
            }
        }

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                if (token.messageId != messageId || token.generation != deletionGeneration) return@withLock false
                if (!token.allowDeleted && messageId in deletedMessageIds) return@withLock false
                val candidate = VectorStore.VectorEntry(id, vector, metadata)
                val oversized =
                    indexBudgetBytes > 0 &&
                        VectorEviction.serializedSizeOf(candidate) + INDEX_SHELL_BYTES > indexBudgetBytes
                if (oversized) {
                    Log.w(TAG, "Dropping vector $id because one entry exceeds the index budget")
                    return@withLock false
                }
                // 替换语义：同一来源身份（owner + session + message_id）在索引中至多一条
                deleteByIdentityLocked(db, metadata)
                upsertRow(db, id, vector, metadata)
                evictForBudgetLocked(db)
                // 与内存实现同契约：写入后可能因淘汰被移除，按"索引里是否还存在"如实上报
                existsLocked(db, id)
            }
        }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                db.beginTransaction()
                try {
                    entries.forEach { entry ->
                        // 与 addIfAllowed 同一不变量：同来源身份至多一条
                        deleteByIdentityLocked(db, entry.metadata)
                        upsertRow(db, entry.id, entry.vector, entry.metadata)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                evictForBudgetLocked(db)
            }
        }
    }

    /** 同来源身份的旧副本全部删除（无身份三元组的条目不收敛，仅按 vector_id 覆盖） */
    private fun deleteByIdentityLocked(
        db: SQLiteDatabase,
        metadata: Map<String, Any>,
    ) {
        val owner = metadata["owner_id"] as? String ?: return
        val session = metadata["session_id"] as? String ?: return
        val message = metadata["message_id"] as? String ?: return
        knownTables.forEach { dim ->
            db.execSQL(
                "DELETE FROM ${tableName(dim)} WHERE owner_id=? AND session_id=? AND message_id=?",
                arrayOf(owner, session, message),
            )
        }
    }

    private fun upsertRow(
        db: SQLiteDatabase,
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        val table = ensureTableLocked(db, vector.size)
        // vec0 的 TEXT 元数据列拒绝 NULL：缺失键统一落为空串（"无主/未知"语义由调用侧
        // 用空串判定，见 purgeSessionsNotIn / deleteWhereMetadataDiffers）
        db.execSQL(
            "INSERT OR REPLACE INTO $table " +
                "(vector_id, embedding, owner_id, session_id, message_id, character_id, content_type, " +
                "content, created_at, embedding_fingerprint, source_revision) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                id,
                vector.toVec0Literal(),
                metadata["owner_id"]?.toString() ?: "",
                metadata["session_id"]?.toString() ?: "",
                metadata["message_id"]?.toString() ?: "",
                metadata["character_id"]?.toString() ?: "",
                metadata["content_type"]?.toString() ?: "",
                metadata["content"]?.toString(),
                metadata["created_at"]?.toString() ?: "",
                metadata["embedding_fingerprint"]?.toString() ?: "",
                metadata["source_revision"]?.toString() ?: "",
            ),
        )
    }

    private fun ensureTableLocked(
        db: SQLiteDatabase,
        dimension: Int,
    ): String {
        if (dimension !in knownTables) {
            // vec0 列有两种：元数据列（无前缀）可在 KNN 查询的 WHERE 里过滤（=/IN 下推）；
            // 辅助列（+ 前缀）是纯载荷，KNN 查询里过滤会被拒——content 永不过滤，放辅助列
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS ${tableName(dimension)} USING vec0(" +
                    "embedding float[$dimension] distance_metric=cosine, " +
                    "vector_id TEXT PRIMARY KEY, " +
                    "owner_id TEXT, session_id TEXT, message_id TEXT, character_id TEXT, " +
                    "content_type TEXT, created_at TEXT, embedding_fingerprint TEXT, source_revision TEXT, " +
                    "+content TEXT)",
            )
            knownTables += dimension
        }
        return tableName(dimension)
    }

    // ---- 检索 ----

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                if (topK <= 0 || queryVector.size !in knownTables) return@withLock emptyList()
                val where = buildFilterWhere(filters) ?: return@withLock emptyList()
                val table = tableName(queryVector.size)
                // vec0 KNN：MATCH 配 k 约束（k 与 LIMIT 互斥，vec0 会拒绝同现）；元数据过滤下推到 C 层
                val sql =
                    "SELECT vector_id, distance, owner_id, session_id, message_id, character_id, " +
                        "content_type, content, created_at, embedding_fingerprint, source_revision " +
                        "FROM $table WHERE embedding MATCH ? AND k = ?${where.clause} ORDER BY distance"
                val args = arrayListOf<Any?>(queryVector.toVec0Literal(), topK)
                args.addAll(where.args)
                val results = mutableListOf<VectorStore.SearchResult>()
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        results += cursor.toSearchResult(queryVector.size)
                    }
                }
                results
            }
        }

    /**
     * 过滤器 → SQL WHERE 片段；返回 null 表示出现未知键——与 [InMemoryVectorStore] 的
     * "键缺失不匹配"同语义：无结果而不是报错。值是 [Iterable] 时按集合成员（IN）匹配。
     */
    private fun buildFilterWhere(filters: Map<String, Any>): FilterWhere? {
        if (filters.isEmpty()) return FilterWhere("", emptyList())
        val clause = StringBuilder()
        val args = mutableListOf<Any?>()
        filters.forEach { (key, value) ->
            if (key !in FILTERABLE_COLUMNS) return null
            when (value) {
                is Iterable<*> -> {
                    val values = value.toList()
                    if (values.isEmpty()) return null
                    clause.append(" AND $key IN (")
                    clause.append(values.joinToString(",") { "?" })
                    clause.append(")")
                    values.forEach { args += it?.toString() }
                }
                else -> {
                    clause.append(" AND $key = ?")
                    args += value.toString()
                }
            }
        }
        return FilterWhere(clause.toString(), args)
    }

    private data class FilterWhere(
        val clause: String,
        val args: List<Any?>,
    )

    // ---- 删除 ----

    override suspend fun get(id: String): VectorStore.VectorEntry? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                knownTables.forEach { dim ->
                    db.rawQuery(
                        "SELECT vector_id, vec_to_json(embedding), owner_id, session_id, message_id, character_id, " +
                            "content_type, content, created_at, embedding_fingerprint, source_revision " +
                            "FROM ${tableName(dim)} WHERE vector_id = ? LIMIT 1",
                        arrayOf(id),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) return@withLock cursor.toVectorEntry(dim)
                    }
                }
                null
            }
        }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                knownTables.forEach { dim ->
                    db.execSQL("DELETE FROM ${tableName(dim)} WHERE vector_id=?", arrayOf(id))
                }
            }
        }
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                val where = buildFilterWhere(filters.filterKeys { it != VectorStore.MARK_MESSAGES_DELETED })
                if (where == null && filters.keys.any { it != VectorStore.MARK_MESSAGES_DELETED }) {
                    return@withLock 0 // 未知键：与内存实现同语义，无匹配
                }
                var deleted = 0
                knownTables.forEach { dim ->
                    val table = tableName(dim)
                    // 指令键：命中行的 message_id 先登记为"已删除"，挡住尚在 embedding 的在途写入
                    if (filters[VectorStore.MARK_MESSAGES_DELETED] == true) {
                        val ids = mutableListOf<String>()
                        db.rawQuery(
                            "SELECT message_id FROM $table WHERE message_id != ''${where?.clause ?: ""}",
                            (where?.args ?: emptyList()).toTypedArray(),
                        ).use { cursor ->
                            while (cursor.moveToNext()) cursor.getString(0)?.let { ids += it }
                        }
                        if (ids.isNotEmpty()) {
                            deletionGeneration++
                            deletedMessageIds.addAll(ids)
                        }
                    }
                    db.execSQL("DELETE FROM $table WHERE 1=1${where?.clause ?: ""}", (where?.args ?: emptyList()).toTypedArray())
                    deleted +=
                        db.rawQuery("SELECT changes()", null).use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                }
                if (deleted > 0) incrementalVacuumLocked(db)
                deleted
            }
        }

    override suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int =
        withContext(Dispatchers.IO) {
            if (key !in FILTERABLE_COLUMNS) {
                throw IllegalArgumentException("Unknown metadata column: $key")
            }
            mutex.withLock {
                val db = openLocked()
                var deleted = 0
                knownTables.forEach { dim ->
                    // 缺失该键的历史条目（空串）同样算"不相符"，与内存实现一致
                    db.execSQL("DELETE FROM ${tableName(dim)} WHERE $key = '' OR $key != ?", arrayOf(value))
                    deleted +=
                        db.rawQuery("SELECT changes()", null).use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                }
                if (deleted > 0) incrementalVacuumLocked(db)
                deleted
            }
        }

    override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            if (knownSessionIds.isEmpty()) return@withContext 0
            if (knownSessionIds.size > MAX_IN_VARIABLES) {
                // SQLite 变量上限（3.32+ 为 32766）：超出时不做整批判定——宁可残留，
                // 也不能把"没查全"当成"已删除"（与会话级对账的保守闸门同哲学）
                Log.w(TAG, "会话数超出单次对账上限（${knownSessionIds.size}），跳过本轮孤儿会话清理")
                return@withContext 0
            }
            mutex.withLock {
                val db = openLocked()
                var deleted = 0
                val purgedMessageIds = mutableListOf<String>()
                knownTables.forEach { dim ->
                    val table = tableName(dim)
                    // 与内存实现同一准则：session_id 缺失（空串）的行无法证明归属，一律保留
                    val placeholders = knownSessionIds.joinToString(",") { "?" }
                    val args = knownSessionIds.toTypedArray()
                    db.rawQuery(
                        "SELECT message_id FROM $table WHERE session_id != '' AND session_id NOT IN ($placeholders)",
                        args,
                    ).use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let { purgedMessageIds += it }
                    }
                    db.execSQL(
                        "DELETE FROM $table WHERE session_id != '' AND session_id NOT IN ($placeholders)",
                        args,
                    )
                    val changed =
                        db.rawQuery("SELECT changes()", null).use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                    deleted += changed
                }
                if (deleted > 0) {
                    deletionGeneration++
                    purgedMessageIds.forEach { deletedMessageIds.remove(it) }
                    incrementalVacuumLocked(db)
                    Log.w(TAG, "启动对账：删除 $deleted 条所属会话已不存在的向量")
                }
                deleted
            }
        }

    // ---- 闸门 ----

    override suspend fun canIndexMessage(messageId: String): Boolean = mutex.withLock { messageId !in deletedMessageIds }

    override suspend fun markMessagesDeleted(messageIds: Collection<String>) {
        mutex.withLock {
            if (messageIds.isNotEmpty()) {
                deletionGeneration++
                deletedMessageIds.addAll(messageIds)
            }
        }
    }

    override suspend fun clearMessageDeleted(messageId: String) {
        mutex.withLock { deletedMessageIds.remove(messageId) }
    }

    // ---- 统计与维护 ----

    override suspend fun count(): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                var total = 0
                knownTables.forEach { dim ->
                    db.rawQuery("SELECT COUNT(*) FROM ${tableName(dim)}", null).use {
                        it.moveToFirst()
                        total += it.getInt(0)
                    }
                }
                total
            }
        }

    /** 索引占用 = db 文件 + WAL 的磁盘字节数 */
    override suspend fun indexBytes(): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                openLocked()
                walAwareBytes()
            }
        }

    override suspend fun indexStats(): IndexStats =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                val bytes = walAwareBytes()
                IndexStats(count = countLocked(db), bytes = bytes, budgetBytes = indexBudgetBytes, persistedBytes = bytes)
            }
        }

    override suspend fun indexedMessageIds(fingerprint: String): Set<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                val ids = mutableSetOf<String>()
                knownTables.forEach { dim ->
                    db.rawQuery(
                        "SELECT message_id FROM ${tableName(dim)} WHERE embedding_fingerprint = ?",
                        arrayOf(fingerprint),
                    ).use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let { ids += it }
                    }
                }
                ids
            }
        }

    override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                val entries = mutableListOf<VectorStore.VectorEntry>()
                knownTables.forEach { dim ->
                    db.rawQuery(
                        "SELECT vector_id, vec_to_json(embedding), owner_id, session_id, message_id, character_id, " +
                            "content_type, content, created_at, embedding_fingerprint, source_revision " +
                            "FROM ${tableName(dim)}",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) entries += cursor.toVectorEntry(dim)
                    }
                }
                entries
            }
        }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = openLocked()
                // 与内存实现同语义：清向量并推进删除代次，但**不清闸门**——
                // 清空常紧跟删除操作（clearIndex 先登记了全部 id），闸门要挡住尚在途的写入
                deletionGeneration++
                knownTables.forEach { dim -> db.execSQL("DELETE FROM ${tableName(dim)}") }
                incrementalVacuumLocked(db)
            }
        }
    }

    /** SQLite 库文件永远是权威态，reload 是 no-op（区别于 JSON 实现的内存态重建） */
    override suspend fun reload() = Unit

    /** 事务提交即持久（WAL + synchronous=NORMAL），没有待写的合并窗口 */
    override suspend fun flush(): Boolean = true

    override suspend fun importIndex(content: String): Boolean =
        withContext(Dispatchers.IO) {
            val entries =
                decodeVectorIndex(content) ?: run {
                    Log.w(TAG, "索引内容无法解析，保留本地索引")
                    return@withContext false
                }
            mutex.withLock {
                val db = openLocked()
                db.beginTransaction()
                try {
                    knownTables.forEach { dim -> db.execSQL("DELETE FROM ${tableName(dim)}") }
                    entries.forEach { entry ->
                        // 与 loadFromDisk 同一闸门：已登记删除意图的 message_id 不再复活
                        val messageId = entry.metadata["message_id"] as? String
                        if (messageId != null && messageId in deletedMessageIds) return@forEach
                        upsertRow(db, entry.id, entry.vector, entry.metadata)
                    }
                    db.setTransactionSuccessful()
                } catch (e: Exception) {
                    Log.e(TAG, "索引导入失败，已回滚：${e.message}", e)
                    return@withLock false
                } finally {
                    db.endTransaction()
                }
                incrementalVacuumLocked(db)
                Log.d(TAG, "索引已整体导入（${entries.size} 条）")
                true
            }
        }

    // ---- 内部工具 ----

    /** 单条存在性核查（addIfAllowed 的"写入后可能已被淘汰"上报用） */
    private fun existsLocked(
        db: SQLiteDatabase,
        id: String,
    ): Boolean {
        knownTables.forEach { dim ->
            db.rawQuery("SELECT 1 FROM ${tableName(dim)} WHERE vector_id=? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        }
        return false
    }

    private fun countLocked(db: SQLiteDatabase): Int {
        var total = 0
        knownTables.forEach { dim ->
            db.rawQuery("SELECT COUNT(*) FROM ${tableName(dim)}", null).use {
                it.moveToFirst()
                total += it.getInt(0)
            }
        }
        return total
    }

    private fun walAwareBytes(): Long {
        var bytes = dbFile.length()
        File(dbFile.parentFile, "${dbFile.name}-wal").takeIf { it.exists() }?.let { bytes += it.length() }
        return bytes
    }

    /** 预算治理：超出时按 [VectorEviction] 的价值序淘汰；sqlite 侧字节数以文件真实大小为准 */
    private fun evictForBudgetLocked(db: SQLiteDatabase) {
        if (indexBudgetBytes <= 0) return
        val bytes = walAwareBytes()
        if (bytes <= indexBudgetBytes) return
        val entries = mutableListOf<VectorStore.VectorEntry>()
        knownTables.forEach { dim ->
            db.rawQuery(
                "SELECT vector_id, vec_to_json(embedding), owner_id, session_id, message_id, character_id, " +
                    "content_type, content, created_at, embedding_fingerprint, source_revision " +
                    "FROM ${tableName(dim)}",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) entries += cursor.toVectorEntry(dim)
            }
        }
        val plan = VectorEviction.planForBudget(entries, bytes, indexBudgetBytes)
        if (plan.victims.isEmpty()) return
        plan.victims.chunked(PURGE_IN_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            knownTables.forEach { dim ->
                db.execSQL("DELETE FROM ${tableName(dim)} WHERE vector_id IN ($placeholders)", chunk.toTypedArray())
            }
        }
        incrementalVacuumLocked(db)
        Log.w(TAG, "索引超出磁盘预算，淘汰 ${plan.victims.size} 条向量（summary 优先保留）")
    }

    private fun incrementalVacuumLocked(db: SQLiteDatabase) {
        runCatching { db.rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() } }
    }

    private fun android.database.Cursor.toSearchResult(dimension: Int): VectorStore.SearchResult {
        val metadata = toMetadataMap(dimension, contentStartIndex = 2)
        return VectorStore.SearchResult(
            id = getString(0),
            // vec0 cosine distance → similarity
            score = 1f - getFloat(1),
            content = metadata["content"] as? String ?: "",
            metadata = metadata,
        )
    }

    /** 游标 → 条目：embedding 列经 vec_to_json 取出（vec0 内部以 float32 BLOB 存储，直接 getString 会炸） */
    private fun android.database.Cursor.toVectorEntry(dimension: Int): VectorStore.VectorEntry =
        VectorStore.VectorEntry(
            id = getString(0),
            vector = getString(1).parseVec0Literal(),
            metadata = toMetadataMap(dimension, contentStartIndex = 2),
        )

    /** 游标 → 元数据 map：列序固定（见各 SELECT），dimension 由表名补回（与写入侧的元数据形状一致） */
    private fun android.database.Cursor.toMetadataMap(
        dimension: Int,
        contentStartIndex: Int,
    ): Map<String, Any> =
        buildMap {
            // toSearchResult/toVectorEntry 的前两列分别是 (vector_id, distance) 与 (vector_id, vec_to_json(embedding))，
            // 元数据列从 contentStartIndex 开始
            put("owner_id", getString(contentStartIndex) ?: "")
            put("session_id", getString(contentStartIndex + 1) ?: "")
            put("message_id", getString(contentStartIndex + 2) ?: "")
            put("character_id", getString(contentStartIndex + 3) ?: "")
            put("content_type", getString(contentStartIndex + 4) ?: "")
            put("content", getString(contentStartIndex + 5) ?: "")
            put("created_at", getString(contentStartIndex + 6) ?: "")
            put("embedding_fingerprint", getString(contentStartIndex + 7) ?: "")
            // vec0 元数据列不接受 NULL，缺键落成空串；读回时把空串还原为"键缺失"
            // （VectorRecallValidator 的 revision 比对以 null 为"未记录"，空串会被误判为版本漂移）
            getString(contentStartIndex + 8)?.takeIf { it.isNotEmpty() }?.let { put("source_revision", it) }
            put("dimension", dimension.toString())
        }

    companion object {
        private const val TAG = "SqliteVecStore"

        /** 索引库文件名（filesDir 下；与 Room 主库分离——Room 的 framework SQLite 加载不了扩展） */
        const val FILE_NAME = "vector_index.db"

        /** jniLibs 里的 vec0 扩展文件名（extractNativeLibs 后落在 nativeLibraryDir） */
        const val EXTENSION_NAME = "vec0.so"

        private const val TABLE_PREFIX = "vec_vectors_"

        /** 元数据过滤支持的列（未知键与内存实现同语义：无匹配） */
        private val FILTERABLE_COLUMNS =
            setOf(
                "owner_id",
                "session_id",
                "message_id",
                "character_id",
                "content_type",
                "created_at",
                "embedding_fingerprint",
                "source_revision",
            )

        private const val PURGE_IN_CHUNK = 500

        /** SQLite 变量数安全上限（3.32+ 为 32766，留余量）：超出时放弃整批判定而不是误判 */
        private const val MAX_IN_VARIABLES = 30000

        /** 单条记录的最小骨架字节数（ oversized 判定与内存实现同口径） */
        private const val INDEX_SHELL_BYTES = 16L

        private const val DELETED_IDS_INITIAL_CAPACITY = 64
        private const val LRU_LOAD_FACTOR = 0.75f
        private const val DELETED_IDS_MAX_CAPACITY = 4096

        private fun tableName(dimension: Int) = "$TABLE_PREFIX$dimension"
    }
}

/** FloatArray → vec0 的 JSON 文本字面量形态 `[0.1,0.2,...]` */
private fun FloatArray.toVec0Literal(): String =
    buildString(size * 12 + 2) {
        append('[')
        this@toVec0Literal.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append(f)
        }
        append(']')
    }

/** vec0 返回的 embedding 文本形态解析回 FloatArray（维护路径用：快照/备份导出） */
private fun String.parseVec0Literal(): FloatArray {
    val trimmed = trim()
    if (trimmed.length < 2) return FloatArray(0)
    val body = trimmed.substring(1, trimmed.length - 1)
    if (body.isBlank()) return FloatArray(0)
    val parts = body.split(',')
    return FloatArray(parts.size) { parts[it].trim().toFloat() }
}
