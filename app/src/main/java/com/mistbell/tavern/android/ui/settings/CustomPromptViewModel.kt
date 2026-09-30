package com.mistbell.tavern.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义提示词管理 ViewModel
 */
class CustomPromptViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.container.database
    private val promptDao = db.customPromptDao()

    // 所有提示词
    val allPrompts: StateFlow<List<CustomPrompt>> =
        promptDao.observeAll()
            .map { entities -> entities.map { it.toCustomPrompt() } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // 已启用的提示词
    val enabledPrompts: StateFlow<List<CustomPrompt>> =
        promptDao.observeEnabled()
            .map { entities -> entities.map { it.toCustomPrompt() } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // 已启用的提示词数量
    val enabledPromptsCount: StateFlow<Int> =
        promptDao.observeEnabledCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0,
            )

    // 消息状态
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 初始化：创建一些预设提示词示例
        viewModelScope.launch {
            if (promptDao.getCount() == 0) {
                createDefaultPrompts()
            }
        }
    }

    /**
     * 创建默认提示词
     */
    private suspend fun createDefaultPrompts() {
        val defaultPrompts =
            listOf(
                CustomPromptEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "文雅古风",
                    content = "请使用典雅的文言文风格回复，措辞优美，用词考究，展现古典文学的韵味。",
                    type = "WRITING_STYLE",
                    position = "SYSTEM_END",
                    isEnabled = false,
                    priority = 50,
                    tags = Json.encodeToString(listOf("文风", "古风")),
                    description = "使用古典中文回复",
                ),
                CustomPromptEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "口语化日常",
                    content = "请使用轻松随意的口语化表达，就像朋友间的日常聊天，可以使用网络流行语。",
                    type = "WRITING_STYLE",
                    position = "SYSTEM_END",
                    isEnabled = false,
                    priority = 50,
                    tags = Json.encodeToString(listOf("文风", "口语")),
                    description = "轻松的日常对话风格",
                ),
                CustomPromptEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "专业严谨",
                    content = "请使用专业、正式、严谨的语言风格，逻辑清晰，措辞准确。",
                    type = "WRITING_STYLE",
                    position = "SYSTEM_END",
                    isEnabled = false,
                    priority = 50,
                    tags = Json.encodeToString(listOf("文风", "专业")),
                    description = "正式专业的表达方式",
                ),
            )
        promptDao.insertAll(defaultPrompts)
    }

    /**
     * 添加提示词
     */
    fun addPrompt(prompt: CustomPrompt) {
        viewModelScope.launch {
            try {
                val entity = prompt.toEntity()
                promptDao.insert(entity)
                _message.value = "已添加提示词"
            } catch (e: Exception) {
                _message.value = "添加失败: ${e.message}"
            }
        }
    }

    /**
     * 更新提示词
     */
    fun updatePrompt(prompt: CustomPrompt) {
        viewModelScope.launch {
            try {
                val entity = prompt.toEntity()
                promptDao.update(entity.copy(updatedAt = System.currentTimeMillis()))
                _message.value = "已更新提示词"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    /**
     * 删除提示词
     */
    fun deletePrompt(id: String) {
        viewModelScope.launch {
            try {
                promptDao.deleteById(id)
                _message.value = "已删除提示词"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    /**
     * 切换提示词启用状态
     */
    fun togglePrompt(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            try {
                promptDao.setEnabled(id, enabled)
                _message.value = if (enabled) "已启用提示词" else "已禁用提示词"
            } catch (e: Exception) {
                _message.value = "操作失败: ${e.message}"
            }
        }
    }

    /**
     * 获取指定类型的提示词
     */
    fun getPromptsByType(type: PromptType): Flow<List<CustomPrompt>> {
        return promptDao.observeByType(type.name)
            .map { entities -> entities.map { it.toCustomPrompt() } }
    }

    /**
     * 构建最终的提示词（用于实际聊天）
     */
    suspend fun buildFinalPrompt(
        systemPrompt: String,
        userMessage: String,
    ): Pair<String, String> {
        val enabledPrompts = promptDao.getEnabled()

        // 按位置和优先级排序
        val sortedPrompts = enabledPrompts.sortedByDescending { it.priority }

        // 系统提示词
        var finalSystem = ""

        // 1. 系统提示词开头
        sortedPrompts
            .filter { it.position == "SYSTEM_START" }
            .forEach { finalSystem += it.content + "\n\n" }

        // 2. 原始系统提示词
        finalSystem += systemPrompt + "\n\n"

        // 3. 系统提示词末尾
        sortedPrompts
            .filter { it.position == "SYSTEM_END" }
            .forEach { finalSystem += it.content + "\n\n" }

        // 用户消息
        var finalUser = ""

        // 4. 用户消息前缀
        sortedPrompts
            .filter { it.position == "USER_PREFIX" }
            .forEach { finalUser += it.content + "\n\n" }

        // 5. 原始用户消息
        finalUser += userMessage + "\n\n"

        // 6. 用户消息后缀
        sortedPrompts
            .filter { it.position == "USER_SUFFIX" }
            .forEach { finalUser += it.content + "\n\n" }

        return Pair(finalSystem.trim(), finalUser.trim())
    }

    /**
     * 清除消息
     */
    fun clearMessage() {
        _message.value = null
    }
}

/**
 * 实体转换为领域模型
 */
private fun CustomPromptEntity.toCustomPrompt(): CustomPrompt {
    return CustomPrompt(
        id = id,
        name = name,
        content = content,
        type =
            when (type) {
                "JAILBREAK" -> PromptType.JAILBREAK
                "WRITING_STYLE" -> PromptType.WRITING_STYLE
                "CHARACTER" -> PromptType.CHARACTER
                else -> PromptType.CUSTOM
            },
        position =
            when (position) {
                "SYSTEM_START" -> PromptPosition.SYSTEM_START
                "SYSTEM_END" -> PromptPosition.SYSTEM_END
                "USER_PREFIX" -> PromptPosition.USER_PREFIX
                "USER_SUFFIX" -> PromptPosition.USER_SUFFIX
                "ASSISTANT_PREFIX" -> PromptPosition.ASSISTANT_PREFIX
                else -> PromptPosition.SYSTEM_END
            },
        isEnabled = isEnabled,
        priority = priority,
        tags =
            try {
                Json.decodeFromString<List<String>>(tags)
            } catch (e: Exception) {
                emptyList()
            },
        description = description,
    )
}

/**
 * 领域模型转换为实体
 */
private fun CustomPrompt.toEntity(): CustomPromptEntity {
    return CustomPromptEntity(
        id = id,
        name = name,
        content = content,
        type = type.name,
        position = position.name,
        isEnabled = isEnabled,
        priority = priority,
        tags = Json.encodeToString(tags),
        description = description,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )
}
