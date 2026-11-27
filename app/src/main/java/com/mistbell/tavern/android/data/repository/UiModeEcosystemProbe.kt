package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.model.UiModeEcosystemSignals
import com.mistbell.tavern.android.util.BuiltInCharacters
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
 * - 导入过酒馆卡：角色表里存在**非内置**角色（P3 第 4 项起排除内置，见下）。
 *
 * 「导入过酒馆卡」的口径（P3 第 4 项定稿）：内置角色的 id 固定为 `builtin_` 前缀
 * （单一真相 `BuiltInCharacters.isBuiltIn`，与内置角色落库同一口径），本查询一律排除——
 * 全新安装（只有内置角色落库）时信号为假，不会把新装设备误判成「带酒馆资产」。
 * 用户自己导入的卡仍是导入路径生成的随机 UUID，照常命中；升级前的旧版示例卡
 * （同样是随机 UUID 的历史副本）也照常命中——偏向预选高级 = 保持升级前现状，符合总原则。
 */
object UiModeEcosystemProbe {
    /** 一次性收集四个信号（首启判定只跑一次；其余启动路径不查） */
    suspend fun signals(db: AppDatabase): UiModeEcosystemSignals =
        withContext(Dispatchers.IO) {
            // 角色一次取全：卡内正则与「导入过酒馆卡」共用同份快照
            val characters = db.characterDao().getAllOnce()
            UiModeEcosystemSignals(
                hasRegexRules =
                    db.globalRegexDao().getAll().isNotEmpty() ||
                        characters.any { entity -> hasCardRegexScripts(entity) },
                hasEnabledPacks =
                    db.extensionPackDao().getEnabled().any { !it.isBuiltin } ||
                        db.rulePackDao().observeEnabled().first().isNotEmpty(),
                hasHtmlUiEnabled = db.settingsDao().getValue("html_ui_enabled") == "1",
                hasImportedTavernCard = characters.any { entity -> !BuiltInCharacters.isBuiltIn(entity.id) },
            )
        }

    /** 卡内 regex_scripts 信号：extensions.regex_scripts 为非空 JSON 数组即算（解析失败视为没有） */
    internal fun hasCardRegexScripts(entity: CharacterEntity): Boolean =
        (entity.toDomain().data?.extensions?.get("regex_scripts") as? JsonArray)?.isNotEmpty() == true
}
