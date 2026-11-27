package com.mistbell.tavern.android.data.model

/**
 * 界面模式（P3「简洁模式是执行边界」，docs/ROADMAP.md / docs/UI_MODE.md）。
 *
 * 本文件是模式能力的**单一真相**：零依赖（只许用 Kotlin 标准库），data 与 extension
 * 下的闸门只 import 本包，不得反向依赖 ui.*。
 *
 * - [SIMPLE]：三个 Tab 的默认层。规划表里「不执行，也不在后台跑」的能力在此模式下全部关闭。
 * - [ADVANCED]：现有能力原样保留（正则、脚本、HTML 界面、扩展钩子）。
 *
 * 存库值是小写名字（"simple" / "advanced"），缺省或无法识别一律回落 [ADVANCED]——
 * 本项落地时升级用户与全新安装都保持现状行为（默认简洁的切换是 P3 第 2 项，不在本次范围）。
 */
enum class UiMode {
    SIMPLE,
    ADVANCED,
    ;

    /** 存库值：小写名字（SettingsRepository 写入、读出共用） */
    val storedValue: String
        get() = name.lowercase()

    companion object {
        /**
         * 从 settings 原始值解析模式：null / 空串 / 无法识别（含旧稿枚举残留 "beginner"，
         * 该枚举从未接线落库）一律 [ADVANCED]，未设置时行为与历史版本完全一致。
         */
        fun fromSetting(raw: String?): UiMode =
            raw
                ?.trim()
                ?.let { normalized ->
                    entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: ADVANCED
                } ?: ADVANCED
    }
}

/**
 * 一个界面模式下允许运行的能力清单（规划表「不执行，也不在后台跑」的一一对应）。
 *
 * 所有闸门只读本清单，不各自判断模式；模式 → 能力的换算只有 [enabledCapabilities] 一处。
 * [ADVANCED] 全部为 true；[SIMPLE] 全部为 false。中间态不存在——如果某天要单独关某条
 * 能力，先改这里并同步 docs/UI_MODE.md 的矩阵。
 */
data class EnabledCapabilities(
    /** 卡级与全局正则（展示、用户输入、提示词三路） */
    val cardAndGlobalRegex: Boolean,
    /** 规则包内容：提示词块 / 输入输出正则 / 包宏 */
    val rulePackContent: Boolean,
    /** 规则包脚本（含脚本注册的动态斜杠命令，随脚本卸载一并摘除） */
    val rulePackScripts: Boolean,
    /** 扩展包宿主钩子 */
    val extensionPackHooks: Boolean,
    /** 卡内脚本与 HTML 界面（WebView、桥、宿主层、悬浮球、WebView 预热） */
    val cardScriptsAndHtmlUi: Boolean,
    /** MVU 变量更新（写 ext.mvu.* + 发 VariablesUpdated） */
    val mvuVariableUpdates: Boolean,
) {
    /** 规则包内容与脚本共用同一个包源闸点（装饰器），两者任一关闭即整源给空表 */
    val rulePacksAllowed: Boolean
        get() = rulePackContent && rulePackScripts
}

/** 模式 → 能力清单（唯一换算点） */
fun enabledCapabilities(mode: UiMode): EnabledCapabilities {
    val allOn = mode == UiMode.ADVANCED
    return EnabledCapabilities(
        cardAndGlobalRegex = allOn,
        rulePackContent = allOn,
        rulePackScripts = allOn,
        extensionPackHooks = allOn,
        cardScriptsAndHtmlUi = allOn,
        mvuVariableUpdates = allOn,
    )
}
