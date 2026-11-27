package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 一张卡的「需要高级模式」能力概况（P3 导入报告，docs/ROADMAP.md「导入报告」节）。
 *
 * 简洁模式下这些能力保持关闭（闸门见 docs/UI_MODE.md §1–§3，本类型只做判定不做闸）。
 * 默认值即「无」——干净卡导入后报告不出现这一栏。
 */
data class AdvancedCapabilityProfile(
    /** 卡内正则条数（extensions.regex_scripts 的条目数） */
    val regexCount: Int = 0,
    /** 卡内脚本段数（extensions.tavern_helper.scripts 的段数） */
    val scriptCount: Int = 0,
    /** 开场白 / 备用开场白 / 示例对话 / 角色描述 / 世界书条目里出现 HTML 界面片段（containsHtml 口径） */
    val hasHtmlUi: Boolean = false,
    /** 上述语料里出现 <UpdateVariable> 标记（MVU 状态栏变量更新） */
    val hasMvuUpdates: Boolean = false,
) {
    /** 任一能力命中（导入报告「需要高级模式」一栏与角色卡小标记的显示条件） */
    val hasAny: Boolean
        get() = regexCount > 0 || scriptCount > 0 || hasHtmlUi || hasMvuUpdates

    companion object {
        /** 无任何高级能力（干净卡的缺省值） */
        val NONE: AdvancedCapabilityProfile = AdvancedCapabilityProfile()
    }
}

/**
 * 批量导入（酒馆数据文件夹同步）的「需要高级模式」聚合：各类能力的数量与涉及的角色名。
 * 数量口径：正则 / 脚本是条数（段数）累加；HTML 界面 / MVU 是命中角色的个数。
 */
data class AdvancedCapabilityTotals(
    val regexCount: Int = 0,
    val scriptCount: Int = 0,
    val htmlUiCount: Int = 0,
    val mvuCount: Int = 0,
    val regexCharacters: List<String> = emptyList(),
    val scriptCharacters: List<String> = emptyList(),
    val htmlUiCharacters: List<String> = emptyList(),
    val mvuCharacters: List<String> = emptyList(),
) {
    val hasAny: Boolean
        get() = regexCount > 0 || scriptCount > 0 || htmlUiCount > 0 || mvuCount > 0

    companion object {
        /** 无任何高级能力（空批 / 全干净卡的缺省值） */
        val NONE: AdvancedCapabilityTotals = AdvancedCapabilityTotals()

        /** 多张卡的聚合（纯函数）：数量累加、角色名去重且保持出现顺序 */
        fun aggregate(cards: List<Pair<String, AdvancedCapabilityProfile>>): AdvancedCapabilityTotals {
            val regexNames = linkedSetOf<String>()
            val scriptNames = linkedSetOf<String>()
            val htmlNames = linkedSetOf<String>()
            val mvuNames = linkedSetOf<String>()
            var regexTotal = 0
            var scriptTotal = 0
            cards.forEach { (name, profile) ->
                regexTotal += profile.regexCount
                scriptTotal += profile.scriptCount
                if (profile.regexCount > 0) regexNames += name
                if (profile.scriptCount > 0) scriptNames += name
                if (profile.hasHtmlUi) htmlNames += name
                if (profile.hasMvuUpdates) mvuNames += name
            }
            return AdvancedCapabilityTotals(
                regexCount = regexTotal,
                scriptCount = scriptTotal,
                htmlUiCount = htmlNames.size,
                mvuCount = mvuNames.size,
                regexCharacters = regexNames.toList(),
                scriptCharacters = scriptNames.toList(),
                htmlUiCharacters = htmlNames.toList(),
                mvuCharacters = mvuNames.toList(),
            )
        }
    }
}

