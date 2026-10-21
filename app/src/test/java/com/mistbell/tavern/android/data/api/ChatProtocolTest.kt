package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多协议适配单测：请求体编码、认证头/URL、响应解析、SSE 增量解析。
 *
 * ChatProtocol 各实现是 internal，测试同包可直接访问。
 */
class ChatProtocolTest {
    private val messages =
        listOf(
            ChatMessage(role = "system", content = "你是助手"),
            ChatMessage(role = "user", content = "你好"),
            ChatMessage(role = "assistant", content = "你好！有什么可以帮你？"),
            ChatMessage(role = "user", content = "讲个笑话"),
        )

    private fun anthropicConfig() =
        LlmConfig(
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            model = "claude-sonnet-4-20250514",
            type = ApiType.ANTHROPIC,
        )

    private fun geminiConfig() =
        LlmConfig(
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "gemini-key",
            model = "gemini-2.0-flash",
            type = ApiType.GEMINI,
        )

    private fun bodyOf(request: okhttp3.Request): String =
        request.body
            ?.let { okio.Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }
            .orEmpty()

    // ---- Anthropic 请求 ----

    @Test
    fun `anthropic 请求头与端点`() {
        val request = AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = false)

        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("sk-ant-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun `anthropic system 提取为顶级字段且消息仅含 user assistant`() {
        val body = bodyOf(AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = false))

        assertTrue(body.contains("\"system\":\"你是助手\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
        // system 角色不得出现在 messages 数组中
        val messagesSection = body.substringAfter("\"messages\":").substringBeforeLast(']')
        assertTrue(!messagesSection.contains("\"role\":\"system\""))
    }

    @Test
    fun `anthropic 流式请求带 stream 标记`() {
        val body = bodyOf(AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = true))
        assertTrue(body.contains("\"stream\":true"))
    }

    // ---- Anthropic 响应解析 ----

    @Test
    fun `anthropic 非流式响应解析正文与用量`() {
        val body =
            """{"content":[{"type":"text","text":"你好！"}],"usage":{"input_tokens":12,"output_tokens":5},
               "stop_reason":"end_turn"}""".replace("\n", "").replace(" ", "")
        val content = AnthropicChatProtocol.parseContent(body)

        assertEquals("你好！", content.text)
        assertEquals(12, content.usage?.promptTokens)
        assertEquals(5, content.usage?.completionTokens)
        assertEquals("end_turn", content.finishReason)
    }

