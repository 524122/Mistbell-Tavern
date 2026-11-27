package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.model.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扩展包宿主钩子运行时的界面模式闸（P3 执行边界，docs/UI_MODE.md）JVM 单测。
 *
 * 生产接法：AppContainer 传 `modeProvider = { uiMode }`（可空：null=模式未知），start()
 * 里 combine 包流与模式，简洁/未知模式给 onPacksChanged 空表，已加载钩子走现有 unload。
 * 本测试翻转模式 Flow 验证同一条响应式路径：高级加载 → 简洁卸载（注入/回复处理全失效）
 * → 高级恢复；模式未知时不加载。
 */
class ExtensionHookRuntimeManagerUiModeGateTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "exthook-mode-test-${System.nanoTime()}")
    private val memSettings = mutableMapOf<String, String>()
    private val mode = MutableStateFlow<UiMode?>(UiMode.ADVANCED)
    private val dao = FakeExtensionPackDao()

    private val manager =
        ExtensionHookRuntimeManager(
            daoProvider = { dao },
            filesRoot = root,
            settingsDaoProvider = { fakeSettingsDao },
            capabilityHostProvider = { null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            modeProvider = { mode },
        )

    private val fakeSettingsDao =
        object : SettingsDao {
            override fun getAll(): Flow<List<SettingsEntity>> {
                return MutableStateFlow(memSettings.map { SettingsEntity(it.key, it.value) })
            }

            override suspend fun getValue(key: String): String? = memSettings[key]

            override fun observeValue(key: String): Flow<String?> = MutableStateFlow(memSettings[key]).map { it }

            override suspend fun upsert(setting: SettingsEntity) {
                memSettings[setting.key] = setting.value
            }

            override suspend fun deleteByKey(key: String) {
                memSettings.remove(key)
            }

            override suspend fun deleteAll() {
                memSettings.clear()
            }
        }

    @After
    fun tearDown() {
        manager.sandbox.shutdown()
        root.deleteRecursively()
    }

    private fun installHookedPack(id: String): ExtensionPackEntity {
        val manifest =
            """{"id":"$id","name":"钩子包$id","type":"extension","loadingOrder":100,""" +
                """"hostHooks":"hook.js","permissions":["prompt:inject","reply:process"]}"""
        ExtensionPackStore.extractContent(
            root,
            id,
            mapOf(
                "manifest.json" to manifest,
                "hook.js" to
                    """
                    tavern.onPrompt(function () { return [{content: "HOOK_INJECT"}]; });
                    tavern.onReply(function (t) { return t + "!"; });
                    """.trimIndent(),
            ),
        )
        return ExtensionPackEntity(
            id = id,
            name = "钩子包$id",
            author = "",
            version = "1",
            loadingOrder = 100,
            permissionsJson = ExtensionPackStore.encodePermissions(listOf("prompt:inject", "reply:process")),
            hasHooks = true,
            enabled = true,
            permissionsConfirmed = true,
            contentHash = "hash-$id",
            installedAt = "t",
        )
    }

    @Test
    fun `高级模式加载后切简洁卸载 再切回高级恢复`() {
        val pack = installHookedPack("hooked")
        dao.packs.value = listOf(pack)
        manager.start()

        // 高级模式：钩子已加载
        awaitUntil { manager.promptInjections("{}").isNotEmpty() }
        assertEquals(listOf("HOOK_INJECT"), manager.promptInjections("{}").single().injections.map { it.content })
        assertEquals("x!", manager.processReplies("x", "{}"))

        // 切简洁：combine 发空表 → onPacksChanged(emptyList()) 走现有 unload
        mode.value = UiMode.SIMPLE
        awaitUntil { manager.promptInjections("{}").isEmpty() }
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))

        // 切回高级：同一包恢复加载
        mode.value = UiMode.ADVANCED
        awaitUntil { manager.promptInjections("{}").isNotEmpty() }
        assertEquals(listOf("HOOK_INJECT"), manager.promptInjections("{}").single().injections.map { it.content })
        assertEquals("x!", manager.processReplies("x", "{}"))
    }

    @Test
    fun `简洁模式启动时钩子不加载`() {
        mode.value = UiMode.SIMPLE
        dao.packs.value = listOf(installHookedPack("hooked-simple"))
        manager.start()
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))
    }

    @Test
    fun `模式未知时不加载 模式读回后恢复装载`() {
        // 启动头几毫秒：包已启用但 ui_mode 还没读回来——绝不抢先加载执行钩子
        mode.value = null
        dao.packs.value = listOf(installHookedPack("hooked-unknown"))
        manager.start()
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))

        // 数据库读回模式后，下一次发射即装载
        mode.value = UiMode.ADVANCED
        awaitUntil { manager.promptInjections("{}").isNotEmpty() }
        assertEquals(listOf("HOOK_INJECT"), manager.promptInjections("{}").single().injections.map { it.content })
    }

    private fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("等待超时：模式切换未生效")
            Thread.sleep(10)
        }
    }

    private class FakeExtensionPackDao : ExtensionPackDao {
        val packs = MutableStateFlow<List<ExtensionPackEntity>>(emptyList())

        override fun observeAll(): Flow<List<ExtensionPackEntity>> = packs

        override suspend fun getById(id: String): ExtensionPackEntity? = packs.value.firstOrNull { it.id == id }

        override fun observeEnabled() = packs.map { list -> list.filter { it.enabled } }

        override suspend fun getEnabled(): List<ExtensionPackEntity> = packs.value.filter { it.enabled }

        override suspend fun upsert(pack: ExtensionPackEntity) {
            packs.value = packs.value.filterNot { it.id == pack.id } + pack
        }

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ) {
            packs.value = packs.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override suspend fun deleteById(id: String) {
            packs.value = packs.value.filterNot { it.id == id }
        }
    }
}