/**
 * 「需要高级模式」的判定（纯函数，唯一换算点，P3 导入报告与角色卡小标记共用）。
 *
 * 判定字段（规划原文）：
 * - 正则：`extensions.regex_scripts` 的条目数；
 * - 卡内脚本：`extensions.tavern_helper.scripts` 的段数（数组取长度、对象取键数）；
 * - HTML 界面：开场白（含备用开场白）、示例对话、角色描述、世界书条目里出现
 *   [HtmlCardText.containsHtml] 口径的片段；
 * - MVU 变量更新：同一语料里出现 `<UpdateVariable>` 标记（忽略大小写）。
 *
 * 输入选型：核心只吃「extensions + 文本语料」，再提供实体 / 领域模型两个适配——
 * 导入时（CharacterImportResult：实体 + 内嵌世界书条目）与列表上事后显示标记
 * （领域模型：卡自身字段）走同一个核心，同一张卡两种表示判定结果一致（单测背书）。
 * 角色卡小标记不落库：列表页按需用本判定现场计算，无需 schema 变更。
 */
object AdvancedCapabilityDetector {
    /** MVU 块的起始标记（生态惯例 `<UpdateVariable>…</UpdateVariable>`；忽略大小写） */
    private const val MVU_MARKER = "<UpdateVariable"

    /** 核心判定：extensions（透传保真的原始 JSON）+ 参与判定的文本语料 */
    fun detect(
        extensions: JsonObject?,
        texts: List<String>,
    ): AdvancedCapabilityProfile =
        AdvancedCapabilityProfile(
            regexCount = extensions?.countScripts("regex_scripts") ?: 0,
            scriptCount = extensions?.tavernHelperScriptCount() ?: 0,
            hasHtmlUi = texts.any(HtmlCardText::containsHtml),
            hasMvuUpdates = texts.any { it.contains(MVU_MARKER, ignoreCase = true) },
        )

    /** 导入结果适配：实体（dataJson 解出 extensions 与备用开场白）+ 内嵌世界书条目 */
    fun detect(result: CharacterImportResult): AdvancedCapabilityProfile {
        return detect(result.character, result.worldBookEntries)
    }

    /** 实体适配（导入落库侧）：dataJson 与世界书条目 */
    fun detect(
        entity: CharacterEntity,
        entries: List<WorldBookEntryEntity>,
    ): AdvancedCapabilityProfile {
        val data = entity.dataOrNull()
        val texts =
            buildList {
                add(entity.firstMes)
                add(entity.mesExample)
                add(entity.description)
                addAll(data?.alternateGreetings.orEmpty())
                entries.forEach { add(it.content) }
            }
        return detect(data?.extensions, texts)
    }

    /** 领域模型适配（角色列表侧）：与实体适配同一张卡时结果一致（dataJson ↔ data 字段） */
    fun detect(character: Character): AdvancedCapabilityProfile {
        val texts =
            buildList {
                add(character.firstMes)
                add(character.mesExample)
                add(character.description)
                addAll(character.data?.alternateGreetings.orEmpty())
            }
        return detect(character.data?.extensions, texts)
    }

    /** dataJson → CharacterData（容忍坏 JSON：与 CharacterEntity.toDomain 同一容错口径） */
    private fun CharacterEntity.dataOrNull(): CharacterData? =
        try {
            if (dataJson.isNotBlank()) {
                Json { ignoreUnknownKeys = true }.decodeFromString(CharacterData.serializer(), dataJson)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    /** 数组取长度、对象取键数，其余（缺字段 / 类型不符）一律 0 */
    private fun JsonObject.countScripts(key: String): Int =
        when (val element = this[key]) {
            is JsonArray -> element.size
            is JsonObject -> element.size
            else -> 0
        }

    /** extensions.tavern_helper.scripts 的段数（tavern_helper 缺失或非对象时 0） */
    private fun JsonObject.tavernHelperScriptCount(): Int {
        val helper = this["tavern_helper"] as? JsonObject
        return helper?.countScripts("scripts") ?: 0
    }
}
