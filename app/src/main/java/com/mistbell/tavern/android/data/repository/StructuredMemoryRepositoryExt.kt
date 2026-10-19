package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.api.model.StructuredMemory

/**
 * 结构化记忆的向量同步辅助。
 *
 * 历史遗留：本文件曾移植后端 `retrieveForConversation()` 作为召回实现，但生产链路实际走
 * `PromptBuilder.buildMemoryContext`（会话级作用域）。该扩展函数无生产调用点，且检索语义
 * （角色级、无会话维度）与已修复的生产实现不一致，已删除——召回保持唯一实现，
 * 避免后续修作用域/评分/预算时出现两套互不相同的召回行为。
 */

/**
 * 检查记忆是否需要同步到向量数据库
 *
 * 同步阈值：重要性 >= 7
 */
fun StructuredMemory.shouldSyncToVector(): Boolean {
    return importance >= 7
}

/**
 * 构建向量内容
 *
 * 格式：标题: 内容 [类型]
 */
fun StructuredMemory.buildVectorContent(): String {
    val sb = StringBuilder()

    if (!title.isNullOrBlank()) {
        sb.append(title).append(": ")
    }

    sb.append(content)

    sb.append(" [").append(memoryType).append("]")

    return sb.toString()
}
