package com.mistbell.tavern.android.ui.chat.htmlui

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * HTML 界面与宿主的桥：注入 WebView 的唯一对象（window.TavernBridge）。
 *
 * 安全红线：
 * - 只有本类的白名单方法对 JS 可见；绝不注入宿主集合/运行时/评估器
 * - 变量会话级隔离（宿主侧 ext.hui.<sessionId>.* 键）
 * - 方法跑在 WebView 私有线程：数据读写全部经 [HtmlBridgeHost] 回到宿主协程
 *
 * JS 侧门面（页面加载时由 HtmlBubble 注入封装为 window.TavernHelper 风格 API）：
 * - getVar(key) / setVar(key, value)（同步返回值，宿主桥阻塞实现）
 * - getChatMessages(limit)（JSON 数组字符串）
 * - sendMessage(text)
 * - log(msg)
 * - showToast(message)（长度受限的用户提示）
 * - eventEmit(type, payloadJson)
 * - setChatMessages / createChatMessage / deleteChatMessages / getLastMessageId（消息写 API）
 * - triggerGenerate()（返回 JSON：{started:true} 或 {error:...}）/ stopGeneration()
 * - substituteParams(str)（宏替换）
 */
@Suppress("TooManyFunctions") // 桥方法即白名单契约，逐个对应一类宿主能力
class TavernJsBridge(
    private val host: HtmlBridgeHost,
    private val onHeightReport: (Int) -> Unit,
    private val onScrollableChange: (Boolean) -> Unit = {},
) {
    @JavascriptInterface
    fun log(message: String?) {
        Log.d(TAG, message ?: "null")
    }

    @JavascriptInterface
    fun showToast(message: String) {
        if (message.isNotBlank()) host.showToast(message.take(MAX_TOAST_LENGTH))
    }

    @JavascriptInterface
    fun getVar(key: String): String = host.getVar(key)

    @JavascriptInterface
    fun setVar(
        key: String,
        value: String,
    ) {
        host.setVar(key, value)
    }

    @JavascriptInterface
    fun getChatMessages(limit: Int): String = host.getChatMessagesJson(limit.coerceIn(1, MAX_MESSAGES_SLICE))

    @JavascriptInterface
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        host.sendMessage(text.take(MAX_SEND_LENGTH))
    }

    @JavascriptInterface
    fun eventEmit(
        type: String,
        payload: String?,
    ) {
        host.emitEvent(type, payload.orEmpty())
    }

    // ---- MVU 兼容层（净室）：会话级 stat_data 读写 + 命令触发 ----

    @JavascriptInterface
    fun getMvuVariables(): String = host.getMvuVariablesJson()

    @JavascriptInterface
    fun insertMvuVariables(json: String) {
        host.insertMvuVariablesJson(json)
    }

    @JavascriptInterface
    fun triggerSlash(command: String) {
        if (command.isBlank()) return
        host.triggerSlash(command.take(MAX_SEND_LENGTH))
    }

    /** 生效正则管线快照（酒馆助手 getTavernRegexes 契约：全局先、卡级后，数组序即执行序） */
    @JavascriptInterface
    fun getTavernRegexes(): String = host.getTavernRegexesJson()

    // ---- 酒馆助手消息写 API：id 寻址（净室差异，见 BridgeMessageContract 注释） ----

    /** setChatMessages([{id, content, thinking?}]) → {updated:N}（部分 id 不存在不计入） */
    @JavascriptInterface
    fun setChatMessages(payloadJson: String): String = host.setChatMessagesJson(payloadJson)

    /** createChatMessage({role, content}) → {id:...}（会话末尾追加，不经 LLM 与回滚链） */
    @JavascriptInterface
    fun createChatMessage(payloadJson: String): String = host.createChatMessageJson(payloadJson)

    /** deleteChatMessages([id,...]) → {deleted:N} */
    @JavascriptInterface
    fun deleteChatMessages(idsJson: String): String = host.deleteChatMessagesJson(idsJson)

    /** 最新一条消息的 id（空串 = 无消息） */
    @JavascriptInterface
    fun getLastMessageId(): String = host.getLastMessageId()

    // ---- 酒馆助手生成控制与宏替换（generate / stopGeneration / substituteParams） ----

    /**
     * generate 兼容入口：有会话则启动一次生成（不插入用户消息），同步返回
     * {"started":true}；无会话返回契约错误 JSON（门面 JSON.parse 后得到 {error:...}）。
     */
    @JavascriptInterface
    fun triggerGenerate(): String = host.triggerGenerateJson()

    /** 停止当前生成（与输入框停止同一通道）；无生成任务时空操作。 */
    @JavascriptInterface
    fun stopGeneration() {
        host.stopGeneration()
    }

    /** substituteParams：{{char}}/{{user}} 等宏替换；输入超限原样返回（契约见 HtmlFacadeContract） */
    @JavascriptInterface
    fun substituteParams(input: String): String = host.substituteParams(input)

    /** 页面高度回报（气泡据此调整 WebView 高度） */
    @JavascriptInterface
    fun reportHeight(heightPx: Int) {
        Log.d(TAG, "reportHeight: $heightPx")
        onHeightReport(heightPx)
    }

    /** 页面是否存在可纵向滚动的内容（决定面板手势由 WebView 还是外层聊天列表接管） */
    @JavascriptInterface
    fun reportScrollable(scrollable: Boolean) {
        onScrollableChange(scrollable)
    }

    companion object {
        private const val TAG = "TavernJsBridge"
        private const val MAX_MESSAGES_SLICE = 50
        private const val MAX_SEND_LENGTH = 2_000
        private const val MAX_TOAST_LENGTH = 240
    }
}

