package com.mistbell.tavern.android.ui.chat.htmlui

import android.webkit.WebView
import androidx.compose.runtime.MutableLongState

/**
 * 聊天事件推送的页面侧补推循环（HtmlBubble / HtmlHostLayer / HtmlFullscreenDialog 三处消费端共用）：
 * 把队列中 version 晚于 [lastPushedSeq] 的事件逐条广播给页面（__tavernEmit）并推进游标。
 *
 * - 游标由调用方以 remember 持有，挂载时对齐队列末尾（旧事件不重放——事件是通知而非状态）；
 * - 队列增长 / WebView 重建导致 effect 重启时，序号守卫保证逐条各推一次、不重不漏
 *   （重组合并多条事件也按序逐条送达，不被"最新单值"并帧吞掉）；
 * - WebView 未就绪（null）时跳过，待下次 effect 以新 WebView 实例重启时补推剩余事件。
 */
internal fun drainChatEvents(
    events: List<HtmlFacadeContract.HtmlChatEvent>,
    webView: WebView?,
    lastPushedSeq: MutableLongState,
) {
    if (webView == null) return
    events.forEach { event ->
        if (event.version > lastPushedSeq.longValue) {
            webView.evaluateJavascript(HtmlFacadeContract.emitJs(event), null)
            lastPushedSeq.longValue = event.version
        }
    }
}
