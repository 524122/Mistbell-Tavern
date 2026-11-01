package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
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
 * 扩展包宿主钩子运行时（P3）JVM 单测：真 Rhino + 假 DAO + 临时目录。
 *
 * 覆盖：load/unload/reconcile（hash 变化自动重载）、PermissionBroker 裁剪
 * （未授权方法在 tavern 对象上不存在、调用即 TypeError）、钩子异常/死循环按
 * 「无产出」降级、prompt 注入按包 loadingOrder 排序。
 */
class ExtensionHookRuntimeManagerTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "exthook-test-${System.nanoTime()}")
    private val memSettings = mutableMapOf<String, String>()
    private val capability = FakeCapabilityHost()

    /** 能力宿主挂载点（模拟 ChatViewModel 挂载/摘除；null = 无聊天页在场） */
    private var activeCapability: HookCapabilityHost? = capability
    private val dao = FakeExtensionPackDao()

    private val manager =
        ExtensionHookRuntimeManager(
            daoProvider = { dao },
            filesRoot = root,
            settingsDaoProvider = { fakeSettingsDao },
            capabilityHostProvider = { activeCapability },
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

            override suspend fun deleteAll() {
                memSettings.clear()
            }
        }

    @After
    fun tearDown() {
        activeCapability = capability
        manager.sandbox.shutdown()
        root.deleteRecursively()
    }

    // ---- 装配辅助 ----

    private fun installPack(
        id: String,
        hooks: String,
        permissions: List<String>,
        loadingOrder: Int = 100,
    ): ExtensionPackEntity {
        val manifest =
            """{"id":"$id","name":"包$id","type":"extension","loadingOrder":$loadingOrder,""" +
                """"hostHooks":"hook.js","permissions":${permissions.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        ExtensionPackStore.extractContent(
            root,
            id,
            mapOf("manifest.json" to manifest, "hook.js" to hooks),
        )
        return ExtensionPackEntity(
            id = id,
            name = "包$id",
            author = "",
            version = "1",
            loadingOrder = loadingOrder,
            permissionsJson = ExtensionPackStore.encodePermissions(permissions),
            hasHooks = true,
            enabled = true,
            permissionsConfirmed = true,
            contentHash = "hash-$id",
            installedAt = "t",
        )
    }

    // ---- 生命周期 ----

    @Test
    fun `启用包加载后 onPrompt 可产出 消息读取走授权能力面`() {
        val pack =
            installPack(
                id = "census",
                hooks =
                    """
                    tavern.onPrompt(function (ctxJson) {
                      var messages = JSON.parse(tavern.getChatMessages(5));
                      return [{position: "SYSTEM_END", name: "census", content: "共" + messages.length + "条"}];
                    });
                    """.trimIndent(),
                permissions = listOf("messages:read", "prompt:inject"),
            )
        capability.messagesJson = """[{"id":"m1","role":"user","content":"hi"}]"""
        manager.onPacksChanged(listOf(pack))

        val injections = manager.promptInjections("{}")
        assertEquals(1, injections.size)
        assertEquals("census", injections[0].packId)
        assertEquals("共1条", injections[0].injections.single().content)
    }

    @Test
    fun `禁用删除后 unload 注入与回复处理全部失效`() {
        val pack =
            installPack(
                id = "gone",
                hooks =
                    """
                    tavern.onPrompt(function () { return [{content: "注入"}]; });
                    tavern.onReply(function (t) { return t + "!"; });
                    """.trimIndent(),
                permissions = listOf("prompt:inject", "reply:process"),
            )
        manager.onPacksChanged(listOf(pack))
        assertEquals(listOf("注入"), manager.promptInjections("{}").single().injections.map { it.content })
        assertEquals("x!", manager.processReplies("x", "{}"))

        manager.onPacksChanged(emptyList())
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))
    }

    @Test
    fun `覆盖安装 hook 源码变化时 reconcile 自动重载`() {
        val pack =
            installPack(
                id = "reload",
                hooks = "tavern.onPrompt(function () { return [{content: \"v1\"}]; });",
                permissions = listOf("prompt:inject"),
            )
        manager.onPacksChanged(listOf(pack))
        assertEquals("v1", manager.promptInjections("{}").single().injections.single().content)

        // 覆盖安装：同 id，hook.js 内容变化（实体 hash 同步变化；运行时以源码 hash 比对）
        installPack(
            id = "reload",
            hooks = "tavern.onPrompt(function () { return [{content: \"v2\"}]; });",
            permissions = listOf("prompt:inject"),
        )
        manager.onPacksChanged(listOf(pack))
        assertEquals("v2", manager.promptInjections("{}").single().injections.single().content)
    }

    @Test
    fun `无 hostHooks 声明的包不进入运行时`() {
        val plain =
            ExtensionPackEntity(
                id = "plain",
                name = "无钩子包",
                author = "",
                version = "1",
                permissionsJson = "[]",
                hasHooks = false,
                enabled = true,
                permissionsConfirmed = true,
                contentHash = "h",
                installedAt = "t",
            )
        ExtensionPackStore.extractContent(
            root,
            "plain",
            mapOf("manifest.json" to """{"id":"plain","name":"x"}"""),
        )
        manager.onPacksChanged(listOf(plain))
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))
    }

    // ---- PermissionBroker 裁剪 ----

    @Test
    fun `未授权方法在 tavern 对象上不存在 调用即 TypeError`() {
        val pack =
            installPack(
                id = "broker",
                hooks =
                    """
                    tavern.onPrompt(function () {
                      var probe = {
                        hasWrite: typeof tavern.setChatMessages,
                        hasGenerate: typeof tavern.triggerGenerate,
                        hasVar: typeof tavern.getVar,
                        hasRead: typeof tavern.getChatMessages,
                        hasLog: typeof tavern.log,
                        scopeLeak: typeof __tavernHost
                      };
                      try {
                        tavern.setChatMessages("[]");
                        probe.writeCall = "unexpected-ok";
                      } catch (e) {
                        probe.writeCall = (e instanceof TypeError || e.name === "TypeError") ? "typeerror" : String(e);
                      }
                      return [{content: JSON.stringify(probe)}];
                    });
                    """.trimIndent(),
                permissions = listOf("messages:read", "prompt:inject"),
            )
        manager.onPacksChanged(listOf(pack))

        val content = manager.promptInjections("{}").single().injections.single().content
        val probe =
            content
                .removePrefix("{")
                .removeSuffix("}")
                .split(",")
                .associate { pair -> pair.split(":").let { (k, v) -> k.trim('"') to v.trim('"') } }
        assertEquals("undefined", probe["hasWrite"])
        assertEquals("undefined", probe["hasGenerate"])
        assertEquals("undefined", probe["hasVar"])
        assertEquals("function", probe["hasRead"])
        assertEquals("function", probe["hasLog"])
        assertEquals("undefined", probe["scopeLeak"])
        assertEquals("typeerror", probe["writeCall"])
        assertTrue(capability.written.isEmpty())
    }

    @Test
    fun `未授权钩子方法注册即失败 包加载降级为无产出`() {
        val pack =
            installPack(
                id = "noperm",
                hooks = "tavern.onPrompt(function () { return [{content: \"越权\"}]; });",
                permissions = listOf("messages:read"),
            )
        manager.onPacksChanged(listOf(pack))
        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x", manager.processReplies("x", "{}"))
    }

    @Test
    fun `授权能力面全链路 写消息 生成控制 包级变量`() {
        val pack =
            installPack(
                id = "full",
                hooks =
                    """
                    tavern.onReply(function (t) {
                      tavern.setVar("runs", t);
                      tavern.setChatMessages('[{"id":"m1","content":"改写"}]');
                      tavern.createChatMessage('{"role":"user","content":"追加"}');
                      tavern.deleteChatMessages('["m2"]');
                      tavern.insertOrAssignVariables('{"锚点":"值"}');
                      var gen = tavern.triggerGenerate();
                      tavern.stopGeneration();
                      return t + "|" + gen + "|" + tavern.getVar("runs") + "|" + tavern.getVariables();
                    });
                    """.trimIndent(),
                permissions = listOf("reply:process", "messages:write", "generation:control", "variables"),
            )
        capability.mvuJson = """{"主角":"阿莉雅"}"""
        manager.onPacksChanged(listOf(pack))

        val out = manager.processReplies("1", "{}")
        assertEquals("""1|{"started":true}|1|{"主角":"阿莉雅"}""", out)
        assertEquals(4, capability.written.size)
        assertEquals("""[{"id":"m1","content":"改写"}]""", capability.written[0])
        assertEquals("""{"role":"user","content":"追加"}""", capability.written[1])
        assertEquals("""["m2"]""", capability.written[2])
        assertEquals("""{"锚点":"值"}""", capability.written[3])
        assertEquals(1, capability.generated)
        assertEquals(1, capability.stopped)
        assertEquals("1", memSettings["ext.full.runs"])
    }

    @Test
    fun `无聊天页在场时读侧空数据 写侧契约错误`() {
        activeCapability = null
        val pack =
            installPack(
                id = "idle",
                hooks =
                    """
                    tavern.onReply(function (t) {
                      return JSON.stringify({
                        msgs: tavern.getChatMessages(5),
                        last: tavern.getLastMessageId(),
                        vars: tavern.getVariables(),
                        write: tavern.setChatMessages("[]"),
                        generate: tavern.triggerGenerate()
                      });
                    });
                    """.trimIndent(),
                permissions =
                    listOf("reply:process", "messages:read", "messages:write", "generation:control", "variables"),
            )
        manager.onPacksChanged(listOf(pack))

        val out = manager.processReplies("x", "{}")
        // glue 返回 JS 字符串，JSON.stringify 后值带引号
        assertTrue(out.contains("\"msgs\":\"[]\""))
        assertTrue(out.contains("\"last\":\"\""))
        assertTrue(out.contains("\"vars\":\"{}\""))
        assertTrue(out.contains("当前无会话"))
        assertTrue(capability.written.isEmpty())
        assertEquals(0, capability.generated)
    }

    // ---- 钩子降级 ----

    @Test
    fun `钩子异常与死循环按无产出降级 且不毒死沙箱线程`() {
        val pack =
            installPack(
                id = "degrade",
                hooks =
                    """
                    tavern.onPrompt(function () { throw new Error("炸了"); });
                    tavern.onReply(function () { while (true) {} });
                    """.trimIndent(),
                permissions = listOf("prompt:inject", "reply:process"),
            )
        val healthy =
            installPack(
                id = "healthy",
                hooks = "tavern.onReply(function (t) { return t + \"+\"; });",
                permissions = listOf("reply:process"),
                loadingOrder = 200,
            )
        manager.onPacksChanged(listOf(pack, healthy))

        assertTrue(manager.promptInjections("{}").isEmpty())
        assertEquals("x+", manager.processReplies("x", "{}"))
    }

    @Test
    fun `onPrompt 返回非法 JSON 按无产出处理`() {
        val pack =
            installPack(
                id = "badjson",
                hooks = "tavern.onPrompt(function () { return \"不是数组\"; });",
                permissions = listOf("prompt:inject"),
            )
        manager.onPacksChanged(listOf(pack))
        assertTrue(manager.promptInjections("{}").isEmpty())
    }

    // ---- 执行顺序 ----

    @Test
    fun `prompt 注入按包 loadingOrder 升序 同序按 id 稳定`() {
        val late =
            installPack(
                id = "late",
                hooks = "tavern.onPrompt(function () { return [{content: \"L\"}]; });",
                permissions = listOf("prompt:inject"),
                loadingOrder = 200,
            )
        val early =
            installPack(
                id = "early",
                hooks = "tavern.onPrompt(function () { return [{content: \"E\"}]; });",
                permissions = listOf("prompt:inject"),
                loadingOrder = 50,
            )
        val tieA =
            installPack(
                id = "a-tie",
                hooks = "tavern.onPrompt(function () { return [{content: \"A\"}]; });",
                permissions = listOf("prompt:inject"),
                loadingOrder = 50,
            )
        manager.onPacksChanged(listOf(late, early, tieA))

        val order = manager.promptInjections("{}").map { it.packId }
        assertEquals(listOf("a-tie", "early", "late"), order)
    }

    // ---- 测试假件 ----

    private class FakeCapabilityHost : HookCapabilityHost {
        var messagesJson: String = "[]"
        var lastId: String = ""
        var mvuJson: String = "{}"
        val written = mutableListOf<String>()
        var generated = 0
        var stopped = 0

        override fun getChatMessagesJson(limit: Int): String = messagesJson

        override fun getLastMessageId(): String = lastId

        override fun setChatMessagesJson(payloadJson: String): String {
            written += payloadJson
            return """{"updated":1}"""
        }

        override fun createChatMessageJson(payloadJson: String): String {
            written += payloadJson
            return """{"id":"new-1"}"""
        }

        override fun deleteChatMessagesJson(idsJson: String): String {
            written += idsJson
            return """{"deleted":1}"""
        }

        override fun getMvuVariablesJson(): String = mvuJson

        override fun insertMvuVariablesJson(json: String) {
            written += json
        }

        override fun triggerGenerateJson(): String {
            generated++
            return """{"started":true}"""
        }

        override fun stopGeneration() {
            stopped++
        }
    }

    private class FakeExtensionPackDao : ExtensionPackDao {
        val packs = MutableStateFlow<List<ExtensionPackEntity>>(emptyList())

        override fun observeAll(): Flow<List<ExtensionPackEntity>> = packs

        override suspend fun getById(id: String): ExtensionPackEntity? = packs.value.firstOrNull { it.id == id }

        override fun observeEnabled(): Flow<List<ExtensionPackEntity>> {
            return packs.map { list -> list.filter { it.enabled } }
        }

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
