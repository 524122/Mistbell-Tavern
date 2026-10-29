package com.mistbell.tavern.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigFields
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.normalizeApiType
import java.util.UUID

internal data class ApiConfigDraftInfo(
    val document: ApiConfigDocument,
    val notice: String?,
)

internal data class RequestPreviewUiState(
    val isJsonValid: Boolean,
    val expandedGroups: Set<String>,
    val connectionExpanded: Boolean,
    val generatedExpanded: Boolean,
)

internal fun initialApiConfigDraft(apiConfig: ApiConfig?): Pair<ApiConfigDocument, Boolean> {
    val base =
        (apiConfig?.configJson?.let { ApiConfigJson.parse(it) } as? ApiConfigJsonResult.Valid)
            ?.document
            ?.copy(
                id = apiConfig.id,
                name = apiConfig.name,
                type = normalizeApiType(apiConfig.type),
                baseUrl = ApiConfigJson.stripUrlCredentials(apiConfig.apiUrl),
                model = apiConfig.model,
                streamingEnabled = apiConfig.streamingEnabled,
                contextTokenLimit = apiConfig.contextTokenLimit,
            )
            ?: ApiConfigJson.fromFields(
                fields =
                    ApiConfigFields(
                        id = apiConfig?.id ?: UUID.randomUUID().toString(),
                        name = apiConfig?.name.orEmpty(),
                        type = normalizeApiType(apiConfig?.type ?: ApiType.OPENAI),
                        baseUrl = ApiConfigJson.stripUrlCredentials(apiConfig?.apiUrl.orEmpty()),
                        model = apiConfig?.model.orEmpty(),
                        hasApiKey = apiConfig?.apiKey?.isNotBlank() == true,
                        streamingEnabled = apiConfig?.streamingEnabled ?: true,
                        contextTokenLimit =
                            apiConfig?.contextTokenLimit ?: ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
                    ),
            )
    val materialized = base.copy(request = ApiRequestTemplate.materialize(base.request, base.type))
    return materialized to (materialized.request != base.request)
}