    @Test
    fun `anthropic 流式 content_block_delta 产出正文增量`() {
        val data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"片段"}}"""
        val result = AnthropicChatProtocol.parseStreamData(data)

        assertEquals(StreamParseResult.Delta("片段"), result)
    }

    @Test
    fun `openai 非流式解析 reasoning_content 与正文分离`() {
        val body =
            """{"choices":[{"message":{"role":"assistant","content":"答案","reasoning_content":"先分析"}}]}"""
        val content = OpenAiChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals("先分析", content.thinking)
    }

    @Test
    fun `openai 流式解析 reasoning_content 增量`() {
        val data =
            """{"choices":[{"delta":{"role":"assistant","content":"","reasoning_content":"推理"}}]}"""
        assertEquals(StreamParseResult.Delta("", "推理"), OpenAiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `openai request 扩展字段合并且保护 messages stream`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request =
                    buildJsonObject {
                        put("temperature", 0.2)
                        put("stream", true)
                        put("messages", "bad")
                        put("vendor_flag", true)
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertTrue(body.contains("\"vendor_flag\":true"))
        // stream 是受保护字段：扩展 JSON 里的 stream=true 不得覆盖调用方的 stream=false
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(!body.contains("\"stream\":true"))
        assertTrue(!body.contains("\"messages\":\"bad\""))
    }

    @Test
    fun `anthropic thinking block 与正文分离`() {
        val body =
            """{"content":[{"type":"thinking","thinking":"先想"},{"type":"text","text":"答案"}]}"""
        val content = AnthropicChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals("先想", content.thinking)
    }

    @Test
    fun `anthropic 流式 message_start 与 message_delta 产出用量`() {
        val start =
            AnthropicChatProtocol.parseStreamData(
                """{"type":"message_start","usage":{"input_tokens":30,"output_tokens":1}}""",
            )
        val delta =
            AnthropicChatProtocol.parseStreamData(
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},
                   "usage":{"input_tokens":0,"output_tokens":12}}""".replace("\n", "").replace(" ", ""),
            )

        assertEquals(30, (start as StreamParseResult.UsageUpdate).usage.promptTokens)
        assertEquals(12, (delta as StreamParseResult.UsageUpdate).usage.completionTokens)
    }

    @Test
    fun `anthropic 流式忽略无关事件与坏 JSON`() {
        assertEquals(
            StreamParseResult.Ignore,
            AnthropicChatProtocol.parseStreamData("""{"type":"content_block_stop"}"""),
        )
        assertEquals(StreamParseResult.Ignore, AnthropicChatProtocol.parseStreamData("{bad"))
    }

    // ---- Gemini 请求 ----

    @Test
    fun `gemini 端点 key 在 query 流式加 alt sse`() {
        val nonStream = GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = false)
        val stream = GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = true)

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash" +
                ":generateContent?key=gemini-key",
            nonStream.url.toString(),
        )
        assertTrue(stream.url.toString().contains("&alt=sse"))
    }

    @Test
    fun `gemini assistant 映射为 model 且 system 走 systemInstruction`() {
        val body = bodyOf(GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = false))

        assertTrue(body.contains("\"role\":\"model\""))
        assertTrue(!body.contains("\"role\":\"assistant\""))
        assertTrue(body.contains("\"systemInstruction\":{\"parts\":[{\"text\":\"你是助手\"}]}"))
    }

    // ---- Gemini 响应解析 ----

    @Test
    fun `gemini 非流式响应解析正文与用量`() {
        val body =
            """{"candidates":[{"content":{"parts":[{"text":"答案"}],"role":"model"},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"totalTokenCount":14}}"""
                .replace("\n", "").replace(" ", "")
        val content = GeminiChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals(10, content.usage?.promptTokens)
        assertEquals(4, content.usage?.completionTokens)
        assertEquals("STOP", content.finishReason)
    }

    @Test
    fun `gemini 流式 chunk 产出正文增量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":"逐字"}],"role":"model"}}]}"""
        assertEquals(StreamParseResult.Delta("逐字"), GeminiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `gemini thought part 作为思维链增量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":"推理","thought":true}],"role":"model"}}]}"""
        assertEquals(StreamParseResult.Delta("", "推理"), GeminiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `gemini 流式末帧携带用量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":20,"totalTokenCount":28}}"""
                .replace("\n", "").replace(" ", "")
        val result = GeminiChatProtocol.parseStreamData(data)

        assertEquals(28, (result as StreamParseResult.UsageUpdate).usage.totalTokens)
    }

    @Test
    fun `anthropic request 扩展保护 system 且递归合并 thinking`() {
        val config =
            anthropicConfig().copy(
                request =
                    buildJsonObject {
                        put("system", "用户旁路注入")
                        put("temperature", 0.5)
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", "enabled")
                                put("budget_tokens", 1024)
                            },
                        )
                    },
            )
        val body = bodyOf(AnthropicChatProtocol.buildRequest(config, messages, stream = false))

        // system 是受保护字段：保持协议从消息提取的值，用户 JSON 不得覆盖
        assertTrue(body.contains("\"system\":\"你是助手\""))
        assertTrue(!body.contains("用户旁路注入"))
        assertTrue(body.contains("\"temperature\":0.5"))
        // Anthropic 供应商扩展字段递归保留
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024}"))
    }

    @Test
    fun `gemini request 扩展保护 contents 且递归合并 generationConfig`() {
        val config =
            geminiConfig().copy(
                request =
                    buildJsonObject {
                        put("contents", "bad")
                        put("systemInstruction", "bad")
                        put(
                            "generationConfig",
                            buildJsonObject {
                                put("responseMimeType", "application/json")
                                put("maxOutputTokens", 2048)
                            },
                        )
                    },
            )
        val body = bodyOf(GeminiChatProtocol.buildRequest(config, messages, stream = false))

        assertTrue(!body.contains("\"contents\":\"bad\""))
        assertTrue(!body.contains("\"systemInstruction\":\"bad\""))
        // generationConfig 递归合并：协议装配的 temperature 保留，用户扩展字段加入并可覆盖输出预算
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(body.contains("\"maxOutputTokens\":2048"))
        assertTrue(body.contains("\"temperature\":0.8"))
    }

    @Test
    fun `openai 默认温度与输出预算也会实际发送`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                temperature = 0.8,
                maxTokens = 1024,
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.8"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"stream\":false"))
    }

    @Test
    fun `openai 模板默认值按协议发送且 null 不发送`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request = ApiRequestTemplate.templateFor(ApiType.OPENAI),
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.8"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"top_p\":0.9"))
        assertTrue(body.contains("\"frequency_penalty\":0.0"))
        assertTrue(!body.contains("\"top_k\""))
        assertTrue(!body.contains("\"thinking\""))
        assertTrue(!body.contains("\"reasoning_effort\""))
        assertTrue(!body.contains("\"response_format\""))
    }

    @Test
    fun `anthropic 模板默认值按协议发送且 null 不发送`() {
        val config = anthropicConfig().copy(request = ApiRequestTemplate.templateFor(ApiType.ANTHROPIC))
        val body = bodyOf(AnthropicChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.8"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"top_p\":0.9"))
        assertTrue(!body.contains("\"top_k\""))
        assertTrue(!body.contains("\"thinking\""))
        assertTrue(!body.contains("\"metadata\""))
    }

    @Test
    fun `gemini 模板默认值按协议发送且 null 不发送`() {
        val config = geminiConfig().copy(request = ApiRequestTemplate.templateFor(ApiType.GEMINI))
        val body = bodyOf(GeminiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"maxOutputTokens\":1024"))
        assertTrue(body.contains("\"temperature\":0.8"))
        assertTrue(body.contains("\"topP\":0.9"))
        assertTrue(!body.contains("\"topK\""))
        assertTrue(!body.contains("\"responseMimeType\""))
        assertTrue(!body.contains("\"responseSchema\""))
        assertTrue(!body.contains("\"thinkingConfig\""))
        assertTrue(!body.contains("\"safetySettings\""))
    }

    @Test
    fun `协议合并前递归删除 null 但保留 false 零和空容器`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request =
                    buildJsonObject {
                        put("null_field", JsonNull)
                        put("flag", false)
                        put("zero", 0)
                        put("empty_object", buildJsonObject { })
                        put("empty_array", buildJsonArray { })
                        put(
                            "array",
                            buildJsonArray {
                                add(1)
                                add(JsonNull)
                                add(false)
                                add(0)
                            },
                        )
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(!body.contains("\"null_field\""))
        assertTrue(body.contains("\"flag\":false"))
        assertTrue(body.contains("\"zero\":0"))
        assertTrue(body.contains("\"empty_object\":{}"))
        assertTrue(body.contains("\"empty_array\":[]"))
        assertTrue(body.contains("\"array\":[1,false,0]"))
    }

    @Test
    fun `openai per call 输出预算不被持久 request 压掉`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                temperature = 0.2,
                maxTokens = 4096,
                request =
                    buildJsonObject {
                        put("temperature", 0.9)
                        put("max_tokens", 1024)
                    },
            ).withCallOverrides(temperature = 0.2, maxTokens = 4096)
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertTrue(body.contains("\"max_tokens\":4096"))
    }

    @Test
    fun `gemini per call 输出预算不被持久 request 压掉`() {
        val config =
            geminiConfig()
                .copy(
                    request =
                        buildJsonObject {
                            put("generationConfig", buildJsonObject { put("maxOutputTokens", 512) })
                        },
                ).withCallOverrides(maxTokens = 4096)
        val body = bodyOf(GeminiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"maxOutputTokens\":4096"))
        assertTrue(!body.contains("\"maxOutputTokens\":512"))
    }

    @Test
    fun `withCallOverrides 支持把输出预算覆盖回默认值`() {
        val config =
            geminiConfig()
                .copy(
                    maxTokens = 4096,
                    request = buildJsonObject { put("generationConfig", buildJsonObject { put("maxOutputTokens", 512) }) },
                ).withCallOverrides(maxTokens = 1024)
        val body = bodyOf(GeminiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"maxOutputTokens\":1024"))
        assertTrue(!body.contains("\"maxOutputTokens\":4096"))
    }

    @Test
    fun `openai 与 anthropic 端点路径追加在 query 前`() {
        val openAi =
            OpenAiChatProtocol.buildRequest(
                LlmConfig(baseUrl = "https://api.example.com/v1?tenant=a", apiKey = "k", model = "m"),
                messages,
                stream = false,
            )
        assertEquals("https://api.example.com/v1/chat/completions?tenant=a", openAi.url.toString())

        val anthropic =
            AnthropicChatProtocol.buildRequest(
                anthropicConfig().copy(baseUrl = "https://api.anthropic.com?tenant=a"),
                messages,
                stream = false,
            )
        assertEquals("https://api.anthropic.com/v1/messages?tenant=a", anthropic.url.toString())
    }

    @Test
    fun `gemini 端点保留非凭据 query 且 key 与 alt 只合并一次`() {
        val config =
            geminiConfig().copy(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta?foo=1&key=old&alt=sse",
            )
        val request = GeminiChatProtocol.buildRequest(config, messages, stream = true)
        assertEquals(listOf("1"), request.url.queryParameterValues("foo"))
        assertEquals(listOf("gemini-key"), request.url.queryParameterValues("key"))
        assertEquals(listOf("sse"), request.url.queryParameterValues("alt"))
        assertTrue(request.url.encodedPath.endsWith("/v1beta/models/gemini-2.0-flash:generateContent"))
    }

    @Test
    fun `request 扩展中的凭据字段不会进入请求体`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request =
                    buildJsonObject {
                        put("apiKey", "real")
                        put("x-api-key", "real")
                        put("access_token", "real")
                        put("headers", buildJsonObject { put("Authorization", "Bearer real") })
                        put("max_tokens", 1024)
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(!body.contains("real"))
        assertTrue(!body.contains("apiKey"))
        assertTrue(!body.contains("access_token"))
        assertTrue(body.contains("\"max_tokens\":1024"))
    }

    @Test
    fun `anthropic 不会收到 OpenAI 专有的 thinking disabled 字段`() {
        val config = anthropicConfig().copy(disableThinking = true)
        val body = bodyOf(AnthropicChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(!body.contains("\"thinking\""))
    }

    @Test
    fun `显式 requestOverrides 必须最后覆盖非默认兜底值`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                temperature = 0.6,
                maxTokens = 2048,
                request = buildJsonObject { put("max_tokens", 512) },
                requestOverrides =
                    buildJsonObject {
                        put("temperature", 0.2)
                        put("max_tokens", 1024)
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(!body.contains("\"max_tokens\":2048"))
    }

    @Test
    fun `显式 requestOverrides 可恢复默认温度与输出预算`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                temperature = 0.8,
                maxTokens = 4096,
                request =
                    buildJsonObject {
                        put("max_tokens", 512)
                        put("temperature", 0.2)
                    },
                requestOverrides =
                    buildJsonObject {
                        put("max_tokens", 1024)
                        put("temperature", 0.8)
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.8"))
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(!body.contains("\"max_tokens\":4096"))
    }

    @Test
    fun `真实配置温度 0_8 在采样预设下仍是 0_8`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                temperature = 0.8,
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"temperature\":0.8"))
    }

    @Test
    fun `显式 requestOverrides 优先级高于持久 request`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request =
                    buildJsonObject {
                        put("max_tokens", 512)
                        put("temperature", 0.9)
                    },
                requestOverrides =
                    buildJsonObject {
                        put("max_tokens", 4096)
                        put("temperature", 0.2)
                    },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"max_tokens\":4096"))
        assertTrue(body.contains("\"temperature\":0.2"))
    }

    @Test
    fun `连接测试的 5 tokens 覆盖持久 request`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "m",
                request = buildJsonObject { put("max_tokens", 1024) },
            ).withCallOverrides(maxTokens = 5)
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"max_tokens\":5"))
    }

    @Test
    fun `openai request 扩展不得覆盖 model`() {
        val config =
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret",
                model = "real-model",
                request = buildJsonObject { put("model", "user-model") },
            )
        val body = bodyOf(OpenAiChatProtocol.buildRequest(config, messages, stream = false))
        assertTrue(body.contains("\"model\":\"real-model\""))
        assertTrue(!body.contains("user-model"))
    }

    // ---- 分发 ----

    @Test
    fun `按类型分发到对应协议`() {
        assertTrue(chatProtocolFor(ApiType.OPENAI) is OpenAiChatProtocol)
        assertTrue(chatProtocolFor(ApiType.CUSTOM) is OpenAiChatProtocol)
        assertTrue(chatProtocolFor(ApiType.ANTHROPIC) is AnthropicChatProtocol)
        assertTrue(chatProtocolFor(ApiType.GEMINI) is GeminiChatProtocol)
    }

    @Test
    fun `类型大小写与空白差异不影响协议分发`() {
        assertTrue(chatProtocolFor(" Gemini ") is GeminiChatProtocol)
        assertTrue(chatProtocolFor("ANTHROPIC\n") is AnthropicChatProtocol)
        assertTrue(chatProtocolFor("OpenAI") is OpenAiChatProtocol)
        assertEquals(ApiType.GEMINI, normalizeApiType("  GeMiNi  "))
    }
}
