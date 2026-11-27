package com.mistbell.tavern.android.util

import androidx.room.InvalidationTracker
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
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 内置角色（P3 第 4 项，docs/UI_MODE.md「首启与默认层」）JVM 单测。
 *
 * 覆盖三件事：
 * - 目录与资产不漂移：[BuiltInCharacters.all] 里的每一位都能在 assets 里找到、能被
 *   [CardParser] 解析、落库 id 是稳定的 `builtin_` 前缀（生态信号排除的单一口径）；
 * - 新内置角色（沈青梧 / 程野）的开场白确实预埋了可被记住的具体细节（P4 会话记忆演示要用），
 *   且 [AdvancedCapabilityDetector] 判定为无高级能力（卡是干净卡）；
 * - [BuiltInCharacters.install] 的幂等语义：全新安装四位全落库（世界书先行）、重复安装与
 *   同名的老示例卡（升级前随机 id）都跳过、空清单零写入（老用户不被自动塞进新角色）。
 *
 * 资产读取直接走仓库文件（Gradle 单测工作目录 = 模块目录 app/），与 SampleCharactersTest 同一路径。
 * 数据库用 Proxy 替身（与 UiModeEcosystemProbeTest 同一写法）：只注册 install 触及的方法。
 */
class BuiltInCharactersTest {
    // ---- 资产与目录 ----

    private fun assetFile(assetName: String): File = File("src/main/assets/sample_characters/$assetName")

    private fun readAsset(assetName: String): String = assetFile(assetName).readText(Charsets.UTF_8)

    @Test
    fun `目录与资产一一对应且字段完整`() {
        assertTrue("内置角色至少要有四位", BuiltInCharacters.all.size >= 4)
        val dir = File("src/main/assets/sample_characters")
        assertTrue("内置角色资产目录不存在", dir.isDirectory)
        BuiltInCharacters.all.forEach { def ->
            assertTrue("资产缺失: ${def.assetName}", assetFile(def.assetName).isFile)
            assertTrue("id 必须带内置前缀: ${def.id}", def.id.startsWith(BuiltInCharacters.ID_PREFIX))
            assertTrue("displayName 不能为空: ${def.id}", def.displayName.isNotBlank())
            assertTrue("blurb 不能为空: ${def.id}", def.blurb.isNotBlank())
            assertEquals(
                "displayName 应与卡内 name 一致（同名幂等检查靠它）: ${def.assetName}",
                def.displayName,
                BuiltInCharacters.parseAsset(readAsset(def.assetName), def)!!.character.name,
            )
        }
        // id 由资产名派生，保证「目录条目 ↔ 稳定 id」不会各改各的
        BuiltInCharacters.all.forEach { def ->
            assertEquals(
                "id 应为前缀 + 资产名去扩展名: ${def.id}",
                BuiltInCharacters.ID_PREFIX + def.assetName.removeSuffix(".json"),
                def.id,
            )
        }
    }

    @Test
    fun `四位内置角色都能解析且稳定id生效`() {
        BuiltInCharacters.all.forEach { def ->
            val result = BuiltInCharacters.parseAsset(readAsset(def.assetName), def)
            assertNotNull("解析失败: ${def.assetName}", result)
            assertEquals("落库 id 必须是稳定内置 id: ${def.assetName}", def.id, result!!.character.id)
            assertFalse("开场白不能为空: ${def.id}", result.character.firstMes.isBlank())
            assertNotNull("内置角色都带背景世界书: ${def.id}", result.worldBook)
            assertTrue("世界书条目不能为空: ${def.id}", result.worldBookEntries.isNotEmpty())
        }
    }

    @Test
    fun `四位卡都无高级能力`() {
        BuiltInCharacters.all.forEach { def ->
            val result = BuiltInCharacters.parseAsset(readAsset(def.assetName), def)!!
            val profile = AdvancedCapabilityDetector.detect(result)
            assertFalse(
                "内置卡必须无高级能力（${def.assetName}）: $profile",
                profile.hasAny,
            )
        }
    }

