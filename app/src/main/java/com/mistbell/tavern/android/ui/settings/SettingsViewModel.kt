package com.mistbell.tavern.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.repository.BackupManager
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MAX
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MIN
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // stateIn 订阅超时：与全仓 ViewModel 的 WhileSubscribed(5000) 同值，收敛为具名常量
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }

    private val repo = SettingsRepository(application)
    private val db get() = TavernApplication.instance.container.database

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

    // --- 提示词模板（全局 KV，明文存储；空值语义见各 resolver） ---
    //
    // 用 observeValue + stateIn 而不是一次性 getValue：备份恢复或别处改写后，编辑对话框能实时同步。
    // 这四项都**展示原始存储值**（未设置即空串），默认值在消费端（ChatSettingsResolver）与副标题里说明——
    // 若在这里回填默认值，用户一保存就会把默认文本固化成显式值，默认值日后调整将不再生效。

    val mainPromptSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_MAIN_PROMPT)

    val userNameSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_USER_NAME)

    val userPersonaSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_USER_PERSONA)

    val groupChatRulesSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_GROUP_CHAT_RULES)

    // 世界书全局设置（扫描深度 / token 预算；展示原始存储值，空=跟随内置默认，解析见 ChatSettingsResolver）
    val worldInfoScanDepthSetting: StateFlow<String> =
        observeTextSetting(ChatSettingsResolver.KEY_WORLD_INFO_SCAN_DEPTH)

    val worldInfoBudgetPercentSetting: StateFlow<String> =
        observeTextSetting(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_PERCENT)

    // WI 预算绝对上限（酒馆 world_info_budget_cap；空/0=不封顶）
    val worldInfoBudgetCapSetting: StateFlow<String> =
        observeTextSetting(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_CAP)

    // @D 落点开关：缺省 true（插进历史内部，酒馆一致）；"0"=尾部注入（省 token）
    val worldInfoInHistorySetting: StateFlow<Boolean> =
        db.settingsDao()
            .observeValue(ChatSettingsResolver.KEY_WORLD_INFO_IN_HISTORY)
            .map { ChatSettingsResolver.worldInfoInHistory(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), true)

    // 递归扫描开关：缺省 false（对齐酒馆 world_info_recursive 默认关）
    val worldInfoRecursiveSetting: StateFlow<Boolean> =
        db.settingsDao()
            .observeValue(ChatSettingsResolver.KEY_WORLD_INFO_RECURSIVE)
            .map { ChatSettingsResolver.worldInfoRecursive(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), false)

    // min-activations 加深扫描（酒馆 skew）：空/0=关闭
    val worldInfoMinActivationsSetting: StateFlow<String> =
        observeTextSetting(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS)

    val worldInfoMinActivationsDepthMaxSetting: StateFlow<String> =
        observeTextSetting(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS_DEPTH_MAX)

    // include_names：缺省 true（酒馆默认开）；扫描文本带「名字: 」前缀
    val worldInfoIncludeNamesSetting: StateFlow<Boolean> =
        db.settingsDao()
            .observeValue(ChatSettingsResolver.KEY_WORLD_INFO_INCLUDE_NAMES)
            .map { ChatSettingsResolver.worldInfoIncludeNames(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), true)

    private fun observeTextSetting(key: String): StateFlow<String> =
        db.settingsDao()
            .observeValue(key)
            .map { it.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), "")

    /** 静默保存（不弹提示）：数字输入框逐键保存用；存空串 = 恢复"未设置"语义 */
    fun saveTextSettingQuietly(
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            db.settingsDao().upsert(com.mistbell.tavern.android.data.local.entity.SettingsEntity(key, value))
        }
    }

    /**
     * 保存提示词类设置（四个键共用）。明文存储，自动纳入备份（BackupManager 整表导出 settings）。
     * 存空串 = 恢复"未设置"语义（主提示词/人设不注入、群聊规范回落内置默认）。
     */
    fun saveTextSetting(
        key: String,
        value: String,
        savedMessage: String,
    ) {
        viewModelScope.launch {
            db.settingsDao().upsert(com.mistbell.tavern.android.data.local.entity.SettingsEntity(key, value))
            _message.value = savedMessage
        }
    }

    // --- 对话生成设置（KV 缺省值：流式开 / 上下文 4096） ---
    private val _streamingEnabled = MutableStateFlow(true)
    val streamingEnabled: StateFlow<Boolean> = _streamingEnabled

    private val _defaultContextTokens = MutableStateFlow(4096)
    val defaultContextTokens: StateFlow<Int> = _defaultContextTokens

    init {
        loadSettings()
        loadLlmConfig()
        loadDarkMode()
        observeGenerationSettings()
    }

    // 从 settings 表观察对话生成相关 KV 键并解析灌入 StateFlow
    private fun observeGenerationSettings() {
        viewModelScope.launch {
            db.settingsDao().observeValue("streaming_enabled")
                .map { it != "0" } // 缺省/null 均视为开启
                .collect { _streamingEnabled.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("default_context_tokens")
                .map { (it?.toIntOrNull() ?: 4096).coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX) }
                .collect { _defaultContextTokens.value = it }
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
        val v = n.coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("default_context_tokens", v.toString()),
            )
            _defaultContextTokens.value = v
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

    fun clearMessage() {
        _message.value = null
    }

    // --- 数据（S4 全量备份/恢复） ---
    fun createBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val summary = BackupManager(getApplication()).exportToUri(uri)
                _message.value = "备份完成：已导出 ${summary.totalAdded} 项数据"
            } catch (e: Exception) {
                android.util.Log.e("Settings", "Backup export failed", e)
                _message.value = e.message ?: "备份失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val summary = BackupManager(getApplication()).restoreFromUri(uri)
                _message.value =
                    "恢复完成：合并 ${summary.totalAdded} 项" +
                    (if (summary.totalSkipped > 0) "（跳过已存在 ${summary.totalSkipped} 项）" else "")
            } catch (e: Exception) {
                android.util.Log.e("Settings", "Backup restore failed", e)
                _message.value = e.message ?: "恢复失败"
            } finally {
                _isLoading.value = false
            }
        }
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
                version = "0.9.5-beta",
                versionCode = 16,
                releaseDate = "2026-10-20",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "新增模型参数独立页面：五个采样参数滑条与请求超时/重试，直接编辑默认 API 配置并与面板 JSON 双向同步",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "请求超时/重试迁入每个 API 配置的 JSON 文档；长期记忆全局默认开关删除，会话设置改为普通开关",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "修复新增 API 配置按名称→密钥→模型顺序填写时保存闪退的问题",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "移除设置页记忆栏、全局采样预设与 llm_* 覆盖键等旧参数链路（记忆与向量引擎保留）",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.4-beta",
                versionCode = 15,
                releaseDate = "2026-10-19",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "API 配置升级为版本化文档，提供 request 参数模板、请求预览、协议保护字段和 URL 凭据清理",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "API 编辑器改为基础配置、请求参数、请求预览三个页签，支持模板重置和脱敏 JSON 导入导出",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "记忆系统改为会话内长期记忆，支持历史裁剪后的召回和严格会话隔离",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "新增向量同步持久化待办和崩溃恢复，避免进程中断后缺失、旧版本或已删除摘要残留",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "完成真实本地 ONNX embedding 生成、重启恢复、缓存召回和模型指纹隔离验收",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.3-beta",
                versionCode = 14,
                releaseDate = "2026-10-03",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "补齐 continue、swipe 与正常发送、重生、继续、取消、部分回复的统一落库链路，保持正文、思维链和候选索引一致",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "增强 World Info：多来源合并、次关键词、三轮递归扫描、sticky/cooldown/delay、概率和分组择优",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "PromptTrace 增加激活原因、插入位置、历史裁剪原因和 OpenAI/Anthropic/Gemini 协议映射说明",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "删除、撤销、回退、重生和清空会话时同步清理 VectorStore，避免 Room 消息与向量记录脱节",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "保持 Room 为主存储，新增酒馆 JSONL 双向导入导出并保留消息顺序、角色归属、thinking、swipes 和 thinkingSwipes",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.2-beta",
                versionCode = 13,
                releaseDate = "2026-10-03",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "支持 OpenAI/DeepSeek、Anthropic 和 Gemini 思维链解析，正文与思维链分开保存并在聊天气泡中显示",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "优化聊天输入区、格式快捷字符和 Markdown 语义高亮，降低输入栏整体高度并改善主题适配",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "修复横屏前摄安全区、左侧 NavigationRail 空白、导航裁切和连续旋转后的 inset 失效问题",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "适配 Android 17（API 37）编译与目标 SDK，保留 Android 8.0（API 26）最低支持",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "优化首屏启动、角色头像解码、会话搜索、提示词装配、记忆抽取和 Compose 列表重组",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "完善提示词模板与 PromptTrace，补齐主提示词、用户人设、post-history 指令、群聊规范和世界书位置/深度语义",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "改善长期记忆抽取可靠性与向量同步，降低重复召回，并将动态世界书条目后移以稳定提示词前缀缓存",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.1-beta",
                versionCode = 12,
                releaseDate = "2026-10-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "统一聊天、会话、角色、世界书和设置页面的视觉风格，并支持横屏/平板 NavigationRail 自适应导航",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "角色卡与会话卡支持点击、长按和三点菜单，聊天页支持完整提示词查看与开场白切换",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "API 配置卡片化，SSE 流式开关按 API 保存，高级采样、超时和重试设置集中管理",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "聊天底部输入栏与渐隐遮罩改为统一测量和绘制，减少键盘切换时的透明缝隙与布局跳动",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "背景图加载、消息列表渲染和点击收起键盘逻辑整理，降低无效布局与重组",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "修复角色背景图异步加载期间底色不稳定、底部渐隐遮罩被覆盖的问题",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "更新 Android 17（API 37）编译与目标 SDK，保留 Android 8.0（API 26）最低支持",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.0-beta",
                versionCode = 11,
                releaseDate = "2026-09-26",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "本地语义向量：内置 bge-small-zh 模型（ONNX），无需 API Key 即可使用真正的语义记忆召回",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "向量记忆设置：记忆源三档（自动/API/本地 ONNX）、召回条数与相似度阈值可调",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "安装包按 CPU 架构分包（arm64 约 53MB），不再被无关架构拖大",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.8.0-beta",
                versionCode = 10,
                releaseDate = "2026-09-26",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "全量备份/恢复：角色、会话、记忆、设置与主题包打包为单个 zip；合并恢复不删本地数据",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "首启引导：新用户三步上手（内置示例角色一键导入 + API Key 图文指引），全部步骤可跳过",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "会话设置三态化：上下文长度/长期记忆支持「跟随全局」，全局默认改动对存量会话真正生效",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "LLM 配置双源真相根修：采样预设/超时/重试设置此前对实际聊天请求不生效",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.7.0-beta",
                versionCode = 9,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "群聊模式：多角色会话（上限 20），AI 轮流以各角色身份发言，气泡按说话者显示名字与配色",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "@名字 指定谁回应；“让TA继续”一键推动下一位角色发言",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "会话模式骨架（v17 迁移）：老会话自动经典模式，无感升级",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "经典模式角色点选收敛修复；群聊消息读写改为会话级（NPC 发言不再丢失）",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.6.0-beta",
                versionCode = 8,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "长会话窗口分页：打开只加载最近 200 条，上滚自动加载更早消息并保持阅读位置",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "冷启动与会话列表提速（新数据库索引 + 重活移出主线程 + 头像位图缓存降采样）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "流式输出 80ms 节流且只刷新流式气泡，长回复生成不再整机发涩",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "滚动体验重做：进会话直达底部，上翻阅读不再被强制拉回",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "撤销/回退/重新生成不再丢失已加载的更早历史；数据库 v16 迁移（分页性能索引）",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.5.0-beta",
                versionCode = 7,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "真流式输出：逐 token 渲染，生成中可停止（已生成部分保留）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "采样预设（创意/平衡/精确）与高级参数、超时重试、会话附加指令",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "长期记忆词法召回：CJK 分词 + 会话内检索，替代伪向量（ONNX 语义向量后置）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "设置页按新信息架构重组；流式开关/默认上下文长度/默认长期记忆可配",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "消息回溯/重新生成/失败回滚等十余项正确性修复；API Key 加密存储",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.4.0-beta",
                versionCode = 6,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "主题包系统（T1）：zip 导入/分享，角色专属/全局主题，聊天页配色与背景",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "会话级主题：会话 → 角色 → 全局 → 默认层层回落",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "SillyTavern 生态互通：角色卡 v1/v2 JSON 与 PNG 埋卡导入导出、世界书导入",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "宏引擎：{{char}}/{{user}}/{{random}}/{{roll::3d6}} 等，未知宏原样保留",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "think 标签提取为折叠思考区；导入诊断报告",
                        ),
                    ),
            ),
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
