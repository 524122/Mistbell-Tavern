package com.mistbell.tavern.android.data.repository

/**
 * 长期记忆的召回范围。
 *
 * **产品决策（2026-10）**：长期记忆永久只做 **ownerId + sessionId** 范围——
 * 禁止跨会话召回/移动/复制。结构化、向量、词法三条召回通道统一按 owner + 当前会话过滤；
 * 群聊不按主角色过滤；缺失 sessionId 时拒绝召回，绝不降级扩大范围。
 *
 * 因此本枚举实际只剩 [SESSION] 一个有效取值：
 * - [CHARACTER] / [OWNER] 已**永久废弃**，仅为兼容旧设置值/旧备份中的原始字符串保留枚举项
 *   （[fromRaw] 对一切输入都归一化为 [SESSION]，[toRaw] 保留以便旧数据仍可写回）；
 * - 新代码不得使用 CHARACTER / OWNER，也不得新增依赖 [MemoryScope.KEY] 的档位逻辑。
 */
enum class MemoryScope {
    SESSION,

    /** 永久废弃：仅为兼容旧设置值/旧备份保留枚举项；语义已归一化为 [SESSION] */
    @Deprecated("永久废弃，仅为兼容旧设置值/旧备份保留枚举项；新代码一律使用 SESSION")
    CHARACTER,

    /** 永久废弃：仅为兼容旧设置值/旧备份保留枚举项；语义已归一化为 [SESSION] */
    @Deprecated("永久废弃，仅为兼容旧设置值/旧备份保留枚举项；新代码一律使用 SESSION")
    OWNER,

    ;

    companion object {
        /** settings KV 键（历史遗留：该设置不再产生任何作用，读取恒为 SESSION） */
        const val KEY = "memory_recall_scope"

        /**
         * KV 原始值 → scope：**一切输入（含 null/非法值/旧档位值）都归一化为 [SESSION]**。
         * 保留参数与返回类型仅为兼容旧调用方与旧测试的编译（忽略输入是归一化的刻意行为）。
         */
        @Suppress("UnusedParameter")
        fun fromRaw(raw: String?): MemoryScope = SESSION

        /** scope → KV 存储值（写回用；CHARACTER/OWNER 仍映射旧字符串，保证旧读取端可解析） */
        fun toRaw(scope: MemoryScope): String =
            when (scope) {
                SESSION -> "session"
                CHARACTER -> "character"
                OWNER -> "owner"
            }
    }
}
