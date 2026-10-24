package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * HTML 界面 WebView 沙箱配置（与 JS 沙箱同一安全哲学：默认全拒、显式放行）。
 *
 * - 文档经 loadDataWithBaseURL(tavern.local) 加载；后续请求一律拦截：
 *   放行 https 被动资源（图片/样式/字体/音频/视频——楼层界面的图标字体与 BGM 需求），
 *   脚本（外链 .js）、fetch/XHR（connect-src 'none'）、导航一律阻断
 * - 内联脚本允许（楼层界面的本质），DOM storage / 文件访问 / 地理位置 / 下载 / 多窗口全禁
 * - CSP meta 注入作为第二道防线（拦截器是第一道）
 */
object HtmlUiSandbox {
    const val BASE_URL = "https://tavern.local/"

    private const val CSP =
        "default-src 'none'; " +
            "script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline' https:; " +
            "img-src https: data:; " +
            "font-src https: data:; " +
            "media-src https:; " +
            "connect-src 'none'; " +
            "frame-src 'none'; object-src 'none';"

    private const val BLOCKED_STATUS = 204

    /** 被动资源扩展名：只读展示/播放，无代码执行面。 */
    private val PASSIVE_EXTENSIONS =
        setOf(
            // 图片
            "png", "jpg", "jpeg", "webp", "gif", "svg", "avif", "ico",
            // 样式
            "css",
            // 字体
            "woff", "woff2", "ttf", "otf", "eot",
            // 音视频
            "mp3", "wav", "ogg", "m4a", "flac", "mp4", "webm", "mov", "m4v",
        )

    /**
     * 拦截判定（纯函数，便于单测）：
     * - https + 被动扩展名 → 放行
     * - https + Accept 含 text/css（fonts.googleapis.com/css2 这类无扩展名样式表）→ 放行
     * - 其余（脚本 .js、fetch/XHR、明文 http、未知类型）→ 阻断
     */
    internal fun shouldAllowHttps(
        url: String,
        acceptHeader: String?,
    ): Boolean {
        val extension =
            url
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('.', "")
                .lowercase()
        if (extension in PASSIVE_EXTENSIONS) return true
        return acceptHeader?.contains("text/css") == true
    }

    /** 在 HTML 文档 <head> 内注入 viewport 与 CSP meta（无 <head> 则前置）。 */
    fun injectCsp(html: String): String {
        val meta =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">"
        val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (headMatch != null) {
            html.replaceRange(headMatch.range.last + 1, headMatch.range.last + 1, "\n$meta")
        } else {
            "$meta\n$html"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        webView: WebView,
        onExternalBlocked: (String) -> Unit,
        onPageFinished: (WebView) -> Unit,
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    val host = url?.host?.lowercase().orEmpty()
                    // data: 方案是 loadDataWithBaseURL 内容的实际加载形式（新版本 WebView 会把
                    // 传入内容转成 data: URL 再发起拦截回调）——文档本体，必须放行
                    val isInitialDoc = url?.scheme == "data"
                    val isPassiveHttps =
                        url?.scheme == "https" &&
                            shouldAllowHttps(
                                url.toString(),
                                request.requestHeaders?.get("Accept"),
                            )
                    // 放行：初始文档（data: / tavern.local）与 https 被动资源；其余一律阻断并上报占位
                    val blocked = !isInitialDoc && host != "tavern.local" && !isPassiveHttps
                    if (blocked) {
                        android.util.Log.w("HtmlUiSandbox", "blocked: $url")
                        onExternalBlocked(url?.toString().orEmpty())
                    }
                    return if (blocked) block() else null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = true // 楼层界面不导航外部链接

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    onPageFinished(view)
                }
            }

        // 卡片 JS 控制台转发 logcat：兼容垫片/卡片脚本异常可诊断（此前脚本静默失败只能猜）
        webView.webChromeClient =
            object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "HtmlUiConsole",
                        "${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                    )
                    return true
                }
            }
    }

    /** 阻断响应：空 204。 */
    private fun block(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            BLOCKED_STATUS,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}
