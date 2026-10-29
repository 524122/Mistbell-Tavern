package com.mistbell.tavern.android.ui.settings

import com.mistbell.tavern.android.data.api.ApiConfigFields
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.ApiType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigEditorLogicTest {
    private fun document(name: String = "Config") =
        ApiConfigJson.fromFields(
            fields =
                ApiConfigFields(
                    id = "id-1",
                    name = name,
                    type = "openai",
                    baseUrl = "https://api.example.com/v1",
                    model = "model-1",
                    hasApiKey = true,
                    streamingEnabled = true,
                    contextTokenLimit = 4096,
                ),
        )

    @Test
    fun `非法 JSON 草稿时修改基础字段不覆盖草稿`() {
        val draft = """{"name": "draft", bad"""
        val next = document(name = "Changed")
        val text =
            ApiConfigEditorLogic.afterBaseEdit(
                currentText = draft,
                currentError = "JSON 格式错误",
                next = next,
                hasApiKey = true,
            )
        assertEquals(draft, text)
    }

    @Test
    fun `合法草稿时基础字段修改会同步 JSON`() {
        val text =
            ApiConfigEditorLogic.afterBaseEdit(
                currentText = ApiConfigJson.format(document("Old")),
                currentError = null,
                next = document("New"),
                hasApiKey = true,
            )
        assertTrue(text.contains("\"name\": \"New\""))
        assertFalse(text.contains("\"name\": \"Old\""))
    }

    @Test
    fun `修改 API Key 会清除合法草稿上的陈旧错误`() {
        val edit = ApiConfigEditorLogic.afterApiKeyEdit(ApiConfigJson.format(document()), hasApiKey = true)
        assertNull(edit.error)
        assertNotNull(edit.parsed)
        assertTrue(edit.text.contains("[已配置]"))
    }

    @Test
    fun `修改 API Key 不覆盖非法草稿`() {
        val draft = "{bad"
        val edit = ApiConfigEditorLogic.afterApiKeyEdit(draft, hasApiKey = false)
        assertEquals(draft, edit.text)
        assertNotNull(edit.error)
        assertNull(edit.parsed)
    }

    @Test
    fun `表单自动草稿未填完模型时密钥输入不冻结后续表单同步`() {
        // 新配置自然填写顺序（名称 → 密钥 → 模型）：密钥输入时草稿 model 为空，validate 不通过
        val incompleteDraft = ApiConfigJson.format(document(name = "A").copy(model = ""))
        assertTrue(
            com.mistbell.tavern.android.data.api.ApiConfigJson.parse(incompleteDraft) is
                com.mistbell.tavern.android.data.api.ApiConfigJsonResult.Invalid,
        )

        // 非手动编辑：不得把“草稿暂时不完整”当作 sticky JSON 错误
        val edit =
            ApiConfigEditorLogic.afterApiKeyEdit(
                incompleteDraft,
                hasApiKey = true,
                protectInvalidDraft = false,
            )
        assertNull(edit.error)
        assertNull(edit.parsed)

        // 随后填写模型，afterBaseEdit 必须重新生成可保存的 JSON
        val text =
            ApiConfigEditorLogic.afterBaseEdit(
                currentText = incompleteDraft,
                currentError = edit.error,
                next = document(name = "A"),
                hasApiKey = true,
                protectInvalidDraft = false,
            )
        assertTrue(text.contains("\"model\": \"model-1\""))
        assertTrue(
            com.mistbell.tavern.android.data.api.ApiConfigJson.parse(text) is
                com.mistbell.tavern.android.data.api.ApiConfigJsonResult.Valid,
        )
    }

    @Test
    fun `用户手改坏的 JSON 在保护开启时仍不被覆盖且保留错误`() {
        val draft = """{"name": "draft", bad"""
        val text =
            ApiConfigEditorLogic.afterBaseEdit(
                currentText = draft,
                currentError = "JSON 格式错误",
                next = document(),
                hasApiKey = true,
                protectInvalidDraft = true,
            )
        assertEquals(draft, text)

        val edit =
            ApiConfigEditorLogic.afterApiKeyEdit(
                "{bad",
                hasApiKey = false,
                protectInvalidDraft = true,
            )
        assertEquals("{bad", edit.text)
        assertNotNull(edit.error)
    }

    @Test
    fun `复制脱敏 JSON 拒绝非法草稿`() {
        assertNull(ApiConfigEditorLogic.safeJsonForClipboard("{bad", hasApiKey = true))
        assertNull(
            ApiConfigEditorLogic.safeJsonForClipboard(
                """{"schemaVersion":1,"id":"x","name":"x","type":"openai",""" +
                    """"baseUrl":"u","model":"m","apiKey":"secret"}""",
                hasApiKey = true,
            ),
        )
    }

    @Test
    fun `复制脱敏 JSON 从安全文档生成并移除 URL 凭据`() {
        val unsafeDocument =
            document().copy(baseUrl = "https://api.example.com/v1?foo=1&key=secret")
        val safe =
            ApiConfigEditorLogic.safeJsonForClipboard(
                ApiConfigJson.format(unsafeDocument),
                hasApiKey = true,
            )
        assertNotNull(safe)
        assertFalse(safe!!.contains("secret"))
        assertFalse(safe.contains("key="))
        assertTrue(safe.contains("foo=1"))
        assertTrue(safe.contains("[已配置]"))
    }

    @Test
    fun `空 request 或未修改旧模板切换协议使用新模板`() {
        val emptySwitch = ApiConfigEditorLogic.requestForTypeSwitch(buildJsonObject { }, ApiType.OPENAI, ApiType.GEMINI)
        assertEquals(ApiRequestTemplate.templateFor(ApiType.GEMINI), emptySwitch.request)
        assertFalse(emptySwitch.keptUserRequest)

        val templateSwitch =
            ApiConfigEditorLogic.requestForTypeSwitch(
                ApiRequestTemplate.templateFor(ApiType.OPENAI),
                ApiType.OPENAI,
                ApiType.ANTHROPIC,
            )
        assertEquals(ApiRequestTemplate.templateFor(ApiType.ANTHROPIC), templateSwitch.request)
        assertFalse(templateSwitch.keptUserRequest)
    }

    @Test
    fun `用户修改过的 request 切换协议时保留并提示核对`() {
        val modified =
            ApiRequestTemplate.materialize(
                buildJsonObject {
                    put("temperature", 0.2)
                    put("vendor_extension", "keep")
                },
                ApiType.OPENAI,
            )
        val switch = ApiConfigEditorLogic.requestForTypeSwitch(modified, ApiType.OPENAI, ApiType.GEMINI)
        assertEquals(modified, switch.request)
        assertTrue(switch.keptUserRequest)
    }

    @Test
    fun `首个缺失必填项提示按名称地址密钥模型优先级`() {
        assertEquals(
            "请填写配置名称后保存",
            ApiConfigEditorLogic.firstMissingRequiredHint("", "https://x", "k", "m"),
        )
        assertEquals(
            "请填写 API 地址后保存",
            ApiConfigEditorLogic.firstMissingRequiredHint("n", "", "k", "m"),
        )
        assertEquals(
            "请填写 API 密钥后保存",
            ApiConfigEditorLogic.firstMissingRequiredHint("n", "https://x", "", "m"),
        )
        assertEquals(
            "请填写模型后保存",
            ApiConfigEditorLogic.firstMissingRequiredHint("n", "https://x", "k", ""),
        )
        assertNull(ApiConfigEditorLogic.firstMissingRequiredHint("n", "https://x", "k", "m"))
    }

    @Test
    fun `未修改协议模板重置不需要确认`() {
        val json =
            ApiConfigJson.format(
                document().copy(request = ApiRequestTemplate.templateFor(ApiType.OPENAI)),
            )
        assertFalse(ApiConfigEditorLogic.requiresResetConfirmation(json, ApiType.OPENAI))
    }

    @Test
    fun `修改已知参数或含自定义字段重置需要确认`() {
        val modified =
            ApiRequestTemplate.materialize(
                buildJsonObject { put("temperature", 0.2) },
                ApiType.OPENAI,
            )
        assertTrue(
            ApiConfigEditorLogic.requiresResetConfirmation(
                ApiConfigJson.format(document().copy(request = modified)),
                ApiType.OPENAI,
            ),
        )

        val custom = ApiRequestTemplate.materialize(buildJsonObject { put("vendor_extension", "keep") }, ApiType.OPENAI)
        assertTrue(
            ApiConfigEditorLogic.requiresResetConfirmation(
                ApiConfigJson.format(document().copy(request = custom)),
                ApiType.OPENAI,
            ),
        )
    }

    @Test
    fun `非法 JSON 草稿重置需要确认`() {
        assertTrue(ApiConfigEditorLogic.requiresResetConfirmation("{bad", ApiType.OPENAI))
    }

    @Test
    fun `编辑器 JSON request 位于首字段且可解析`() {
        val document =
            document().copy(
                request =
                    ApiRequestTemplate.materialize(
                        buildJsonObject { put("vendor_extension", "keep") },
                        ApiType.OPENAI,
                    ),
            )
        val formatted = ApiConfigEditorLogic.formatForEditor(document, hasApiKey = true)

        assertTrue(formatted.indexOf("\"request\"") < formatted.indexOf("\"id\""))
        val parsed = ApiConfigJson.parse(formatted)
        assertTrue(parsed is com.mistbell.tavern.android.data.api.ApiConfigJsonResult.Valid)
        assertEquals(
            document.request,
            (parsed as com.mistbell.tavern.android.data.api.ApiConfigJsonResult.Valid).document.request,
        )
    }

    @Test
    fun `保存文档清理 URL 凭据并规范化类型`() {
        val finalDocument =
            ApiConfigEditorLogic.finalDocument(
                parsed = document(),
                fields =
                    ApiConfigEditorFields(
                        id = "id-1",
                        name = "Config",
                        type = " Gemini ",
                        apiUrl = "https://generativelanguage.googleapis.com?foo=1&key=secret",
                        model = "gemini-2.0-flash",
                        streamingEnabled = false,
                        contextTokenLimit = 8192,
                    ),
            )
        assertEquals(ApiType.GEMINI, finalDocument.type)
        assertEquals("https://generativelanguage.googleapis.com?foo=1", finalDocument.baseUrl)
    }
}
