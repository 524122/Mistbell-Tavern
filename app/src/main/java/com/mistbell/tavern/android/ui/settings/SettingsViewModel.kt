package com.mistbell.tavern.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)
    private val db get() = TavernApplication.instance.database

    private val _llmConfig = MutableStateFlow(LlmConfig())
    val llmConfig: StateFlow<LlmConfig> = _llmConfig

    private val _settings = MutableStateFlow<JsonObject?>(null)
    val settings: StateFlow<JsonObject?> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _darkMode = MutableStateFlow("system")
    val darkMode: StateFlow<String> = _darkMode

    private val _memoryExtractionPrompt = MutableStateFlow("")
    val memoryExtractionPrompt: StateFlow<String> = _memoryExtractionPrompt

    // --- 对话生成设置（KV 缺省值：流式开 / 上下文 4096 / 长期记忆默认关） ---
    private val _streamingEnabled = MutableStateFlow(true)
    val streamingEnabled: StateFlow<Boolean> = _streamingEnabled

    private val _defaultContextTokens = MutableStateFlow(4096)
    val defaultContextTokens: StateFlow<Int> = _defaultContextTokens

    private val _defaultLtmEnabled = MutableStateFlow(false)
    val defaultLtmEnabled: StateFlow<Boolean> = _defaultLtmEnabled

    // --- 生成与采样设置（KV 键与 SettingsRepository.getLlmConfig 的组装约定一致） ---
    // 采样预设：creative/balanced/precise/custom（custom = 不套预设，去提供商页调参）
    private val _samplingPreset = MutableStateFlow("balanced")
    val samplingPreset: StateFlow<String> = _samplingPreset

    // 请求超时（秒，15..600）与重试次数（0..5）
    private val _requestTimeout = MutableStateFlow(90)
    val requestTimeout: StateFlow<Int> = _requestTimeout

    private val _requestRetries = MutableStateFlow(2)
    val requestRetries: StateFlow<Int> = _requestRetries

    init {
        loadSettings()
        loadLlmConfig()
        loadDarkMode()
        loadMemoryExtractionPrompt()
        observeGenerationSettings()
        observeSamplingSettings()
    }

    // 观察采样预设 / 超时 / 重试三个 KV 键（缺省：balanced / 90s / 2 次）
    private fun observeSamplingSettings() {
        viewModelScope.launch {
            db.settingsDao().observeValue("sampling_preset")
                .map { it ?: "balanced" }
                .collect { _samplingPreset.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("request_timeout_seconds")
                .map { (it?.toIntOrNull() ?: 90).coerceIn(15, 600) }
                .collect { _requestTimeout.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("request_retries")
                .map { (it?.toIntOrNull() ?: 2).coerceIn(0, 5) }
                .collect { _requestRetries.value = it }
        }
    }

    fun setSamplingPreset(name: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("sampling_preset", name),
            )
            _samplingPreset.value = name
        }
    }

    fun setRequestTimeout(seconds: Int) {
        val v = seconds.coerceIn(15, 600)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("request_timeout_seconds", v.toString()),
            )
            _requestTimeout.value = v
        }
    }

    fun setRequestRetries(n: Int) {
        val v = n.coerceIn(0, 5)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("request_retries", v.toString()),
            )
            _requestRetries.value = v
        }
    }

    // 从 settings 表观察三个对话生成相关 KV 键并解析灌入 StateFlow
    private fun observeGenerationSettings() {
        viewModelScope.launch {
            db.settingsDao().observeValue("streaming_enabled")
                .map { it != "0" } // 缺省/null 均视为开启
                .collect { _streamingEnabled.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("default_context_tokens")
                .map { it?.toIntOrNull() ?: 4096 }
                .collect { _defaultContextTokens.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("default_ltm_enabled")
                .map { it == "1" } // 缺省/null 视为关闭
                .collect { _defaultLtmEnabled.value = it }
        }
    }

    fun setStreamingEnabled(v: Boolean) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("streaming_enabled", if (v) "1" else "0"),
            )
            _streamingEnabled.value = v
        }
    }

    fun setDefaultContextTokens(n: Int) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("default_context_tokens", n.toString()),
            )
            _defaultContextTokens.value = n
        }
    }

    fun setDefaultLtmEnabled(v: Boolean) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("default_ltm_enabled", if (v) "1" else "0"),
            )
            _defaultLtmEnabled.value = v
        }
    }

    private fun loadDarkMode() {
        viewModelScope.launch {
            val mode = db.settingsDao().getValue("dark_mode") ?: "system"
            _darkMode.value = mode
        }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(com.mistbell.tavern.android.data.local.entity.SettingsEntity("dark_mode", mode))
            _darkMode.value = mode
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repo.loadAndCacheSettings()
                _settings.value = repo.observeSettings().first() as? JsonObject
            } catch (e: Exception) {
                _message.value = "加载设置失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadLlmConfig() {
        viewModelScope.launch {
            _llmConfig.value = repo.getLlmConfig()
        }
    }

    fun updateLlmConfig(config: LlmConfig) {
        viewModelScope.launch {
            repo.saveLlmConfig(config)
            _llmConfig.value = config
            _message.value = "LLM 配置已保存"
        }
    }

    fun testLlmConnection() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val config = _llmConfig.value
                val ok = LlmClient.testConnection(config)
                _message.value = if (ok) "连接成功" else "连接失败，请检查配置"
            } catch (e: Exception) {
                _message.value = "连接失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateTemperature(temperature: Double) {
        viewModelScope.launch {
            try {
                repo.updateTemperature(temperature)
                _llmConfig.value = _llmConfig.value.copy(temperature = temperature)
                _settings.value = repo.observeSettings().first() as? JsonObject
                _message.value = "温度已更新"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    fun updateMaxTokens(maxTokens: Int) {
        viewModelScope.launch {
            try {
                repo.updateMaxTokens(maxTokens)
                _llmConfig.value = _llmConfig.value.copy(maxTokens = maxTokens)
                _settings.value = repo.observeSettings().first() as? JsonObject
                _message.value = "最大 Token 已更新"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    fun updateServerUrl(url: String) {
        ApiClient.setServerUrl(getApplication(), url)
        loadSettings()
        _message.value = "服务器地址已更新"
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun loadMemoryExtractionPrompt() {
        viewModelScope.launch {
            val prompt =
                db.settingsDao().getValue("memory_extraction_prompt")
                    ?: getDefaultMemoryExtractionPrompt()
            _memoryExtractionPrompt.value = prompt
        }
    }

    fun saveMemoryExtractionPrompt(prompt: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    "memory_extraction_prompt",
                    prompt,
                ),
            )
            _memoryExtractionPrompt.value = prompt
            _message.value = "记忆提取提示词已保存"
        }
    }

    fun getDefaultMemoryExtractionPrompt(): String {
        return """
            你是角色扮演聊天系统的长期记忆抽取引擎。请分析这一轮对话中是否存在值得长期保存的记忆。

            只输出一个 JSON 对象，结构必须完全如下：
            {
              "emotion": {
                "primaryEmotion": "简短情绪标签或空字符串",
                "secondaryEmotion": "简短情绪标签或空字符串",
                "intensity": 0.0,
                "situationType": "normal",
                "memoryWorthiness": 0.0,
                "stabilityMultiplier": 1.0,
                "emotionalAtmosphere": "简短氛围或空字符串",
                "reasoning": "简短理由"
              },
              "triplets": [
                {
                  "subject": "规范主体，用户事实通常使用 user",
                  "relation": "关系或动词",
                  "object": "目标或取值",
                  "memoryType": "fact|event|emotion|core|preference|identity|relationship|goal|note",
                  "importance": 0.0,
                  "tags": ["3-8 个用于检索的稳定关键词"],
                  "aliases": ["0-6 个用户可能用来提起这条记忆的说法"],
                  "rawText": "一条基于原文的正式记忆句"
                }
              ],
              "npcMentions": []
            }

            situationType must be one of:
            life_death, deep_trauma, sacred_moment, vulnerability, betrayal,
            reunion, parting, identity_reveal, normal.

            只抽取持久信息：身份、稳定偏好、关系、重要事件、创伤/恐惧、承诺、目标、边界、反复关注的问题。
            可以从 User 和 Assistant 两侧抽取：User 表达的偏好/边界/身份，以及 Assistant 回复中已经发生或确认的剧情事件。
            不要保存寒暄、填充语、临时情绪，或原文没有表达的事实。
            core 记忆只用于生死、誓言、关键身份揭示、神圣转折点。
            如果没有值得保存的内容，triplets 返回 []。

            语言规则非常重要：
            - rawText、object、tags、aliases 必须优先使用用户原文语言。
            - 用户用中文表达时，rawText 必须是中文正式记忆句，不要翻译成英文。
            - 可以保留主体 user，但其余内容尽量中文化。
            - 不要输出 "user refuse to wear women's clothing" 这类英文句；应输出 "user 拒绝穿女装"。

            rawText 必须是正式记忆句，不是一句第一人称原话。
            用户事实优先使用 subject "user"。例如：
            - "我叫墨轩" -> subject "user", relation "name", object "墨轩", rawText "user 的名字是墨轩"
            - "我喜欢结构化长期记忆" -> rawText "user 喜欢结构化长期记忆"
            - "我可不穿女人衣服，给我个斗笠" -> rawText "user 拒绝穿女装" / "user 想要斗笠"

            ⚠️ 重要：区分"陈述事实"和"告知信息"
            当 NPC 告诉 user 某个信息时，记录"NPC 知道/告知"，而非直接断言：
            - ❌ 错误："角色：你不会游泳" -> rawText "user 不会游泳"（这是直接断言）
            - ✅ 正确："角色：你不会游泳" -> subject "角色名", relation "知道", object "user不会游泳", rawText "角色名知道user不会游泳"
            - ❌ 错误："医生：你的血压偏高" -> rawText "user 血压偏高"
            - ✅ 正确："医生：你的血压偏高" -> rawText "医生告知user血压偏高"

            只有当 user 自己陈述或事实已确认时，才直接记录为 user 的属性：
            - ✅ 正确："User: 我确实不会游泳" -> rawText "user 不会游泳"
            - ✅ 正确："角色检查后确认user对花粉过敏" -> rawText "user 对花粉过敏（角色确认）"

            当 user 向 NPC 告知自己的信息时，可以同时记录事实和知情关系：
            - "User: 我不会游泳" -> 可提取两条：rawText "user 不会游泳" + rawText "角色名知道user不会游泳"

            ❌ 错误示例（不要这样做）：
            - rawText: "User: 想带我走就带嘛 Assistant: 角色听你这么说..." （这是原对话，不是记忆句）
            - rawText: "下午-市中心>咖啡店>靠窗位置..." （这是场景描述，不是记忆）
            - rawText: "她笑够了，站起身来，拍了拍衣服..." （这是过程描写，不是记忆）

            ✅ 正确示例：
            - rawText: "user 表达了愿意跟随角色"
            - rawText: "角色指出 user 需要帮助"
            - rawText: "user 和角色在公园中对话"

            重要约束：
            - rawText 长度必须在 10-100 字之间
            - 不要包含 "User:" "Assistant:" 等对话标记
            - 不要包含场景描述标签（如 [下午-地点>场所...]）
            - 不要包含大段角色动作或心理描写
            - 只提取核心事实、关系、偏好、事件

            对话片段：
            %s

            不要输出 Markdown，不要解释，只输出 JSON。
            """.trimIndent()
    }

    // --- Changelog ---

    private val _changelog = MutableStateFlow<List<com.mistbell.tavern.android.data.model.VersionInfo>>(emptyList())
    val changelog: StateFlow<List<com.mistbell.tavern.android.data.model.VersionInfo>> = _changelog.asStateFlow()

    private val _isLoadingChangelog = MutableStateFlow(false)
    val isLoadingChangelog: StateFlow<Boolean> = _isLoadingChangelog.asStateFlow()

    fun loadChangelog() {
        viewModelScope.launch {
            _isLoadingChangelog.value = true
            try {
                val api = ApiClient.getApi(getApplication())
                val response = api.getChangelog()
                _changelog.value = response.versions
            } catch (e: Exception) {
                // 降级到默认数据
                _changelog.value = getDefaultChangelog()
            } finally {
                _isLoadingChangelog.value = false
            }
        }
    }

    private fun getDefaultChangelog(): List<com.mistbell.tavern.android.data.model.VersionInfo> {
        return listOf(
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.3.0",
                versionCode = 5,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "应用冷启动优化（完全延迟初始化）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "启用资源压缩和代码优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "添加 ProGuard 规则支持混淆"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "集成 Paging 3 库（消息分页基础）"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.2",
                versionCode = 4,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "优化 Compose 重组性能（时间戳缓存）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量存储延迟加载优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量存储内存限制（最多 1000 条）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "优化列表项比较逻辑减少重组"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.1",
                versionCode = 3,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "数据库查询性能优化（添加关键索引）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "修复会话列表 N+1 查询问题"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量搜索结果缓存优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "LLM API 添加超时重试机制"),
                        com.mistbell.tavern.android.data.model.ChangeItem("fix", "修复重装应用后数据库迁移失败问题"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.0",
                versionCode = 2,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "引号高亮显示（支持中英日文引号）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "动作括号高亮（橙色斜体）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "全局点击外部收起键盘"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "角色编辑页面完全中文化"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.1.0",
                versionCode = 1,
                releaseDate = "2026-06-20",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "初始版本发布"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "角色管理功能"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "聊天对话功能"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "LLM 提供商配置"),
                    ),
            ),
        )
    }
}
