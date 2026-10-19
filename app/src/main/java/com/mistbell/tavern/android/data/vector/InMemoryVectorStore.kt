package com.mistbell.tavern.android.data.vector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Collections

/**
 * 内存向量存储实现（带持久化和内存限制）
 *
 * 移植自后端 InMemoryVectorStore.java
 *
 * 特点：
 * - 内存存储，快速检索
 * - 自动持久化到磁盘
 * - 支持过滤条件
 * - 余弦相似度计算
 * - 可选内存限制：传入正数时按 [VectorEviction] 淘汰；传入 0 表示持久化索引不淘汰
 *
 * ## 落盘策略（改动理由）
 *
 * 持久化是**整文件重写**的：满 1000 条 × 512 维时单次约 8 MB。此前 `add()` 每写一条就落一次盘，
 * 意味着每发一条消息都要序列化并写入 8 MB——比 embedding 本身更拖发送链路（且写盘还在持有互斥锁
 * 的情况下同步进行）。现在：
 *
 * - **写入合并窗口**：[FLUSH_DELAY_MS] 内最多落盘一次，窗口内的多次改动并入同一次写；
 * - **删除立即落盘**：删除若停留在窗口里，进程被杀会让已删向量复活，代价比慢一点更大；
 * - **写入原子替换**：先写 `.tmp` 再改名——直接覆写时若进程中断会留下半截 JSON，
 *   整份索引（不只是这一次的改动）全部读不回来；
 * - **失败自动重排**：落盘失败不丢弃状态，排回下一个窗口重试，直到与内存一致；
 * - [flush] 供备份导出前、进程退到后台时立即补齐。
 *
 * 单次写入仍是整文件重写：这是当前格式的固有限制，合并窗口把它从"每消息一次"降到"每窗口一次"。
 *
 * ## 磁盘容量治理
 *
 * 关闭条数上限（生产传 0）后索引会无界增长——早期记忆不再被静默丢掉，但文件本身成了
 * "用得越久越大"的定时炸弹，低存储设备上先是写不进去、再是整份索引一起坏掉。所以另设**字节预算**
 * [indexBudgetBytes]：落盘时按真实序列化字节判定，超预算按 [VectorEviction] 的优先级淘汰
 * （普通消息先于 summary、同档内最旧的先走），并在日志与 [indexStats] 里如实上报。
 *
 * ## 写入闸门
 *
 * embedding 是慢操作，跑完时源消息可能已经被删（LLM 失败回滚、撤销、回退、重抽、清空会话）。
 * 删除方登记删除意图（[markMessagesDeleted]），写入方在真正 add 之前问一次 [canIndexMessage]——
 * 否则那条向量就是"删除之后才落地"的孤儿，跨会话召回还会把它捞回来。
 */
