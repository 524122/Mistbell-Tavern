package com.mistbell.tavern.android.data.api.model

data class StructuredMemory(
    val id: Long = 0,
    val ownerId: String,
    val characterId: String?,
    val sessionId: String?,
    val memoryType: String,
    val title: String?,
    val content: String,
    val structuredData: String?, // JSON string for table data
    val importance: Int = 5, // 1-10
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val lastAccessedAt: String?,
    val accessCount: Int = 0,
    val relatedMessageIds: List<String> = emptyList(),
    val sourceType: String = "manual", // manual, auto_extract, import
)

/**
 * 单条记忆**注入提示词时**的正文长度上限（字符）。
 *
 * 这是**投影**上限，不是存储上限：Room 里永远保存完整正文，只有渲染进提示词时才截断。
 * 曾经把它用在写入路径上——用户在编辑框敲了 500 字、看到"保存成功"，落库只剩 300 字，
 * 后半段永久丢失。注入预算该在装配层解决，不该靠毁数据来实现。
 */
const val MAX_MEMORY_CONTENT_CHARS = 300

/**
 * 按 [max] 截断记忆正文（**仅用于提示词投影**）。截断处补省略号，
 * 让"这条记忆在本次提示词里不完整"这件事可见，而不是静默丢尾。
 */
fun String.truncateMemoryContent(max: Int = MAX_MEMORY_CONTENT_CHARS): String {
    if (length <= max) return this
    return take(max).trimEnd() + "…"
}

// 记忆类型
object MemoryType {
    const val PREFERENCE = "preference" // 偏好
    const val IDENTITY = "identity" // 身份
    const val RELATIONSHIP = "relationship" // 关系
    const val EVENT = "event" // 事件
    const val CORE = "core" // 核心
    const val GOAL = "goal" // 目标
    const val EMOTION = "emotion" // 情绪
    const val NOTE = "note" // 笔记

    // 兼容旧类型
    const val CHARACTER_INFO = "character_info"
    const val ITEM = "item"
    const val LOCATION = "location"
    const val FACT = "fact"

    /**
     * profile 类记忆：描述"这个角色/用户是什么样"，不随会话改变。
     *
     * **仅用于分类/排序**（召回选择器第二轮优待它们），**不再影响作用域**——
     * 会话内长期记忆（2026-10 产品决策）下一切抽取落库的记忆都归属产生它的会话
     * （见 [extractedSessionId]）。
     */
    val PROFILE_TYPES: Set<String> = setOf(CHARACTER_INFO, IDENTITY, PREFERENCE, RELATIONSHIP)

    /** 该类型是否属于 profile 类（大小写不敏感） */
    fun isProfileType(type: String): Boolean = type.lowercase() in PROFILE_TYPES

    /**
     * 抽取落库时的会话戳：**恒为 [sourceSessionId]**——记忆的归属创建时确定（owner + session），
     * 之后不可变；profile 类记忆同样留在产生它的会话内，不再落成角色级（session_id 为空）。
     * 保留 [memoryType] 参数仅为兼容旧调用方与旧测试的编译（其值不再影响结果，忽略输入是刻意行为）。
     */
    @Suppress("UnusedParameter")
    fun extractedSessionId(
        memoryType: String,
        sourceSessionId: String,
    ): String? = sourceSessionId

    fun getDescription(type: String): String =
        when (type) {
            PREFERENCE -> "偏好"
            IDENTITY -> "身份"
            RELATIONSHIP -> "关系"
            EVENT -> "事件"
            CORE -> "核心"
            GOAL -> "目标"
            EMOTION -> "情绪"
            NOTE -> "笔记"
            CHARACTER_INFO -> "角色信息"
            ITEM -> "物品"
            LOCATION -> "地点"
            FACT -> "事实"
            else -> "未知"
        }

    fun getAll(): List<String> =
        listOf(
            PREFERENCE,
            IDENTITY,
            RELATIONSHIP,
            EVENT,
            CORE,
            GOAL,
            EMOTION,
            NOTE,
            CHARACTER_INFO,
            ITEM,
            LOCATION,
            FACT,
        )
}

// 来源类型
object SourceType {
    const val MANUAL = "manual"
    const val AUTO_EXTRACT = "auto_extract"
    const val IMPORT = "import"

    fun getDescription(type: String): String =
        when (type) {
            MANUAL -> "手动创建"
            AUTO_EXTRACT -> "自动提取"
            IMPORT -> "导入"
            else -> "未知"
        }
}
