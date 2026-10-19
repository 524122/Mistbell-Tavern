package com.mistbell.tavern.android.data.vector

/**
 * 向量存储接口
 *
 * 提供向量的增删改查和相似度检索功能
 * 移植自后端 VectorStore.java
 *
 * 注解说明：存储契约本身就该覆盖"增删查 + 维护（reload/flush）+ 容量统计"这几类操作，
 * 拆成多个接口只会逼调用方同时持有好几份引用，没有收益。
 */
@Suppress("TooManyFunctions")
interface VectorStore {
    /**
     * 添加向量到存储
     *
     * @param id 向量ID
     * @param vector 向量数据（通常是 1536 维的浮点数组）
     * @param metadata 元数据（包含 content、ownerId、characterId 等）
     */
    suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    )

    /** Captures the deletion generation before embedding starts. */
    suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean = false,
    ): MessageWriteToken?

    /** Checks the captured generation and inserts under the same store lock. */
    suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: MessageWriteToken,
    ): Boolean

    data class MessageWriteToken(val messageId: String, val generation: Long, val allowDeleted: Boolean)

    /**
     * 批量添加向量
     */
    suspend fun addBatch(entries: List<VectorEntry>)

    /**
     * 搜索相似向量
     *
     * @param queryVector 查询向量
     * @param topK 返回前 K 个最相似的结果
     * @param filters 过滤条件（如 ownerId、characterId、sessionId）。
     *   值为 [Iterable] 时按**集合成员**匹配（`metadata[key] ∈ value`），用于"召回范围是一组会话"
     *   这种过滤；否则按等值匹配。
     * @return 搜索结果列表，按相似度降序排列
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any> = emptyMap(),
    ): List<SearchResult>

    /**
     * 根据 ID 获取向量
     */
    suspend fun get(id: String): VectorEntry?

    /**
     * 根据 ID 删除向量
     */
    suspend fun delete(id: String)

    /**
     * 根据过滤条件批量删除
     *
     * [filters] 里可以带一个**非元数据**的指令键 [MARK_MESSAGES_DELETED]：见 [markMessagesDeleted]，
     * 命中行的 message_id 会在删除前登记为"已删除"，用于挡住尚在 embedding 的在途写入。
     * 该键不参与元数据匹配（它不是元数据），其余键仍是等值/集合匹配。
     */
    suspend fun deleteByFilters(filters: Map<String, Any>): Int

    /**
     * 维护用：删除 metadata[key] 与 [value] 不相等的向量（**含缺失该键的历史条目**），返回删除条数。
     *
     * 用途：切换 embedding 模型后清掉旧指纹的向量——它们既进不了召回（被指纹过滤挡住），
     * 又占着内存上限把新向量挤出去。
     */
    suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int

    /**
     * 获取存储的向量数量
     */
    suspend fun count(): Int

    /**
     * 索引当前占用的磁盘字节数（按落盘时的精确序列化长度累计）。
     *
     * 与 [count] 分开：条数上限管不住体积——同一份索引在 512 维本地模型与 1536 维 API 模型下
     * 差 3 倍，真正会撑爆低存储设备的是字节数。落盘预算（见 [InMemoryVectorStore.indexBudgetBytes]）
     * 按这个值判定，设置页也用它显示占用。
     */
    suspend fun indexBytes(): Long

    /**
     * 索引容量与落盘预算的可观测快照。
     */
    suspend fun indexStats(): IndexStats

    // ---- 写入闸门：把"删除后又被后台写入捞回来"这类竞态堵在数据层 ----

    /**
     * 该 message id 现在还能不能写进索引。
     *
     * 写入路径的 embedding 是慢操作（本地推理或 API 往返）；它跑完时源消息完全可能已经被删掉
     * （LLM 失败回滚、撤销、回退、重抽、清空会话）。此时那条向量是"删除之后才落地"的孤儿。
     * 调用方拿不到删除事件，只能由存储记住删除意图、在写入落地前问一次。
     */
    suspend fun canIndexMessage(messageId: String): Boolean

    /**
     * 记录"这些 message id 已删除"，此后针对它们的写入一律 [canIndexMessage] = false。
     *
     * 与 [deleteByFilters] 的区别是**语义**：删除是"替换掉这条消息的旧向量"（消息还在，
     * 只是内容变了）还是"这条消息没了"。只有后者才该压制后续写入；[deleteByFilters] 因此
     * **不**登记删除意图，替换型写入走 [clearMessageDeleted] 解除。
     */
    suspend fun markMessagesDeleted(messageIds: Collection<String>)

    /** 解除删除意图：消息被重新创建／重写时需要，否则它的新向量会一直被压住。 */
    suspend fun clearMessageDeleted(messageId: String)

    /**
     * 启动对账：删掉**所属会话已不存在**的向量，返回删除条数。
     *
     * 进程被杀会丢掉内存里的删除意图，磁盘上却留着已删会话的向量；重启后它们照样能被召回
     * （跨会话召回还会放大这个影响）。这里以 Room 的会话表为准做一次收敛。
     *
     * @param knownSessionIds Room 里现存的**全部**会话 id（跨 owner）。调用方必须先确认会话表非空，
     *   否则库没打开／查询失败会退化成"删光整份索引"。
     */
    suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int

    companion object {
        /**
         * [deleteByFilters] 的指令键：登记删除意图（不是元数据，不参与匹配）。
         *
         * 删除方往往只有"会话/角色"这一层条件，拿不到该范围内每条消息的 id；而在途的
         * embedding 用的是 message_id 作闸门键。这条指令让存储自己在删除时把命中行的
         * message_id 登记掉——调用方不需要先遍历一遍索引。
         */
        const val MARK_MESSAGES_DELETED = "__mark_deleted"

        /**
         * 结构化记忆的角色级记录落到向量层时用的 `session_id` 哨兵值。
         *
         * 向量过滤是逐字段等值匹配、没有"空值"语义，`session_id IS NULL` 的角色级记忆必须给个
         * 具体值占位。定义在这里（而不是各调用方各写一份字面量）是因为**写入方与召回方必须逐字一致**：
         * 此前两边各写一份常量，一旦有人改了其中一处，角色级记忆的摘要副本就会静默召不回。
         */
        const val CHARACTER_LEVEL_SESSION = "cross_session"

        /** 角色级记录缺失 `character_id` 时的哨兵值（同上：写入方与召回方共用一份定义）。 */
        const val CHARACTER_LEVEL_CHARACTER = "unknown"

        /** 结构化记忆同步到向量库时的 content_type（召回去重与淘汰豁免都按它判断）。 */
        const val SUMMARY_CONTENT_TYPE = "summary"
    }

    /**
     * 维护用：索引中**当前 embedding 指纹下**已存在的 message_id 集合。
     *
     * 重建索引时用它跳过已索引的消息——重复 embedding 既费额度又会产生重复向量
     * （每次写入生成新的 vectorId，message_id 是唯一的幂等键）。
     *
     * [fingerprint] 必须参与判断：只按 message_id 跳过的话，换过 embedding 模型之后
     * 旧指纹的向量会把所有候选都判成"已索引"，重建一条都不写、召回却是空的。
     * 无指纹的历史条目同样算"未索引"（它们本就进不了检索）。
     */
    suspend fun indexedMessageIds(fingerprint: String): Set<String>

    /** 维护与恢复用的稳定快照；调用方不得在召回热路径扫描全表。 */
    suspend fun entriesSnapshot(): List<VectorEntry>

    /**
     * 清空所有向量
     */
    suspend fun clear()

    /**
     * 维护用：丢弃内存态、从持久化介质重新加载。
     *
     * 备份恢复在进程运行期间原子替换了索引文件，必须重载，否则本进程仍拿旧索引做召回。
     */
    suspend fun reload()

    /**
     * 立即把内存态落盘，并放弃尚未触发的合并写入窗口。
     *
     * 写入是合并的（见 [InMemoryVectorStore]），正常情况下落后磁盘一个窗口；备份导出前必须
     * 先 flush，否则备份里的索引是过期的；进程退到后台时也应 flush，缩小被强杀的丢失窗口。
     *
     * @return 本次落盘是否成功。调用方（如备份导出）需要据此判断拿到的是不是最新索引——
     *         一个只会吞异常的 flush 保证不了任何事。
     */
    suspend fun flush(): Boolean

    /**
     * 维护用：用 [content] 整体替换持久化索引。
     *
     * 在存储自己的锁内完成"校验 → 落盘 → 重建内存态"三件事，调用方（备份恢复）**不得**
     * 自己去动索引文件：那样会与后台的 add/flush 各写各的（同一个 `.tmp`、相互改名、
     * 用替换前的旧内存态把刚恢复的索引覆盖回去）。
     *
     * @return 是否替换成功；内容不可解析或写盘失败都返回 false，并保持原索引不变
     */
    suspend fun importIndex(content: String): Boolean

    /**
     * 向量条目
     */
    data class VectorEntry(
        val id: String,
        val vector: FloatArray,
        val metadata: Map<String, Any>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VectorEntry

            if (id != other.id) return false
            if (!vector.contentEquals(other.vector)) return false
            if (metadata != other.metadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + vector.contentHashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }

    /**
     * 搜索结果
     */
    data class SearchResult(
        val id: String,
        // 相似度分数 (0-1)
        val score: Float,
        val content: String,
        val metadata: Map<String, Any>,
    )
}

/**
 * 索引容量快照：条数 + 字节占用 + 落盘预算。
 *
 * 与 [VectorStore] 同文件、顶层声明：它是存储契约的组成（`indexStats()` 的返回类型），
 * 但不需要嵌进接口里——调用方 `import ...VectorStore` 后直接可用 `IndexStats`。
 */
data class IndexStats(
    val count: Int,
    val bytes: Long,
    /** 落盘预算（字节）；0 或负数表示不限制。 */
    val budgetBytes: Long,
    /** 最近一次成功写盘的实际字节数；可能暂时落后于内存估算。 */
    val persistedBytes: Long? = null,
) {
    val overBudget: Boolean get() = budgetBytes > 0 && bytes > budgetBytes
}
