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
    /** 可选：zip 内 JS 脚本文件名（声明即视为"脚本包"，导入需用户确认代码执行风险） */
    val script: String? = null,
    /**
     * ---- 扩展包新增字段（EXTENSION_PLATFORM_DESIGN.md §3）；缺省即规则包，向后兼容 ----
     * 包类型："extension" = 扩展包（P1 管理平面：权限清单确认 + filesDir 落盘存储），
     * 缺省 / "rulepack" = 规则包。同一解析器骨架，按类型走差异校验而非另起炉灶。
     */
    val type: String = RulePackManifest.TYPE_RULEPACK,
    /** 权限声明：严格白名单 + network:<host> 动态形态（PermissionCatalog 校验，目录外导入失败） */
    val permissions: List<String> = emptyList(),
    /** 可选：zip 内 WebView 预注入 JS 文件名（≤64KB；P1 仅存在性/大小校验 + 落盘，不解析不执行） */
    val webviewPreload: String? = null,
    /** 可选：zip 内宿主钩子 JS 文件名（≤256KB，沿用 MAX_SCRIPT_BYTES；P1 仅校验 + 落盘） */
    val hostHooks: String? = null,
) {
    companion object {
        const val TYPE_RULEPACK = "rulepack"
        const val TYPE_EXTENSION = "extension"
    }
}

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

/** 四个可选内容文件 + 可选脚本源码的合并体（RulePackEntity.contentJson 的序列化形态） */
@Serializable
data class RulePackContent(
    val prompts: List<RulePackPrompt> = emptyList(),
    val regex: List<RulePackRegexRule> = emptyList(),
    val macros: List<RulePackMacro> = emptyList(),
    val quickReplies: List<RulePackQuickReply> = emptyList(),
    /** manifest.script 声明的脚本源码（null=非脚本包）；随 contentJson 存储，零额外迁移 */
    val scriptSource: String? = null,
    /**
     * manifest.webviewPreload 声明的预注入 JS 源码（null=未声明）。
     * P1 仅校验+随扩展包落盘存储；注入接线是 P2，此字段对规则包恒为 null。
     */
    val webviewPreloadSource: String? = null,
    /** manifest.hostHooks 声明的宿主钩子 JS 源码（null=未声明）；P1 同上，不解析不执行 */
    val hostHooksSource: String? = null,
)
