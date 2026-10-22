package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RhinoSandbox + TavernHostApi 的 JVM 单测（Rhino 纯 Java，真跑脚本）。
 */
class RhinoSandboxTest {
    private val sandbox = RhinoSandbox()
    private val memSettings = mutableMapOf<String, String>()

    private val fakeDao =
        object : SettingsDao {
            override fun getAll(): Flow<List<SettingsEntity>> = MutableStateFlow(settingsList())

            private fun settingsList(): List<SettingsEntity> = memSettings.map { SettingsEntity(it.key, it.value) }

            override suspend fun getValue(key: String): String? = memSettings[key]

            override fun observeValue(key: String): Flow<String?> = MutableStateFlow(memSettings[key]).map { it }

            override suspend fun upsert(setting: SettingsEntity) {
                memSettings[setting.key] = setting.value
            }

            override suspend fun deleteAll() {
                memSettings.clear()
            }
        }

    private fun load(
        packId: String,
        source: String,
    ) = sandbox.loadPack(packId, source, TavernHostApi(packId, sandbox) { fakeDao })

    @After
    fun tearDown() {
        sandbox.shutdown()
    }

    @Test
    fun `onPrompt 注册后可被调用并返回 JSON`() {
        val result =
            load(
                "p1",
                """
                tavern.onPrompt(function(ctxJson) {
                    var ctx = JSON.parse(ctxJson);
                    return [{position: "SYSTEM_END", content: "注入 " + ctx.char}];
                });
                """.trimIndent(),
            )
        assertTrue(result is RhinoSandbox.SandboxResult.Ok)
        val out = sandbox.callRegistered("p1", TavernHostApi.REG_ON_PROMPT, arrayOf("{\"char\":\"阿莉雅\"}"))
        assertTrue(out!!.contains("注入 阿莉雅"))
    }

    @Test
    fun `初始化死循环被指令预算中止且不产生运行时`() {
        val result = load("loop", "while (true) {}")
        assertTrue(result is RhinoSandbox.SandboxResult.Failure)
        // 加载失败后包运行时已移除
        assertTrue(sandbox.registrationsOf("loop").isEmpty())
    }

    @Test
    fun `钩子内死循环被中止且后续调用不受影响`() {
        load(
            "p2",
            """
            tavern.onPrompt(function() { while (true) {} });
            tavern.onReply(function(t) { return "ok:" + t; });
            """.trimIndent(),
        )
        assertNull(sandbox.callRegistered("p2", TavernHostApi.REG_ON_PROMPT, arrayOf("{}")))
        // 沙箱线程未被毒死，另一个钩子仍可执行
        assertEquals("ok:x", sandbox.callRegistered("p2", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
    }

    @Test
    fun `JS 异常按无产出降级`() {
        load("p3", "tavern.onReply(function(t) { throw new Error('炸了'); });")
        assertNull(sandbox.callRegistered("p3", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
    }

    @Test
    fun `无法触达 Java 与文件系统`() {
        load(
            "p4",
            """
            tavern.onReply(function(t) {
                try { return String(java.lang.Runtime.getRuntime().exec("id")); } catch (e) {}
                try { return String(Packages.java.lang.System.getProperty("user.home")); } catch (e) {}
                try { return String(new java.io.File("/etc/passwd").length()); } catch (e) {}
                return "blocked";
            });
            """.trimIndent(),
        )
        assertEquals("blocked", sandbox.callRegistered("p4", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
    }

    @Test
    fun `registerCommand 与 registerMacro 注册名可见`() {
        load(
            "p5",
            """
            tavern.registerCommand("demo", function(args, ctx) { return "cmd:" + args; });
            tavern.registerMacro("mymacro", function(ctx) { return "mv"; });
            """.trimIndent(),
        )
        assertTrue(sandbox.hasRegistration("p5", TavernHostApi.REG_COMMAND + "demo"))
        assertTrue(sandbox.hasRegistration("p5", TavernHostApi.REG_MACRO + "mymacro"))
        assertEquals("cmd:hi", sandbox.callRegistered("p5", TavernHostApi.REG_COMMAND + "demo", arrayOf("hi", "{}")))
    }

    @Test
    fun `getVar 与 setVar 包级持久变量`() {
        load("p6", "tavern.onReply(function(t) { tavern.setVar('k', 'v-' + t); return tavern.getVar('k'); });")
        assertEquals("v-x", sandbox.callRegistered("p6", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
        assertEquals("v-x", memSettings["ext.p6.k"])
    }

    @Test
    fun `包间作用域隔离`() {
        load("a", "var shared = 'A'; tavern.onReply(function(t) { return shared; });")
        load("b", "tavern.onReply(function(t) { return typeof shared === 'undefined' ? 'clean' : shared; });")
        assertEquals("A", sandbox.callRegistered("a", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
        assertEquals("clean", sandbox.callRegistered("b", TavernHostApi.REG_ON_REPLY, arrayOf("x", "{}")))
    }

    @Test
    fun `on 事件仅支持白名单事件名`() {
        load(
            "p7",
            """
            tavern.on("message:sent", function(p) {});
            tavern.on("rm -rf /", function(p) {});
            """.trimIndent(),
        )
        assertTrue(sandbox.hasRegistration("p7", TavernHostApi.REG_EVENT + "message:sent"))
        assertTrue(sandbox.registrationsOf("p7").none { it.contains("rm -rf") })
    }
}
