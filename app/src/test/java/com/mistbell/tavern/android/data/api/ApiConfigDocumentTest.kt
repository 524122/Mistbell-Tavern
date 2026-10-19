package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigDocumentTest {
    private fun document(request: kotlinx.serialization.json.JsonObject = buildJsonObject { }): ApiConfigDocument =
        ApiConfigDocument(
            id = "deepseek-main",
            name = "DeepSeek",
            type = "openai-compatible",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            apiKeyRef = "secure:deepseek-main",
            request = request,
        )

    @Test
    fun `完整配置序列化反序列化 round trip`() {
        val original = document(buildJsonObject { put("top_k", 20) })
        val result = ApiConfigJson.parse(ApiConfigJson.format(original))
        assertTrue(result is ApiConfigJsonResult.Valid)
        assertEquals(original, (result as ApiConfigJsonResult.Valid).document)
    }

    @Test
    fun `requestFirst 仅改变展示顺序并保持文档内容`() {
        val original = document(buildJsonObject { put("top_k", 20) })
        val defaultText = ApiConfigJson.format(original)
        val requestFirstText = ApiConfigJson.format(original, requestFirst = true)

        assertTrue(defaultText.indexOf("\"id\"") < defaultText.indexOf("\"request\""))
        assertTrue(requestFirstText.indexOf("\"request\"") < requestFirstText.indexOf("\"id\""))
        val parsed = ApiConfigJson.parse(requestFirstText) as ApiConfigJsonResult.Valid
        assertEquals(original, parsed.document)
    }

    @Test
    fun `未知 request 字段保留`() {
        val original = document(buildJsonObject { put("vendor_extension", "keep") })
        val parsed = ApiConfigJson.parse(ApiConfigJson.format(original)) as ApiConfigJsonResult.Valid
        assertEquals("keep", parsed.document.request["vendor_extension"]?.toString()?.trim('"'))
    }

    @Test
    fun `基础字段映射到同一个文档对象`() {
        val formatted =
            ApiConfigJson.format(
                ApiConfigJson.fromFields(
                    id = "x",
                    name = "Changed",
                    type = "openai",
                    baseUrl = "https://example.test/v1",
                    model = "model-2",
                    hasApiKey = true,
                    streamingEnabled = false,
                    contextTokenLimit = 8192,
                ),
            )
        val parsed = ApiConfigJson.parse(formatted) as ApiConfigJsonResult.Valid
        assertEquals("Changed", parsed.document.name)
        assertEquals(8192, parsed.document.contextTokenLimit)
        assertEquals("[已配置]", ApiConfigJson.redacted(parsed.document, true).apiKeyRef)
    }

    @Test
    fun `非法 JSON 和非 object 被拒绝`() {
        assertTrue(ApiConfigJson.parse("{bad") is ApiConfigJsonResult.Invalid)
        assertTrue(ApiConfigJson.parse("[]") is ApiConfigJsonResult.Invalid)
    }

    @Test
    fun `敏感字段不会进入配置文档`() {
        assertTrue(ApiConfigJson.parse("{\"schemaVersion\":1,\"id\":\"x\",\"name\":\"x\",\"type\":\"openai\",\"baseUrl\":\"u\",\"model\":\"m\",\"apiKey\":\"real\"}") is ApiConfigJsonResult.Invalid)
    }

    @Test
    fun `敏感字段大小写连字符与嵌套旁路都被拒绝`() {
        val variants =
            listOf(
                "api_key",
                "apiKey",
                "x-api-key",
                "access_token",
                "authToken",
                "authorization",
                "secret",
                "password",
                "client_secret",
            )
        variants.forEach { field ->
            val json =
                """{"schemaVersion":1,"id":"x","name":"x","type":"openai","baseUrl":"u","model":"m",""" +
                    """"request":{"$field":"real"}}"""
            assertTrue("$field 应被拒绝", ApiConfigJson.parse(json) is ApiConfigJsonResult.Invalid)
        }
        val nested =
            """{"schemaVersion":1,"id":"x","name":"x","type":"openai","baseUrl":"u","model":"m",
               "request":{"headers":{"Authorization":"Bearer real"}}}""".replace("\n", "")
        assertTrue(ApiConfigJson.parse(nested) is ApiConfigJsonResult.Invalid)
    }

    @Test
    fun `token 预算字段不是凭据`() {
        val request =
            buildJsonObject {
                put("max_tokens", 1024)
                put("maxOutputTokens", 2048)
                put("thinking", buildJsonObject { put("budget_tokens", 2048); put("thinkingBudgetTokens", 4096) })
            }
        val document = document(request)
        val result = ApiConfigJson.parse(ApiConfigJson.format(document))
        assertTrue(result is ApiConfigJsonResult.Valid)
        assertEquals(request, (result as ApiConfigJsonResult.Valid).document.request)
    }

    @Test
    fun `request 递归合并并保护 messages stream`() {
        val base = buildJsonObject { put("messages", "generated"); put("stream", false); put("options", buildJsonObject { put("a", 1) }) }
        val extension = buildJsonObject { put("messages", "user"); put("stream", true); put("options", buildJsonObject { put("b", 2) }); put("temperature", 0.7) }
        val merged = ApiConfigJson.mergeRequest(base, extension)
        assertEquals("generated", merged.json["messages"]?.toString()?.trim('"'))
        assertEquals("false", merged.json["stream"]?.toString())
        assertTrue(merged.json["options"].toString().contains("\"a\":1"))
        assertTrue(merged.json["options"].toString().contains("\"b\":2"))
        assertTrue(merged.ignoredFields.contains("messages"))
        assertTrue(merged.ignoredFields.contains("stream"))
    }

    @Test
    fun `request merge 拒绝凭据旁路但保留采样预算`() {
        val extension =
            buildJsonObject {
                put("apiKey", "real")
                put("x-api-key", "real")
                put("access_token", "real")
                put("authorization", "Bearer real")
                put("max_tokens", 1024)
                put("maxOutputTokens", 2048)
                put("thinkingBudgetTokens", 4096)
                put("headers", buildJsonObject { put("Authorization", "Bearer real") })
            }
        val merged = ApiConfigJson.mergeRequest(buildJsonObject { }, extension)
        assertTrue(merged.ignoredFields.contains("apiKey"))
        assertTrue(merged.ignoredFields.contains("x-api-key"))
        assertTrue(merged.ignoredFields.contains("access_token"))
        assertTrue(merged.ignoredFields.contains("authorization"))
        assertTrue(merged.ignoredFields.contains("headers.Authorization"))
        assertEquals("1024", merged.json["max_tokens"]?.toString())
        assertEquals("2048", merged.json["maxOutputTokens"]?.toString())
        assertEquals("4096", merged.json["thinkingBudgetTokens"]?.toString())
        assertTrue(!merged.json.containsKey("apiKey"))
        assertTrue(!merged.json.containsKey("x-api-key"))
        assertTrue(!merged.json.containsKey("access_token"))
    }

    @Test
    fun `JSON Schema 属性名和 required 列表不被凭据规则破坏`() {
        val request =
            buildJsonObject {
                put(
                    "response_format",
                    buildJsonObject {
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", "profile")
                                put(
                                    "schema",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("model", buildJsonObject { put("type", "string") })
                                                put("password", buildJsonObject { put("type", "string") })
                                                put("api_key", buildJsonObject { put("type", "string") })
                                            },
                                        )
                                        put("required", buildJsonArray { add("model"); add("password") })
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", "lookup")
                                        put(
                                            "parameters",
                                            buildJsonObject {
                                                put("type", "object")
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put("password", buildJsonObject { put("type", "string") })
                                                        put("model", buildJsonObject { put("type", "string") })
                                                    },
                                                )
                                                put("required", buildJsonArray { add("password") })
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        val merged = ApiConfigJson.mergeRequest(buildJsonObject { }, request)
        val mergedText = merged.json.toString()
        assertTrue(mergedText.contains("\"model\":{\"type\":\"string\"}"))
        assertTrue(mergedText.contains("\"password\":{\"type\":\"string\"}"))
        assertTrue(mergedText.contains("\"api_key\":{\"type\":\"string\"}"))
        assertTrue(mergedText.contains("\"required\":[\"model\",\"password\"]"))
        assertTrue(merged.ignoredFields.isEmpty())

        val imported = ApiConfigJson.parse(ApiConfigJson.format(document(request)))
        assertTrue(imported is ApiConfigJsonResult.Valid)
        assertEquals(request, (imported as ApiConfigJsonResult.Valid).document.request)
    }

    @Test
    fun `数组元素中的凭据也会被拒绝`() {
        val merged =
            ApiConfigJson.mergeRequest(
                buildJsonObject { },
                buildJsonObject {
                    put(
                        "headers",
                        buildJsonArray {
                            add(buildJsonObject { put("Authorization", "Bearer real") })
                        },
                    )
                },
            )
        assertTrue(merged.ignoredFields.contains("headers[0].Authorization"))
        assertTrue(!merged.json.toString().contains("Bearer real"))
    }

    @Test
    fun `数组字段直接替换`() {
        val merged =
            ApiConfigJson.mergeRequest(
                buildJsonObject { put("stop", buildJsonArray { add("a") }) },
                buildJsonObject { put("stop", buildJsonArray { add("b"); add("c") }) },
            )
        assertEquals("[\"b\",\"c\"]", merged.json["stop"].toString())
    }

    @Test
    fun `schema migration v0`() {
        val result = ApiConfigJson.parse("{\"id\":\"x\",\"name\":\"x\",\"type\":\"openai\",\"baseUrl\":\"u\",\"model\":\"m\"}")
        assertTrue(result is ApiConfigJsonResult.Valid)
        assertTrue((result as ApiConfigJsonResult.Valid).migrated)
    }

    @Test
    fun `URL 预览隐藏 query credential`() {
        assertEquals("https://example.test/v1?key=•••", ApiConfigJson.redactUrl("https://example.test/v1?key=real"))
        assertEquals("https://example.test/v1?key=•••", ApiConfigJson.redacted(document().copy(baseUrl = "https://example.test/v1?key=real"), true).baseUrl)
        assertEquals("https://example.test/v1", ApiConfigJson.stripUrlCredentials("https://example.test/v1?key=real"))
    }

    @Test
    fun `URL 凭据在中间时保留非凭据参数与分隔符`() {
        val url = "https://example.test/v1?foo=1&key=real&bar=2"
        assertEquals(
            "https://example.test/v1?foo=1&key=•••&bar=2",
            ApiConfigJson.redactUrl(url),
        )
        assertEquals(
            "https://example.test/v1?foo=1&bar=2",
            ApiConfigJson.stripUrlCredentials(url),
        )
        // 末尾/开头凭据同样不能留下破损的 ? 或 &
        assertEquals(
            "https://example.test/v1?foo=1",
            ApiConfigJson.stripUrlCredentials("https://example.test/v1?foo=1&token=secret"),
        )
        assertEquals(
            "https://example.test/v1?foo=1",
            ApiConfigJson.stripUrlCredentials("https://example.test/v1?key=secret&foo=1"),
        )
    }

    @Test
    fun `URL userinfo 凭据也会移除和脱敏`() {
        assertEquals(
            "https://example.test/v1",
            ApiConfigJson.stripUrlCredentials("https://alice:secret@example.test/v1"),
        )
        assertEquals(
            "https://example.test/v1?foo=1",
            ApiConfigJson.stripUrlCredentials("https://alice:secret@example.test/v1?foo=1&client_secret=s3cr3t"),
        )
        assertEquals(
            "https://•••@example.test/v1",
            ApiConfigJson.redactUrl("https://alice:secret@example.test/v1"),
        )
    }

    @Test
    fun `URL 畸形地址与相对地址的清理边界`() {
        // 相对地址只做 query 凭据清理，不猜测主机
        assertEquals(
            "/v1?foo=1",
            ApiConfigJson.stripUrlCredentials("/v1?key=secret&foo=1"),
        )
        assertEquals(
            "?foo=1",
            ApiConfigJson.stripUrlCredentials("?key=secret&foo=1"),
        )
        // 编码形式的 userinfo 也移除
        assertEquals(
            "https://example.test/v1?foo=1",
            ApiConfigJson.stripUrlCredentials("https://alice%3Asecret@example.test/v1?foo=1&client_secret=x"),
        )
        // 含空格等畸形绝对地址无法安全清理：明确拒绝，不返回猜测结果
        assertNull(ApiConfigJson.sanitizeBaseUrl("https://alice:secret@exa mple.test/v1?key=x"))
        assertEquals("", ApiConfigJson.stripUrlCredentials("https://alice:secret@exa mple.test/v1?key=x"))
        // opaque 形式同样拒绝持久化，但异常文本仍可逐参数脱敏
        assertNull(ApiConfigJson.sanitizeBaseUrl("localhost:8080/v1?key=x"))
        assertEquals(
            "failed localhost:8080/v1?client_secret=•••&foo=1",
            ApiConfigJson.redactUrl("failed localhost:8080/v1?client_secret=secret&foo=1"),
        )
        assertEquals(
            "request failed: /v1?client_secret=•••&foo=1 https://•••@example.test/v1?key=•••",
            ApiConfigJson.redactUrl(
                "request failed: /v1?client_secret=secret&foo=1 https://alice:secret@example.test/v1?key=secret",
            ),
        )
    }

    @Test
    fun `异常消息内嵌的含凭据 URL 也会脱敏`() {
        assertEquals(
            "request failed: https://example.test/v1?foo=1&key=•••",
            ApiConfigJson.redactUrl("request failed: https://example.test/v1?foo=1&key=secret"),
        )
    }

    @Test
    fun `导入 URL 的 query 凭据被移除`() {
        val json =
            """{"schemaVersion":1,"id":"x","name":"x","type":"openai","baseUrl":"https://example.test/v1?foo=1&key=secret","model":"m"}"""
        val parsed = ApiConfigJson.parse(json) as ApiConfigJsonResult.Valid
        assertEquals("https://example.test/v1?foo=1", parsed.document.baseUrl)
    }

    @Test
    fun `实体回填同步清理 apiUrl 并拒绝覆盖未知配置`() {
        val validRequest = buildJsonObject { put("vendor_extension", "keep") }
        val validDocument = document(validRequest).copy(baseUrl = "https://example.test/v1?foo=1")
        val validEntity =
            com.mistbell.tavern.android.data.local.entity.ApiConfigEntity(
                id = "x",
                name = "x",
                apiUrl = "https://example.test/v1?foo=1&key=old",
                apiKey = "k",
                model = "m",
                configJson = ApiConfigJson.format(validDocument),
            )
        val backfilled = ApiConfigJson.fromEntityForBackfill(validEntity)
        assertNotNull(backfilled)
        assertEquals("https://example.test/v1?foo=1", backfilled!!.baseUrl)
        assertEquals("keep", backfilled.request["vendor_extension"]?.toString()?.trim('"'))

        val future = validEntity.copy(configJson = validEntity.configJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        assertNull(ApiConfigJson.fromEntityForBackfill(future))
        val broken = validEntity.copy(configJson = "{bad")
        assertNull(ApiConfigJson.fromEntityForBackfill(broken))

        val legacy =
            validEntity.copy(
                apiUrl = "https://alice:secret@example.test/v1?foo=1&key=old",
                configJson = "",
            )
        assertEquals(
            "https://example.test/v1?foo=1",
            ApiConfigJson.fromEntityForBackfill(legacy)?.baseUrl,
        )
        val malformedLegacy =
            validEntity.copy(
                apiUrl = "https://alice:secret@exa mple.test/v1?key=old",
                configJson = "",
            )
        assertNull(ApiConfigJson.fromEntityForBackfill(malformedLegacy))
    }

    @Test
    fun `legacy blank configJson 回填不会自动物化模板`() {
        val entity =
            com.mistbell.tavern.android.data.local.entity.ApiConfigEntity(
                id = "legacy",
                name = "Legacy",
                apiUrl = "https://example.test/v1",
                apiKey = "k",
                model = "m",
                configJson = "",
            )
        assertTrue(ApiConfigJson.fromEntity(entity).request.isEmpty())
        val backfill = ApiConfigJson.fromEntityForBackfill(entity)
        assertNotNull(backfill)
        assertTrue(backfill!!.request.isEmpty())
    }

    @Test
    fun `新增配置与旧列回填都只保留清理后的 baseUrl`() {
        val fromFields =
            ApiConfigJson.fromFields(
                id = "x",
                name = "x",
                type = "openai",
                baseUrl = "https://example.test/v1?foo=1&key=secret",
                model = "m",
                hasApiKey = true,
                streamingEnabled = true,
                contextTokenLimit = 4096,
            )
        assertEquals("https://example.test/v1?foo=1", fromFields.baseUrl)

        val fromEntity =
            ApiConfigJson.fromEntity(
                com.mistbell.tavern.android.data.local.entity.ApiConfigEntity(
                    id = "x",
                    name = "x",
                    apiUrl = "https://example.test/v1?foo=1&key=secret",
                    apiKey = "k",
                    model = "m",
                ),
            )
        assertEquals("https://example.test/v1?foo=1", fromEntity.baseUrl)
    }

    @Test
    fun `合并结果递归展开应用与忽略字段的实际值`() {
        val extension =
            buildJsonObject {
                put("temperature", 0.7)
                put("reasoning_effort", "high")
                put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", 2048) })
                put("response_format", buildJsonObject { put("type", "json_object") })
                put("messages", "bad")
            }
        val merged = ApiConfigJson.mergeRequest(buildJsonObject { }, extension)
        val applied = merged.applied.map { it.toString() }
        assertTrue(applied.contains("temperature = 0.7"))
        assertTrue(applied.contains("reasoning_effort = \"high\""))
        // 嵌套对象递归到叶子，而不是停在 request 顶层 key
        assertTrue(applied.contains("thinking.type = \"enabled\""))
        assertTrue(applied.contains("thinking.budget_tokens = 2048"))
        assertTrue(applied.contains("response_format.type = \"json_object\""))
        assertTrue(!applied.any { it == "thinking" || it.startsWith("thinking =") })
        // 被保护字段同样带值
        assertTrue(merged.ignored.map { it.toString() }.contains("messages = \"bad\""))
    }

    @Test
    fun `flattenRequest 递归展开叶子且空对象原样显示`() {
        val request =
            buildJsonObject {
                put("top_k", 20)
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("empty_object", buildJsonObject { })
            }
        val flat = ApiConfigJson.flattenRequest(request).map { it.toString() }
        assertTrue(flat.contains("top_k = 20"))
        assertTrue(flat.contains("thinking.type = \"disabled\""))
        assertTrue(flat.contains("empty_object = {}"))
    }

    @Test
    fun `受保护字段按接口协议分别处理`() {
        val openai = ApiConfigJson.protectedFieldsForType("openai")
        val anthropic = ApiConfigJson.protectedFieldsForType("anthropic")
        val gemini = ApiConfigJson.protectedFieldsForType("gemini")
        // 通用：messages / stream / model / authorization / api_key
        listOf("messages", "stream", "model", "authorization", "api_key").forEach {
            assertTrue(it in openai && it in anthropic && it in gemini)
        }
        // Anthropic 额外保护 system（协议从消息提取为顶级字段）
        assertTrue("system" in anthropic && "system" !in openai)
        // Gemini 额外保护 contents / systemInstruction / key
        assertTrue("contents" in gemini && "systeminstruction" in gemini && "key" in gemini)
        assertTrue("contents" !in openai)
    }
}
