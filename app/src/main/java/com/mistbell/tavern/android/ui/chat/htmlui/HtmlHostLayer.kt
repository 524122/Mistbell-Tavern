package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** CSS 像素矩形（与 WebView 视口同一坐标系）。 */
data class CssRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

data class RegionReport(
    val viewportWidthCssPx: Float,
    val fullscreen: Boolean,
    val rects: List<CssRect>,
)

/** 解析 __tavernCollectRegions 的返回（evaluateJavascript 回调为带引号的 JSON 字符串）。 */
@Suppress("ReturnCount", "MagicNumber") // 早退即非法输入；捕获组序号即协议
internal fun parseRegionReport(raw: String?): RegionReport? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val text =
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
        } else {
            raw
        }
    val vw = Regex("\"vw\":([0-9.]+)").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
    val fullscreen = Regex("\"fullscreen\":(true|false)").find(text)?.groupValues?.get(1) == "true"
    val rects =
        Regex("\\[\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\]")
            .findAll(text)
            .map { m ->
                val g = m.groupValues
                CssRect(g[1].toFloat(), g[2].toFloat(), g[3].toFloat(), g[4].toFloat())
            }.toList()
    return RegionReport(vw, fullscreen, rects)
}

/** 区域感知的覆盖层 WebView：可交互区域外的按下直接交回父布局（聊天区）。 */
class HostLayerWebView(context: Context) : WebView(context) {
    var regionReport: RegionReport? = null

    @Suppress("ReturnCount") // 按下即判穿透，分支即语义
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val report = regionReport
            if (report == null || report.viewportWidthCssPx <= 0f) return false
            if (!report.fullscreen) {
                val scale = width / report.viewportWidthCssPx
                if (scale <= 0f) return false
                val cssX = event.x / scale
                val cssY = event.y / scale
                if (report.rects.none { it.contains(cssX, cssY) }) return false
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * 全局宿主层：覆盖聊天区的透明 WebView，承载卡片 tavern_helper.scripts。
 *
 * 酒馆助手环境下卡片脚本把 window.parent 当宿主窗口，在其文档里创建悬浮球/弹窗；
 * 此前这些脚本只能注入消息面板，球被钉在面板内。现在改由本层执行——
 * 面板仅保留消息内联 HTML，助手脚本在此层运行，悬浮控件可覆盖整个聊天区。
 *
 * 触摸穿透：JS 周期性上报可交互区域（悬浮球/弹层 rect），
 * 区域外的 ACTION_DOWN 返回 false，事件交回聊天区，消息滚动/输入不受影响。
 */
@Suppress("FunctionNaming", "LongMethod")
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlHostLayer(
    scripts: List<HtmlScript>,
    host: HtmlBridgeHost,
    variablesVersion: Long,
    modifier: Modifier = Modifier,
) {
    val webViewRef = remember(scripts) { mutableStateOf<HostLayerWebView?>(null) }

    androidx.compose.runtime.DisposableEffect(scripts) {
        onDispose { webViewRef.value?.destroy() }
    }

    AndroidView(
        factory = { context ->
            val layer = HostLayerWebView(context)
            webViewRef.value = layer
            // 覆盖层必须真正透明：部分 WebView 版本硬件加速下透明背景失效变白，
            // 需要软件层 + 清空背景资源双重保底
            layer.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            layer.setBackgroundResource(0)
            HtmlWebViewFactory.create(
                context = context,
                html = HOST_SHELL_HTML,
                scripts = scripts,
                host = host,
                onHeightReport = {},
                onExternalBlocked = { android.util.Log.d("HtmlHostLayer", "blocked: $it") },
                webViewFactory = { layer },
            )
        },
        modifier = modifier.fillMaxSize(),
    )

    // 轮询可交互区域（悬浮球位置会随拖动变化，模态会开合，故用周期轮询而非一次性订阅）
    LaunchedEffect(webViewRef.value) {
        val layer = webViewRef.value ?: return@LaunchedEffect
        while (true) {
            layer.evaluateJavascript("window.__tavernCollectRegions ? window.__tavernCollectRegions() : null") { raw ->
                parseRegionReport(raw)?.let { layer.regionReport = it }
            }
            delay(REGION_POLL_MS)
        }
    }

    // MVU 变量推送（与内嵌气泡同一契约）
    LaunchedEffect(variablesVersion, webViewRef.value) {
        if (variablesVersion > 0) {
            val varsJson =
                com.mistbell.tavern.android.extension.scripting.ScriptRuntimeManager
                    .jsonString(host.getMvuVariablesJson())
            webViewRef.value?.evaluateJavascript(
                "window.__tavernEmit && window.__tavernEmit('mvu_variable_update_ended', $varsJson)",
                null,
            )
        }
    }
}

private const val REGION_POLL_MS = 500L

/**
 * 宿主壳文档：透明、禁滚动、预置区域采集函数。
 * 模态（弹窗/状态面板打开）时整层可交互，卡片的外部点击关闭逻辑才能收到事件。
 */
private val HOST_SHELL_HTML =
    """
    <!DOCTYPE html>
    <html>
    <head>
    <style>
      html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: transparent; }
    </style>
    </head>
    <body>
    <script>
    window.__tavernCollectRegions = function () {
      const out = { vw: window.innerWidth, regions: [], fullscreen: false };
      const modalSel = [
        '#cx-floating-status-root.is-open',
        '.cx-char-modal', '.cx-modal', '.cx-detail-page',
        '.cx-workshop-cf-modal', '.cx-workshop-cf-overlay',
      ].join(',');
      const modalOpen = Array.from(document.querySelectorAll(modalSel)).some(el => {
        const st = getComputedStyle(el);
        return st.display !== 'none' && st.visibility !== 'hidden' &&
          Number(st.opacity) > 0.05 && st.pointerEvents !== 'none';
      });
      if (modalOpen) { out.fullscreen = true; return JSON.stringify(out); }
      ['#cx-floating-status-root', '#cx-workshop-cf-root'].forEach(sel => {
        const el = document.querySelector(sel);
        if (!el) return;
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) out.regions.push([r.left, r.top, r.right, r.bottom]);
      });
      return JSON.stringify(out);
    };
    </script>
    </body>
    </html>
    """.trimIndent()
