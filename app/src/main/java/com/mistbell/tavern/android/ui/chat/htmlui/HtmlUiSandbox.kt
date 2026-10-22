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
 *   仅放行 https: 的图片扩展名（被动内容，CG 收集类界面的底线需求）
 * - 内联脚本允许（楼层界面的本质），外链脚本/样式/fetch/XHR 全断
 * - 禁 DOM storage / 文件访问 / 地理位置 / 下载 / 多窗口
 * - CSP meta 注入作为第二道防线（拦截器是第一道）
 */
object HtmlUiSandbox {
    const val BASE_URL = "https://tavern.local/"

    private const val CSP =
        "default-src 'none'; " +
            "script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; " +
            "img-src https: data:; " +
            "font-src data:; " +
            "connect-src 'none'; " +
            "frame-src 'none'; object-src 'none';"

    private const val BLOCKED_STATUS = 204

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif")

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
                    val isHttpsImage =
                        url?.scheme == "https" &&
                            url.lastPathSegment
                                ?.substringAfterLast('.', "")
                                ?.lowercase() in IMAGE_EXTENSIONS
                    // 放行：初始文档（data: / tavern.local）与 https 图片；其余一律阻断并上报占位
                    val blocked = !isInitialDoc && host != "tavern.local" && !isHttpsImage
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
