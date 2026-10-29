package com.mistbell.tavern.android.data.vector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * sqlite-vec 向量存储：vec0 虚拟表（C 层精确 KNN）+ 独立 SQLite 库文件。
 *
 * 与 [InMemoryVectorStore] 的差异只在于存储引擎，契约语义逐条对齐：
 *
 * - **增量持久化**：SQLite 事务提交即落盘（WAL），[VectorStore.flush] 恒为 true，
 *   [VectorStore.reload] 是 no-op（库文件永远是权威态）；
 * - **离堆存储**：向量不占 JVM 堆，indexBudgetBytes 治理的是 db 文件字节数而不是内存；
 * - **维度分表**：vec0 的维度建表即固定，按向量长度路由到 `vec_vectors_N`（懒创建）；
 *   换 embedding 模型后旧维度表仍在，但检索按指纹过滤，不会跨向量空间混搜；
 * - **删除意图闸门保持内存态**：进程结束丢失由启动对账兜底，与 JSON 实现同一故障模型；
 * - **备份兼容**：[VectorStore.importIndex] 读写与 JSON 实现相同的线格式，
 *   旧备份可以直接恢复进本实现。
 *
 * Android 系统 SQLite 不支持加载扩展，库文件经由 requery sqlite-android 打开，
 * vec0.so 在装配期以文件系统路径传入（要求 manifest `extractNativeLibs="true"`）。
 *
 * 四个职责委托共享同一 [SqliteVecStoreState]。[SqliteVecStoreState.mutex] 不可重入，
 * 公开方法取锁、内部 Locked 方法不取锁；锁同时保护复合写入与删除闸门的逻辑原子性。
 */
class SqliteVecStore private constructor(
    private val state: SqliteVecStoreState,
) : VectorStore,
    ContentOperations by SqliteVecContentOperations(state),
    StatisticsOperations by SqliteVecStatisticsOperations(state),
    GateOperations by SqliteVecGateOperations(state),
    MaintenanceOperations by SqliteVecMaintenanceOperations(state) {
    constructor(
        dbFile: File,
        extensionPath: String,
        indexBudgetBytes: Long = InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES,
        loadGate: suspend () -> Unit = {},
    ) : this(SqliteVecStoreState(dbFile, extensionPath, indexBudgetBytes, loadGate))

    /** 生产装配用：索引库固定放在 filesDir 下，扩展来自应用原生库目录。 */
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

    /**
     * 装配期探测：打开库并确认 vec0 扩展可用（`SELECT vec_version()`）。
     * 构造本身不做 IO，扩展加载/建库失败只会在首次打开时暴露。
     */
    suspend fun probe(): Boolean =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                runCatching {
                    val db = state.openLocked()
                    db.rawQuery("SELECT vec_version()", null).use { cursor ->
                        cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank()
                    }
                }.getOrElse { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Exception -> {
                            Log.e(TAG, "vec0 扩展探测失败：${throwable.message}", throwable)
                            false
                        }
                        else -> throw throwable
                    }
                }
            }
        }

    companion object {
        private const val TAG = "SqliteVecStore"

        /** 索引库文件名（filesDir 下；与 Room 主库分离——Room 的 framework SQLite 加载不了扩展） */
        const val FILE_NAME = "vector_index.db"

        /** jniLibs 里的 vec0 扩展文件名（extractNativeLibs 后落在 nativeLibraryDir） */
        const val EXTENSION_NAME = "vec0.so"
    }
}
