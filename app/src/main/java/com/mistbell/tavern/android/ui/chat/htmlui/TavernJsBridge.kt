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
 * - eventEmit(type, payloadJson)
 */
class TavernJsBridge(
    private val host: HtmlBridgeHost,
    private val onHeightReport: (Int) -> Unit,
) {
    @JavascriptInterface
    fun log(message: String?) {
        Log.d(TAG, message ?: "null")
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

    /** 页面高度回报（气泡据此调整 WebView 高度） */
    @JavascriptInterface
    fun reportHeight(heightPx: Int) {
        Log.d(TAG, "reportHeight: $heightPx")
        onHeightReport(heightPx)
    }

    companion object {
        private const val TAG = "TavernJsBridge"
        private const val MAX_MESSAGES_SLICE = 50
        private const val MAX_SEND_LENGTH = 2_000
    }
}

/** 桥宿主接口：ChatViewModel 实现，向 WebView 提供受控数据与动作。 */
interface HtmlBridgeHost {
    fun getVar(key: String): String

    fun setVar(
        key: String,
        value: String,
    )

    /** 最近 limit 条消息的 JSON（[{role, content}]，只读） */
    fun getChatMessagesJson(limit: Int): String

    fun sendMessage(text: String)

    fun emitEvent(
        type: String,
        payload: String,
    )
}
