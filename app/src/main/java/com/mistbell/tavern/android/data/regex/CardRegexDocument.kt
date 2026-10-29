package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val REGEX_SCRIPTS_KEY = "regex_scripts"
private const val USER_INPUT_PLACEMENT = 1
private const val AI_OUTPUT_PLACEMENT = 2

data class RegexRuleDraft(
    val id: Int,
    val name: String,
    val findRegex: String,
    val replaceString: String = "",
    val disabled: Boolean = false,
    val userInput: Boolean = false,
    val aiOutput: Boolean = true,
    val markdownOnly: Boolean = true,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = false,
    val minDepthText: String = "",
    val maxDepthText: String = "",
    val trimStringsText: String = "",
    val original: JsonObject? = null,
) {
    val placement: List<Int>
        get() =
            buildList {
                if (userInput) add(USER_INPUT_PLACEMENT)
                if (aiOutput) add(AI_OUTPUT_PLACEMENT)
            }

    val trimStrings: List<String>
        get() = trimStringsText.split('\n').filter { it.isNotEmpty() }

    fun toScript(): CardRegexEngine.CardRegexScript =
        CardRegexEngine.CardRegexScript(
            name = name.ifBlank { "未命名" },
            findRegex = findRegex,
            replaceString = replaceString,
            disabled = disabled,
            placement = placement,
            trimStrings = trimStrings,
            markdownOnly = markdownOnly,
            promptOnly = promptOnly,
            runOnEdit = runOnEdit,
            minDepth = minDepthText.toIntOrNull(),
            maxDepth = maxDepthText.toIntOrNull(),
        )

    fun validationError(): String? =
        when {
            findRegex.isBlank() -> "匹配表达式不能为空"
            !disabled && placement.isEmpty() -> "启用规则至少选择用户输入或 AI 输出"
            !disabled && CardRegexEngine.compileRegex(findRegex) == null -> "匹配表达式无效"
            minDepthText.isNotEmpty() && minDepthText.toIntOrNull() == null -> "最小深度须为整数"
            maxDepthText.isNotEmpty() && maxDepthText.toIntOrNull() == null -> "最大深度须为整数"
            depthRangeInvalid() -> "最小深度不能大于最大深度"
            else -> null
        }

    private fun depthRangeInvalid(): Boolean {
        val min = minDepthText.toIntOrNull()
        val max = maxDepthText.toIntOrNull()
        return min != null && max != null && min >= 0 && max >= 0 && min > max
    }

    fun toJson(): JsonObject {
        val fields = original?.toMutableMap() ?: mutableMapOf()
        fields["scriptName"] = JsonPrimitive(name.ifBlank { "未命名" })
        fields["findRegex"] = JsonPrimitive(findRegex)
        fields["replaceString"] = JsonPrimitive(replaceString)
        fields["disabled"] = JsonPrimitive(disabled)
        fields["placement"] = JsonArray(placement.map(::JsonPrimitive))
        fields["trimStrings"] = JsonArray(trimStrings.map(::JsonPrimitive))
        fields["markdownOnly"] = JsonPrimitive(markdownOnly)
        fields["promptOnly"] = JsonPrimitive(promptOnly)
        fields["runOnEdit"] = JsonPrimitive(runOnEdit)
        setNullableInt(fields, "minDepth", minDepthText)
        setNullableInt(fields, "maxDepth", maxDepthText)
        return JsonObject(fields)
    }

    private fun setNullableInt(
        fields: MutableMap<String, JsonElement>,
        key: String,
        value: String,
    ) {
        val parsed = value.toIntOrNull()
        if (parsed == null) fields.remove(key) else fields[key] = JsonPrimitive(parsed)
    }
}

enum class RegexPreviewMode {
    DISPLAY,
    USER_INPUT,
    PROMPT,
}

data class RegexPreviewRequest(
    val input: String = "",
    val mode: RegexPreviewMode = RegexPreviewMode.DISPLAY,
    val role: String = "assistant",
    val depthText: String = "0",
    val isEdit: Boolean = false,
)

data class RegexPreviewResult(
    val text: String,
    val skippedInvalidRules: Int = 0,
)

