package com.mistbell.tavern.android.data.repository

import androidx.room.InvalidationTracker
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.ApiConfigDao
import com.mistbell.tavern.android.data.local.dao.CharacterDao
import com.mistbell.tavern.android.data.local.dao.CustomPromptDao
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.GlobalRegexDao
import com.mistbell.tavern.android.data.local.dao.MemoryDao
import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.local.dao.QuickReplyDao
import com.mistbell.tavern.android.data.local.dao.RulePackDao
import com.mistbell.tavern.android.data.local.dao.SessionDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.dao.ThemePackDao
import com.mistbell.tavern.android.data.local.dao.VectorMemoryDao
import com.mistbell.tavern.android.data.local.dao.VectorSyncOutboxDao
import com.mistbell.tavern.android.data.local.dao.WorldBookDao
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import com.mistbell.tavern.android.data.local.entity.RulePackEntity
import com.mistbell.tavern.android.data.model.UiModeEcosystemSignals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 生态信号查询（P3 第 2 项，docs/UI_MODE.md §5）JVM 单测。
 * 沿用仓库既有写法（SessionMemoryResetTest / FakeTavernDb）：匿名 AppDatabase 子类
 * + java.lang.reflect.Proxy 按方法名分发；只注册本查询触及的方法，没注册的直接抛——
 * 生产查询路径变了会立刻失败而不是静默返回默认值。
 *
 * 判定口径即 UiModeEcosystemProbe KDoc 的四条；特别覆盖「内置 tavernhelper-compat 不算」。
 */
class UiModeEcosystemProbeTest {
    /** 内存表：被代理的 DAO 方法按真实语义读写这些集合 */
    private class FakeTables {
        val settings = linkedMapOf<String, String>()
        val globalRegex = mutableListOf<GlobalRegexRuleEntity>()
        val characters = mutableListOf<CharacterEntity>()
        val extensionPacks = mutableListOf<ExtensionPackEntity>()
        val rulePacks = mutableListOf<RulePackEntity>()
    }

    private lateinit var tables: FakeTables

    @Before
    fun setUp() {
        tables = FakeTables()
    }

