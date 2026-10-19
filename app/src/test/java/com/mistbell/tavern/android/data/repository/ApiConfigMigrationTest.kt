package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.RequestSamplingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * providers_json → api_configs 迁移的纯函数单测（解析 / id 保留 / 默认归属 / 采样参数映射）。
 */
class ApiConfigMigrationTest {
    // ---- parseProvidersJson ----

    @Test
    fun `正常 JSON 解析出提供商列表`() {
        val json =
            """[
              {"id":"p1","name":"OpenAI","endpoint":"https://api.openai.com/v1","apiKey":"sk-x","selectedModel":"gpt-4o"},
              {"id":"p2","name":"Proxy","endpoint":"https://proxy.example.com","apiKey":"","selectedModel":"claude"}
            ]"""
        val providers = ApiConfigMigration.parseProvidersJson(json)
        assertEquals(2, providers.size)
        assertEquals("p1", providers[0].id)
        assertEquals("https://api.openai.com/v1", providers[0].endpoint)
        assertEquals("gpt-4o", providers[0].selectedModel)
    }

    @Test
    fun `坏 JSON 返回空列表`() {
        assertTrue(ApiConfigMigration.parseProvidersJson("{not json").isEmpty())
        assertTrue(ApiConfigMigration.parseProvidersJson("").isEmpty())
        assertTrue(ApiConfigMigration.parseProvidersJson("  ").isEmpty())
    }

    // ---- toApiConfigEntities ----

    @Test
    fun `迁移保留原 ProviderConfig id 且 activeId 为默认`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[
                  {"id":"p1","name":"A","endpoint":"u1","apiKey":"k1","selectedModel":"m1"},
                  {"id":"p2","name":"B","endpoint":"u2","apiKey":"k2","selectedModel":"m2"}
                ]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "p2", now = 1000L)

        assertEquals(2, entities.size)
        // id 保留：存量 session.providerId 引用不断链
        assertEquals("p1", entities[0].id)
        assertEquals("p2", entities[1].id)
        // activeId 为默认，其余非默认
        assertEquals(false, entities[0].isDefault)
        assertEquals(true, entities[1].isDefault)
        // 字段映射
        assertEquals("u2", entities[1].apiUrl)
        assertEquals("k2", entities[1].apiKey)
        assertEquals("m2", entities[1].model)
        // sortOrder 按列表顺序
        assertEquals(0, entities[0].sortOrder)
        assertEquals(1, entities[1].sortOrder)
        // 缺省 type 回落 openai，context1m 缺省 false
        assertEquals("openai", entities[0].type)
        assertEquals(false, entities[0].context1m)
    }

    @Test
    fun `迁移保留旧提供商的 type 与 1M 上下文标记`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[
              {"id":"p1","name":"A","endpoint":"u","apiKey":"k","selectedModel":"m","type":"custom","context1M":true},
              {"id":"p2","name":"B","endpoint":"u","apiKey":"k","selectedModel":"m","type":""}
            ]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "", now = 0L)

        assertEquals("custom", entities[0].type)
        assertEquals(true, entities[0].context1m)
        // 空 type 回落 openai
        assertEquals("openai", entities[1].type)
    }

    @Test
    fun `无 activeId 时第一个提供商为默认`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"u","apiKey":"k","selectedModel":"m"}]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "", now = 0L)
        assertEquals(true, entities.single().isDefault)
    }

    // ---- activeProviderSampling ----

    @Test
    fun `高级参数映射到 request 采样字段`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"u","apiKey":"k","selectedModel":"m",
                     "temperature":0.7,"top_p":0.9,"top_k":40,"frequency_penalty":0.1,"max_tokens":2048}]""",
            )
        val sampling = ApiConfigMigration.activeProviderSampling(providers.single())

        assertEquals(0.7, sampling.temperature!!, 1e-9)
        assertEquals(0.9, sampling.topP!!, 1e-9)
        assertEquals(40, sampling.topK)
        assertEquals(0.1, sampling.frequencyPenalty!!, 1e-9)
        assertEquals(2048, sampling.maxTokens)

        // 写入 openai request JSON 后键位与 getLlmConfig 读取约定一致
        val request =
            RequestSamplingParams.write(
                kotlinx.serialization.json.buildJsonObject { },
                ApiType.OPENAI,
                sampling,
            )
        assertEquals("0.7", request.getValue("temperature").toString())
        assertEquals("0.9", request.getValue("top_p").toString())
        assertEquals("40", request.getValue("top_k").toString())
        assertEquals("0.1", request.getValue("frequency_penalty").toString())
        assertEquals("2048", request.getValue("max_tokens").toString())
    }

    @Test
    fun `高级参数全缺省映射为 null（未设置不发送）`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"u","apiKey":"k","selectedModel":"m"}]""",
            )
        val sampling = ApiConfigMigration.activeProviderSampling(providers.single())

        assertEquals(null, sampling.temperature)
        assertEquals(null, sampling.topP)
        assertEquals(null, sampling.topK)
        assertEquals(null, sampling.frequencyPenalty)
        assertEquals(null, sampling.maxTokens)
    }
}
