package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity

/**
 * 「可用」的唯一判定：填了密钥（非空白）即视为可用。
 *
 * 新建会话的绑定解析（resolveSessionProviderId）与发送时的回落解析
 * （ChatRepository.loadLlmConfig 的 firstUsableProviderId）共用这一个定义，
 * 两处口径不一致会造成「创建时绑定了、发送时却不认」（或反向）的错位。
 */
internal fun hasUsableApiKey(config: ApiConfigEntity): Boolean = config.apiKey.isNotBlank()

/**
 * 新建会话时绑定的提供商（纯函数，唯一换算点）：
 * - 用户选了就用所选；
 * - 没选：优先「默认且填了密钥」的配置，其次「第一个填了密钥」的配置，都没有则保持旧取法（第一行）。
 *
 * 此前不看默认标记也不看密钥，直接取第一行——新用户在设置里连好 AI 之后开聊，会话却绑在
 * 没填密钥的内置配置上，发送时报「LLM 未配置」（P3 装机实测复现）。
 *
 * 放在数据层（自 ui/chat/ChatSetupViewModel.kt 原样迁入）：发送时的回落解析同样要用
 * 「可用」判定，定义留在 ui 会造成 data.repository → ui 的反向依赖。
 */
internal fun resolveSessionProviderId(
    configs: List<ApiConfigEntity>,
    selectedId: String?,
): String {
    if (!selectedId.isNullOrBlank()) return selectedId
    return configs.firstOrNull { it.isDefault && hasUsableApiKey(it) }?.id
        ?: configs.firstOrNull { hasUsableApiKey(it) }?.id
        ?: configs.firstOrNull()?.id
        ?: ""
}

/**
 * 第一个「可用」（填了密钥）的配置 id；一个可用的都没有时返回 null——调用方保持现状
 * （用默认配置，发送时照旧报「LLM 未配置」）。
 */
internal fun firstUsableProviderId(configs: List<ApiConfigEntity>): String? = configs.firstOrNull(::hasUsableApiKey)?.id
