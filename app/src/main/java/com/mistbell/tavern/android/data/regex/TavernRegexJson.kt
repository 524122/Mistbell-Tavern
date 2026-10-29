package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 酒馆助手契约的正则脚本 JSON（getTavernRegexes）：字段名对齐 ST regex 扩展的
 * scriptName/findRegex/... 形态，id 以 "global-"/"card-" 前缀区分来源。
 * 数组顺序即执行顺序（全局先、卡级后，与显示/提示词管线一致）。
 */
object TavernRegexJson {
    // encodeDefaults：未设置的 minDepth/maxDepth 显式输出 null（对齐 ST regex 脚本 JSON 形态）
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class TavernRegexScript(
        val id: String,
        val scriptName: String,
        val findRegex: String,
        val replaceString: String,
        val trimStrings: List<String>,
        val placement: List<Int>,
        val disabled: Boolean,
        val markdownOnly: Boolean,
        val promptOnly: Boolean,
        val runOnEdit: Boolean,
        val minDepth: Int? = null,
        val maxDepth: Int? = null,
    )

    fun toTavernJson(
        global: List<CardRegexEngine.CardRegexScript>,
        card: List<CardRegexEngine.CardRegexScript>,
    ): String {
        val globalScripts = global.mapIndexed { index, script -> script.toTavernScript("global-$index") }
        val cardScripts = card.mapIndexed { index, script -> script.toTavernScript("card-$index") }
        return json.encodeToString(ListSerializer(TavernRegexScript.serializer()), globalScripts + cardScripts)
    }

    private fun CardRegexEngine.CardRegexScript.toTavernScript(id: String): TavernRegexScript =
        TavernRegexScript(
            id = id,
            scriptName = name,
            findRegex = findRegex,
            replaceString = replaceString,
            trimStrings = trimStrings,
            placement = placement,
            disabled = disabled,
            markdownOnly = markdownOnly,
            promptOnly = promptOnly,
            runOnEdit = runOnEdit,
            minDepth = minDepth,
            maxDepth = maxDepth,
        )
}
