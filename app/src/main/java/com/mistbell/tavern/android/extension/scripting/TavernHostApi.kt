package com.mistbell.tavern.android.extension.scripting

import android.util.Log
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.Function

/**
 * 注入脚本全局的 `tavern` 宿主对象（每包一个实例）。
 *
 * JS 侧 API（详见 docs/SCRIPTING.md）：
 * - tavern.log(msg)
 * - tavern.onPrompt(fn(ctxJson))      — 提示词注入，fn 返回 [{position, content, name?}] 或 null
 * - tavern.onReply(fn(text, ctxJson)) — 回复处理，fn 返回新文本
 * - tavern.registerCommand(name, fn(args, ctxJson))
 * - tavern.registerMacro(name, fn(ctxJson))
 * - tavern.on(event, fn(payloadJson)) — message:sent / message:received /
 *   generation:started / generation:ended / chat:changed
 * - tavern.getVar(key) / tavern.setVar(key, value) — 包级持久变量（settings 表 ext.<packId>.<key>）
 *
 * 入参出参一律 JSON 字符串，JS 侧用 JSON.parse / JSON.stringify。
 */
class TavernHostApi(
    private val packId: String,
    private val sandbox: RhinoSandbox,
    private val settingsDaoProvider: () -> SettingsDao,
) {
    fun log(message: Any?) {
        Log.d(TAG_PREFIX + packId, message?.toString() ?: "null")
    }

    // ---- 钩子注册（初始化评估期调用） ----

    fun onPrompt(fn: Function) = sandbox.register(packId, REG_ON_PROMPT, fn)

    fun onReply(fn: Function) = sandbox.register(packId, REG_ON_REPLY, fn)

    fun registerCommand(
        name: String,
        fn: Function,
    ) {
        val normalized = name.trim().removePrefix("/").lowercase()
        if (normalized.isEmpty()) {
            Log.w(TAG_PREFIX + packId, "registerCommand: 命令名为空，已忽略")
            return
        }
        sandbox.register(packId, "$REG_COMMAND$normalized", fn)
    }

    fun registerMacro(
        name: String,
        fn: Function,
    ) {
        val normalized = name.trim().lowercase()
        if (normalized.isEmpty()) {
            Log.w(TAG_PREFIX + packId, "registerMacro: 宏名为空，已忽略")
            return
        }
        sandbox.register(packId, "$REG_MACRO$normalized", fn)
    }

    fun on(
        event: String,
        fn: Function,
    ) {
        val normalized = event.trim().lowercase()
        if (normalized !in SUPPORTED_EVENTS) {
            Log.w(TAG_PREFIX + packId, "on: 不支持的事件「$event」（支持：$SUPPORTED_EVENTS）")
            return
        }
        sandbox.register(packId, "$REG_EVENT$normalized", fn)
    }

    // ---- 包级持久变量 ----

    fun getVar(key: String): String =
        runBlocking {
            settingsDaoProvider().getValue(varKey(key)) ?: ""
        }

    fun setVar(
        key: String,
        value: String,
    ) {
        runBlocking {
            settingsDaoProvider().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(varKey(key), value),
            )
        }
    }

    private fun varKey(key: String): String = "ext.$packId.${key.trim()}"

    companion object {
        private const val TAG_PREFIX = "JS·"

        internal const val REG_ON_PROMPT = "hook:onPrompt"
        internal const val REG_ON_REPLY = "hook:onReply"
        internal const val REG_COMMAND = "cmd:"
        internal const val REG_MACRO = "macro:"
        internal const val REG_EVENT = "event:"

        val SUPPORTED_EVENTS =
            setOf(
                "message:sent",
                "message:received",
                "generation:started",
                "generation:ended",
                "chat:changed",
                "variables:updated",
            )
    }
}
