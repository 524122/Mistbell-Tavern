package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.AppDatabase

/**
 * 一次召回能看见的范围——结构化 / 向量 / 词法三条通道**共用的唯一口径**。
 *
 * ## 当前语义（2026-10 产品决策）
 *
 * 长期记忆**永久只做 ownerId + sessionId 范围**：窗口恒为「owner + 当前会话」——
 *
 * - [characterId] 恒为 `null`：不按角色收窄。群聊里 NPC 与主角色的记忆同属这一次对话，
 *   会话边界已覆盖角色隔离，额外的角色条件只会把同一次对话切碎；
 * - [sessionIds] 恒为 `{当前会话}`（[currentSessionId] 为 blank 时为 `null`）：
 *   `null` 表示**没有会话边界**——三条通道此时一律**拒绝召回**（返回空），
 *   绝不退化成 owner 级（那正是"别的会话内容被注入本提示词"的历史缺陷）；
 * - [includeUnassigned] 恒为 `false`：不再召回 `session_id IS NULL` 的角色级记录；
 * - [sessionLimitExceeded] 恒为 `false`：会话集合只有一个 id，不存在枚举上限问题。
 *
 * 窗口内的 [scope] 字段仅为兼容旧数据类签名保留，解析结果恒为 [MemoryScope.SESSION]；
 * [MAX_SESSION_IDS] 同理（历史常量，不再参与判定）。
 *
 * ## 有意的取舍
 *
 * **宁可不召回，不可串线**：会话被删除/尚未建立时窗口没有边界，三条通道一致地召回空气，
 * 而不是静默放宽到 owner 级。
 *
 * 解析逻辑不碰 Room（[resolve] 的 db / characterId / scope / ownedSessionIds 参数仅为
 * 兼容旧签名保留，不参与解析）。
 */
data class RecallWindow(
    val ownerId: String,
    /** 本轮对话所在的会话：排除窗口与"本会话"判定都用它 */
    val currentSessionId: String,
    val characterId: String?,
    val sessionIds: Set<String>?,
    val includeUnassigned: Boolean,
    val scope: MemoryScope = MemoryScope.SESSION,
    val sessionLimitExceeded: Boolean = false,
) {
    /**
     * 向量层的会话过滤值集合：`null` = 没有会话边界（调用方必须拒绝召回，见文件头说明）。
     *
     * 恒等于 [sessionIds]——历史版本曾在这里附加角色级哨兵
     * （VectorStore.CHARACTER_LEVEL_SESSION），会话内记忆不再需要它。
     */
    val vectorSessionValues: Set<String>?
        get() = sessionIds

    /** 历史遗留字段：向量通道已不按角色过滤（见文件头），恒为 null。保留仅为兼容旧引用。 */
    val vectorCharacterValues: Any?
        get() = characterId

    /**
     * 历史遗留字段：向量通道是否要把"角色级记录"（哨兵值占位）算进过滤。
     * 会话内记忆不再召回角色级记录，恒为 false。保留仅为兼容旧引用。
     */
    val includesCharacterLevelRecords: Boolean
        get() = false

    companion object {
        /**
         * 历史常量：单次召回允许枚举的会话数上限。**已不再参与任何判定**
         * （窗口恒为单会话），保留仅为兼容旧测试引用。
         */
        const val MAX_SESSION_IDS = 500

        /**
         * 生产入口（历史签名）。**db / characterId / scope 不再参与解析**：
         * 窗口恒为 owner + 当前会话，见文件头说明。
         *
         * 参数刻意保留（兼容旧调用方/旧测试的签名），归一化后不再使用。
         */
        @Suppress("UnusedParameter")
        suspend fun resolve(
            db: AppDatabase,
            ownerId: String,
            characterId: String,
            currentSessionId: String,
            scope: MemoryScope,
        ): RecallWindow = resolve(ownerId, characterId, currentSessionId, scope, emptyList())

        /**
         * 解析召回窗口（纯函数）。**characterId / scope / ownedSessionIds 不再参与解析**，
         * 保留参数仅为兼容旧调用方与旧测试的编译（刻意保留，归一化后忽略）。
         */
        @Suppress("UnusedParameter")
        fun resolve(
            ownerId: String,
            characterId: String,
            currentSessionId: String,
            scope: MemoryScope,
            ownedSessionIds: Collection<String>,
        ): RecallWindow =
            RecallWindow(
                ownerId = ownerId,
                currentSessionId = currentSessionId,
                characterId = null,
                sessionIds = currentSessionId.takeIf { it.isNotBlank() }?.let { setOf(it) },
                includeUnassigned = false,
                scope = MemoryScope.SESSION,
                sessionLimitExceeded = false,
            )
    }
}
