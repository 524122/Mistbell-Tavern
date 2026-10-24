package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Suppress("MagicNumber") // 颜色字面量即设计值
private val SCRIM_COLOR = Color(0xFF0A0A0C)

@Suppress("MagicNumber")
private val CLOSE_BUTTON_BG = Color(0x66000000)

@Suppress("MagicNumber")
private val BLOCKED_HINT_COLOR = Color(0xCCFFFFFF)

/** 全屏附加样式：页面至少撑满视口、去 UA 默认边距（内嵌气泡不注入，避免改变卡片原生排版） */
private const val FULLSCREEN_STYLE =
    "<style>html,body{min-height:100vh;margin:0!important;padding:0!important;}</style>"

/** 把全屏样式插入 <head> 内（无 <head> 才前置——文档以 <!DOCTYPE> 开头时绝不能前插，否则触发怪异模式） */
private fun withFullscreenStyle(html: String): String {
    val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (headMatch != null) {
        html.replaceRange(headMatch.range.last + 1, headMatch.range.last + 1, "\n" + FULLSCREEN_STYLE)
    } else {
        FULLSCREEN_STYLE + html
    }
}

/**
 * HTML 界面全屏沉浸层：边缘到边缘渲染同一沙箱 WebView（与内嵌气泡同一工厂与桥），
 * 隐藏系统栏；右上 ✕ 或返回键关闭。用于苍玄界开场这类重交互界面。
 *
 * 与内嵌气泡是两个 WebView 实例（页面内临时状态不共享），但变量/ MVU 数据
 * 都落在宿主 settings，重进后界面可从变量重建。
 */
@Suppress("FunctionNaming", "LongMethod")
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlFullscreenDialog(
    html: String,
    scripts: List<HtmlScript>,
    host: HtmlBridgeHost,
    variablesVersion: Long,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        BackHandler { onDismiss() }
        // 沉浸：隐藏状态栏/导航栏，关闭时恢复（ transient 行为允许边缘滑动临时呼出）
        val view = androidx.compose.ui.platform.LocalView.current
        val dialogWindow = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            // 覆盖系统栏区域：仅 decorFitsSystemWindows=false 不够，需显式撑满窗口并加布局旗标
            dialogWindow?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            dialogWindow?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            val controller =
                dialogWindow?.let { window ->
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    WindowCompat.getInsetsController(window, window.decorView)
                }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }

        val blockedResources = remember { mutableStateListOf<String>() }
        val webViewRef = remember { arrayOfNulls<android.webkit.WebView>(1) }

        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { webViewRef[0]?.destroy() }
        }

        // 变量更新推送（与内嵌气泡同一契约）
        LaunchedEffect(variablesVersion) {
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

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SCRIM_COLOR),
        ) {
            AndroidView(
                factory = { context ->
                    HtmlWebViewFactory
                        .create(
                            context = context,
                            // 全屏附加样式：页面至少撑满视口、去 UA 默认边距，实现边缘到边缘
                            // （内嵌气泡不注入，保持卡片原生排版；须插入 <head> 内——
                            //  置于 <!DOCTYPE> 前会触发怪异模式，卡片 flex 居中布局错位）
                            html = withFullscreenStyle(html),
                            scripts = scripts,
                            host = host,
                            onHeightReport = {},
                            onExternalBlocked = { blockedResources.add(it) },
                        ).also { webViewRef[0] = it }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 关闭按钮：半透明圆底 ✕，避免压内容时找不到出口
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(10.dp)
                        .background(CLOSE_BUTTON_BG, CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("✕", color = Color.White)
            }

            if (blockedResources.isNotEmpty()) {
                Text(
                    text = "已按安全策略阻止 ${blockedResources.size} 项外部资源",
                    color = BLOCKED_HINT_COLOR,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                )
            }
        }
    }
}
