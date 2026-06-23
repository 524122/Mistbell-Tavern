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
    val sourceType: String = "manual" // manual, auto_extract, import
)

// 记忆类型
object MemoryType {
    const val PREFERENCE = "preference"      // 偏好
    const val IDENTITY = "identity"          // 身份
    const val RELATIONSHIP = "relationship"  // 关系
    const val EVENT = "event"               // 事件
    const val CORE = "core"                 // 核心
    const val GOAL = "goal"                 // 目标
    const val EMOTION = "emotion"           // 情绪
    const val NOTE = "note"                 // 笔记

    // 兼容旧类型
    const val CHARACTER_INFO = "character_info"
    const val ITEM = "item"
    const val LOCATION = "location"
    const val FACT = "fact"

    fun getDescription(type: String): String = when (type) {
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

    fun getAll(): List<String> = listOf(
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
        FACT
    )
}

// 来源类型
object SourceType {
    const val MANUAL = "manual"
    const val AUTO_EXTRACT = "auto_extract"
    const val IMPORT = "import"

    fun getDescription(type: String): String = when (type) {
        MANUAL -> "手动创建"
        AUTO_EXTRACT -> "自动提取"
        IMPORT -> "导入"
        else -> "未知"
    }
}
