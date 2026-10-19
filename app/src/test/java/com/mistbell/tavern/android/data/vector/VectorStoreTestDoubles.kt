package com.mistbell.tavern.android.data.vector

/**
 * 向量侧的共享测试替身。
 *
 * 存储本体依赖 Android Context（落盘路径），所以服务层的测试都靠这里的替身——
 * 过滤语义要与 `InMemoryVectorStore.matchesFilters` 保持一致（全字段等值）。
 */
internal class FakeVectorStore : VectorStore {
    val entries = linkedMapOf<String, VectorStore.VectorEntry>()
    val deleteFilterLog = mutableListOf<Map<String, Any>>()
    val searchFilterLog = mutableListOf<Map<String, Any>>()

    /** 命中这些 message_id 的写入直接抛错，用来验证"单条失败不中断整体" */
    val failOnMessageIds = mutableSetOf<String>()

    /** 被登记为"已删除"的 message id（写入闸门），与真实存储的语义保持一致 */
    val deletedMessageIds = mutableSetOf<String>()
    private var deletionGeneration = 0L

    /** 索引统计里回填的预算；0 = 不限制（与生产默认不同，测试按需显式给） */
    var budgetBytes: Long = 0

    /**
     * 种一条向量。
     *
     * @param injectFingerprint 默认 true：检索一律按 embedding 指纹过滤（跨模型向量不得混搜），
     *   种下的向量必须是"当前指纹"的，否则任何检索都查不到它——测试替身替的是生产写入的形状。
     *   验证"清理旧指纹"这类用例时传 false，自己写 metadata 里的指纹。
     */
    fun seed(
        id: String,
        metadata: Map<String, Any>,
        injectFingerprint: Boolean = true,
    ) {
        entries[id] =
            VectorStore.VectorEntry(
                id,
                FloatArray(4) { 1f },
                if (injectFingerprint) metadata + ("embedding_fingerprint" to FINGERPRINT) else metadata,
            )
    }

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        val messageId = metadata["message_id"] as? String
        if (messageId != null && messageId in failOnMessageIds) {
            error("测试注入的写入失败：$messageId")
        }
        entries[id] = VectorStore.VectorEntry(id, vector, metadata)
    }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        entries.forEach { this.entries[it.id] = it }
    }

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean {
        val allowed =
            token.messageId == messageId &&
                token.generation == deletionGeneration &&
                (token.allowDeleted || messageId !in deletedMessageIds)
        if (!allowed) return false
        add(id, vector, metadata)
        return true
    }

    override suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean,
    ): VectorStore.MessageWriteToken? {
        if (!replacePrevious && messageId in deletedMessageIds) return null
        if (replacePrevious) deletedMessageIds.remove(messageId)
        return VectorStore.MessageWriteToken(messageId, deletionGeneration, replacePrevious)
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> {
        searchFilterLog += filters
        return entries.values
            .filter { matches(it.metadata, filters) }
            .map { VectorStore.SearchResult(it.id, 1f, it.metadata["content"] as? String ?: "", it.metadata) }
            .take(topK)
    }

    override suspend fun get(id: String): VectorStore.VectorEntry? = entries[id]

    override suspend fun delete(id: String) {
        entries.remove(id)
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
        deleteFilterLog += filters
        val doomed = entries.values.filter { matches(it.metadata, filters) }
        // 指令键（与 InMemoryVectorStore.deleteByFilters 同语义）：命中行的 message_id 登记为已删除
        if (filters[VectorStore.MARK_MESSAGES_DELETED] == true) {
            markMessagesDeleted(doomed.mapNotNull { it.metadata["message_id"] as? String })
        }
        doomed.forEach { entries.remove(it.id) }
        return doomed.size
    }

    override suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int {
        val doomed = entries.values.filter { it.metadata[key]?.toString() != value }.map { it.id }
        doomed.forEach { entries.remove(it) }
        return doomed.size
    }

    override suspend fun count(): Int = entries.size

    /** 字节数用"条数 × 固定体量"占位：本替身只验证统计口径被读出，精确量法由 VectorEvictionTest 覆盖 */
    override suspend fun indexBytes(): Long = entries.size.toLong() * STUB_ENTRY_BYTES

    override suspend fun indexStats(): IndexStats =
        IndexStats(
            count = entries.size,
            bytes = indexBytes(),
            budgetBytes = budgetBytes,
        )

    override suspend fun canIndexMessage(messageId: String): Boolean = messageId !in deletedMessageIds

    override suspend fun markMessagesDeleted(messageIds: Collection<String>) {
        deletionGeneration++
        deletedMessageIds += messageIds
        val doomed =
            entries.values
                .filter { entry ->
                    val messageId = entry.metadata["message_id"]
                    messageId is String && messageIds.contains(messageId)
                }.map { it.id }
        doomed.forEach { entries.remove(it) }
    }

    override suspend fun clearMessageDeleted(messageId: String) {
        deletedMessageIds -= messageId
    }

    override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int {
        val doomed =
            entries.values
                .filter { it.metadata["session_id"]?.toString()?.let { s -> s !in knownSessionIds } == true }
                .map { it.id }
        doomed.forEach { entries.remove(it) }
        return doomed.size
    }

    override suspend fun indexedMessageIds(fingerprint: String): Set<String> =
        entries.values
            .filter { it.metadata["embedding_fingerprint"]?.toString() == fingerprint }
            .mapNotNullTo(mutableSetOf()) { it.metadata["message_id"] as? String }

    override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> = entries.values.toList()

    override suspend fun clear() {
        deletionGeneration++
        entries.clear()
    }

    override suspend fun reload() {
        // 测试替身无持久化介质，重载无操作
    }

    override suspend fun flush(): Boolean {
        // 测试替身无持久化介质，落盘恒成功
        return true
    }

    override suspend fun importIndex(content: String): Boolean {
        // 测试替身无持久化介质，整体替换无操作
        return true
    }

    // 与 InMemoryVectorStore.matchesFilters 逐字对齐（见该方法的说明）：
    // Iterable = 集合成员匹配；指令键不是元数据，一律跳过
    private fun matches(
        metadata: Map<String, Any>,
        filters: Map<String, Any>,
    ): Boolean =
        filters.all { (k, v) ->
            if (k == VectorStore.MARK_MESSAGES_DELETED) return@all true
            val actual = metadata[k]
            when (v) {
                is Iterable<*> -> actual != null && v.any { it == actual }
                else -> actual == v
            }
        }

    private companion object {
        const val STUB_ENTRY_BYTES = 64L

        /** 与 [StubEmbeddingService] 的默认指纹一致 */
        const val FINGERPRINT = "stub:model-a:4"
    }
}

/** 固定指纹的 embedding 替身：用来验证按指纹/维度隔离，以及写入时的元数据落库 */
internal class StubEmbeddingService(
    private val fp: String = "stub:model-a:4",
) : EmbeddingService {
    override suspend fun embed(text: String): FloatArray = FloatArray(4) { 0.5f }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { FloatArray(4) { 0.5f } }

    override fun getDimension(): Int = 4

    override val fingerprint: String get() = fp
}
