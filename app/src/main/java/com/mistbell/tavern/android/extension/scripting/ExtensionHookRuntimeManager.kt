package com.mistbell.tavern.android.extension.scripting

import android.util.Log
import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 扩展包宿主钩子运行时（P3，EXTENSION_PLATFORM_DESIGN.md §4/§7）：把 P1 落盘的
 * manifest.hostHooks 从制品变为执行——观察 extension_packs（enabled 且 hasHooks），
 * 完全对标规则包 [ScriptRuntimeManager] 的生命周期语义：
 *
 * - 启用 → 从 filesDir/extensions/<id>/ 读 hook 源码并初始化运行时
 * - 禁用/删除 → 拆除（prompt 注入、回复处理、事件订阅、动态注册全部失效）
 * - 覆盖安装（源码 hash 变化）→ 自动重载（reconcile 同款 hash 比对）
 *
 * 与规则包的关键差异在注入侧：`tavern` 宿主对象经 [PermissionBroker] 按包授权清单
 * 逐方法装配（[RhinoSandbox.loadPack] 的 grantedMethods 路径）——未授权方法在对象上
 * 不存在，而非运行时报错。Rhino 沙箱安全机器全部复用（ClassShutter 白名单、
 * 50 万初始化 / 20 万单次调用指令预算、3s 墙钟、每包独立 scope、JSON 桥）。
 *
 * 官方内置包 tavernhelper-compat 无 hostHooks（hasHooks=false），不进入本运行时。
 * 全部 JS 执行收敛在 [RhinoSandbox] 的单线程内，本类只做编排；钩子异常/超时由沙箱
 * 降级为 null，消费方按「该钩子无产出」继续（与规则包一致，绝不掀翻聊天链路）。
 */
