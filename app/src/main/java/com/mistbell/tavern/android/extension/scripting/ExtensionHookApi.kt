package com.mistbell.tavern.android.extension.scripting

import android.util.Log
import com.mistbell.tavern.android.ui.chat.htmlui.BridgeMessageContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.Function

/**
 * 扩展包宿主钩子的 Java 宿主对象（每包一个实例）：实现全部能力方法，但**暴露面由
 * PermissionBroker 决定**——RhinoSandbox 按授权清单经 tavern-broker 引导片只把已授权
 * 方法绑定进包 scope 的 `tavern` 对象（未授权方法在对象上不存在，调用即 TypeError）。
 * 本类是能力的实现侧，不是权限的执行侧；权限的唯一权威是 [PermissionBroker.grantedMethods]。
 *
 * 与规则包 [TavernHostApi] 的差异：
 * - 日志 TAG `JSEXT·<packId>`（扩展包独立前缀）
 * - 新增消息读/写、生成控制、MVU 变量树方法（走 [HookCapabilityHost]，即 ChatViewModel
 *   htmlBridgeHost 同款实现——上限契约原样沿用，无新引擎能力）
 * - 入参出参仍一律 JSON 字符串；返回值在 broker 引导片里 String() 归一为 JS 字符串
 *   （规则包侧需脚本自行 String() 转换，这里是已知差异，见 docs/SCRIPTING.md）
 */
@Suppress("TooManyFunctions") // 宿主对象即能力白名单契约，逐个方法对应一类能力（桥侧同注释）
class ExtensionHookApi(
    private val packId: String,
    private val sandbox: RhinoSandbox,
    private val settingsDaoProvider: () -> com.mistbell.tavern.android.data.local.dao.SettingsDao,
    private val capabilityHostProvider: () -> HookCapabilityHost?,
) {
    // ---- 诊断日志（恒可用） ----

    fun log(message: Any?) {
        Log.d(TAG_PREFIX + packId, message?.toString() ?: "null")
    }

    // ---- 钩子注册（初始化评估期调用；是否可见由 broker 决定） ----

    fun onPrompt(fn: Function) = sandbox.register(packId, TavernHostApi.REG_ON_PROMPT, fn)

    fun onReply(fn: Function) = sandbox.register(packId, TavernHostApi.REG_ON_REPLY, fn)

    fun on(
        event: String,
        fn: Function,
    ) {
        val normalized = event.trim().lowercase()
        if (normalized !in TavernHostApi.SUPPORTED_EVENTS) {
            Log.w(TAG_PREFIX + packId, "on: 不支持的事件「$event」（支持：${TavernHostApi.SUPPORTED_EVENTS}）")
            return
        }
        sandbox.register(packId, "${TavernHostApi.REG_EVENT}$normalized", fn)
    }

    // ---- 包级持久变量（variables；键风格与规则包一致） ----

    fun getVar(key: String): String =
        runBlocking(Dispatchers.IO) {
            settingsDaoProvider().getValue(varKey(key)) ?: ""
        }

    fun setVar(
        key: String,
        value: String,
    ) {
        runBlocking(Dispatchers.IO) {
            settingsDaoProvider().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(varKey(key), value),
            )
        }
    }

    private fun varKey(key: String): String = "ext.$packId.${key.trim()}"

    // ---- MVU 变量树（variables）----

    fun getVariables(): String = capabilityHostProvider()?.getMvuVariablesJson() ?: EMPTY_VARIABLES

    fun insertOrAssignVariables(json: String) {
        capabilityHostProvider()?.insertMvuVariablesJson(json)
    }

    // ---- 消息读（messages:read）----

    fun getChatMessages(limit: Int): String {
        // 块体而非表达式体：同行写法超过 120 列（detekt MaxLineLength 与 ktlint
        // function-signature 对表达式体同一行结论相反，块体两侧都满足）
        val messages = capabilityHostProvider()?.getChatMessagesJson(limit.coerceIn(1, MAX_MESSAGES_SLICE))
        return messages ?: "[]"
    }

    fun getLastMessageId(): String = capabilityHostProvider()?.getLastMessageId().orEmpty()

    // ---- 消息写（messages:write；契约与上限走 BridgeMessageContract 同一实现）----

    fun setChatMessages(payloadJson: String): String =
        capabilityHostProvider()?.setChatMessagesJson(payloadJson) ?: BridgeMessageContract.error(NO_SESSION)

    fun createChatMessage(payloadJson: String): String =
        capabilityHostProvider()?.createChatMessageJson(payloadJson) ?: BridgeMessageContract.error(NO_SESSION)

    fun deleteChatMessages(idsJson: String): String =
        capabilityHostProvider()?.deleteChatMessagesJson(idsJson) ?: BridgeMessageContract.error(NO_SESSION)

    // ---- 生成控制（generation:control）----

    fun triggerGenerate(): String {
        // 块体而非表达式体：同行写法超过 120 列（理由同 getChatMessages）
        return capabilityHostProvider()?.triggerGenerateJson() ?: BridgeMessageContract.error(NO_SESSION)
    }

    fun stopGeneration() {
        capabilityHostProvider()?.stopGeneration()
    }

    companion object {
        private const val TAG_PREFIX = "JSEXT·"
        private const val MAX_MESSAGES_SLICE = 50
        private const val EMPTY_VARIABLES = "{}"
        private const val NO_SESSION = "当前无会话"
    }
}