    @Test
    fun `新角色开场白预埋可被记住的细节`() {
        // 沈青梧（民国修表匠）：来历（父亲传店）、习惯（修表先听故事）、在意的物（落地钟）、心愿（出师）
        val qingwuDef = BuiltInCharacters.byId("builtin_qingwu")!!
        val qingwu = BuiltInCharacters.parseAsset(readAsset("qingwu.json"), qingwuDef)!!
        val qingwuFacts = listOf("沈青梧", "顺昌钟表行", "家父", "落地钟", "出师", "要紧吗")
        qingwuFacts.forEach { fact ->
            assertTrue("沈青梧开场白应预埋「$fact」", qingwu.character.firstMes.contains(fact))
        }
        // 程野（山巅气象站观测员）：来历（驻站）、习惯（人工读数）、在意的物（雷达/辣椒酱）、心愿（拍全雷暴延时）
        val chengyeDef = BuiltInCharacters.byId("builtin_chengye")!!
        val chengye = BuiltInCharacters.parseAsset(readAsset("chengye.json"), chengyeDef)!!
        val chengyeFacts = listOf("程野", "云顶气象站", "雷达", "辣椒酱", "延时", "怎么上来的")
        chengyeFacts.forEach { fact ->
            assertTrue("程野开场白应预埋「$fact」", chengye.character.firstMes.contains(fact))
        }
        // 四位的开场白都以提问收尾（引出用户自己的具体事实，P4 记忆演示的另一半）
        BuiltInCharacters.all.forEach { def ->
            val mes = BuiltInCharacters.parseAsset(readAsset(def.assetName), def)!!.character.firstMes
            assertTrue("开场白应带向用户的提问: ${def.id}", mes.contains("？") || mes.contains("?"))
        }
    }

    // ---- missingInstalled ----

    @Test
    fun `missingInstalled全新安装返回全部`() {
        assertEquals(BuiltInCharacters.all, BuiltInCharacters.missingInstalled(emptyList()))
    }

    @Test
    fun `missingInstalled已有内置只返回缺的`() {
        val missing = BuiltInCharacters.missingInstalled(listOf("builtin_aria", "builtin_chengye"))
        assertEquals(listOf("builtin_kael", "builtin_qingwu"), missing.map { it.id })
    }

    @Test
    fun `missingInstalled全部已存在返回空`() {
        val allIds = BuiltInCharacters.all.map { it.id }
        assertTrue(BuiltInCharacters.missingInstalled(allIds).isEmpty())
    }

    @Test
    fun `isBuiltIn判定与落库口径一致`() {
        BuiltInCharacters.all.forEach { assertTrue(BuiltInCharacters.isBuiltIn(it.id)) }
        assertFalse(BuiltInCharacters.isBuiltIn("some-random-uuid"))
        assertFalse(BuiltInCharacters.isBuiltIn(""))
    }

    // ---- install（Proxy 替身库）----

    /** install 触及的内存表 + 调用顺序（验证世界书先行） */
    private class FakeTables {
        val characters = mutableListOf<CharacterEntity>()
        val books = mutableListOf<WorldBookEntity>()
        val entryCount = mutableListOf<Int>()

        /** 事件序："book:<id>" / "entries:<bookId>" / "char:<id>"，验证世界书先行与零写入 */
        val events = mutableListOf<String>()
    }

