package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提供商解析纯函数的单测：
 * - [resolveSessionProviderId]：新建会话的绑定解析（ChatSetupViewModel 建会话时用）；
 * - [firstUsableProviderId]：发送时的回落解析（ChatRepository.loadLlmConfig 规则 3 用）。
 *
 * 装机实测复现的缺陷：用户没选提供商时，旧实现直接取配置表的第一行——那一行是没填密钥的内置
 * 「OpenAI API」。新用户在设置里连好 AI 之后开聊，会话却绑在没密钥的配置上，发送时报
 * 「LLM 未配置」。绑定只在创建时解析一次还不够（首启时配置表尚未加载完 / 早期版本一律绑第一行），
 * 发送时按同一「可用」口径回落（见 ChatRepository.loadLlmConfig）。
 */
class ChatSetupProviderResolutionTest {
    private fun config(
        id: String,
        apiKey: String = "",
        isDefault: Boolean = false,
    ): ApiConfigEntity =
        ApiConfigEntity(
            id = id,
            name = id,
            apiUrl = "https://example.invalid/v1",
            apiKey = apiKey,
            model = "m",
            isDefault = isDefault,
        )

    /** 内置的默认配置（没填密钥）排第一行，用户另建了一个填了密钥的配置 */
    private fun freshInstallConfigs(): List<ApiConfigEntity> {
        val keylessDefault = config("builtin", isDefault = true)
        val mine = config("mine", apiKey = "sk-test")
        return listOf(keylessDefault, mine)
    }

    @Test
    fun `用户选了提供商就用所选`() {
        assertEquals("mine", resolveSessionProviderId(freshInstallConfigs(), "mine"))
        assertEquals("builtin", resolveSessionProviderId(freshInstallConfigs(), "builtin"))
    }

    @Test
    fun `没选时默认配置可用就绑定默认配置`() {
        val configs = listOf(config("mine", apiKey = "sk-test"), config("main", apiKey = "sk-main", isDefault = true))
        assertEquals("main", resolveSessionProviderId(configs, null))
    }

    @Test
    fun `没选且默认配置没有密钥时绑定第一个填了密钥的配置_而不是第一行`() {
        assertEquals("mine", resolveSessionProviderId(freshInstallConfigs(), null))
        assertEquals("mine", resolveSessionProviderId(freshInstallConfigs(), ""))
        assertEquals("mine", resolveSessionProviderId(freshInstallConfigs(), "  "))
    }

    @Test
    fun `默认配置可用时不被排在前面且有密钥的配置抢先`() {
        val configs = listOf(config("first", apiKey = "sk-first"), config("main", apiKey = "sk-main", isDefault = true))
        assertEquals("main", resolveSessionProviderId(configs, null))
    }

    @Test
    fun `全都没有密钥时保持旧取法_第一行`() {
        val configs = listOf(config("a"), config("b"))
        assertEquals("a", resolveSessionProviderId(configs, null))
    }

    @Test
    fun `配置表为空时返回空串_由发送路径回落到全局默认配置`() {
        assertEquals("", resolveSessionProviderId(emptyList(), null))
    }

    // ---- firstUsableProviderId：发送时的回落目标 ----

    @Test
    fun `有可用的配置时回落到第一个填了密钥的配置`() {
        val configs =
            listOf(
                config("builtin", isDefault = true),
                config("a", apiKey = "sk-a"),
                config("b", apiKey = "sk-b"),
            )
        assertEquals("a", firstUsableProviderId(configs))
    }

    @Test
    fun `全都没有密钥时返回null_发送路径保持默认配置`() {
        assertNull(firstUsableProviderId(listOf(config("a"), config("main", isDefault = true))))
    }

    @Test
    fun `配置表为空时返回null`() {
        assertNull(firstUsableProviderId(emptyList()))
    }
}