class InMemoryVectorStore(
    private val storageFile: File,
    private val maxVectorsInMemory: Int = DEFAULT_MAX_VECTORS,
    private val flushDelayMs: Long = FLUSH_DELAY_MS,
    private val indexBudgetBytes: Long = DEFAULT_INDEX_BUDGET_BYTES,
    private val loadGate: suspend () -> Unit = {},
) : VectorStore {
    /**
     * 生产装配用：索引固定放在 `filesDir` 下。
     *
     * 主构造收 [File] 而不是 Context，是为了让落盘策略能在 JVM 单测里跑起来——
     * "合并窗口到底有没有合并""删除会不会停在窗口里"这类问题，只有真跑才说得清。
     */
    constructor(
        context: Context,
        maxVectorsInMemory: Int = DEFAULT_MAX_VECTORS,
        flushDelayMs: Long = FLUSH_DELAY_MS,
        indexBudgetBytes: Long = DEFAULT_INDEX_BUDGET_BYTES,
        loadGate: suspend () -> Unit = {},
    ) : this(File(context.filesDir, FILE_NAME), maxVectorsInMemory, flushDelayMs, indexBudgetBytes, loadGate)

    private val vectors = mutableMapOf<String, VectorStore.VectorEntry>()

    /**
     * 保护 [vectors] 与 [deletedMessageIds] 的互斥锁。
     *
     * **`kotlinx.coroutines.sync.Mutex` 不可重入**（同一条协程里嵌套 `withLock` 会永久挂起，
     * 而不是像 `ReentrantLock` 那样直接放行）。因此本类遵守一条硬纪律：
     *
     * - `…Locked` 结尾的私有函数**只在调用方已持锁时调用，自己绝不再取锁**；
     * - 公开入口（add/delete/clear/flush/…）自己取锁，然后只调用 `…Locked` 版本；
     * - 真正落盘的 [writeToDisk] 不取锁，由 [persistSnapshot] 在锁外调用（内容来自锁内拍的
     *   [Snapshot] 快照）。
     *
     * 违反这条纪律不会报错，只会让索引整体卡死（`add`/`delete`/`flush` 都停在同一个锁上，
     * 发送链路跟着一起卡），所以任何新代码都必须先想清楚"我现在是否已经持锁"。
     */
    private val mutex = Mutex()
    private val persistMutex = Mutex()

    /**
     * 已删除、不得再写入索引的 message id。
     *
     * 有界 LRU：删除是常态操作，不设界就是另一处无界增长。容量远大于"同时在途的 embedding 数"
     * （每条消息的写入窗口只有一次 embedding 的时长），被挤掉的老记录早已没有在途写入。
     */
    private val deletedMessageIds: MutableSet<String> =
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(DELETED_IDS_INITIAL_CAPACITY, LRU_LOAD_FACTOR, true) {
                // ktlint 与 detekt 行宽口径冲突（单行 137 字符：满足 ktlint 140、违反 detekt 120），
                // 保持块体并豁免 ktlint 的 function-signature（它要求可同行的函数体必须与签名同行）。
                @Suppress("ktlint:standard:function-signature")
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
                    return size > MAX_TRACKED_DELETED_IDS
                }
            },
        )
    private var deletionGeneration = 0L

    // prettyPrint 会让 512 维向量每个分量独占一行（约 40 字节/分量，单条 7.7 KB）。
    // 索引是机器读的，没有人会去看它，紧凑输出省下约 1/3 体积。
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    /** 合并窗口的执行器：写盘永远在 IO 线程，绝不占用调用方线程（发送链路、主线程） */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否已排入一次待写。只在 [mutex] 内读写，故无需额外的原子类型。 */
    private var savePending = false

    /**
     * 最近一次成功落盘的**实测字节数**（见 [writeToDisk]）。
     *
     * 估算（[VectorEviction.serializedSizeOf]）刻意偏保守，直接拿它显示占用会让设置页虚高；
     * 落盘时反正要编码一次，顺手记下真实体量，供 [indexBytes]/[indexStats] 如实上报。
     */
    private var lastPersistedBytes: Long? = null

    /**
     * 下次落盘前的等待时长。
     *
     * 写失败时指数退避（磁盘满、被占用等瞬时问题不该让索引永远停在旧版本），成功后复位。
     * 这就是"失败不丢状态、直到与内存一致"的实现——只排一次队、忽略失败返回值是不够的。
     */
    private var retryDelayMs = FLUSH_DELAY_MS

    companion object {
        private const val TAG = "InMemoryVectorStore"

        /** 持久化索引文件名。备份/恢复按此名读写，必须与 [storageFile] 同源（禁止两处手写字面量）。 */
        const val FILE_NAME = "vector_store.json"

        /** 内存中保留的向量条数上限，超限按 [VectorEviction] 淘汰 */
        const val DEFAULT_MAX_VECTORS = 1000

        /** 写入合并窗口：窗口内的多次改动并入同一次整文件写入 */
        const val FLUSH_DELAY_MS = 1_500L

        /**
         * 索引落盘预算（字节）：不做条数淘汰之后，**字节数**才是真正会撑爆设备的量。
         *
         * 16 MB ≈ 约 3.2 万条 512 维消息（约 8 千条 1536 维 API 向量）。取值依据：低存储设备上
         * 一份索引文件远不该是占用大头，而整文件重写意味着峰值还需要再乘一份临时文件——
         * 预算太大时"写不进去"本身就会变成新的故障模式。
         */
        const val DEFAULT_INDEX_BUDGET_BYTES = 16L * 1024 * 1024

        /** 删除意图追踪的容量上限（有界 LRU，见 [deletedMessageIds]） */
        private const val MAX_TRACKED_DELETED_IDS = 4096
        private const val DELETED_IDS_INITIAL_CAPACITY = 128

        /** JDK 默认负载因子：容量只是初始值，这里不打算调性能 */
        private const val LRU_LOAD_FACTOR = 0.75f

        /** 落盘失败后的退避倍数与上限 */
        private const val RETRY_BACKOFF_FACTOR = 2
        private const val MAX_RETRY_DELAY_MS = 30_000L

        /**
         * 编码后实测仍超预算时，最多再淘汰几轮。
         *
         * 估算与实际转义体量可能有偏差，但每轮淘汰都会真的删条目，两轮足够收敛；
         * 单条就超预算时 [VectorEviction.planForBudget] 会自动停下（再删就什么都不剩了）。
         */
        private const val BUDGET_ENFORCE_ATTEMPTS = 2

        /** `{"vectors":[…]}` 的外壳字节数 */
        private const val INDEX_SHELL_BYTES = 16L

        /** 向量 metadata 里的 embedding 指纹键（与 [VectorMemoryService] 写入时一致） */
        private const val FINGERPRINT_KEY = "embedding_fingerprint"

        /** 向量 metadata 里的归属键：启动对账按会话收敛时要用 */
        private const val SESSION_KEY = "session_id"

        /** 向量 metadata 里的消息键（与文件末尾的扩展函数共用） */
        internal const val MESSAGE_KEY = "message_id"

        /**
         * 备份恢复前的索引校验：能解析成 [VectorStoreData] 才接受。
         *
         * 索引文件是向量召回的唯一权威（Room 的 vector_memory 只存元数据、不含向量，
         * 无法重建索引），写进一个坏文件会让召回整体失效且不可逆，故先验后写。
         * 校验用的解析器（与实例 [json] 同配置）在伴生对象里只建一次。
         */
        private val validatorJson = Json { ignoreUnknownKeys = true }

        fun isValidIndex(content: String): Boolean =
            try {
                validatorJson.decodeFromString<VectorStoreData>(content)
                true
            } catch (_: Exception) {
                false
            }
    }

    private var isLoaded = false

    private suspend fun ensureLoaded() {
        if (!isLoaded) {
            // 加载闸门：升级清零（SessionMemoryReset）必须早于任何磁盘读取完成，
            // 否则刚被删除的旧索引会被立刻读回来，清零形同虚设
            loadGate()
            loadFromDisk()
            isLoaded = true
        }
    }

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        mutex.withLock {
            ensureLoaded()
            putReplacingIdentityLocked(VectorStore.VectorEntry(id, vector, metadata))
            evictOverflowLocked()
            scheduleSaveLocked()
        }
        Log.d(TAG, "Added vector: $id (dimension: ${vector.size})")
    }

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean {
        val retained =
            mutex.withLock {
                ensureLoaded()
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
                // 替换语义：同一来源身份（owner + session + message_id）在索引中至多一条——
                // 先移除全部旧副本（历史随机 vectorId 可能堆积多条）再写入新项
                putReplacingIdentityLocked(candidate)
                evictOverflowLocked()
                evictForBudgetLocked()
                scheduleSaveLocked()
                vectors.containsKey(id)
            }
        if (retained) Log.d(TAG, "Added vector atomically: $id (dimension: ${vector.size})")
        return retained
    }

    override suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean,
    ): VectorStore.MessageWriteToken? =
        mutex.withLock {
            ensureLoaded()
            if (!replacePrevious && messageId in deletedMessageIds) return@withLock null
            if (replacePrevious) deletedMessageIds.remove(messageId)
            VectorStore.MessageWriteToken(messageId, deletionGeneration, replacePrevious)
        }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        mutex.withLock {
            ensureLoaded()
            entries.forEach { entry ->
                // 与 addIfAllowed 同一不变量：同来源身份至多一条（批量导入/重建也要收敛重复）
                putReplacingIdentityLocked(entry)
            }
            evictOverflowLocked()
            scheduleSaveLocked()
        }
        Log.d(TAG, "Added ${entries.size} vectors in batch")
    }

    /**
     * 按来源身份写入并替换旧副本（调用方必须已持有 [mutex]）。
     *
     * 不变量：**同一 (owner_id, session_id, message_id) 在索引中至多一条**。历史上每次写入
     * 都生成随机 vectorId 而旧项不删，同一 messageId 会堆积多条旧摘要、全部被召回——
     * 这里在写入前移除同身份的全部旧项。vectorId 本身保持随机（无需稳定键）。
     * 无来源身份的条目（理论上的裸写入）不做身份收敛，仅按 id 覆盖。
     */
    private fun putReplacingIdentityLocked(entry: VectorStore.VectorEntry) {
        val identity =
            identityOf(entry.metadata) ?: run {
                vectors[entry.id] = entry
                return
            }
        vectors.entries.removeIf { (_, existing) -> identityOf(existing.metadata) == identity }
        vectors[entry.id] = entry
    }

    /** 条目的来源身份三元组；缺任一字段返回 null（该条目不参与身份收敛） */
    private fun identityOf(metadata: Map<String, Any>): Triple<*, *, *>? {
        val owner = metadata["owner_id"] as? String
        val session = metadata["session_id"] as? String
        val message = metadata["message_id"] as? String
        return if (owner != null && session != null && message != null) {
            Triple(owner, session, message)
        } else {
            null
        }
    }

    /** 超限则按 [VectorEviction] 淘汰（调用方必须已持有 [mutex]） */
    private fun evictOverflowLocked() {
        // 生产索引以磁盘持久化为准，AppContainer 传 0，避免静默遗忘早期长期记忆。
        // JVM 测试和受限场景仍可传正数验证淘汰策略。
        if (maxVectorsInMemory <= 0) return
        val overflow = vectors.size - maxVectorsInMemory
        if (overflow <= 0) return

        val victims = VectorEviction.selectVictims(vectors.values.toList(), overflow)
        victims.forEach { vectors.remove(it) }
        Log.w(
            TAG,
            "容量上限 $maxVectorsInMemory 已达，淘汰 ${victims.size} 条向量" +
                "（summary 优先保留；被淘汰的向量无法自动重建，即永久遗忘）",
        )
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureLoaded()
                vectors.values
                    .filter { entry -> matchesFilters(entry, filters) }
                    // 维度守卫：单个维度不符的向量（换 embedding 模型后的遗留）必须跳过，
                    // 否则 cosineSimilarity 抛错会让整次检索失败、召回整体归零
                    .filter { entry ->
                        val ok = entry.vector.size == queryVector.size
                        if (!ok) {
                            Log.w(
                                TAG,
                                "Skipped vector ${entry.id}: dimension " +
                                    "${entry.vector.size} != query ${queryVector.size}",
                            )
                        }
                        ok
                    }
                    .map { entry ->
                        val score = VectorUtils.cosineSimilarity(queryVector, entry.vector)
                        VectorStore.SearchResult(
                            id = entry.id,
                            score = score,
                            content = entry.metadata["content"] as? String ?: "",
                            metadata = entry.metadata,
                        )
                    }
                    .sortedByDescending { it.score }
                    .take(topK)
            }
        }

    override suspend fun get(id: String): VectorStore.VectorEntry? {
        return mutex.withLock {
            ensureLoaded()
            vectors[id]
        }
    }

    override suspend fun delete(id: String) {
        // 先取锁做内存删除，再在锁外落盘：落盘自己会取锁，而在锁内调用它会二次取锁（Mutex 不可重入）
        val snapshot: Snapshot
        mutex.withLock {
            ensureLoaded()
            vectors.remove(id)
            snapshot = Snapshot(vectors.values.toList(), evictForBudgetLocked())
        }
        persistSnapshot(snapshot)
        Log.d(TAG, "Deleted vector: $id")
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
        val snapshot: Snapshot?
        val deleted =
            mutex.withLock {
                ensureLoaded()
                val toDelete =
                    vectors.values.filter { entry ->
                        matchesFilters(entry, filters)
                    }

                // 指令键：命中行的 message_id 先登记为"已删除"，挡住尚在 embedding 的在途写入
                if (filters[VectorStore.MARK_MESSAGES_DELETED] == true) {
                    markDeletedLocked(toDelete)
                }

                toDelete.forEach { entry ->
                    vectors.remove(entry.id)
                }

                if (toDelete.isEmpty()) {
                    snapshot = null
                } else {
                    snapshot = Snapshot(vectors.values.toList(), evictForBudgetLocked())
                }
                toDelete.size
            }

        snapshot?.let { persistSnapshot(it) }
        Log.d(TAG, "Deleted $deleted vectors by filters")
        return deleted
    }

    override suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int {
        val snapshot: Snapshot?
        val deleted =
            mutex.withLock {
                ensureLoaded()
                // 缺失该键的历史条目也算"不相符"：指纹引入前写入的向量来源不明，一并清掉
                val toDelete =
                    vectors.values
                        .filter { entry -> entry.metadata[key]?.toString() != value }
                        .map { it.id }

                toDelete.forEach { vectors.remove(it) }

                snapshot =
                    if (toDelete.isEmpty()) {
                        null
                    } else {
                        Snapshot(vectors.values.toList(), evictForBudgetLocked())
                    }
                toDelete.size
            }

        snapshot?.let { persistSnapshot(it) }
        Log.d(TAG, "Deleted $deleted vectors whose metadata[$key] != $value")
        return deleted
    }

    override suspend fun count(): Int {
        return mutex.withLock {
            ensureLoaded()
            vectors.size
        }
    }

    override suspend fun indexBytes(): Long =
        mutex.withLock {
            ensureLoaded()
            // 有实测值就用实测值：估算偏保守，用它显示占用会让设置页虚高
            measureLocked().bytes
        }

    override suspend fun indexStats(): IndexStats =
        mutex.withLock {
            ensureLoaded()
            val measured = measureLocked()
            IndexStats(
                count = vectors.size,
                bytes = measured.bytes,
                budgetBytes = indexBudgetBytes,
                persistedBytes = lastPersistedBytes,
            )
        }

    // ktlint 与 detekt 行宽口径冲突：单行 121 字符满足 ktlint 的 140 上限但违反 detekt 的 120，
    // 保持断行并豁免 ktlint 的 function-signature（它要求短函数体与签名同行）。
    @Suppress("ktlint:standard:function-signature")
    override suspend fun canIndexMessage(messageId: String): Boolean =
        mutex.withLock { messageId !in deletedMessageIds }

    override suspend fun markMessagesDeleted(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        mutex.withLock {
            deletionGeneration++
            deletedMessageIds.addAll(messageIds.filter { it.isNotBlank() })
        }
    }

    override suspend fun clearMessageDeleted(messageId: String) {
        mutex.withLock { deletedMessageIds.remove(messageId) }
    }

    override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int {
        val snapshot: Snapshot?
        val deleted =
            mutex.withLock {
                ensureLoaded()
                // 没有 session_id 的条目无法判断归属（如指纹引入前的历史数据），一律保留：
                // 对账的准则是"能证明它所属的会话没了"，而不是"我没看见它的会话"。
                // 注意 session_id="cross_session" 的哨兵条目（CHARACTER_LEVEL_SESSION，升级前
                // 的全局/角色级记忆向量）会被当孤儿清掉——这是预期：会话内记忆改造后哨兵归属
                // 不再合法，升级清零 + 本对账共同保证索引里不再残留哨兵向量。
                val orphans =
                    vectors.values.filter { entry ->
                        val session = entry.metadata[SESSION_KEY]?.toString()
                        session != null && session !in knownSessionIds
                    }

                orphans.forEach { entry ->
                    vectors.remove(entry.id)
                    entry.messageIdForGate()?.let { deletedMessageIds.remove(it) }
                }
                if (orphans.isNotEmpty()) deletionGeneration++

                if (orphans.isEmpty()) {
                    snapshot = null
                } else {
                    Log.w(TAG, "启动对账：删除 ${orphans.size} 条所属会话已不存在的向量")
                    snapshot = Snapshot(vectors.values.toList(), evictForBudgetLocked())
                }
                orphans.size
            }

        snapshot?.let { persistSnapshot(it) }
        return deleted
    }

    override suspend fun indexedMessageIds(fingerprint: String): Set<String> =
        mutex.withLock {
            ensureLoaded()
            vectors.values
                // 只有落在当前指纹下的向量才算"已索引"：换模型后旧向量必须被判为待重建，
                // 否则重建会"全部跳过"而召回是空的（旧向量已被检索过滤器挡掉）
                .filter { it.metadata[FINGERPRINT_KEY]?.toString() == fingerprint }
                .mapNotNullTo(mutableSetOf()) { it.metadata[MESSAGE_KEY] as? String }
        }

    override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> =
        mutex.withLock {
            ensureLoaded()
            vectors.values.toList()
        }

    override suspend fun clear() {
        val snapshot: Snapshot
        mutex.withLock {
            ensureLoaded()
            deletionGeneration++
            vectors.clear()
            snapshot = Snapshot(emptyList(), 0)
        }
        persistSnapshot(snapshot)
        Log.d(TAG, "Cleared all vectors")
    }

    override suspend fun flush(): Boolean {
        val snapshot =
            mutex.withLock {
                ensureLoaded()
                savePending = false // 本次直接写当前状态，取消已排的窗口任务
                Snapshot(vectors.values.toList(), evictForBudgetLocked())
            }
        if (persistSnapshot(snapshot)) {
            mutex.withLock { retryDelayMs = FLUSH_DELAY_MS }
            return true
        }
        Log.w(TAG, "flush 落盘失败，已排入重试")
        mutex.withLock { scheduleSaveLocked() }
        return false
    }

    override suspend fun importIndex(content: String): Boolean =
        mutex.withLock {
            ensureLoaded()
            if (!isValidIndex(content)) {
                Log.w(TAG, "索引内容无法解析，保留本地索引")
                return@withLock false
            }

            // 替换前留一份原内容：写坏了要回滚，绝不能留下"目标文件不见了"的状态
            val previous = storageFile.takeIf { it.exists() }?.readBytes()
            // 必须按 UTF-8 写：索引里存着中文正文，用 ISO-8859-1 编码会把它们变成 '?'，
            // 而读回时按 UTF-8 解不回来——恢复出来的记忆正文是坏的
            val bytes = content.toByteArray(Charsets.UTF_8)
            val temp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            val written =
                try {
                    temp.writeBytes(bytes)
                    moveTempIntoPlace(temp)
                } catch (e: Exception) {
                    Log.e(TAG, "索引替换失败：${e.message}", e)
                    temp.delete()
                    false
                }

            if (!written) {
                previous?.let { runCatching { storageFile.writeBytes(it) } }
                return@withLock false
            }

            // 文件已换：丢弃待写并以新文件为准重建内存态，否则旧内存态会把刚换的索引写回去
            savePending = false
            retryDelayMs = FLUSH_DELAY_MS
            vectors.clear()
            loadFromDisk()
            isLoaded = true
            Log.d(TAG, "索引已整体替换并重载（${vectors.size} 条）")
            true
        }

    override suspend fun reload() {
        mutex.withLock {
            // 丢弃待写：紧接着以磁盘内容为准。否则待写任务可能在备份替换索引之后触发，
            // 用替换前的旧内存态把刚恢复的索引覆盖回去
            savePending = false
            vectors.clear()
            loadFromDisk()
            isLoaded = true
        }
        Log.d(TAG, "Reloaded ${vectors.size} vectors from disk")
    }

    /**
     * 检查向量条目是否匹配过滤条件
     *
     * 值有三种形态：
     * - [Iterable]：**集合成员**匹配（`metadata[key] ∈ value`）。召回范围是一组会话时必须用集合，
     *   等值匹配表达不了"当前会话 + 该角色名下其它会话"；
     * - 其它：等值匹配（`metadata[key] == value`）；
     * - 键缺失：不匹配（无值不等于"任意值"——否则漏写的元数据会被任何过滤命中）。
     *
     * 指令键 [VectorStore.MARK_MESSAGES_DELETED] 不是元数据，一律跳过（由删除路径单独解释）。
     */
    private fun matchesFilters(
        entry: VectorStore.VectorEntry,
        filters: Map<String, Any>,
    ): Boolean {
        if (filters.isEmpty()) return true

        return filters.all { (key, value) ->
            if (key == VectorStore.MARK_MESSAGES_DELETED) return@all true
            val metadataValue = entry.metadata[key]
            when (value) {
                is Iterable<*> -> metadataValue != null && value.any { it == metadataValue }
                else -> metadataValue == value
            }
        }
    }

    /**
     * 排入一次合并窗口内的落盘（调用方必须已持有 [mutex]）。
     *
     * 窗口内已有待写任务时直接并入，不再排第二个——所以"高频写入"的代价被压成每窗口一次整文件写。
     */
    private fun scheduleSaveLocked() {
        if (savePending) return
        savePending = true
        writeScope.launch {
            delay(retryDelayMs)
            // 到这里已经离开调用方的锁作用域；后面的落盘自己取锁
            val snapshot =
                mutex.withLock {
                    // 窗口期间被 flush()/reload()/importIndex() 抢先处理过，本次无需再写
                    if (!savePending) return@withLock null
                    savePending = false
                    Snapshot(vectors.values.toList(), evictForBudgetLocked())
                } ?: return@launch

            if (persistSnapshot(snapshot)) {
                mutex.withLock { retryDelayMs = FLUSH_DELAY_MS }
            } else {
                // 写失败**不丢状态**：内存态仍领先磁盘，退避后重排，直到写成功。
                // 此前这里直接丢掉 saveToDisk 的返回值，"直到与内存一致"只是注释里的承诺
                mutex.withLock {
                    retryDelayMs = (retryDelayMs * RETRY_BACKOFF_FACTOR).coerceAtMost(MAX_RETRY_DELAY_MS)
                    Log.w(TAG, "落盘失败，${retryDelayMs}ms 后重试")
                    scheduleSaveLocked()
                }
            }
        }
    }

    /**
     * 立即落盘；失败则排回窗口重试。
     *
     * **必须在锁外调用**（它自己会取锁）：Mutex 不可重入，在 `…Locked` 里调用它就是死锁。
     *
     * 这里是**预算的唯一收口**：任何一条落盘路径（合并窗口、立即落盘、flush）都经过它，
     * 而它每次都用真实编码体量复核一次预算，超了就再淘汰一轮重编码。只在锁内估算一次是不够的
     * ——写入窗口会在 flush 之后被重新排上，那条路径若不复核，磁盘上的索引就会超过预算
     * （低存储设备上这就是写失败）。
     */
    private suspend fun persistSnapshot(snapshot: Snapshot): Boolean =
        persistMutex.withLock {
            // 串行化整文件替换，并在真正开始写入前重取最新快照，避免旧快照晚到覆盖新状态。
            var current =
                mutex.withLock {
                    Snapshot(vectors.values.toList(), evictForBudgetLocked() + snapshot.evicted)
                }
            var content = encode(current)
            var evicted = current.evicted

            var attempt = 0
            while (attempt < BUDGET_ENFORCE_ATTEMPTS &&
                indexBudgetBytes > 0 &&
                content.toByteArray(Charsets.UTF_8).size.toLong() > indexBudgetBytes
            ) {
                attempt++
                val trimmed =
                    mutex.withLock {
                        evictForBudgetLocked()
                        Snapshot(vectors.values.toList(), 0)
                    }
                if (trimmed.entries.size == current.entries.size) break
                evicted += current.entries.size - trimmed.entries.size
                current = trimmed
                content = encode(current)
            }

            if (evicted > 0) {
                Log.w(TAG, "落盘预算 $indexBudgetBytes B 已达，本次淘汰 $evicted 条向量")
            }
            val ok = writeToDisk(content)
            if (!ok) {
                Log.w(TAG, "落盘失败，已排入下一个写入窗口重试")
                mutex.withLock { scheduleSaveLocked() }
            }
            ok
        }

    /**
     * 整文件写入：`.tmp` + 原子改名。
     *
     * 纯 IO：不碰 [mutex]、不碰 [vectors]（内容由调用方在锁内拍好的快照编码而来）。
     * 这样"持锁"与"阻塞 IO"彻底分开，不会再出现锁内跨线程的形态。
     *
     * @return 是否成功。失败不抛异常——向量写入不该把调用方（发送链路）掀翻，由重试窗口收敛。
     */
    private suspend fun writeToDisk(content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bytes = content.toByteArray(Charsets.UTF_8)
                val temp = File(storageFile.parentFile, "${storageFile.name}.tmp")
                temp.writeBytes(bytes)
                if (!moveTempIntoPlace(temp)) {
                    false
                } else {
                    mutex.withLock { lastPersistedBytes = bytes.size.toLong() }
                    Log.d(TAG, "Saved ${bytes.size} bytes of vectors to disk")
                    true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save vectors to disk: ${e.message}", e)
                false
            }
        }

    private fun moveTempIntoPlace(temp: File): Boolean {
        return try {
            Files.move(temp.toPath(), storageFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            try {
                Files.move(temp.toPath(), storageFile.toPath(), REPLACE_EXISTING)
                true
            } catch (_: Exception) {
                temp.delete()
                false
            }
        }
    }

    private fun encode(snapshot: Snapshot): String =
        json.encodeToString(
            VectorStoreData(
                vectors =
                    snapshot.entries.map { entry ->
                        SerializableVectorEntry(
                            id = entry.id,
                            vector = entry.vector.toList(),
                            metadata = entry.metadata.mapValues { (_, v) -> v.toString() },
                        )
                    },
            ),
        )

    /**
     * 锁内拍下的"这次要落盘的内容"。
     *
     * 引入它是为了把"持锁"与"阻塞 IO"彻底分开：锁内只把 [vectors] 复制成不可变快照并按预算收敛，
     * 编码与写文件都在锁外进行（Mutex 不可重入，锁内再取锁就是死锁）。
     *
     * @param evicted 这次快照生成时因超预算被淘汰的条数（仅用于日志如实上报）
     */
    private data class Snapshot(
        val entries: List<VectorStore.VectorEntry>,
        val evicted: Int,
    )

    /**
     * 按预算淘汰，直到预估字节落回预算内（调用方必须已持有 [mutex]）。
     *
     * 只在**真的超预算**时才动淘汰，正常路径零开销。
     *
     * @return 实际淘汰条数
     */
    private fun evictForBudgetLocked(): Int {
        val measured = measureLocked()
        if (indexBudgetBytes <= 0 || measured.bytes <= indexBudgetBytes) return 0

        val plan =
            VectorEviction.planForBudget(
                entries = vectors.values.toList(),
                nextBytes = measured.bytes,
                budgetBytes = indexBudgetBytes,
            )
        plan.victims.forEach { id -> vectors.remove(id) }
        return plan.victims.size
    }

    /** 逐条量出索引的字节占用（调用方必须已持有 [mutex]） */
    private fun measureLocked(): MeasuredIndex {
        var bytes = INDEX_SHELL_BYTES
        vectors.values.forEach { entry -> bytes += VectorEviction.serializedSizeOf(entry) }
        return MeasuredIndex(bytes)
    }

    /** 批量登记删除意图（调用方必须已持有 [mutex]） */
    private fun markDeletedLocked(entries: List<VectorStore.VectorEntry>) {
        val ids = entries.mapNotNull { it.messageIdForGate() }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        deletionGeneration++
        deletedMessageIds.addAll(ids)
    }

    private data class MeasuredIndex(val bytes: Long)

    /**
     * 从磁盘加载
     */
    private suspend fun loadFromDisk() =
        withContext(Dispatchers.IO) {
            if (!storageFile.exists()) {
                Log.d(TAG, "No existing vector store file")
                return@withContext
            }

            try {
                val content = storageFile.readText()
                val data = json.decodeFromString<VectorStoreData>(content)

                // 旧格式收敛：历史实现每次写入生成随机 vectorId 且旧项不删，磁盘上同一
                // (owner, session, message_id) 可能堆多条旧副本——全部会被召回。加载时按
                // 来源身份去重，只保留文件顺序中的最后一条（向量是可重建投影，任一条都可
                // 由下次写入自愈；保留最后一条最接近"最新一次写入"的语义）。
                val keptByIdentity = mutableMapOf<Triple<*, *, *>, String>()

                data.vectors.forEach { entry ->
                    // 已被删除的消息不再复活：内存里的删除意图比磁盘旧内容新
                    val messageId = entry.metadata[MESSAGE_KEY]
                    if (messageId != null && messageId in deletedMessageIds) return@forEach
                    val identity = identityOf(entry.metadata)
                    if (identity != null) {
                        keptByIdentity.remove(identity)?.let { staleId -> vectors.remove(staleId) }
                        keptByIdentity[identity] = entry.id
                    }
                    vectors[entry.id] =
                        VectorStore.VectorEntry(
                            id = entry.id,
                            vector = entry.vector.toFloatArray(),
                            metadata = entry.metadata,
                        )
                }

                Log.d(TAG, "Loaded ${vectors.size} vectors from disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vectors from disk: ${e.message}", e)
            }
        }

    @Serializable
    private data class VectorStoreData(
        val vectors: List<SerializableVectorEntry>,
    )

    @Serializable
    private data class SerializableVectorEntry(
        val id: String,
        val vector: List<Float>,
        val metadata: Map<String, String>,
    )
}

/** 该条目对应的源消息 id（登记/解除删除意图用）；摘要副本同样带 `message_id`。 */
private fun VectorStore.VectorEntry.messageIdForGate(): String? = metadata[InMemoryVectorStore.MESSAGE_KEY] as? String
