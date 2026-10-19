package com.mistbell.tavern.android.data.vector

/**
 * 向量容量淘汰策略：索引超限时该丢谁。
 *
 * 索引有两条上限，都必须丢东西，而被丢掉的向量**无法自动重建**（重建要重新调用 embedding，
 * 本地要跑推理、API 要花额度），所以每次淘汰都是一次遗忘：
 *
 * - **条数上限**（[InMemoryVectorStore.maxVectorsInMemory]，生产传 0 即关闭）；
 * - **落盘预算**（[InMemoryVectorStore.indexBudgetBytes]）：条数管不住体积——同一份索引在
 *   512 维本地模型与 1536 维 API 模型下差 3 倍，真正撑爆低存储设备的是字节数。
 *
 * 两条上限共用同一套优先级：**先丢普通消息 → 同档内丢 `created_at` 最旧的 → 缺时间戳的历史条目最先丢**。
 * 排序是稳定且可预期的：
 *
 * 1. 结构化记忆同步过来的 summary 是被蒸馏过的事实，与一条普通聊天消息不是一个量级，最后才丢；
 * 2. 插入顺序 ≠ 新旧：写入已存在的 id 不会把它挪到队尾（[MutableMap] 保留首次插入位置），
 *    于是"刚更新过的记忆"反而可能排在队首被丢——所以按 `created_at` 而不是插入序。
 *
 * 纯函数、不碰存储：存储本体依赖 Android Context 跑不了 JVM 单测，而"丢谁"恰恰是最该有回归的地方。
 */
internal object VectorEviction {
    /** 结构化记忆同步副本的 content_type：价值最高，最后才丢 */
    internal const val CONTENT_TYPE_SUMMARY = "summary"

    /**
     * 从 [entries] 中挑出 [overflow] 条应淘汰的向量 id，按淘汰优先级从高到低。
     */
    internal fun selectVictims(
        entries: List<VectorStore.VectorEntry>,
        overflow: Int,
    ): List<String> {
        if (overflow <= 0 || entries.isEmpty()) return emptyList()
        return orderedByDisposability(entries)
            .take(overflow)
            .map { it.id }
    }

    /**
     * 落盘预算下的淘汰计划。
     *
     * @param entries 全部条目（调用方在存储锁内取的快照）
     * @param nextBytes 这些条目按当前格式落盘的字节数
     * @param budgetBytes 预算；0 或负数 = 不限制
     * @return (需要淘汰的 id，淘汰后的预估字节数)。不超预算时淘汰集为空、预估值为 [nextBytes]。
     */
    internal fun planForBudget(
        entries: List<VectorStore.VectorEntry>,
        nextBytes: Long,
        budgetBytes: Long,
    ): BudgetPlan {
        if (budgetBytes <= 0 || nextBytes <= budgetBytes) {
            return BudgetPlan(emptyList(), nextBytes)
        }

        val victims = mutableListOf<String>()
        var bytes = nextBytes
        val candidates = orderedByDisposability(entries)
        // 永不清空索引：最后一条不参与淘汰。留下一条超预算的向量，比留下空索引更符合
        // "长期记忆"的预期（空索引等于把整份记忆一次抹掉，而它甚至无法自动重建）
        val evictable = candidates.dropLast(1)
        for (entry in evictable) {
            if (bytes <= budgetBytes) break
            victims += entry.id
            bytes -= serializedSizeOf(entry)
        }

        return BudgetPlan(victims, bytes.coerceAtLeast(0))
    }

    /**
     * 单条向量在落盘格式里的字节数，**逐字段与 [InMemoryVectorStore] 的序列化对齐**。
     *
     * 各常量刻意取"最长形态"并留余量：这份估算要用来判定"写下去会不会超过预算"，**低估**
     * 会让索引真的写超预算（然后在低存储设备上写失败），**高估**只是提前淘汰一点内容。
     * 因此宁可偏大——落盘时还会用真实字节数复核一次（见 `InMemoryVectorStore` 的预算收敛）。
     */
    internal fun serializedSizeOf(entry: VectorStore.VectorEntry): Long {
        var size = ENTRY_OVERHEAD + utf8Length(entry.id)
        // 每个分量最长形态 "-1.4012985E-45,"（含分隔符）
        size += entry.vector.size.toLong() * FLOAT_FIELD_BYTES
        entry.metadata.forEach { (key, value) ->
            size += METADATA_ENTRY_OVERHEAD + utf8Length(key) + utf8Length(value.toString())
        }
        return size
    }

    // UTF-8 字节数：中文元数据（标题、正文）是索引体积的大头，按字符数算会严重低估
    private fun utf8Length(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            bytes +=
                when {
                    codePoint < UTF8_ONE_BYTE_MAX -> UTF8_ONE_BYTE_LENGTH
                    codePoint < UTF8_TWO_BYTE_MAX -> UTF8_TWO_BYTE_LENGTH
                    codePoint < UTF8_THREE_BYTE_MAX -> UTF8_THREE_BYTE_LENGTH
                    else -> UTF8_FOUR_BYTE_LENGTH
                }
            i += Character.charCount(codePoint)
        }
        return bytes
    }

    private fun orderedByDisposability(entries: List<VectorStore.VectorEntry>): List<VectorStore.VectorEntry> =
        entries.sortedWith(compareBy({ isSummary(it) }, { createdAtOf(it) }))

    // Boolean 天然可比（false < true）：普通消息先于 summary 被淘汰
    private fun isSummary(entry: VectorStore.VectorEntry): Boolean {
        return entry.metadata["content_type"] == CONTENT_TYPE_SUMMARY
    }

    // created_at 存的是 ISO-8601 或毫秒时间戳字符串，字典序与时间序一致；缺失按最旧处理
    private fun createdAtOf(entry: VectorStore.VectorEntry): String {
        return entry.metadata["created_at"]?.toString().orEmpty()
    }

    /** `{"id":"…","vector":[…],"metadata":{…}}` 的固定骨架字符数（保守略估） */
    private const val ENTRY_OVERHEAD = 64L

    /** 单个分量在紧凑 JSON 里的最长形态 + 分隔符 + 余量 */
    private const val FLOAT_FIELD_BYTES = 20L

    /** `"key":"value",` 的引号/冒号/逗号等骨架 + 余量 */
    private const val METADATA_ENTRY_OVERHEAD = 12L

    // UTF-8 编码的码点上界：1 字节 < 0x80、2 字节 < 0x800、3 字节 < 0x10000，其余 4 字节
    private const val UTF8_ONE_BYTE_MAX = 0x80
    private const val UTF8_TWO_BYTE_MAX = 0x800
    private const val UTF8_THREE_BYTE_MAX = 0x10000
    private const val UTF8_ONE_BYTE_LENGTH = 1
    private const val UTF8_TWO_BYTE_LENGTH = 2
    private const val UTF8_THREE_BYTE_LENGTH = 3
    private const val UTF8_FOUR_BYTE_LENGTH = 4

    internal data class BudgetPlan(
        val victims: List<String>,
        val bytesAfterEviction: Long,
    )
}
