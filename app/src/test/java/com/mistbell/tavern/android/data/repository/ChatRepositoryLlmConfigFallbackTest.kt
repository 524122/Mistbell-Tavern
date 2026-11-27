package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.eval.memory.EvalContext
import com.mistbell.tavern.android.eval.memory.EvalRuntime
import com.mistbell.tavern.android.eval.memory.FakeTavernDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * loadLlmConfig 发送时可用性回落的契约（装机实测缺陷：会话绑定的 API 配置没填密钥时，
 * 发送直接报「LLM 未配置」，不会改用库里已有的可用配置）。
 *
 * 语义钉死（规则编号见 ChatRepository.loadLlmConfig 的注释）：
 * - 规则 3：空绑定/行已删 + 默认配置没密钥 → 回落到第一个填了密钥的配置；一个可用的都没有
 *   → 保持默认配置（发送时照旧报错）；
 * - 规则 2：绑定了没密钥的内置默认 → 视同没绑定回落；绑定没密钥的**非默认**（用户显式选的）
 *   → 尊重选择不回落；
 * - 规则 1：默认配置自己可用 → 用默认配置，不回落；
 * - 模型口径：会话 modelId 非空沿用会话的，为空用回落配置的模型；
 * - 规则 5：sessionId 为 null 的后台调用方走同样的回落。
 *
 * 基建沿用 ChatRepositoryInjectedMemoriesEventTest 的替身套路（EvalRuntime 换容器 +
 * FakeTavernDb 内存库，api_configs 行经 registerApiConfig 登记），只测配置解析，
 * 不发请求、不碰真网。
 */
class ChatRepositoryLlmConfigFallbackTest {
    private val fakeDb = FakeTavernDb()
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        runBlocking {
            EvalRuntime.installSession(fakeDb)
            installTestPendingExtractionStore()
            database = fakeDb.db()
            repository = ChatRepository(EvalContext())
        }
    }

    @After
    fun tearDown() {
        EvalRuntime.restore()
    }

    // ---- 规则 3：空绑定 / 行已删 ----

    @Test
    fun `空绑定_默认配置没密钥_回落到库里带密钥的配置`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("mine", apiUrl = "https://mine.invalid/v1", apiKey = "sk-mine"))
            seedSession(providerId = "")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("sk-mine", config.apiKey)
            assertEquals("https://mine.invalid/v1", config.baseUrl)
        }

    @Test
    fun `绑定的配置行已删除时视同没绑定_同样回落`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("mine", apiUrl = "https://mine.invalid/v1", apiKey = "sk-mine"))
            seedSession(providerId = "deleted-row")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("sk-mine", config.apiKey)
            assertEquals("https://mine.invalid/v1", config.baseUrl)
        }

    @Test
    fun `一个可用的都没有时保持默认配置_密钥为空由发送路径报错`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("a", apiUrl = "https://a.invalid/v1"))
            seedSession(providerId = "")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("", config.apiKey)
            assertEquals("https://builtin.invalid/v1", config.baseUrl)
        }

    // ---- 规则 2：绑定了没密钥的配置 ----

    @Test
    fun `绑定到没密钥的内置默认配置时视同没绑定_回落到带密钥的配置`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("mine", apiUrl = "https://mine.invalid/v1", apiKey = "sk-mine"))
            seedSession(providerId = "builtin")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("sk-mine", config.apiKey)
            assertEquals("https://mine.invalid/v1", config.baseUrl)
        }

    @Test
    fun `显式绑定到没密钥的非默认配置时尊重选择_不回落`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("chosen", apiUrl = "https://chosen.invalid/v1"))
            seedSession(providerId = "chosen")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("仍是绑定的那一个（密钥为空），没有悄悄换成别的配置", "", config.apiKey)
            assertEquals("https://chosen.invalid/v1", config.baseUrl)
        }

    // ---- 规则 1：默认配置自己可用 ----

    @Test
    fun `默认配置自己有密钥时用默认配置_不回落`() =
        runBlocking {
            fakeDb.registerApiConfig(
                config("main", apiUrl = "https://main.invalid/v1", apiKey = "sk-main", isDefault = true),
            )
            fakeDb.registerApiConfig(config("other", apiUrl = "https://other.invalid/v1", apiKey = "sk-other"))
            seedSession(providerId = "")

            val config = repository.effectiveLlmConfig(SESSION)

            assertEquals("sk-main", config.apiKey)
            assertEquals("https://main.invalid/v1", config.baseUrl)
        }

    // ---- 模型口径 ----

    @Test
    fun `回落时模型沿用会话modelId_为空时用回落配置的模型`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(
                config("mine", apiUrl = "https://mine.invalid/v1", apiKey = "sk-mine", model = "m-mine"),
            )

            seedSession(providerId = "", modelId = "session-model")
            assertEquals("session-model", repository.effectiveLlmConfig(SESSION).model)

            seedSession(providerId = "", modelId = "")
            assertEquals("m-mine", repository.effectiveLlmConfig(SESSION).model)
        }

    // ---- 规则 5：sessionId 为 null 的后台调用方 ----

    @Test
    fun `sessionId为null的后台调用方走同样的回落`() =
        runBlocking {
            fakeDb.registerApiConfig(config("builtin", apiUrl = "https://builtin.invalid/v1", isDefault = true))
            fakeDb.registerApiConfig(config("mine", apiUrl = "https://mine.invalid/v1", apiKey = "sk-mine"))

            val config = repository.effectiveLlmConfig(null)

            assertEquals("sk-mine", config.apiKey)
            assertEquals("m-mine", config.model)
        }

    // ---- 基建 ----

    private fun config(
        id: String,
        apiUrl: String,
        apiKey: String = "",
        isDefault: Boolean = false,
        model: String = "m-$id",
    ): ApiConfigEntity =
        ApiConfigEntity(
            id = id,
            name = id,
            apiUrl = apiUrl,
            apiKey = apiKey,
            model = model,
            isDefault = isDefault,
        )

    private suspend fun seedSession(
        providerId: String,
        modelId: String = "",
    ) {
        database.sessionDao().upsert(
            SessionEntity(
                id = SESSION,
                ownerId = OWNER,
                characterId = CHARACTER,
                title = "LLM 配置回落测试会话",
                createdAt = BASE_TIME.toString(),
                updatedAt = BASE_TIME.toString(),
                messageCount = 0,
                providerId = providerId,
                modelId = modelId,
                worldBookId = "",
                summaryJson = "",
            ),
        )
    }

    /** 与 ChatRepositoryInjectedMemoriesEventTest 同款：pendingExtractionStore 换成临时目录替身 */
    private fun installTestPendingExtractionStore() {
        tempDir = Files.createTempDirectory("llm-config-fallback").toFile()
        val container: AppContainer = TavernApplication.instance.container
        val field =
            AppContainer::class.java.declaredFields.firstOrNull { it.name == "pendingExtractionStore\$delegate" }
                ?: error("AppContainer.pendingExtractionStore 的 delegate 字段不存在")
        field.isAccessible = true
        field.set(
            container,
            lazyOf(PendingExtractionStore(File(tempDir, "pending-extraction.json"), loadGate = {})),
        )
    }

    private companion object {
        const val OWNER = "local-user"
        const val CHARACTER = "c1"
        const val SESSION = "s1"
        val BASE_TIME: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
    }
}
