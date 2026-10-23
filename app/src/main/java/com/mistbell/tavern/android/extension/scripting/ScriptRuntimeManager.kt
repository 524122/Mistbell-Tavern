package com.mistbell.tavern.android.extension.scripting

import android.util.Log
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.repository.EnabledRulePack
import com.mistbell.tavern.android.data.repository.RulePackSource
import com.mistbell.tavern.android.data.repository.currentPacks
import com.mistbell.tavern.android.extension.ExtensionRegistry
import com.mistbell.tavern.android.extension.SlashCommandContext
import com.mistbell.tavern.android.extension.SlashCommandHandler
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import com.mistbell.tavern.android.extension.TavernExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 脚本包运行时管理器：缓存驱动脚本包（manifest.script 声明）的加载/卸载生命周期，
 * 并向钩子链路提供统一调用口。
 *
 * - 包启用/覆盖安装（源码变化）→ 初始化运行时；禁用/删除 → 拆除（注销动态斜杠命令）
 * - TavernEventBus 事件转发为 JS `tavern.on(event, fn)` 调用
 * - 全部 JS 执行收敛在 [RhinoSandbox] 的单线程内，本类只做编排
 */
@Suppress("TooManyFunctions") // 生命周期 + 钩子分发 + 事件转发是同一职责的内聚拆分
class ScriptRuntimeManager(
    private val source: RulePackSource,
    private val settingsDaoProvider: () -> SettingsDao,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sandbox = RhinoSandbox()

    /** packId → 已加载脚本源码的 hash（覆盖安装源码变化时触发重载） */
    private val loadedScripts = mutableMapOf<String, Int>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            source.packs.collect { packs ->
                reconcile(packs.orEmpty().filter { it.content.scriptSource != null })
            }
        }
        scope.launch {
            TavernEventBus.events.collect { dispatchEvent(it) }
        }
    }

    // ---- 生命周期 ----

    @Synchronized
    private fun reconcile(scriptedPacks: List<EnabledRulePack>) {
        val want = scriptedPacks.associateBy { it.id }
        (loadedScripts.keys - want.keys).forEach { unload(it) }
        want.values.forEach { pack ->
            val sourceHash = pack.content.scriptSource.hashCode()
            if (loadedScripts[pack.id] != sourceHash) {
                unload(pack.id)
                load(pack, sourceHash)
            }
        }
    }

    private fun load(
        pack: EnabledRulePack,
        sourceHash: Int,
    ) {
        val result =
            sandbox.loadPack(
                pack.id,
                pack.content.scriptSource ?: return,
                TavernHostApi(pack.id, sandbox, settingsDaoProvider),
            )
        when (result) {
            is RhinoSandbox.SandboxResult.Ok -> {
                loadedScripts[pack.id] = sourceHash
                // 动态斜杠命令：init 评估期间脚本经 tavern.registerCommand 注册的命令接入注册表
                sandbox.registrationsOf(pack.id)
                    .filter { it.startsWith(TavernHostApi.REG_COMMAND) }
                    .forEach { registration ->
                        val commandName = registration.removePrefix(TavernHostApi.REG_COMMAND)
                        ExtensionRegistry.register(ScriptSlashCommand(pack.id, pack.name, commandName, this))
                    }
                Log.d(TAG, "脚本包已加载：${pack.id}")
            }
            is RhinoSandbox.SandboxResult.Failure ->
                Log.w(TAG, "脚本包加载失败（已跳过）：${pack.id}：${result.reason}")
        }
    }

    private fun unload(packId: String) {
        sandbox.registrationsOf(packId)
            .filter { it.startsWith(TavernHostApi.REG_COMMAND) }
            .forEach { registration ->
                val commandName = registration.removePrefix(TavernHostApi.REG_COMMAND)
                ExtensionRegistry.unregister(ScriptSlashCommand.extensionId(packId, commandName))
            }
        sandbox.unloadPack(packId)
        loadedScripts.remove(packId)
        Log.d(TAG, "脚本包已卸载：$packId")
    }

    // ---- 钩子调用口 ----

    /** onPrompt 注入项（JS 返回 JSON 数组的元素） */
    @Serializable
    data class ScriptInjection(
        val position: String = "SYSTEM_END",
        val content: String,
        val name: String = "script",
    )

    /** 全部启用脚本包的提示词注入：packName → 注入块列表 */
    fun promptInjections(ctxJson: String): List<Pair<String, List<ScriptInjection>>> =
        packsWithHook(TavernHostApi.REG_ON_PROMPT).mapNotNull { (packId, packName) ->
            val resultJson = sandbox.callRegistered(packId, TavernHostApi.REG_ON_PROMPT, arrayOf(ctxJson))
            val injections =
                resultJson?.let { parseInjections(packId, it) }.orEmpty()
            if (injections.isEmpty()) null else packName to injections
        }

    /** 链式调用全部启用脚本包的 onReply。 */
    fun processReplies(
        initial: String,
        ctxJson: String,
    ): String {
        var result = initial
        packsWithHook(TavernHostApi.REG_ON_REPLY).forEach { (packId, _) ->
            val out = sandbox.callRegistered(packId, TavernHostApi.REG_ON_REPLY, arrayOf(result, ctxJson))
            if (!out.isNullOrEmpty()) result = out
        }
        return result
    }

    /** 宏调用：第一个注册了该宏的包承接；无注册返回 null。 */
    fun callMacro(
        name: String,
        ctxJson: String,
    ): String? {
        val registration = TavernHostApi.REG_MACRO + name.lowercase()
        val packId = loadedScripts.keys.firstOrNull { sandbox.hasRegistration(it, registration) }
        return packId?.let { sandbox.callRegistered(it, registration, arrayOf(ctxJson)) }
    }

    /** 斜杠命令调用（由动态注册的 ScriptSlashCommand 转发）。 */
    fun callCommand(
        packId: String,
        commandName: String,
        args: String,
        ctxJson: String,
    ): String =
        sandbox.callRegistered(packId, TavernHostApi.REG_COMMAND + commandName, arrayOf(args, ctxJson))
            ?: "命令 /$commandName 无返回（执行失败或未实现）"

    // ---- 事件转发 ----

    private fun dispatchEvent(event: TavernEvent) {
        val (eventName, payload) =
            when (event) {
                is TavernEvent.MessageSent -> "message:sent" to eventPayload(event.sessionId)
                is TavernEvent.MessageReceived -> "message:received" to eventPayload(event.sessionId)
                is TavernEvent.GenerationStarted -> "generation:started" to eventPayload(event.sessionId)
                is TavernEvent.GenerationEnded -> "generation:ended" to eventPayload(event.sessionId)
                is TavernEvent.ChatChanged -> "chat:changed" to eventPayload(event.sessionId, event.characterId)
                is TavernEvent.VariablesUpdated ->
                    "variables:updated" to "{\"sessionId\":${jsonString(event.sessionId)},\"version\":${event.version}}"
            }
        packsWithHook(TavernHostApi.REG_EVENT + eventName).forEach { (packId, _) ->
            sandbox.callRegistered(packId, TavernHostApi.REG_EVENT + eventName, arrayOf(payload))
        }
    }

    private fun eventPayload(
        sessionId: String,
        characterId: String? = null,
    ): String =
        buildString {
            append("{\"sessionId\":").append(jsonString(sessionId))
            if (characterId != null) {
                append(",\"characterId\":").append(jsonString(characterId))
            }
            append("}")
        }

    private fun packsWithHook(registration: String): List<Pair<String, String>> =
        loadedScripts.keys
            .filter { sandbox.hasRegistration(it, registration) }
            .mapNotNull { packId -> packNameOf(packId)?.let { packId to it } }

    private fun packNameOf(packId: String): String? = source.currentPacks().firstOrNull { it.id == packId }?.name

    private fun parseInjections(
        packId: String,
        jsonText: String,
    ): List<ScriptInjection> =
        try {
            json.decodeFromString<List<ScriptInjection>>(jsonText)
                .filter { it.content.isNotBlank() }
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.w(TAG, "onPrompt 返回值无法解析为注入数组（包 $packId）：${e.message}")
            emptyList()
        }

    /** 脚本注册的动态斜杠命令（禁用/卸载时从注册表摘除） */
    private class ScriptSlashCommand(
        private val packId: String,
        private val packName: String,
        private val commandName: String,
        private val manager: ScriptRuntimeManager,
    ) : TavernExtension, SlashCommandHandler {
        override val id: String = extensionId(packId, commandName)
        override val name: String = commandName
        override val loadingOrder: Int = LOADING_ORDER
        override val description: String = "来自脚本包「$packName」"

        override suspend fun execute(
            args: String,
            ctx: SlashCommandContext,
        ): String = manager.callCommand(packId, commandName, args, ctxJson(ctx))

        companion object {
            private const val LOADING_ORDER = 300

            fun extensionId(
                packId: String,
                commandName: String,
            ): String = "script-$packId-$commandName"
        }
    }

    companion object {
        private const val TAG = "ScriptRuntimeManager"

        /** JSON 字符串字面量转义（宿主出入参用手工拼，避免 Rhino 对象映射） */
        fun jsonString(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .let { "\"$it\"" }

        /** 钩子上下文 JSON（onPrompt/onReply/命令共用形态） */
        fun ctxJson(
            sessionId: String,
            char: String = "",
            user: String = "",
        ): String =
            "{\"sessionId\":${jsonString(sessionId)}," +
                "\"char\":${jsonString(char)}," +
                "\"user\":${jsonString(user)}}"

        fun ctxJson(ctx: SlashCommandContext): String = ctxJson(ctx.sessionId)
    }
}
