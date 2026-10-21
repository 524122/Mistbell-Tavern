package com.mistbell.tavern.android.data.rulepack

import kotlinx.serialization.Serializable

/**
 * 规则包格式模型（zip 内各 JSON 文件的序列化定义）。
 *
 * 包结构：manifest.json（必需）+ prompts.json / regex.json / macros.json（均可选）。
 * 纯数据、无代码执行；正则仅做 Kotlin Regex.replace，宏值仅做查表替换。
 */

@Serializable
data class RulePackManifest(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "1",
    val description: String = "",
    /** 包级加载顺序（对齐酒馆 loading_order）：多包启用时按此升序应用 */
    val loadingOrder: Int = 100,
)

/** 提示词块：position 复用自定义提示词五值（SYSTEM_START/SYSTEM_END/USER_PREFIX/USER_SUFFIX/ASSISTANT_PREFIX） */
@Serializable
data class RulePackPrompt(
    val name: String,
    val content: String,
    val position: String = "SYSTEM_END",
    val priority: Int = 0,
    val enabled: Boolean = true,
)

/** 输出正则：回复落库前对正文执行 replace；applyTo 含 "input" 时同时作用于提示词中的最终用户消息（仅改 prompt，不改存储） */
@Serializable
data class RulePackRegexRule(
    val name: String,
    val pattern: String,
    val replacement: String = "",
    /** 作用域："output"（回复）/ "input"（用户消息）；缺省仅输出，与 v1 行为一致 */
    val applyTo: List<String> = listOf("output"),
    val enabled: Boolean = true,
)

/** 自定义宏：{{name}} → value；value 可再含一层内置宏（由 MacroEngine 渲染，不递归自定义宏） */
@Serializable
data class RulePackMacro(
    val name: String,
    val value: String,
    val enabled: Boolean = true,
)

/** 快捷回复：label 按钮文案；action = send（直接发送）/ insert（填入输入框）/ command（斜杠命令） */
@Serializable
data class RulePackQuickReply(
    val label: String,
    val action: String,
    val payload: String,
    val enabled: Boolean = true,
)

/** 四个可选内容文件的合并体（RulePackEntity.contentJson 的序列化形态） */
@Serializable
data class RulePackContent(
    val prompts: List<RulePackPrompt> = emptyList(),
    val regex: List<RulePackRegexRule> = emptyList(),
    val macros: List<RulePackMacro> = emptyList(),
    val quickReplies: List<RulePackQuickReply> = emptyList(),
)
