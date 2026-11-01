package com.mistbell.tavern.android.data.extensionpack

import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 官方内置扩展包种子（P2）：确定性 zip（hash 跨进程稳定的前提）、种子决策、
 * assets 里分发的 tavernhelper-compat 制品必须能过 P1 解析管线（资产防线，
 * 坏制品会让官方门面在用户设备上种子失败，读取路径依赖 Gradle 单测工作目录 = app/）。
 */
class BuiltinPackSeedTest {
    // ---- 确定性 zip ----

    @Test
    fun `zipOf 同内容字节稳定 hash 跨进程一致`() {
        val files = mapOf("manifest.json" to "{}", "facade.js" to "x=1;")
        val a = BuiltinPackSeeder.zipOf(files)
        val a2 = BuiltinPackSeeder.zipOf(files)
        assertTrue("同内容 zip 字节必须一致（时间戳固定）", a.contentEquals(a2))
        assertEquals(BuiltinPackSeeder.contentHash(a), BuiltinPackSeeder.contentHash(a2))
        val different = BuiltinPackSeeder.zipOf(mapOf("manifest.json" to "{}", "facade.js" to "x=2;"))
        assertTrue("不同内容 hash 必须可区分", BuiltinPackSeeder.contentHash(a) != BuiltinPackSeeder.contentHash(different))
    }

    @Test
    fun `zipOf 产物可被 RulePackParser 完整解析（零特判复用 P1 管线）`() {
        val bundle =
            RulePackParser.parseZip(
                BuiltinPackSeeder.zipOf(
                    mapOf(
                        "manifest.json" to
                            """{"id":"demo","name":"x","type":"extension",""" +
                            """"provides":["facade:tavernhelper"],"webviewPreload":"facade.js"}""",
                        "facade.js" to "window.TavernHelper = {};",
                    ),
                ),
            )
        assertEquals(RulePackManifest.TYPE_EXTENSION, bundle.manifest.type)
        assertEquals(listOf("facade:tavernhelper"), bundle.manifest.provides)
        assertEquals("window.TavernHelper = {};", bundle.content.webviewPreloadSource)
        assertEquals(setOf("manifest.json", "facade.js"), bundle.files.keys)
    }

    // ---- 种子决策 ----

    private fun old(
        hash: String,
        enabled: Boolean,
    ): ExtensionPackEntity =
        ExtensionPackEntity(
            id = "tavernhelper-compat",
            name = "x",
            author = "",
            version = "1",
            enabled = enabled,
            contentHash = hash,
            installedAt = "t",
        )

    @Test
    fun `种子决策 无行安装 hash变化刷新 同hash跳过`() {
        assertEquals(BuiltinPackSeeder.SeedAction.INSTALL, BuiltinPackSeeder.decide(null, "h1"))
        assertEquals(BuiltinPackSeeder.SeedAction.REFRESH, BuiltinPackSeeder.decide(old("h0", enabled = false), "h1"))
        assertEquals(BuiltinPackSeeder.SeedAction.SKIP, BuiltinPackSeeder.decide(old("h1", enabled = false), "h1"))
    }

    // ---- assets 制品防线（内置包损坏 = 官方门面种子失败）----

    private fun builtinAssetFiles(): Map<String, String> {
        val dir = File("src/main/assets/extensions/tavernhelper-compat")
        assertTrue("内置扩展包资产目录不存在: ${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { f -> f.isFile }.orEmpty().associate { it.name to it.readText(Charsets.UTF_8) }
    }

    @Test
    fun `内置 tavernhelper-compat 制品通过解析且 manifest 形态符合种子契约`() {
        val files = builtinAssetFiles()
        assertTrue(
            "制品必须含 manifest.json 与 facade.js",
            files.keys.containsAll(setOf("manifest.json", "facade.js")),
        )

        val bundle = RulePackParser.parseZip(BuiltinPackSeeder.zipOf(files))
        val manifest = bundle.manifest
        assertEquals("tavernhelper-compat", manifest.id)
        assertEquals(RulePackManifest.TYPE_EXTENSION, manifest.type)
        assertEquals(listOf("facade:tavernhelper"), manifest.provides)
        assertEquals("facade.js", manifest.webviewPreload)
        // 官方包如实声明门面可达的能力面（全部在权限目录内——解析已校验，这里锁定清单）
        assertEquals(
            setOf(
                "messages:read",
                "messages:write",
                "generation:control",
                "variables",
                "events:subscribe",
                "prompt:inject",
                "reply:process",
            ),
            manifest.permissions.toSet(),
        )
        // preload ≤64KB（解析器已强制，这里断言非空且确实注入门面组装）
        assertNotNull(bundle.content.webviewPreloadSource)
        assertTrue(bundle.content.webviewPreloadSource!!.contains("window.TavernHelper"))
        assertTrue(bundle.content.webviewPreloadSource!!.contains("window.Mvu"))
        assertTrue(bundle.content.webviewPreloadSource!!.contains("window.eventOn"))
        // 事件管道已迁原生段：门面制品不得再定义 __tavernEmit（否则与原生段重复定义）
        assertTrue(!bundle.content.webviewPreloadSource!!.contains("__tavernEmit ="))
    }

    @Test
    fun `内置制品 hash 稳定（种子同 hash 跳过的前提）`() {
        val hashA = BuiltinPackSeeder.contentHash(BuiltinPackSeeder.zipOf(builtinAssetFiles()))
        val hashB = BuiltinPackSeeder.contentHash(BuiltinPackSeeder.zipOf(builtinAssetFiles()))
        assertEquals(hashA, hashB)
    }
}
