package com.mistbell.tavern.android.ui.chat.htmlui

import android.content.Context
import android.webkit.WebView

/**
 * WebView 预热池：提前初始化一个裸实例（Chromium 进程/渲染管线），
 * 首个 HTML 消息直接复用，消除首次几百毫秒初始化白屏。
 * 实例只预热不加载文档，气泡工厂仍按自身 html/桥/沙箱完整装配。
 */
object HtmlWebViewPool {
    @Volatile
    private var preheated: WebView? = null

    /** 在后台提前创建实例（用 applicationContext，避免持有 Activity）。 */
    fun preheat(context: Context) {
        if (preheated != null) return
        preheated = WebView(context.applicationContext)
    }

    /** 取出预热实例（一次性；未取到则新建）。 */
    fun take(context: Context): WebView {
        val instance = preheated
        preheated = null
        return instance ?: WebView(context.applicationContext)
    }

    /** 会话退出时兜底销毁，避免跨会话残留。 */
    fun clear() {
        preheated?.destroy()
        preheated = null
    }
}
