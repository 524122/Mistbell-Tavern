package com.mistbell.tavern.android.ui.main

import com.mistbell.tavern.android.data.model.UiMode

/**
 * 主导航目的地（P3 第 2 项「按模式收拢界面」）。
 *
 * 历史教训：底部导航曾按**列表下标**保存选中态（rememberSaveable 存 0–4），目的地一收拢，
 * 旧的下标值就会指到别的 Tab。现在目的地是带稳定 id 的枚举，选中态存 id；恢复出未知 id
 * （旧版本残留或数据异常）一律回落 [CHAT]。
 *
 * 本文件保持零 Android 依赖（icon / 文案等界面皮在 MainScreen.kt），模式 → 可见 Tab 与
 * 选中回落是纯函数，可 JVM 单测。顺序即各容器里的展示顺序。
 */
enum class MainTab(val id: String) {
    CHAT("chat"),
    CHARACTERS("characters"),
    WORLD_BOOK("world_book"),
    REGEX("regex"),
    SETTINGS("settings"),
    ;

    companion object {
        /**
         * 选中态的保存键（rememberSaveable）。第 2 项把「下标 0–4」换成「稳定 id」时**换新键**，
         * 避免升级后从保存态恢复出旧的下标值（"1" 这类下标残值不是任何 id，按未知回落 CHAT）。
         */
        const val SAVE_KEY = "main_selected_tab_id_v2"

        /** 由保存的 id 恢复 Tab；未知 id（含旧版下标残值）回落 CHAT */
        fun fromId(id: String?): MainTab = entries.firstOrNull { it.id == id } ?: CHAT
    }
}

/**
 * 模式 → 可见 Tab（唯一换算点）。
 * 简洁模式：聊天、角色、设置（世界书 / 正则入口只在高级模式出现，规划 P3 第 2 项）；
 * 高级模式：保持现状五个；**null（模式未知，AppContainer.uiMode 尚未从数据库读回）→ 空列表**：
 * 界面与闸门同一口径——未知不渲染随模式变化的东西（宁可空栏一帧，不闪错误的五个 Tab）。
 */
fun visibleMainTabs(mode: UiMode?): List<MainTab> =
    when (mode) {
        UiMode.SIMPLE -> listOf(MainTab.CHAT, MainTab.CHARACTERS, MainTab.SETTINGS)
        UiMode.ADVANCED -> listOf(MainTab.CHAT, MainTab.CHARACTERS, MainTab.WORLD_BOOK, MainTab.REGEX, MainTab.SETTINGS)
        null -> emptyList()
    }

/**
 * 模式切换后选中 Tab 被隐藏时的回落：回落到聊天（模式未知时同样回落聊天）。
 * 只影响当前显示（换回高级后原选中仍恢复）；改写选中态是调用方的选择，本函数不负责持久化。
 */
fun settledMainTab(
    selected: MainTab,
    mode: UiMode?,
): MainTab = if (mode != null && selected in visibleMainTabs(mode)) selected else MainTab.CHAT
