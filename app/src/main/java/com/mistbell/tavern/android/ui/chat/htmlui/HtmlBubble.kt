package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
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
import androidx.compose.runtime.DisposableEffect
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
 * 楼层 HTML 界面气泡：折叠时为一张提示卡，展开后在沙箱 WebView 中渲染并注入桥与卡片脚本。
 *
 * - 展开状态由宿主（ChatViewModel）持有并按会话持久化，重组/回收/重启都不丢
 * - WebView 透明底，无自带背景的 HTML 与聊天背景融合
 * - 工具行提供「全屏」进入沉浸层（HtmlFullscreenDialog）
 *
 * 生命周期：调用方按 messageId + HTML 内容建立组合 key；内容变化时销毁并重建 WebView，
 * 同一文档的重组不重复加载。
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // 楼层气泡的参数即其外部契约，拆分反而碎
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBubble(
    messageId: String,
    html: String,
    scripts: List<HtmlScript>,
    host: HtmlBridgeHost,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** MVU 变量版本：变化时向页面推送 mvu_variable_update_ended（携带最新 stat_data） */
    variablesVersion: Long = 0,
    /** 当前会话聊天事件的有界队列：按 version 游标补推 __tavernEmit（酒馆助手事件名） */
    chatEvents: List<HtmlFacadeContract.HtmlChatEvent> = emptyList(),
    /** 叙事行内 HTML：始终展开、高度封顶 85% 屏高（长内容内滚），不显示全屏/收起入口 */
    narrative: Boolean = false,
) {
    if (!expanded && !narrative) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier =
                modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(true) },
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
    var showFullscreen by remember(messageId) { mutableStateOf(false) }
    // WebView 引用：供变量更新推送等宿主主动调用（evaluateJavascript）
    val webViewRef = remember(messageId) { arrayOfNulls<android.webkit.WebView>(1) }

    // 实例生命周期：离开组合（滚出视口/会话切换）即销毁，避免长会话残留大量 Chromium 实例
    DisposableEffect(messageId) {
        onDispose {
            webViewRef[0]?.let { webView ->
                webView.stopLoading()
                webView.removeJavascriptInterface("TavernBridge")
                webView.destroy()
            }
            webViewRef[0] = null
        }
    }

    // 离开组合即销毁实例，避免长会话中大量 HTML 消息累积 Chromium 实例
    androidx.compose.runtime.DisposableEffect(messageId) {
        onDispose { webViewRef[0]?.destroy() }
    }

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

    // 聊天事件推送（与上方 MVU 推送旁挂同一机制）：chatEvents 是宿主常驻收集的有界队列
    // （不随订阅启停，生成事件的成对快速发射不会在订阅窗口内丢失）。游标挂载时对齐队列
    // 末尾（旧事件不重放），队列增长/WebView 重建时按序补推、序号守卫不重不漏。
    val chatEventLastPushedSeq =
        remember(messageId) {
            androidx.compose.runtime.mutableLongStateOf(chatEvents.lastOrNull()?.version ?: Long.MIN_VALUE)
        }
    LaunchedEffect(chatEvents, messageId) {
        drainChatEvents(chatEvents, webViewRef[0], chatEventLastPushedSeq)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                HtmlWebViewFactory
                    .create(
                        context = context,
                        html = html,
                        scripts = scripts,
                        host = host,
                        onHeightReport = { reported ->
                            // WebView CSS px 即 dp：直接作为 dp 高度（含下限与 85% 屏高上限）。
                            // 超长叙事同样封顶——全高巨型 WebView 会卡死主线程（ANR），
                            // 长内容走面板内滚，外层消息列表滚动不受影响。
                            heightPx =
                                reported
                                    .coerceAtLeast(MIN_HEIGHT_DP)
                                    .coerceAtMost(maxHeightDp)
                        },
                        onExternalBlocked = { blockedResources.add(it) },
                    ).also { webViewRef[0] = it }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(heightPx.dp),
        )

        // 工具行：半透明底色，透明 WebView 下也保持可读
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
                if (!narrative) {
                    Text(
                        text = "全屏",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showFullscreen = true },
                    )
                }
                Text(
                    text = "查看源码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showSource = true },
                )
                if (!narrative) {
                    Text(
                        text = "收起",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onExpandedChange(false) },
                    )
                }
            }
        }
    }

    if (showFullscreen) {
        HtmlFullscreenDialog(
            html = html,
            scripts = scripts,
            host = host,
            variablesVersion = variablesVersion,
            chatEvents = chatEvents,
            onDismiss = { showFullscreen = false },
        )
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

private const val HEIGHT_CAP_RATIO = 0.85f
private const val MIN_HEIGHT_DP = 120
