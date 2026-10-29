package com.mistbell.tavern.android.ui.settings

import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.normalizeApiType
import kotlinx.serialization.json.JsonObject

internal data class ApiConfigEditorFields(
    val id: String,
    val name: String,
    val type: String,
    val apiUrl: String,
    val model: String,
    val streamingEnabled: Boolean,
    val contextTokenLimit: Int,
)

/**
 * API 配置编辑器的纯状态逻辑（不依赖 Compose，可直接单测）。
 *
 * 关键约束：
 * - 非法 JSON 草稿优先（仅当 protectInvalidDraft = true，即用户手动改坏 JSON 时）：
 *   修改基础字段/API Key 不得静默用格式化文档覆盖用户草稿；
 *   表单自动生成的草稿在填写过程中可能暂时不完整（如 model 未填），不属于受保护的
 *   「用户草稿」，此时不得设置 sticky 错误，否则后续表单修改会被永久冻结（保存即失败）；
 * - API Key 不在 JSON 中，但修改它必须重新校验并清除“合法 JSON 上的陈旧错误”；
 * - 复制永远从解析成功的脱敏文档生成，非法/含凭据的草稿一律拒绝复制。
 */
internal object ApiConfigEditorLogic {
    data class JsonEdit(
        val text: String,
        val error: String?,
        /** 仅当草稿可解析时返回导入后的文档，供调用方同步表单字段。 */
        val parsed: ApiConfigDocument? = null,
    )

    data class TypeSwitch(
        val request: JsonObject,
        val keptUserRequest: Boolean,
    )

    fun formatForEditor(
        document: ApiConfigDocument,
        hasApiKey: Boolean,
    ): String = ApiConfigJson.format(ApiConfigJson.redacted(document, hasApiKey), requestFirst = true)

    /** 重置会丢弃 request 修改/自定义字段，或当前 JSON 草稿非法时需要确认。 */
    fun requiresResetConfirmation(
        jsonText: String,
        type: String,
    ): Boolean =
        when (val parsed = ApiConfigJson.parse(jsonText)) {
            is ApiConfigJsonResult.Invalid -> true
            is ApiConfigJsonResult.Valid -> !ApiRequestTemplate.isUnmodifiedTemplate(parsed.document.request, type)
        }

    /**
     * 协议切换：只有空 request 或仍是旧协议原始模板时才替换成新协议模板；
     * 用户改过的 request 原样保留，由 UI 提示核对参数名。
     */
    fun requestForTypeSwitch(
        currentRequest: JsonObject,
        oldType: String,
        newType: String,
    ): TypeSwitch {
        val nextTemplate = ApiRequestTemplate.templateFor(newType)
        val oldTemplate = ApiRequestTemplate.templateFor(oldType)
        return if (currentRequest.isEmpty() || currentRequest == oldTemplate) {
            TypeSwitch(nextTemplate, keptUserRequest = false)
        } else {
            TypeSwitch(currentRequest, keptUserRequest = true)
        }
    }

    /**
     * 基础字段修改：当前草稿没有错误时重新生成 JSON；
     * 仅当错误来自用户手动改坏的草稿（[protectInvalidDraft]）时才原样保留草稿。
     */
    fun afterBaseEdit(
        currentText: String,
        currentError: String?,
        next: ApiConfigDocument,
        hasApiKey: Boolean,
        protectInvalidDraft: Boolean = true,
    ): String =
        if (currentError != null && protectInvalidDraft) {
            currentText
        } else {
            formatForEditor(next, hasApiKey)
        }

    /**
     * API Key 修改：Key 不会写进 JSON，因此不改变 request 内容；
     * 但必须重新解析当前草稿——合法草稿清除陈旧 jsonError，非法草稿保留原文与真实错误。
     * [protectInvalidDraft] = false（表单自动草稿）时，解析失败只保留原文、不置错误：
     * 草稿可能因表单尚未填完（model/name 为空）而通不过 validate，这不是用户改坏的 JSON。
     */
    fun afterApiKeyEdit(
        currentText: String,
        hasApiKey: Boolean,
        protectInvalidDraft: Boolean = true,
    ): JsonEdit =
        when (val parsed = ApiConfigJson.parse(currentText)) {
            is ApiConfigJsonResult.Invalid ->
                if (protectInvalidDraft) {
                    JsonEdit(currentText, parsed.message)
                } else {
                    JsonEdit(currentText, error = null)
                }
            is ApiConfigJsonResult.Valid ->
                JsonEdit(
                    text = formatForEditor(parsed.document, hasApiKey),
                    error = null,
                    parsed = parsed.document,
                )
        }

    /** 手动编辑/粘贴 JSON 后同步文档。 */
    fun afterJsonEdit(text: String): JsonEdit =
        when (val parsed = ApiConfigJson.parse(text)) {
            is ApiConfigJsonResult.Invalid -> JsonEdit(text, parsed.message)
            is ApiConfigJsonResult.Valid -> JsonEdit(text, null, parsed.document)
        }

    /** “复制脱敏 JSON”：只接受能被安全解析的文档；非法草稿返回 null，调用方不得复制原文。 */
    fun safeJsonForClipboard(
        text: String,
        hasApiKey: Boolean,
    ): String? =
        when (val parsed = ApiConfigJson.parse(text)) {
            is ApiConfigJsonResult.Invalid -> null
            is ApiConfigJsonResult.Valid -> formatForEditor(parsed.document, hasApiKey)
        }

    /** 顶部保存提示：按名称、地址、API Key、模型的优先级返回首个缺失项。 */
    fun firstMissingRequiredHint(
        name: String,
        apiUrl: String,
        apiKey: String,
        model: String,
    ): String? =
        when {
            name.isBlank() -> "请填写配置名称后保存"
            apiUrl.isBlank() -> "请填写 API 地址后保存"
            apiKey.isBlank() -> "请填写 API 密钥后保存"
            model.isBlank() -> "请填写模型后保存"
            else -> null
        }

    /** 保存时合并表单基础字段，并统一清理 URL query 凭据/规范化协议类型。 */
    fun finalDocument(
        parsed: ApiConfigDocument,
        fields: ApiConfigEditorFields,
    ): ApiConfigDocument =
        parsed.copy(
            id = fields.id,
            name = fields.name,
            type = normalizeApiType(fields.type),
            baseUrl =
                ApiConfigJson.sanitizeBaseUrl(fields.apiUrl)
                    ?: throw IllegalArgumentException("API 地址包含无法安全清理的凭据或格式错误，请修正后再保存"),
            model = fields.model,
            streamingEnabled = fields.streamingEnabled,
            contextTokenLimit =
                fields.contextTokenLimit.coerceIn(
                    ApiConfigJson.MIN_CONTEXT_TOKEN_LIMIT,
                    ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT,
                ),
        )
}