    private val tables = FakeTables()

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
                    ?: throw UnsupportedOperationException("${iface.simpleName}.${method.name} 未在替身注册")
            handler(args ?: emptyArray())
        } as T

    private fun fakeDb(): AppDatabase =
        object : AppDatabase() {
            override fun characterDao(): CharacterDao =
                daoProxy(
                    CharacterDao::class.java,
                    mapOf(
                        "getAllOnce" to { _ -> tables.characters.toList() },
                        "upsert" to { args ->
                            val entity = args[0] as CharacterEntity
                            tables.characters.removeAll { it.id == entity.id }
                            tables.characters += entity
                            tables.events += "char:${entity.id}"
                            Unit
                        },
                    ),
                )

            override fun worldBookDao(): WorldBookDao =
                daoProxy(
                    WorldBookDao::class.java,
                    mapOf(
                        "upsertBook" to { args ->
                            val book = args[0] as WorldBookEntity
                            tables.books += book
                            tables.events += "book:${book.id}"
                            Unit
                        },
                        "upsertEntries" to { args ->
                            val entries = args[0] as List<*>
                            tables.entryCount += entries.size
                            tables.events += "entries:${entries.size}"
                            Unit
                        },
                    ),
                )

            // install 不触及的 DAO：显式失败
            override fun settingsDao(): SettingsDao = throw UnsupportedOperationException()

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

            override fun globalRegexDao(): GlobalRegexDao = throw UnsupportedOperationException()

            override fun extensionPackDao(): ExtensionPackDao = throw UnsupportedOperationException()

            override fun rulePackDao(): RulePackDao = throw UnsupportedOperationException()

            override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()

            override fun clearAllTables() = throw UnsupportedOperationException()
        }

    /** 与生产调用方（OnboardingViewModel / CharacterListViewModel）同一形状的资产读取 */
    private suspend fun readAssetFromRepo(assetName: String): String = readAsset(assetName)

    @Test
    fun `全新安装四位内置角色全部落库且世界书先行`() =
        runBlocking {
            val installed = BuiltInCharacters.install(fakeDb(), BuiltInCharacters.all, ::readAssetFromRepo)

            assertEquals(BuiltInCharacters.all.map { it.displayName }, installed)
            // 四位角色落库、id 是稳定内置 id、开场白非空（落库即可直接开始聊天）
            assertEquals(BuiltInCharacters.all.map { it.id }, tables.characters.map { it.id })
            tables.characters.forEach { assertTrue(it.firstMes.isNotBlank()) }
            // 每位都带背景世界书，且书的落库先于角色的落库（世界书先行，与导入路径同一顺序）
            assertEquals(BuiltInCharacters.all.size, tables.books.size)
            BuiltInCharacters.all.forEach { def ->
                val charEvent = tables.events.indexOfFirst { it == "char:${def.id}" }
                assertTrue("角色应落库: ${def.id}", charEvent >= 0)
                val char = tables.characters.first { it.id == def.id }
                assertTrue("应挂上背景世界书: ${def.id}", char.worldBookId.isNotBlank())
                assertTrue("书应先于角色落库: ${def.id}", tables.events.indexOf("book:${char.worldBookId}") < charEvent)
            }
        }

    @Test
    fun `重复安装同id跳过不产生重复`() =
        runBlocking {
            val db = fakeDb()
            BuiltInCharacters.install(db, BuiltInCharacters.all, ::readAssetFromRepo)
            tables.events.clear()
            val second = BuiltInCharacters.install(db, BuiltInCharacters.all, ::readAssetFromRepo)
            assertTrue("全部已存在时应零落库", second.isEmpty())
            assertTrue("不应有任何写入", tables.events.isEmpty())
            assertEquals(BuiltInCharacters.all.size, tables.characters.size)
        }

    @Test
    fun `同名随机id的旧示例卡跳过（升级前经旧引导导入的副本）`() =
        runBlocking {
            val oldAria =
                BuiltInCharacters.parseAsset(readAsset("aria.json"), BuiltInCharacters.byId("builtin_aria")!!)!!
                    .character
                    .copy(id = "legacy-random-uuid")
            tables.characters += oldAria
            tables.events.clear()
            val installed = BuiltInCharacters.install(fakeDb(), BuiltInCharacters.all, ::readAssetFromRepo)
            assertFalse("阿莉雅不应重复安装", installed.contains("阿莉雅"))
            assertEquals(listOf("凯尔", "沈青梧", "程野"), installed)
            assertEquals(4, tables.characters.size)
            assertTrue("旧副本应原样保留", tables.characters.any { it.id == "legacy-random-uuid" })
            assertTrue("稳定版阿莉雅不应落库", tables.characters.none { it.id == "builtin_aria" })
        }

    @Test
    fun `空定义清单零写入（老用户不被自动写入的语义）`() =
        runBlocking {
            val installed = BuiltInCharacters.install(fakeDb(), emptyList(), ::readAssetFromRepo)
            assertTrue(installed.isEmpty())
            assertTrue(tables.events.isEmpty())
            assertTrue(tables.characters.isEmpty())
        }
}
