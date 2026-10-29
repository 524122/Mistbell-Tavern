package com.mistbell.tavern.android.data.regex

import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import java.util.UUID

/**
 * 全局正则脚本存取与编排：实体 ↔ 引擎脚本 ↔ 编辑器草稿三态映射，
 * 以及"全局先于角色卡"的执行顺序（对齐酒馆 regex 扩展 SCRIPT_TYPES：
 * GLOBAL=0 先于 SCOPED=1，同层内按列表顺序）。
 */
@Suppress("TooManyFunctions") // 实体/脚本/草稿三态映射与名单解析是同一纯函数存取的内聚职责
object GlobalRegexStore {
    private val json = Json

    // 与卡级 RegexRuleDraft 相同的酒馆 placement 语义：1=用户输入 2=AI 输出
    private const val PLACEMENT_USER_INPUT = 1
    private const val PLACEMENT_AI_OUTPUT = 2

    /** settings KV 键：卡级正则停用名单（逗号分隔角色 id；缺省=全部允许，维持导入即生效的既有行为） */
    const val KEY_CARD_REGEX_DISABLED = "card_regex_disabled"

    fun toScripts(rules: List<GlobalRegexRuleEntity>): List<CardRegexEngine.CardRegexScript> = rules.map(::toScript)

    fun toScript(rule: GlobalRegexRuleEntity): CardRegexEngine.CardRegexScript =
        CardRegexEngine.CardRegexScript(
            name = rule.name,
            findRegex = rule.findRegex,
            replaceString = rule.replaceString,
            disabled = rule.disabled,
            placement = parseIntList(rule.placementJson),
            trimStrings = parseIntListToStrings(rule.trimStringsJson),
            markdownOnly = rule.markdownOnly,
            promptOnly = rule.promptOnly,
            runOnEdit = rule.runOnEdit,
            minDepth = rule.minDepth,
            maxDepth = rule.maxDepth,
        )

    /** 保存整份文档：列表顺序即落库 sortOrder；id 每次重生成（整表替换，无外部引用） */
    fun fromDrafts(
        drafts: List<RegexRuleDraft>,
        now: Long,
    ): List<GlobalRegexRuleEntity> =
        drafts.mapIndexed { index, draft ->
            GlobalRegexRuleEntity(
                id = UUID.randomUUID().toString(),
                name = draft.name.ifBlank { "未命名" },
                findRegex = draft.findRegex,
                replaceString = draft.replaceString,
                disabled = draft.disabled,
                placementJson = encodeIntList(draft.placement),
                trimStringsJson = encodeIntListToStrings(draft.trimStrings),
                markdownOnly = draft.markdownOnly,
                promptOnly = draft.promptOnly,
                runOnEdit = draft.runOnEdit,
                minDepth = draft.minDepthText.toIntOrNull(),
                maxDepth = draft.maxDepthText.toIntOrNull(),
                sortOrder = index,
                createdAt = now,
            )
        }

    fun toDocument(rules: List<GlobalRegexRuleEntity>): CardRegexDocument =
        CardRegexDocument(
            rules =
                rules.mapIndexed { index, rule ->
                    RegexRuleDraft(
                        id = index,
                        name = rule.name,
                        findRegex = rule.findRegex,
                        replaceString = rule.replaceString,
                        disabled = rule.disabled,
                        userInput = parseIntList(rule.placementJson).contains(PLACEMENT_USER_INPUT),
                        aiOutput = parseIntList(rule.placementJson).contains(PLACEMENT_AI_OUTPUT),
                        markdownOnly = rule.markdownOnly,
                        promptOnly = rule.promptOnly,
                        runOnEdit = rule.runOnEdit,
                        minDepthText = rule.minDepth?.toString().orEmpty(),
                        maxDepthText = rule.maxDepth?.toString().orEmpty(),
                        trimStringsText = parseIntListToStrings(rule.trimStringsJson).joinToString("\n"),
                    )
                },
        )

    /** 生效脚本 = 全局 + 角色卡：全局先执行，卡级可处理全局产物（如先剥思维链再转 HTML 面板） */
    fun combine(
        global: List<CardRegexEngine.CardRegexScript>,
        card: List<CardRegexEngine.CardRegexScript>,
    ): List<CardRegexEngine.CardRegexScript> = global + card

    /** 坏 JSON 容错为空数组：规则保留可编辑，但不再命中任何 placement（与卡级坏条目跳过一致） */
    private fun parseIntList(raw: String): List<Int> =
        runCatching { json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.intOrNull } }
            .getOrDefault(emptyList())

    private fun parseIntListToStrings(raw: String): List<String> =
        runCatching { json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.content } }
            .getOrDefault(emptyList())

    private fun encodeIntList(values: List<Int>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    private fun encodeIntListToStrings(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    /** 停用名单解析：空串/null/坏格式一律视为空集（全部允许），单点坏值不拖垮整串 */
    fun parseDisabledIds(raw: String?): Set<String> {
        if (raw == null) return emptySet()
        return raw.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }

    fun formatDisabledIds(ids: Set<String>): String = ids.joinToString(",")
}
