package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.repository.EnabledRulePack
import com.mistbell.tavern.android.data.repository.RulePackSource
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.extension.ExtensionRegistry
import com.mistbell.tavern.android.extension.TavernExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本包运行时的界面模式闸（P3 执行边界，docs/UI_MODE.md）JVM 单测：真 Rhino。
 *
 * 生产接法是 AppContainer 把 [UiModeGatedRulePackSource]（简洁模式发空表）挂到
 * ScriptRuntimeManager.source——本测试用可翻转的假源直接驱动同一条响应式路径：
 * 高级模式加载（onPrompt 产出 + 动态斜杠命令注册进 ExtensionRegistry）→ 切简洁
 * （reconcile 空表走现有 unload：注入为空、命令摘除）→ 切回高级恢复。
 */
class ScriptRuntimeManagerUiModeGateTest {
    /** 模拟模式闸后的包源：模式切简洁时它发空表（UiModeGatedRulePackSource 的行为） */
    private val sourcePacks = MutableStateFlow<List<EnabledRulePack>?>(emptyList())

    private val memSettings = mutableMapOf<String, String>()

    private val manager =
        ScriptRuntimeManager(
            source =
                object : RulePackSource {
                    override val packs: kotlinx.coroutines.flow.StateFlow<List<EnabledRulePack>?> = sourcePacks
                },
            settingsDaoProvider = { fakeSettingsDao },
            scope = CoroutineScope(Dispatchers.Unconfined),
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
        sourcePacks.value = emptyList()
        awaitUntil { manager.promptInjections("{}").isEmpty() }
        ExtensionRegistry.resetForTesting()
    }

    /** 高级模式就绪的脚本包：onPrompt 注入 + 注册动态斜杠命令 */
    private fun scriptedPack() =
        EnabledRulePack(
            id = "script-ui",
            name = "界面探测脚本",
            loadingOrder = 100,
            content =
                RulePackContent(
                    scriptSource =
                        """
                        tavern.onPrompt(function (ctxJson) {
                          return [{position: "SYSTEM_END", name: "probe", content: "SCRIPT_INJECT"}];
                        });
                        tavern.registerCommand("ui-echo", function (args, ctxJson) {
                          return "ECHO:" + args;
                        });
                        """.trimIndent(),
                ),
        )

    @Test
    fun `高级模式加载后切到简洁卸载 再切回高级恢复`() {
        manager.start()
        sourcePacks.value = listOf(scriptedPack())
        awaitUntil { manager.promptInjections("{}").isNotEmpty() }

        // 高级模式：沙箱里确实有包，动态斜杠命令进了注册表
        assertEquals(
            listOf("SCRIPT_INJECT"),
            manager.promptInjections("{}").single().second.map { it.content },
        )
        val command = ExtensionRegistry.findSlashCommand("ui-echo")
        assertNotNull("脚本注册的斜杠命令应在注册表", command)
        assertEquals("script-script-ui-ui-echo", (command as TavernExtension).id)

        // 切简洁：包源发空表 → reconcile 走现有 unload
        sourcePacks.value = emptyList()
        awaitUntil { manager.promptInjections("{}").isEmpty() }
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))
        assertNull("动态斜杠命令应随脚本卸载摘除", ExtensionRegistry.findSlashCommand("ui-echo"))

        // 切回高级：同一脚本重新加载，注入与命令恢复
        sourcePacks.value = listOf(scriptedPack())
        awaitUntil { manager.promptInjections("{}").isNotEmpty() }
        assertEquals(
            listOf("SCRIPT_INJECT"),
            manager.promptInjections("{}").single().second.map { it.content },
        )
        assertNotNull(ExtensionRegistry.findSlashCommand("ui-echo"))
    }

    @Test
    fun `简洁模式启动时脚本包不加载`() {
        // 进程就在简洁模式：包源恒空表，即便库里实际有启用脚本包（委托态模拟）
        sourcePacks.value = emptyList()
        manager.start()
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertNull(ExtensionRegistry.findSlashCommand("ui-echo"))
        assertEquals("x", manager.processReplies("x", "{}"))
    }

    /** 轮询等待响应式切换完成（Unconfined 调度时序的鲁棒写法） */
    private fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("等待超时：响应式切换未完成")
            Thread.sleep(10)
        }
    }
}
