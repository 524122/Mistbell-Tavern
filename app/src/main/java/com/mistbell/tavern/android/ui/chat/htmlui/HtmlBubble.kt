package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 楼层 HTML 界面气泡：默认折叠为一张提示卡，展开后在沙箱 WebView 中渲染并注入桥与卡片脚本。
 *
 * 生命周期：按 messageId remember WebView（LazyColumn 回收重组不丢页面状态）；
 * 文档/脚本注入一次完成（按 messageId + html 记忆）。
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // 楼层气泡的参数即其外部契约，拆分反而碎
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBubble(
    messageId: String,
    html: String,
    scripts: List<HtmlScript>,
    host: HtmlBridgeHost,
    modifier: Modifier = Modifier,
    /** MVU 变量版本：变化时向页面推送 mvu_variable_update_ended（携带最新 stat_data） */
    variablesVersion: Long = 0,
) {
    var expanded by remember(messageId) { mutableStateOf(false) }

    if (!expanded) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier =
                modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🖥", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "HTML 界面 · 点击展开",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val maxHeightDp = (LocalConfiguration.current.screenHeightDp * HEIGHT_CAP_RATIO).toInt()
    var heightPx by remember(messageId) { mutableIntStateOf(MIN_HEIGHT_DP) }
    val blockedResources = remember(messageId) { mutableStateListOf<String>() }
    var showSource by remember(messageId) { mutableStateOf(false) }
    // WebView 引用：供变量更新推送等宿主主动调用（evaluateJavascript）
    val webViewRef = remember(messageId) { arrayOfNulls<android.webkit.WebView>(1) }

    // 变量更新推送：版本变化时向页面广播 mvu_variable_update_ended（携带最新 stat_data）
    LaunchedEffect(variablesVersion, messageId) {
        if (variablesVersion > 0) {
            val varsJson =
                com.mistbell.tavern.android.extension.scripting.ScriptRuntimeManager
                    .jsonString(host.getMvuVariablesJson())
            webViewRef[0]?.evaluateJavascript(
                "window.__tavernEmit && window.__tavernEmit('mvu_variable_update_ended', $varsJson)",
                null,
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // WRAP_CONTENT：高度完全由 Compose Modifier 驱动（此前 MATCH_PARENT+1px 与
                    // interop 布局博弈导致 Modifier.height 失效，视口被钉在 ~76dp 裁掉内容）
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    webViewRef[0] = this
                    val bridge =
                        TavernJsBridge(
                            host = host,
                            onHeightReport = { reported ->
                                // WebView CSS px 即 dp：直接作为 dp 高度（含下限与 70% 屏高上限）
                                heightPx =
                                    reported
                                        .coerceAtLeast(MIN_HEIGHT_DP)
                                        .coerceAtMost(maxHeightDp)
                            },
                        )
                    addJavascriptInterface(bridge, "TavernBridge")
                    // 桥/门面/卡片脚本的注入必须在页面加载完成后进行——
                    // 过早注入会被新文档覆盖（此前高度回报与桥失效即由此而来）
                    HtmlUiSandbox.configure(this, { blockedResources.add(it) }) { webView ->
                        webView.evaluateJavascript(FACADE_JS, null)
                        webView.evaluateJavascript(HEIGHT_REPORT_JS, null)
                        // 卡片脚本以 <script type="module"> 注入（ES Module 语义，支持顶层 import 语法——
                        // 外链 import 被沙箱拦截，单脚本失败不影响其他；evaluateJavascript 的 classic
                        // 语义遇到 import 直接整脚本语法错误）
                        scripts.filter { it.enabled }.forEach { script ->
                            val contentJson =
                                com.mistbell.tavern.android.extension.scripting.ScriptRuntimeManager
                                    .jsonString(script.content)
                            val moduleJs =
                                "(() => { const s = document.createElement('script'); s.type = 'module'; " +
                                    "s.textContent = $contentJson; document.head.appendChild(s); })()"
                            runCatching { webView.evaluateJavascript(moduleJs, null) }
                                .onFailure {
                                    android.util.Log.w("HtmlBubble", "脚本「${script.name}」注入失败：${it.message}")
                                }
                        }
                        webView.evaluateJavascript(HEIGHT_REPORT_JS, null)
                    }
                    loadDataWithBaseURL(
                        HtmlUiSandbox.BASE_URL,
                        HtmlUiSandbox.injectCsp(html),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(heightPx.dp),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (blockedResources.isNotEmpty()) {
                Text(
                    text = "已按安全策略阻止 ${blockedResources.size} 项外部资源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "查看源码",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showSource = true },
            )
            Text(
                text = "收起",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = false },
            )
        }
    }

    if (showSource) {
        AlertDialog(
            onDismissRequest = { showSource = false },
            title = { Text("HTML 源码") },
            text = {
                Text(
                    text = html,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showSource = false }) { Text("关闭") }
            },
        )
    }
}

/** 注入页面的 JS 门面：把桥包装成楼层界面熟悉的 API 形态（TavernHelper + Mvu 兼容层）。 */
private val FACADE_JS =
    """
    window.__tavernListeners = {};
    window.__tavernEmit = function (name, payloadJson) {
      (window.__tavernListeners[name] || []).forEach(function (fn) {
        try { fn(JSON.parse(payloadJson)); } catch (e) { console.error(e); }
      });
    };
    window.TavernHelper = {
      getVar: (k) => window.TavernBridge.getVar(k),
      setVar: (k, v) => window.TavernBridge.setVar(k, String(v)),
      getChatMessages: (n) => Promise.resolve(JSON.parse(window.TavernBridge.getChatMessages(n || 20))),
      sendMessage: (t) => window.TavernBridge.sendMessage(String(t)),
      eventEmit: (t, p) => window.TavernBridge.eventEmit(String(t), JSON.stringify(p ?? null)),
      eventOn: (name, fn) => {
        const key = String(name);
        (window.__tavernListeners[key] = window.__tavernListeners[key] || []).push(fn);
      },
      log: (m) => window.TavernBridge.log(String(m)),
      getVariables: () => Promise.resolve(JSON.parse(window.TavernBridge.getMvuVariables())),
      insertOrAssignVariables: (obj) => Promise.resolve(window.TavernBridge.insertMvuVariables(JSON.stringify(obj ?? {}))),
      registerMvuSchema: (schema) => window.TavernBridge.log("registerMvuSchema 占位（v1 不做 schema 校验）"),
      triggerSlash: (cmd) => window.TavernBridge.triggerSlash(String(cmd)),
    };
    window.Mvu = {
      events: {
        COMMAND_PARSED: "mvu_command_parsed",
        VARIABLE_UPDATE_ENDED: "mvu_variable_update_ended",
      },
      on: (event, fn) => window.TavernHelper.eventOn(event, fn),
    };
    """.trimIndent()

/** 高度回报：即时 + 延迟补报（onPageFinished 时布局/字体未稳定，scrollHeight 偏小）+ DOM 变化持续回报。 */
private val HEIGHT_REPORT_JS =
    """
    (function () {
      if (window.__tavernHeightObserver) return;
      const report = () => window.TavernBridge.reportHeight(document.documentElement.scrollHeight);
      report();
      setTimeout(report, 300);
      setTimeout(report, 1200);
      window.__tavernHeightObserver = new MutationObserver(report);
      window.__tavernHeightObserver.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
    })();
    """.trimIndent()

private const val HEIGHT_CAP_RATIO = 0.7f
private const val MIN_HEIGHT_DP = 120
