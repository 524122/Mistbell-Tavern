package com.mistbell.tavern.android.data.model

// 启动期界面模式缺省与升级判定（P3 第 2 项「按模式收拢界面」，docs/UI_MODE.md §5）。
//
// 与 UiMode 的执行边界分工：UiMode 回答「某能力在该模式下是否运行」（单一真相，勿改语义）；
// 本文件回答「这台设备此刻应该处在什么模式、要不要弹模式选择」。两者都是零依赖纯函数。
//
// 判定规则（docs/UI_MODE.md §5 流程）：
// - settings 里已有可识别的 `ui_mode` → 保持该值，不弹窗不写库（显式选择永远最大）；
// - 引导未完成（= 全新安装，`onboarding_done` 缺失）→ 默认简洁，不弹窗
//   （写入 SIMPLE 的动作在引导完成标记与 UiModePromptCoordinator 的兜底里，见其 KDoc）；
// - 升级用户（引导已完成、从未设置过 ui_mode）首启 → 弹一次模式选择，
//   预选项由生态信号决定（任一信号为真预选高级，否则预选简洁）；
// - 弹过（`ui_mode_prompted`）但 ui_mode 值缺失（理论不应发生，防御分支）→ 保守按高级保持，
//   不再弹——第 1 项的执行闸门缺省高级不变，也不因重弹打断用户。
//
// 注意 UiMode.fromSetting 的缺省仍是 ADVANCED（执行闸门的缺省，勿改）；「全新安装默认简洁」
// 的落库动作是显式写 `ui_mode = "simple"`（引导完成时 / 兜底判定时），不是改解析缺省。

/**
 * 四个生态信号（规划 P3 第 2 项）：升级首启预选高级的依据，任一为真即预选 [UiMode.ADVANCED]。
 * 查询实现在仓库层（`UiModeEcosystemProbe`，suspend），本类只承载数值。
 */
data class UiModeEcosystemSignals(
    /** 有正则规则：全局正则表里有行，或任一角色卡带卡内 `regex_scripts` */
    val hasRegexRules: Boolean,
    /** 有扩展包：任一启用的非内置扩展包，或任一启用的规则包（内置 tavernhelper-compat 不算） */
    val hasEnabledPacks: Boolean,
    /** HTML 界面开着（settings `html_ui_enabled == "1"`） */
    val hasHtmlUiEnabled: Boolean,
    /** 导入过酒馆卡（近似判定：角色表非空；误差与口径见 UiModeEcosystemProbe 注释） */
    val hasImportedTavernCard: Boolean,
) {
    /** 任一信号为真（预选高级的谓词） */
    val hasAny: Boolean
        get() = hasRegexRules || hasEnabledPacks || hasHtmlUiEnabled || hasImportedTavernCard

    companion object {
        /** 无任何信号（全新安装 / 干净升级库） */
        val NONE: UiModeEcosystemSignals =
            UiModeEcosystemSignals(
                hasRegexRules = false,
                hasEnabledPacks = false,
                hasHtmlUiEnabled = false,
                hasImportedTavernCard = false,
            )
    }
}

/** 判定输入：三个 settings 标记（原值）+ 生态信号 */
data class UiModeStartupInputs(
    /** settings `ui_mode` 原始存值；null/空白/无法识别（如从未落库的旧稿枚举残留）= 从未显式设置 */
    val explicitModeRaw: String?,
    /** 首启引导是否已完成（`onboarding_done == "1"`；false = 全新安装） */
    val onboardingDone: Boolean,
    /** 升级模式选择弹窗是否已弹过（`ui_mode_prompted == "1"`；确认与关掉都写） */
    val promptAlreadyShown: Boolean,
    val signals: UiModeEcosystemSignals,
)

/** 判定结果的三种出口 */
sealed interface UiModeStartupResolution {
    /** 保持既有值：settings 已有可识别 ui_mode；或弹过但值缺失时的保守高级。不写库、不弹窗 */
    data class Keep(val mode: UiMode) : UiModeStartupResolution

    /** 全新安装：默认简洁，不弹窗（调用方负责把 SIMPLE 落库） */
    data object FreshInstallSimple : UiModeStartupResolution

    /** 升级首启：弹一次模式选择，[preselect] 为预选项（默认选中；关掉 = 保持预选项） */
    data class Prompt(val preselect: UiMode) : UiModeStartupResolution
}

/** 启动期模式判定（唯一换算点；流程见文件 KDoc） */
fun resolveStartupUiMode(inputs: UiModeStartupInputs): UiModeStartupResolution {
    // 显式判定只认可识别的存值：null/空白/"beginner"（旧稿枚举残留、从未接线落库）都算从未设置，
    // 与 UiMode.fromSetting 的容错口径一致但不吞掉无法识别的值——不可识别 ≠ 用户显式选过高级
    val explicit =
        inputs.explicitModeRaw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw -> UiMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
    return when {
        explicit != null -> UiModeStartupResolution.Keep(explicit)
        !inputs.onboardingDone -> UiModeStartupResolution.FreshInstallSimple
        inputs.promptAlreadyShown -> UiModeStartupResolution.Keep(UiMode.ADVANCED)
        else ->
            UiModeStartupResolution.Prompt(
                if (inputs.signals.hasAny) UiMode.ADVANCED else UiMode.SIMPLE,
            )
    }
}
