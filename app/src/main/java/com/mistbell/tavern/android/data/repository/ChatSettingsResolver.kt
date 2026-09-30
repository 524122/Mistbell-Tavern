package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.SessionEntity

/**
 * 会话级设置的唯一解析入口（SETTINGS.md 分层原则落地）：
 * 全局层是唯一真相源，会话层只存"差异"——**会话显式值 > 全局当前值 > 内置兜底**，
 * 差异在读取时合并，而不是在建会话时拷贝（旧快照语义导致全局默认改动对存量会话永久失效，
 * 且"用户显式设置"与"当时默认值的拷贝"在数据上无法区分）。
 *
 * 全局默认值的原始字符串解析也收敛于此（[globalDefaultContextTokens]/[globalDefaultLtmEnabled]），
 * SettingsRepository 与 PromptBuilder 等读取路径共用同一份实现，杜绝键解析的双源真相。
 */
object ChatSettingsResolver {
    const val CONTEXT_TOKEN_LIMIT_MIN = 1024
    const val CONTEXT_TOKEN_LIMIT_MAX = 1_000_000

    // 全局与会话级均未设置时的内置兜底（全局默认本身缺省/非法时经 [globalDefaultContextTokens] 落到这里）
    private const val FALLBACK_CONTEXT_TOKENS = 4096

    // settings KV 原始值 → 全局默认上下文长度：非法/缺省回退内置兜底，越界钳制到合法区间
    fun globalDefaultContextTokens(raw: String?): Int =
        raw
            ?.toIntOrNull()
            ?.coerceIn(CONTEXT_TOKEN_LIMIT_MIN, CONTEXT_TOKEN_LIMIT_MAX)
            ?: FALLBACK_CONTEXT_TOKENS

    // settings KV 原始值 → 全局默认长期记忆开关（仅显式 "1" 为开，缺省关）
    fun globalDefaultLtmEnabled(raw: String?): Boolean = raw == "1"

    // 生效上下文长度：会话显式值（钳制）优先，null（跟随全局）回落到全局默认
    fun contextTokenLimit(
        session: SessionEntity?,
        globalDefaultTokens: Int,
    ): Int =
        session?.contextTokenLimit?.coerceIn(CONTEXT_TOKEN_LIMIT_MIN, CONTEXT_TOKEN_LIMIT_MAX)
            ?: globalDefaultTokens.coerceIn(CONTEXT_TOKEN_LIMIT_MIN, CONTEXT_TOKEN_LIMIT_MAX)

    // 生效长期记忆开关：会话显式值优先，null（跟随全局）回落到全局默认
    fun longTermMemoryEnabled(
        session: SessionEntity?,
        globalDefaultEnabled: Boolean,
    ): Boolean = session?.enableLongTermMemory ?: globalDefaultEnabled

    // ---- S2 向量记忆召回设置（纯全局行为项，无会话覆盖） ----

    const val RECALL_TOP_K_MIN = 1
    const val RECALL_TOP_K_MAX = 20
    const val DEFAULT_RECALL_TOP_K = 5

    const val SIMILARITY_THRESHOLD_MIN = 0.05f
    const val SIMILARITY_THRESHOLD_MAX = 0.95f
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.35f

    // settings KV 原始值 → 召回条数：非法/缺省回退 5，钳制 1..20
    fun memoryRecallTopK(raw: String?): Int {
        return raw?.toIntOrNull()?.coerceIn(RECALL_TOP_K_MIN, RECALL_TOP_K_MAX) ?: DEFAULT_RECALL_TOP_K
    }

    // settings KV 原始值 → 相似度阈值：非法/缺省回退 0.35，钳制 0.05..0.95
    fun memorySimilarityThreshold(raw: String?): Float =
        raw?.toFloatOrNull()?.coerceIn(SIMILARITY_THRESHOLD_MIN, SIMILARITY_THRESHOLD_MAX)
            ?: DEFAULT_SIMILARITY_THRESHOLD

    // ---- 提示词模板（全局行为项，无会话覆盖） ----
    //
    // 键名一律引用本组常量，禁止在别处手写字符串字面量（照会话模式常量那条纪律：
    // 两侧代理必须引用同一份常量，防双源真相）。

    const val KEY_USER_NAME = "user_name"
    const val KEY_USER_PERSONA = "user_persona"
    const val KEY_MAIN_PROMPT = "main_prompt"
    const val KEY_GROUP_CHAT_RULES = "group_chat_rules_prompt"

    /** `{{user}}` 宏的缺省取值（用户未设置「用户名称」时） */
    const val DEFAULT_USER_NAME = "User"

    /** 群聊规范模板里的用户名占位符（单花括号，**不是**宏引擎的 `{{user}}`） */
    const val GROUP_CHAT_USER_PLACEHOLDER = "{user}"

    /** 群聊规范内置默认模板：用户未自定义（或填空）时使用 */
    const val DEFAULT_GROUP_CHAT_RULES =
        "【群聊规范】本场对话有多位角色在场。你每次只以其中一位角色的身份发言：" +
            "回复必须以『角色名:』开头（半角冒号），随后是该角色的发言。" +
            "历史记录中每行已带『名字:』前缀标注发言者。" +
            "不要替用户（{user}）或其他角色说话，不要旁白多人。一次只发言一位。"

    /** settings KV 原始值 → 用户名：空白回落 [DEFAULT_USER_NAME]（trim 后返回） */
    fun userName(raw: String?): String = raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_USER_NAME

    /** settings KV 原始值 → 用户人设（`{{persona}}` 宏内容）；未设置为空串（不注入该段） */
    fun userPersona(raw: String?): String = raw.orEmpty()

    /** settings KV 原始值 → 全局主提示词；未设置为空串（不注入该段） */
    fun mainPrompt(raw: String?): String = raw.orEmpty()

    /** settings KV 原始值 → 群聊规范模板：空白回落内置默认（单一默认值来源） */
    fun groupChatRules(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP_CHAT_RULES

    /**
     * 规范模板中的 `{user}` 占位符 → 用户名。
     *
     * **必须在宏渲染之后调用**：`{user}` 是 `{{user}}` 的子串，若先替换，自定义模板里写的
     * `{{user}}` 会被啃成 `{用户名}`（宏被破坏）。调用顺序：宏渲染 → 本函数
     * （这样两种写法都能得到用户名：`{user}` 走占位符，`{{user}}` 走宏引擎）。
     */
    fun applyUserNamePlaceholder(
        template: String,
        userName: String,
    ): String = template.replace(GROUP_CHAT_USER_PLACEHOLDER, userName)
}