/** 桥宿主接口：ChatViewModel 实现，向 WebView 提供受控数据与动作。 */
@Suppress("TooManyFunctions") // 桥宿主即白名单契约，逐个方法对应一类受控能力（桥侧/实现侧同注释）
interface HtmlBridgeHost {
    fun getVar(key: String): String

    fun setVar(
        key: String,
        value: String,
    )

    /** 最近 limit 条消息的 JSON（[{role, content}]，只读） */
    fun getChatMessagesJson(limit: Int): String

    fun sendMessage(text: String)

    /** 无界面的测试/预览宿主可忽略提示。 */
    fun showToast(message: String) = Unit

    fun emitEvent(
        type: String,
        payload: String,
    )

    /** 会话级 MVU stat_data（JSON 对象字符串，无则 "{}"） */
    fun getMvuVariablesJson(): String

    /** insertOrAssign 语义：浅合并顶层键进 stat_data 并广播变量更新 */
    fun insertMvuVariablesJson(json: String)

    /** 转发斜杠命令到 SlashCommandDispatcher（与输入框同一通道） */
    fun triggerSlash(command: String)

    /** 生效正则脚本 JSON（ST 字段形态）；默认空数组——测试/预览宿主无正则场景安全降级 */
    fun getTavernRegexesJson(): String = "[]"

    /** 消息写 API 的默认降级：无会话宿主一律拒绝/空操作，返回契约错误 JSON */
    fun setChatMessagesJson(payloadJson: String): String = BridgeMessageContract.error("当前无会话")

    fun createChatMessageJson(payloadJson: String): String = BridgeMessageContract.error("当前无会话")

    fun deleteChatMessagesJson(idsJson: String): String = BridgeMessageContract.error("当前无会话")

    fun getLastMessageId(): String = ""

    /** generate 兼容入口的默认降级：无会话宿主返回契约错误 JSON（页面侧得到 {error:...}） */
    fun triggerGenerateJson(): String = BridgeMessageContract.error("当前无会话")

    /** 停止生成的默认降级：测试/预览宿主无生成状态，空操作 */
    fun stopGeneration() = Unit

    /** substituteParams 的默认降级：无宏上下文宿主原样返回 */
    fun substituteParams(input: String): String = input
}