    /** 未注册的方法直接失败（查询路径变了要显式更新替身） */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> daoProxy(
        iface: Class<T>,
        handlers: Map<String, (Array<Any?>) -> Any?>,
    ): T =
        Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
        ) { _, method, args ->
            val handler =
                handlers[method.name]
                    ?: throw UnsupportedOperationException("${iface.simpleName}.${method.name} 未在生态信号替身注册")
            handler(args ?: emptyArray())
        } as T

    private fun <T> flowOf(provider: () -> List<T>): Flow<List<T>> = flow { emit(provider()) }

    private fun fakeDb(): AppDatabase =
        object : AppDatabase() {
            override fun settingsDao(): SettingsDao =
                daoProxy(
                    SettingsDao::class.java,
                    mapOf(
                        "getValue" to { args -> tables.settings[args[0] as String] },
                    ),
                )

            override fun globalRegexDao(): GlobalRegexDao =
                daoProxy(
                    GlobalRegexDao::class.java,
                    mapOf(
                        "getAll" to { _ -> tables.globalRegex.toList() },
                    ),
                )

            override fun characterDao(): CharacterDao =
                daoProxy(
                    CharacterDao::class.java,
                    mapOf(
                        "getAllOnce" to { _ -> tables.characters.toList() },
                    ),
                )

            override fun extensionPackDao(): ExtensionPackDao =
                daoProxy(
                    ExtensionPackDao::class.java,
                    mapOf(
                        "getEnabled" to { _ -> tables.extensionPacks.filter { it.enabled } },
                    ),
                )

            override fun rulePackDao(): RulePackDao =
                daoProxy(
                    RulePackDao::class.java,
                    mapOf(
                        "observeEnabled" to { _ -> flowOf { tables.rulePacks.filter { it.enabled } } },
                    ),
                )

            // 本查询不触及的 DAO：显式失败
            override fun sessionDao(): SessionDao = throw UnsupportedOperationException()

            override fun messageDao(): MessageDao = throw UnsupportedOperationException()

            override fun memoryDao(): MemoryDao = throw UnsupportedOperationException()

            override fun structuredMemoryDao(): StructuredMemoryDao = throw UnsupportedOperationException()

            override fun vectorMemoryDao(): VectorMemoryDao = throw UnsupportedOperationException()

            override fun vectorSyncOutboxDao(): VectorSyncOutboxDao = throw UnsupportedOperationException()

            override fun themePackDao(): ThemePackDao = throw UnsupportedOperationException()

            override fun apiConfigDao(): ApiConfigDao = throw UnsupportedOperationException()

            override fun customPromptDao(): CustomPromptDao = throw UnsupportedOperationException()

            override fun quickReplyDao(): QuickReplyDao = throw UnsupportedOperationException()

            override fun worldBookDao(): WorldBookDao = throw UnsupportedOperationException()

            override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()

            override fun clearAllTables() = throw UnsupportedOperationException()
        }

    // ---- 夹具 ----

    private fun character(
        id: String,
        regexScripts: Boolean = false,
    ): CharacterEntity {
        val data =
            if (regexScripts) {
                CharacterData(
                    extensions =
                        buildJsonObject {
                            put(
                                "regex_scripts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("scriptName", "rewrite")
                                            put("findRegex", "SECRET")
                                            put("replaceString", "REVEALED")
                                        },
                                    )
                                },
                            )
                        },
                )
            } else {
                CharacterData()
            }
        return CharacterEntity(
            id = id,
            name = "卡$id",
            role = "assistant",
            description = "",
            personality = "",
            scenario = "",
            firstMes = "",
            mesExample = "",
            color = "#000000",
            avatarData = "",
            worldBookId = "",
            dataJson = Json.encodeToString(data),
        )
    }

    private fun extensionPack(
        id: String,
        enabled: Boolean = true,
        isBuiltin: Boolean = false,
    ): ExtensionPackEntity =
        ExtensionPackEntity(
            id = id,
            name = "包$id",
            author = "a",
            version = "1.0",
            enabled = enabled,
            isBuiltin = isBuiltin,
            contentHash = "hash-$id",
            installedAt = "2026-01-01",
        )

    private fun rulePack(
        id: String,
        enabled: Boolean = true,
    ): RulePackEntity =
        RulePackEntity(
            id = id,
            name = "规则包$id",
            author = "a",
            version = "1.0",
            enabled = enabled,
            contentJson = "{}",
            createdAt = "2026-01-01",
        )

    private fun regexRule(id: String): GlobalRegexRuleEntity =
        GlobalRegexRuleEntity(
            id = id,
            name = "全局正则$id",
            findRegex = "SECRET",
            replaceString = "REVEALED",
        )

    private suspend fun probe(): UiModeEcosystemSignals = UiModeEcosystemProbe.signals(fakeDb())

    /** 信号构造（四参具名，测试里一行放不下全字段） */
    private fun signals(
        regex: Boolean = false,
        packs: Boolean = false,
        html: Boolean = false,
        cards: Boolean = false,
    ): UiModeEcosystemSignals =
        UiModeEcosystemSignals(
            hasRegexRules = regex,
            hasEnabledPacks = packs,
            hasHtmlUiEnabled = html,
            hasImportedTavernCard = cards,
        )

    // ---- 四个信号：正则 ----

    @Test
    fun `全局正则表有行即正则信号为真`() {
        runBlocking {
            tables.globalRegex += regexRule("g1")
            assertTrue(probe().hasRegexRules)
        }
    }

    @Test
    fun `角色卡带卡内regex_scripts即正则信号为真`() {
        runBlocking {
            tables.characters += character("c1", regexScripts = true)
            assertTrue(probe().hasRegexRules)
            assertTrue(UiModeEcosystemProbe.hasCardRegexScripts(character("c1", regexScripts = true)))
        }
    }

    @Test
    fun `卡内无regex_scripts不触发正则信号`() {
        runBlocking {
            tables.characters += character("c1", regexScripts = false)
            assertFalse(probe().hasRegexRules)
            assertFalse(UiModeEcosystemProbe.hasCardRegexScripts(character("c1", regexScripts = false)))
        }
    }

    @Test
    fun `dataJson解析失败不触发正则信号`() {
        val broken =
            character("c1", regexScripts = false)
                .copy(dataJson = "{not json")
        assertFalse(UiModeEcosystemProbe.hasCardRegexScripts(broken))
    }

    // ---- 四个信号：扩展包 ----

    @Test
    fun `启用的非内置扩展包算信号`() {
        runBlocking {
            tables.extensionPacks += extensionPack("user-pack")
            assertTrue(probe().hasEnabledPacks)
        }
    }

    @Test
    fun `内置tavernhelper-compat不算信号`() {
        runBlocking {
            // 每台设备都有：官方内置包随启动种子落库且 enabled=true
            tables.extensionPacks += extensionPack("tavernhelper-compat", isBuiltin = true)
            assertFalse(probe().hasEnabledPacks)
        }
    }

    @Test
    fun `停用的非内置扩展包不算信号`() {
        runBlocking {
            tables.extensionPacks += extensionPack("user-pack", enabled = false)
            assertFalse(probe().hasEnabledPacks)
        }
    }

    @Test
    fun `启用的规则包算信号`() {
        runBlocking {
            tables.rulePacks += rulePack("rp1")
            assertTrue(probe().hasEnabledPacks)
        }
    }

    @Test
    fun `停用的规则包不算信号`() {
        runBlocking {
            tables.rulePacks += rulePack("rp1", enabled = false)
            assertFalse(probe().hasEnabledPacks)
        }
    }

    // ---- 四个信号：HTML 界面 ----

    @Test
    fun `html_ui_enabled为1算信号`() {
        runBlocking {
            tables.settings["html_ui_enabled"] = "1"
            assertTrue(probe().hasHtmlUiEnabled)
        }
    }

    @Test
    fun `html_ui_enabled缺省或0不算信号`() {
        runBlocking {
            assertFalse(probe().hasHtmlUiEnabled)
            tables.settings["html_ui_enabled"] = "0"
            assertFalse(probe().hasHtmlUiEnabled)
        }
    }

    // ---- 四个信号：导入过酒馆卡（近似：角色表非空，口径见探针 KDoc）----

    @Test
    fun `角色表非空即导入信号为真`() {
        runBlocking {
            tables.characters += character("c1")
            assertTrue(probe().hasImportedTavernCard)
        }
    }

    @Test
    fun `角色表为空则导入信号为假`() {
        runBlocking {
            assertFalse(probe().hasImportedTavernCard)
        }
    }

    // ---- 汇总 ----

    @Test
    fun `全空库四个信号都为假`() {
        runBlocking {
            val signals = probe()
            assertEquals(UiModeEcosystemSignals.NONE, signals)
        }
    }

    @Test
    fun `任一信号为真hasAny为真`() {
        val one =
            UiModeEcosystemSignals(
                hasRegexRules = false,
                hasEnabledPacks = true,
                hasHtmlUiEnabled = false,
                hasImportedTavernCard = false,
            )
        val all = signals(regex = true, packs = true, html = true, cards = true)
        assertTrue(one.hasAny)
        assertTrue(all.hasAny)
        assertFalse(UiModeEcosystemSignals.NONE.hasAny)
    }
}
