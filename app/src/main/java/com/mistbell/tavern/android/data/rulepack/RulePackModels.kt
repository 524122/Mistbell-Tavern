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

/** 输出正则：回复落库前对正文执行 replace（v1 仅输出侧） */
@Serializable
data class RulePackRegexRule(
    val name: String,
    val pattern: String,
    val replacement: String = "",
    val enabled: Boolean = true,
)

/** 自定义宏：{{name}} → value；value 可再含一层内置宏（由 MacroEngine 渲染，不递归自定义宏） */
@Serializable
data class RulePackMacro(
    val name: String,
    val value: String,
    val enabled: Boolean = true,
)

/** 三个可选内容文件的合并体（RulePackEntity.contentJson 的序列化形态） */
@Serializable
data class RulePackContent(
    val prompts: List<RulePackPrompt> = emptyList(),
    val regex: List<RulePackRegexRule> = emptyList(),
    val macros: List<RulePackMacro> = emptyList(),
)
