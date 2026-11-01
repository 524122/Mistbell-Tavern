package com.mistbell.tavern.android.data.extensionpack

import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 扩展包纯 JVM 生命周期逻辑测试：hash 比对、覆盖安装计划、落盘/清理、启用守卫。 */
class ExtensionPackStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(
        id: String = "demo-ext",
        permissions: List<String> = emptyList(),
        preload: String? = null,
        hooks: String? = null,
        script: String? = null,
    ): RulePackManifest =
        RulePackManifest(
            id = id,
            name = "演示扩展",
            author = "Mistbell",
            version = "1.0.0",
            loadingOrder = 50,
            type = RulePackManifest.TYPE_EXTENSION,
            permissions = permissions,
            webviewPreload = preload,
            hostHooks = hooks,
            script = script,
        )

    private fun entity(
        id: String = "demo-ext",
        permissions: List<String> = listOf("prompt:inject"),
        hash: String = "hash-a",
        enabled: Boolean = true,
        confirmed: Boolean = true,
    ): ExtensionPackEntity =
        ExtensionPackEntity(
            id = id,
            name = "旧名",
            author = "旧作者",
            version = "0.9.0",
            loadingOrder = 100,
            permissionsJson = ExtensionPackStore.encodePermissions(permissions),
            hasPreload = false,
            hasHooks = false,
            enabled = enabled,
            permissionsConfirmed = confirmed,
            contentHash = hash,
            installedAt = "2026-01-01 00:00:00",
        )

    // ---- 内容哈希 ----

    @Test
    fun `contentHash 稳定且对不同字节可区分`() {
        val a = ExtensionPackStore.contentHash("zip-a".toByteArray())
        val a2 = ExtensionPackStore.contentHash("zip-a".toByteArray())
        val b = ExtensionPackStore.contentHash("zip-b".toByteArray())
        assertEquals(a, a2)
        assertNotEquals(a, b)
        assertEquals(64, a.length)
    }

    // ---- 覆盖安装计划 ----

    @Test
    fun `新包计划确认后启用未确认则保持禁用`() {
        val confirmed =
            ExtensionPackStore.planPersist(
                null,
                manifest(permissions = listOf("prompt:inject")),
                "h1",
                confirmedByUser = true,
                now = "t1",
            )
        assertEquals(ExtensionPackStore.PersistMode.FRESH, confirmed.mode)
        assertTrue(confirmed.extract)
        assertTrue(confirmed.entity.enabled)
        assertTrue(confirmed.entity.permissionsConfirmed)

        val unconfirmed =
            ExtensionPackStore.planPersist(
                null,
                manifest(permissions = listOf("prompt:inject")),
                "h1",
                confirmedByUser = false,
                now = "t1",
            )
        assertFalse(unconfirmed.entity.enabled)
        assertFalse(unconfirmed.entity.permissionsConfirmed)
    }

    @Test
    fun `无权限新包未经 UI 确认也视为已确认（无权可授）`() {
        val plan = ExtensionPackStore.planPersist(null, manifest(), "h1", confirmedByUser = false, now = "t1")
        assertTrue(plan.entity.permissionsConfirmed)
    }

    @Test
    fun `同 hash 覆盖仅刷新元数据且保留启停与确认态`() {
        val old = entity(enabled = true, confirmed = true)
        val plan =
            ExtensionPackStore.planPersist(
                old = old,
                manifest = manifest(permissions = listOf("prompt:inject")),
                // 与旧行一致
                contentHash = "hash-a",
                confirmedByUser = true,
                now = "2026-02-02 00:00:00",
            )
        assertEquals(ExtensionPackStore.PersistMode.REFRESH_METADATA, plan.mode)
        assertFalse(plan.extract)
        assertEquals("2026-02-02 00:00:00", plan.entity.installedAt)
        assertEquals("hash-a", plan.entity.contentHash)
        assertTrue(plan.entity.enabled)
        assertTrue(plan.entity.permissionsConfirmed)
    }

    @Test
    fun `hash 变化无扩权时启用态保留`() {
        val old = entity(enabled = true, confirmed = true, hash = "hash-a")
        val plan =
            ExtensionPackStore.planPersist(
                old = old,
                manifest = manifest(permissions = listOf("prompt:inject", "variables")),
                contentHash = "hash-b",
                confirmedByUser = true,
                now = "t2",
            )
        assertEquals(ExtensionPackStore.PersistMode.UPDATE_CONTENT, plan.mode)
        assertTrue(plan.extract)
        assertTrue(plan.entity.enabled)
        assertTrue(plan.entity.permissionsConfirmed)
    }

    @Test
    fun `hash 变化权限集扩大且未经确认则强制回未授权禁用态`() {
        val old = entity(enabled = true, confirmed = true, permissions = listOf("prompt:inject"), hash = "hash-a")
        val plan =
            ExtensionPackStore.planPersist(
                old = old,
                manifest = manifest(permissions = listOf("prompt:inject", "messages:write")),
                contentHash = "hash-b",
                // 防御路径：未经 UI 对新清单确认
                confirmedByUser = false,
                now = "t2",
            )
        assertFalse(plan.entity.enabled)
        assertFalse(plan.entity.permissionsConfirmed)
    }

    @Test
    fun `hash 变化权限集扩大但本轮已确认新清单则保持启用`() {
        // UI 覆盖安装路径：确认框展示完整新权限集，「信任并导入」即完成再确认
        val old = entity(enabled = true, confirmed = true, permissions = listOf("prompt:inject"), hash = "hash-a")
        val plan =
            ExtensionPackStore.planPersist(
                old = old,
                manifest = manifest(permissions = listOf("prompt:inject", "messages:write")),
                contentHash = "hash-b",
                confirmedByUser = true,
                now = "t2",
            )
        assertTrue(plan.entity.enabled)
        assertTrue(plan.entity.permissionsConfirmed)
    }

    @Test
    fun `hash 变化权限集缩小不视为扩权`() {
        val old =
            entity(
                enabled = false,
                confirmed = true,
                permissions = listOf("prompt:inject", "messages:write"),
                hash = "hash-a",
            )
        val plan =
            ExtensionPackStore.planPersist(
                old = old,
                manifest = manifest(permissions = listOf("prompt:inject")),
                contentHash = "hash-b",
                confirmedByUser = false,
                now = "t2",
            )
        assertFalse(plan.entity.enabled) // 保留旧启停态（false）
        assertTrue(plan.entity.permissionsConfirmed) // 旧确认仍然覆盖缩小后的子集
    }

    @Test
    fun `计划实体的权限与 JS 制品标记来自新 manifest`() {
        val plan =
            ExtensionPackStore.planPersist(
                old = null,
                manifest =
                    manifest(
                        permissions = listOf("prompt:inject", "network:api.example.com"),
                        preload = "facade.js",
                        hooks = "hook.js",
                    ),
                contentHash = "h",
                confirmedByUser = true,
                now = "t",
            )
        assertEquals(
            listOf("prompt:inject", "network:api.example.com"),
            ExtensionPackStore.decodePermissions(plan.entity.permissionsJson),
        )
        assertTrue(plan.entity.hasPreload)
        assertTrue(plan.entity.hasHooks)
    }

    // ---- 落盘 / 清理 ----

    @Test
    fun `extractContent 落盘全量条目并清理覆盖后的遗留文件`() {
        val root = tmp.root
        ExtensionPackStore.extractContent(root, "demo-ext", mapOf("manifest.json" to "{}", "facade.js" to "v1"))
        val dir = ExtensionPackStore.packDir(root, "demo-ext")
        assertEquals("v1", File(dir, "facade.js").readText())

        // 覆盖安装：旧文件不残留（sentinel 必须消失）
        File(dir, "stale.txt").writeText("leftover")
        ExtensionPackStore.extractContent(root, "demo-ext", mapOf("manifest.json" to "{}", "facade.js" to "v2"))
        assertEquals("v2", File(dir, "facade.js").readText())
        assertFalse(File(dir, "stale.txt").exists())
        // 换名暂存目录不残留
        assertFalse(File(root, "extensions/demo-ext.incoming").exists())
        assertFalse(File(root, "extensions/demo-ext.old").exists())
    }

    @Test
    fun `extractContent 拒绝不适合作目录名的 id`() {
        listOf("../evil", "a/b", "a\\b", ".", "..").forEach { id ->
            assertThrows("id=$id 应被拒", IllegalArgumentException::class.java) {
                ExtensionPackStore.extractContent(tmp.root, id, mapOf("f" to "x"))
            }
        }
    }

    @Test
    fun `deleteContent 递归删除并清理换名残留`() {
        val root = tmp.root
        ExtensionPackStore.extractContent(root, "demo-ext", mapOf("facade.js" to "x"))
        val dir = ExtensionPackStore.packDir(root, "demo-ext")
        File(root, "extensions/demo-ext.old").mkdirs() // 模拟中断残留
        assertTrue(dir.exists())

        ExtensionPackStore.deleteContent(root, "demo-ext")
        assertFalse(dir.exists())
        assertFalse(File(root, "extensions/demo-ext.old").exists())
    }

    // ---- 启用守卫 ----

    @Test
    fun `未确认权限的包禁止启用已确认放行`() {
        assertThrows(IllegalStateException::class.java) {
            ExtensionPackStore.requireEnableAllowed(entity(confirmed = false))
        }
        ExtensionPackStore.requireEnableAllowed(entity(confirmed = true)) // 不抛即通过
    }

    // ---- 确认需求判定 ----

    @Test
    fun `声明权限或携带任一 JS 制品都需要确认`() {
        // bundle 与解析器产出一致：manifest 声明的制品在 content 中有源码（解析器强制）
        fun bundle(
            manifest: RulePackManifest,
            content: RulePackContent = RulePackContent(),
        ) = RulePackParser.Bundle(
            manifest = manifest,
            content = content,
            warnings = emptyList(),
            files = emptyMap(),
        )

        assertTrue(ExtensionPackStore.needsConfirmation(bundle(manifest(permissions = listOf("variables")))))
        assertTrue(
            ExtensionPackStore.needsConfirmation(
                bundle(manifest(preload = "facade.js"), RulePackContent(webviewPreloadSource = "x")),
            ),
        )
        assertTrue(
            ExtensionPackStore.needsConfirmation(
                bundle(manifest(hooks = "hook.js"), RulePackContent(hostHooksSource = "x")),
            ),
        )
        assertTrue(
            ExtensionPackStore.needsConfirmation(
                bundle(manifest(script = "script.js"), RulePackContent(scriptSource = "x")),
            ),
        )
        assertFalse(
            ExtensionPackStore.needsConfirmation(
                bundle(manifest().copy(type = RulePackManifest.TYPE_EXTENSION)),
            ),
        )
    }

    @Test
    fun `permissionsJson 编解码往返`() {
        val perms = listOf("prompt:inject", "network:api.example.com")
        assertEquals(perms, ExtensionPackStore.decodePermissions(ExtensionPackStore.encodePermissions(perms)))
        assertEquals(emptyList<String>(), ExtensionPackStore.decodePermissions("不是 JSON"))
    }
}