@Suppress("TooManyFunctions") // 生命周期 + 钩子分发 + 事件转发是同一职责的内聚拆分（ScriptRuntimeManager 同型）
class ExtensionHookRuntimeManager(
    private val daoProvider: () -> ExtensionPackDao,
    private val filesRoot: File,
    private val settingsDaoProvider: () -> com.mistbell.tavern.android.data.local.dao.SettingsDao,
    private val capabilityHostProvider: () -> HookCapabilityHost?,
    private val scope: CoroutineScope,
    internal val sandbox: RhinoSandbox = RhinoSandbox(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** packId → 已加载 hook 源码 hash（覆盖安装源码变化时触发重载） */
    private val loadedHooks = mutableMapOf<String, Int>()

    /** 最近一次发射的启用包快照（名称/loadingOrder 元数据；prompt 注入按包 loadingOrder 排序） */
    @Volatile
    private var enabledPacks: List<ExtensionPackEntity> = emptyList()

    private var started = false

    /** 启动常驻收集（幂等）：包启停/覆盖安装/删除经 Room Flow 推到 [onPacksChanged] */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            daoProvider().observeEnabled().collect { packs -> onPacksChanged(packs) }
        }
        scope.launch {
            TavernEventBus.events.collect { dispatchEvent(it) }
        }
    }

    // ---- 生命周期 ----

    /** Flow 发射入口（可测）：只关心声明了 hostHooks 的启用包；对齐 reconcile 语义。 */
    @Synchronized
    internal fun onPacksChanged(packs: List<ExtensionPackEntity>) {
        enabledPacks = packs
        val want = packs.filter { it.hasHooks }.associateBy { it.id }
        (loadedHooks.keys - want.keys).forEach(::unload)
        want.values.forEach { pack ->
            val source = readHookSourceFromDisk(filesRoot, pack)
            if (source == null) {
                // 目录损坏/缺制品：跳过该包（加载失败不缓存 hash，下次发射自动重试）
                if (pack.id in loadedHooks) unload(pack.id)
                return@forEach
            }
            val sourceHash = source.hashCode()
            if (loadedHooks[pack.id] != sourceHash) {
                unload(pack.id)
                load(pack, source, sourceHash)
            }
        }
    }

    private fun load(
        pack: ExtensionPackEntity,
        source: String,
        sourceHash: Int,
    ) {
        val granted = PermissionBroker.grantedMethods(ExtensionPackStore.decodePermissions(pack.permissionsJson))
        val api = ExtensionHookApi(pack.id, sandbox, settingsDaoProvider, capabilityHostProvider)
        val result = sandbox.loadPack(pack.id, source, api, granted)
        when (result) {
            is RhinoSandbox.SandboxResult.Ok -> {
                loadedHooks[pack.id] = sourceHash
                Log.d(TAG, "扩展包宿主钩子已加载：${pack.id}（授权方法：$granted）")
            }
            is RhinoSandbox.SandboxResult.Failure ->
                Log.w(TAG, "扩展包宿主钩子加载失败（已跳过）：${pack.id}：${result.reason}")
        }
    }

    private fun unload(packId: String) {
        if (packId !in loadedHooks) return
        sandbox.unloadPack(packId)
        loadedHooks.remove(packId)
        Log.d(TAG, "扩展包宿主钩子已卸载：$packId")
    }

    // ---- 钩子调用口 ----

    /** 单个启用包的 prompt 注入产出 */
    data class HookedInjections(
        val packId: String,
        val packName: String,
        val injections: List<ScriptInjection>,
    )

    /** onPrompt 注入项（JS 返回 JSON 数组的元素；五档 position 落点由适配层复用 CustomPromptExtension） */
    @Serializable
    data class ScriptInjection(
        val position: String = "SYSTEM_END",
        val content: String,
        val name: String = "hook",
    )

    /**
     * 全部启用钩子包的提示词注入：**按包 loadingOrder 升序**（同序按 id 稳定）依次调用。
     * 返回空列表 = 无产出（未注册/异常/超时/返回不可解析，一律降级）。
     */
    fun promptInjections(ctxJson: String): List<HookedInjections> =
        orderedHookedPacks()
            .filter { sandbox.hasRegistration(it.id, TavernHostApi.REG_ON_PROMPT) }
            .mapNotNull { pack ->
                val resultJson = sandbox.callRegistered(pack.id, TavernHostApi.REG_ON_PROMPT, arrayOf(ctxJson))
                val injections = resultJson?.let { parseInjections(pack.id, it) }.orEmpty()
                if (injections.isEmpty()) null else HookedInjections(pack.id, pack.name, injections)
            }

    /** 链式调用全部启用钩子包的 onReply（按包 loadingOrder 升序）；无产出保持原文。 */
    fun processReplies(
        initial: String,
        ctxJson: String,
    ): String {
        var result = initial
        orderedHookedPacks()
            .filter { sandbox.hasRegistration(it.id, TavernHostApi.REG_ON_REPLY) }
            .forEach { pack ->
                val out = sandbox.callRegistered(pack.id, TavernHostApi.REG_ON_REPLY, arrayOf(result, ctxJson))
                if (!out.isNullOrEmpty()) result = out
            }
        return result
    }

    /** 包级事件转发（六事件白名单内的注册经 tavern.on 建立，禁用即随 scope 一起失效） */
    private fun dispatchEvent(event: TavernEvent) {
        val registration = TavernHostApi.REG_EVENT + TavernEventWire.nameOf(event)
        orderedHookedPacks()
            .filter { sandbox.hasRegistration(it.id, registration) }
            .forEach { pack ->
                sandbox.callRegistered(pack.id, registration, arrayOf(TavernEventWire.payloadOf(event)))
            }
    }

    /** 启用且已加载钩子的包，按 loadingOrder 升序（同序按 id 稳定） */
    private fun orderedHookedPacks(): List<ExtensionPackEntity> =
        enabledPacks
            .filter { it.id in loadedHooks }
            .sortedWith(compareBy({ it.loadingOrder }, { it.id }))

    private fun parseInjections(
        packId: String,
        jsonText: String,
    ): List<ScriptInjection> =
        try {
            json.decodeFromString<List<ScriptInjection>>(jsonText)
                .filter { it.content.isNotBlank() }
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.w(TAG, "onPrompt 返回值无法解析为注入数组（扩展包 $packId）：${e.message}")
            emptyList()
        }

    companion object {
        private const val TAG = "ExtensionHookRuntime"

        /**
         * 从 filesDir 读单包 hook 源码（注入 readHooks 的生产实现）：
         * 包目录内 manifest.json 保存了 hostHooks 文件名，缺文件/解析失败/未声明一律
         * 返回 null（跳过该包，绝不让坏包拖垮运行时）。与 ExtensionPreloadProvider.readPreloadFromDisk 同型。
         */
        internal fun readHookSourceFromDisk(
            filesRoot: File,
            pack: ExtensionPackEntity,
        ): String? =
            runCatching {
                val dir = ExtensionPackStore.packDir(filesRoot, pack.id)
                val manifest =
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<RulePackManifest>(
                            File(dir, RulePackParser.MANIFEST_FILE).readText(),
                        )
                manifest.hostHooks
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name -> File(dir, name).takeIf(File::isFile)?.readText() }
            }.onFailure {
                Log.w(TAG, "扩展包「${pack.id}」hostHooks 读取失败，本包跳过加载：${it.message}")
            }.getOrNull()
    }
}
