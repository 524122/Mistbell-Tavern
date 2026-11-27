package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.model.UiModeEcosystemSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/**
 * 升级首启模式选择（P3 第 2 项）的四个生态信号查询（仓库层，suspend，一次性）。
 *
 * 判定口径（docs/UI_MODE.md §5；与规划原文逐条对应）：
 * - 正则规则：`global_regex_rules` 表有行，或任一角色卡的 `data_json → extensions.regex_scripts`
 *   是非空数组。停用的行/规则也算——它们同样说明这台设备带酒馆资产。
 * - 扩展包：任一启用的**非内置**扩展包，或任一启用的规则包。内置官方包 tavernhelper-compat
 *   随产品发布由启动种子落库（`is_builtin = 1` 且 enabled=true），每台设备都有，不算信号。
 * - HTML 界面：settings `html_ui_enabled == "1"`。
 * - 导入过酒馆卡：**近似**——角色表非空。说明：导入路径（CharacterImporter/Onboarding）不落任何
 *   「来源」标记，角色 id 是导入时生成的 UUID，事后无法区分「用户自己的卡」「内置示例卡」「手建角色」；
 *   且内置示例卡本身就是酒馆 v2 格式、经同一条导入路径落库，因此「存在角色」是当前可获得的最接近
 *   近似。手建角色也会命中（偏向预选高级 = 保持升级前现状，符合「老用户不丢入口」总原则）；
 *   P3 第 4 项落地真正内置角色时，需把它们的标记排除出本查询。
 */
object UiModeEcosystemProbe {
    /** 一次性收集四个信号（首启判定只跑一次；其余启动路径不查） */
    suspend fun signals(db: AppDatabase): UiModeEcosystemSignals =
        withContext(Dispatchers.IO) {
            // 角色一次取全：卡内正则与「导入过酒馆卡」近似共用同份快照
            val characters = db.characterDao().getAllOnce()
            UiModeEcosystemSignals(
                hasRegexRules =
                    db.globalRegexDao().getAll().isNotEmpty() ||
                        characters.any { entity -> hasCardRegexScripts(entity) },
                hasEnabledPacks =
                    db.extensionPackDao().getEnabled().any { !it.isBuiltin } ||
                        db.rulePackDao().observeEnabled().first().isNotEmpty(),
                hasHtmlUiEnabled = db.settingsDao().getValue("html_ui_enabled") == "1",
                hasImportedTavernCard = characters.isNotEmpty(),
            )
        }

    /** 卡内 regex_scripts 信号：extensions.regex_scripts 为非空 JSON 数组即算（解析失败视为没有） */
    internal fun hasCardRegexScripts(entity: CharacterEntity): Boolean =
        (entity.toDomain().data?.extensions?.get("regex_scripts") as? JsonArray)?.isNotEmpty() == true
}