data class CardRegexDocument(
    val rules: List<RegexRuleDraft> = emptyList(),
    val preservedElements: List<Pair<Int, JsonElement>> = emptyList(),
) {
    fun toScripts(): List<CardRegexEngine.CardRegexScript> = rules.map(RegexRuleDraft::toScript)

    fun insert(
        rule: RegexRuleDraft,
        selectedRuleId: Int?,
    ): CardRegexDocument {
        val index =
            rules.indexOfFirst { it.id == selectedRuleId }
                .let { if (it < 0) rules.size else it + 1 }
        return copy(rules = rules.toMutableList().apply { add(index, rule) })
    }

    fun validationErrors(): List<String> =
        rules.mapNotNull { rule ->
            rule.validationError()?.let { error -> "第 ${rules.indexOf(rule) + 1} 条：$error" }
        }

    fun updatedExtensions(current: JsonObject?): JsonObject {
        val fields = current?.toMutableMap() ?: mutableMapOf()
        val serialized = rules.map(RegexRuleDraft::toJson).toMutableList<JsonElement>()
        preservedElements.forEach { (index, element) ->
            serialized.add(index.coerceIn(0, serialized.size), element)
        }
        fields[REGEX_SCRIPTS_KEY] = JsonArray(serialized)
        return JsonObject(fields)
    }

    companion object {
        fun fromExtensions(extensions: JsonObject?): CardRegexDocument {
            val array = extensions?.get(REGEX_SCRIPTS_KEY) as? JsonArray ?: return CardRegexDocument()
            val rules = mutableListOf<RegexRuleDraft>()
            val preserved = mutableListOf<Pair<Int, JsonElement>>()
            array.forEachIndexed { index, element ->
                val obj = element as? JsonObject
                if (obj == null) {
                    preserved += index to element
                } else {
                    val script =
                        CardRegexEngine.parse(JsonObject(mapOf(REGEX_SCRIPTS_KEY to JsonArray(listOf(obj)))))
                            .singleOrNull()
                    rules +=
                        RegexRuleDraft(
                            id = index,
                            name = obj.text("scriptName").ifBlank { "未命名" },
                            findRegex = script?.findRegex ?: obj.text("findRegex"),
                            replaceString = script?.replaceString.orEmpty(),
                            disabled = script?.disabled ?: false,
                            userInput = script?.placement?.contains(USER_INPUT_PLACEMENT) == true,
                            aiOutput = script?.placement?.contains(AI_OUTPUT_PLACEMENT) == true,
                            markdownOnly = script?.markdownOnly ?: false,
                            promptOnly = script?.promptOnly ?: false,
                            runOnEdit = script?.runOnEdit ?: false,
                            minDepthText = script?.minDepth?.toString().orEmpty(),
                            maxDepthText = script?.maxDepth?.toString().orEmpty(),
                            trimStringsText = script?.trimStrings?.joinToString("\n").orEmpty(),
                            original = obj,
                        )
                }
            }
            return CardRegexDocument(rules, preserved)
        }
    }
}

fun evaluateRegexPreview(
    document: CardRegexDocument,
    request: RegexPreviewRequest,
): RegexPreviewResult {
    val depth = request.depthText.toIntOrNull()
    val text =
        when {
            depth == null -> "预览深度须为整数"
            depth < 0 -> "预览深度不能小于 0"
            else -> {
                val scripts = document.toScripts()
                when (request.mode) {
                    RegexPreviewMode.DISPLAY ->
                        CardRegexEngine.applyToDisplay(
                            request.input,
                            request.role,
                            scripts,
                            depth,
                            request.isEdit,
                        )
                    RegexPreviewMode.USER_INPUT ->
                        CardRegexEngine.applyToUserInput(request.input, scripts, request.isEdit)
                    RegexPreviewMode.PROMPT ->
                        CardRegexEngine.applyToPrompt(
                            request.input,
                            request.role,
                            scripts,
                            depth,
                            request.isEdit,
                        )
                }
            }
        }
    val skipped = document.rules.count { !it.disabled && it.validationError() != null }
    return RegexPreviewResult(text, skipped)
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