internal class ApiConfigEditorState(
    initialApiConfig: ApiConfig?,
    initialDraft: Pair<ApiConfigDocument, Boolean>,
) {
    var apiConfig = initialApiConfig
    private val initialDocument = initialDraft.first
    var draftInfo = ApiConfigDraftInfo(initialDocument, notice = null)

    var document by mutableStateOf(initialDocument)
    var jsonValue by mutableStateOf(
        TextFieldValue(
            ApiConfigEditorLogic.formatForEditor(
                initialDocument,
                initialApiConfig?.apiKey?.isNotBlank() == true,
            ),
        ),
    )
    var jsonError by mutableStateOf<String?>(null)
    var manualJsonEdited by mutableStateOf(false)
    var apiUrlError by mutableStateOf<String?>(null)
    var protocolNotice by mutableStateOf<String?>(null)
    var clipboardMessage by mutableStateOf<String?>(null)
    var showResetConfirm by mutableStateOf(false)
    var name by mutableStateOf(initialDocument.name)
    var type by mutableStateOf(initialDocument.type)
    var apiUrl by mutableStateOf(initialDocument.baseUrl)
    var apiKey by mutableStateOf(initialApiConfig?.apiKey.orEmpty())
    var model by mutableStateOf(initialDocument.model)
    var contextTokenLimit by mutableStateOf(initialDocument.contextTokenLimit)
    var context1M by mutableStateOf(
        initialDocument.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT,
    )
    var streamingEnabled by mutableStateOf(initialDocument.streamingEnabled)
    var setAsDefault by mutableStateOf(initialApiConfig?.isDefault ?: false)
    var selectedTab by mutableStateOf(0)
    var responseBudgetExpanded by mutableStateOf(false)
    var previewConnectionExpanded by mutableStateOf(false)
    var previewGeneratedExpanded by mutableStateOf(false)
    var previewExpandedGroups by mutableStateOf(
        setOf(defaultPreviewGroupKey(initialDocument.type)),
    )

    private fun setJsonText(text: String) {
        if (jsonValue.text != text) {
            jsonValue = TextFieldValue(text, selection = TextRange(text.length))
        }
    }

    fun editBase(next: ApiConfigDocument) {
        document = next
        if (!manualJsonEdited) jsonError = null
        setJsonText(
            ApiConfigEditorLogic.afterBaseEdit(
                jsonValue.text,
                jsonError,
                next,
                apiKey.isNotBlank(),
                protectInvalidDraft = manualJsonEdited,
            ),
        )
    }

    private fun resetJson(next: ApiConfigDocument) {
        document = next
        setJsonText(ApiConfigEditorLogic.formatForEditor(next, apiKey.isNotBlank()))
        jsonError = null
        manualJsonEdited = false
    }

    fun updateFromJson(text: String) {
        val edit = ApiConfigEditorLogic.afterJsonEdit(text)
        setJsonText(edit.text)
        jsonError = edit.error
        val parsed = edit.parsed ?: return
        val imported =
            parsed.copy(
                id = apiConfig?.id ?: parsed.id,
                baseUrl =
                    if (parsed.baseUrl == ApiConfigJson.redactUrl(document.baseUrl)) {
                        document.baseUrl
                    } else {
                        parsed.baseUrl
                    },
            )
        document = imported
        name = imported.name
        type = imported.type
        apiUrl = imported.baseUrl
        model = imported.model
        contextTokenLimit = imported.contextTokenLimit
        context1M = imported.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT
        streamingEnabled = imported.streamingEnabled
    }

    fun performReset() {
        resetJson(
            ApiConfigJson.fromFields(
                fields =
                    ApiConfigFields(
                        id = apiConfig?.id ?: document.id,
                        name = name,
                        type = type,
                        baseUrl = apiUrl,
                        model = model,
                        hasApiKey = apiKey.isNotBlank(),
                        streamingEnabled = streamingEnabled,
                        contextTokenLimit = contextTokenLimit,
                        request = ApiRequestTemplate.templateFor(type),
                    ),
            ),
        )
        protocolNotice = null
        clipboardMessage = "已重置为当前协议模板"
        showResetConfirm = false
    }

    fun requestReset() {
        if (ApiConfigEditorLogic.requiresResetConfirmation(jsonValue.text, type)) {
            showResetConfirm = true
        } else {
            performReset()
        }
    }

    fun changeApiKey(newKey: String) {
        apiKey = newKey
        val edit =
            ApiConfigEditorLogic.afterApiKeyEdit(
                jsonValue.text,
                newKey.isNotBlank(),
                protectInvalidDraft = manualJsonEdited,
            )
        setJsonText(edit.text)
        jsonError = edit.error
        edit.parsed?.let { parsed ->
            document =
                parsed.copy(
                    id = apiConfig?.id ?: parsed.id,
                    baseUrl =
                        if (parsed.baseUrl == ApiConfigJson.redactUrl(document.baseUrl)) {
                            document.baseUrl
                        } else {
                            parsed.baseUrl
                        },
                )
        }
    }

    fun changeType(newType: String) {
        val normalized = normalizeApiType(newType)
        val switch = ApiConfigEditorLogic.requestForTypeSwitch(document.request, type, normalized)
        type = normalized
        previewExpandedGroups = setOf(defaultPreviewGroupKey(normalized))
        apiUrl = resolvePresetUrl(normalized, apiUrl)
        apiUrlError = null
        protocolNotice =
            if (switch.keptUserRequest) {
                "已切换协议；request 保留了你修改的字段，请核对参数名是否适用于新协议。"
            } else {
                null
            }
        editBase(document.copy(type = normalized, baseUrl = apiUrl, request = switch.request))
    }

    fun editorFields(parsed: ApiConfigDocument): ApiConfigEditorFields =
        ApiConfigEditorFields(
            id = apiConfig?.id ?: parsed.id,
            name = name,
            type = type,
            apiUrl = apiUrl,
            model = model,
            streamingEnabled = streamingEnabled,
            contextTokenLimit = contextTokenLimit,
        )
}
