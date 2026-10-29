package com.mistbell.tavern.android.data.vector

/**
 * 向量存储接口
 *
 * 提供向量的增删改查和相似度检索功能
 * 移植自后端 VectorStore.java
 *
 * 存储契约按角色分组以便实现侧委托；调用方仍只持有 [VectorStore] 一个引用。
 */
interface VectorStore :
    ContentOperations,
    StatisticsOperations,
    GateOperations,
    MaintenanceOperations {
    data class MessageWriteToken(val messageId: String, val generation: Long, val allowDeleted: Boolean)

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
