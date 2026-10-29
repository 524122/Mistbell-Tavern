package com.mistbell.tavern.android.data.vector

import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.Collections

internal const val SQLITE_VEC_LOG_TAG = "SqliteVecStore"

private const val TABLE_PREFIX = "vec_vectors_"
private const val DELETED_IDS_INITIAL_CAPACITY = 64
private const val LRU_LOAD_FACTOR = 0.75f
private const val DELETED_IDS_MAX_CAPACITY = 4096

/** 所有 SQLite 委托共享这一份状态与同一把锁。 */
internal class SqliteVecStoreState(
    private val dbFile: File,
    private val extensionPath: String,
    val indexBudgetBytes: Long,
    private val loadGate: suspend () -> Unit,
) {
    val mutex = Mutex()

    /** 已删除、不得再写入索引的 message id；仅在 [mutex] 内访问。 */
    val deletedMessageIds: MutableSet<String> = newDeletedMessageIds()

    /** 写入令牌据此判定 embedding 期间是否发生过删除。 */
    var deletionGeneration = 0L

    /** 已创建的 vec0 表（按维度一张）；打开库时从 sqlite_master 恢复。 */
    val knownTables = mutableSetOf<Int>()

    private var db: SQLiteDatabase? = null

    /**
     * 打开库并加载 vec0 扩展（幂等）。加载闸门必须先于任何磁盘读取完成。
     * 调用方必须已持有 [mutex]。
     */
    suspend fun openLocked(): SQLiteDatabase {
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

    /** 索引占用 = db 文件 + WAL 的磁盘字节数。 */
    fun walAwareBytes(): Long {
        var bytes = dbFile.length()
        File(dbFile.parentFile, "${dbFile.name}-wal").takeIf { it.exists() }?.let { bytes += it.length() }
        return bytes
    }
}

private fun newDeletedMessageIds(): MutableSet<String> =
    Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(DELETED_IDS_INITIAL_CAPACITY, LRU_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > DELETED_IDS_MAX_CAPACITY
            }
        },
    )

internal fun sqliteVecTableName(dimension: Int): String = "$TABLE_PREFIX$dimension"
