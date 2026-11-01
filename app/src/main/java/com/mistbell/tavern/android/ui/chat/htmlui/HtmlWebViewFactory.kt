package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import com.mistbell.tavern.android.data.extensionpack.ExtensionPreloadProvider

/**
 * 楼层 HTML 界面 WebView 的统一创建：沙箱配置、桥、门面与卡片脚本注入。
 * 内嵌气泡（HtmlBubble）与全屏沉浸层（HtmlFullscreenDialog）共用，保证两处行为一致。
 *
 * P2 门面预注入：门面（TavernHelper 组装）不再硬编码于此，由官方扩展包
 * tavernhelper-compat 的 facade.js 随注入链进 <head>（ExtensionPreloadProvider 快照）；
 * 引擎原生段（桥/事件总线/高度回报/CSP）保留在宿主。
 */
internal object HtmlWebViewFactory {
    @Suppress("LongParameterList") // 参数即 WebView 装配契约
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: android.content.Context,
        html: String,
        scripts: List<HtmlScript>,
        host: HtmlBridgeHost,
        onHeightReport: (Int) -> Unit,
        onExternalBlocked: (String) -> Unit,
        webViewFactory: (android.content.Context) -> WebView = { HtmlWebViewPool.take(it) },
    ): WebView =
        webViewFactory(context).apply {
            // WRAP_CONTENT：高度完全由 Compose Modifier 驱动（此前 MATCH_PARENT+1px 与
            // interop 布局博弈导致 Modifier.height 失效，视口被钉在 ~76dp 裁掉内容）
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            // 透明底：无自带背景的 HTML 与聊天背景融合，不罩一层白板
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 关闭 overscroll 光晕：内容不溢出时手势不再被 WebView 吃掉，
            // 未消耗的滚动才能经嵌套滚动回传给外层聊天列表
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            // 嵌套滚动：仅当页面确有可滚动内容时才禁止父级拦截——
            // 不可滚动的面板（如 600px 固定开场界面）把手势还给外层聊天列表，
            // 否则大面板会把消息列表「锁死」，滚不到后续消息。
            val scrollableState = booleanArrayOf(false)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE,
                    -> view.parent?.requestDisallowInterceptTouchEvent(scrollableState[0])
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // 事件继续交给 WebView 自身（滚动/点击/拖动）
            }
            addJavascriptInterface(
                TavernJsBridge(
                    host = host,
                    onHeightReport = onHeightReport,
                    onScrollableChange = { scrollableState[0] = it },
                ),
                "TavernBridge",
            )
            // 桥/门面/卡片脚本的注入时序（P2 门面预注入改造）：
            // - 门面（TavernHelper 组装）已迁出为官方扩展包 tavernhelper-compat 的 facade.js，
            //   随注入链在文档组装期进 <head>——卡片自身内联脚本解析期即可见（与酒馆助手上游
            //   predefine 行为对齐），onPageFinished 后不再 evaluateJavascript 门面
            // - 事件总线（__tavernListeners/__tavernEmit）是引擎原生段（EVENT_BUS_JS），同样在
            //   组装期注入——原生推事件（ChatEventPush/MVU 推送）不依赖任何扩展制品启停
            HtmlUiSandbox.configure(this, onExternalBlocked) { webView ->
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
                            android.util.Log.w("HtmlWebViewFactory", "脚本「${script.name}」注入失败：${it.message}")
                        }
                }
                // 脚本注入后再报一次高度（模块渲染异步，MutationObserver 兜底后续变化）
                webView.evaluateJavascript(HEIGHT_REPORT_JS, null)
            }
            loadDataWithBaseURL(
                HtmlUiSandbox.BASE_URL,
                // 注入链（<head> 内最终顺序）：CSP meta（最前，治理其后一切脚本）
                // → 基础垫片（jQuery/lodash 垫片）→ 原生事件总线段
                // → 已启用扩展包 webviewPreload（loadingOrder 升序，同序按 id 稳定）
                // → 卡片自身内容。CSP/meta 与垫片的先后由既有插 head 首位实现保证，
                //   扩展 preload 同样处于 CSP 之后（受治理），解析期先于卡片脚本可用
                HtmlUiSandbox.injectCsp(
                    HtmlPrereqs.inject(context, html, EVENT_BUS_JS, ExtensionPreloadProvider.snapshot()),
                ),
                "text/html",
                "utf-8",
                null,
            )
        }

    /**
     * 原生事件总线段（引擎一部分，不随扩展包启停）：__tavernListeners/__tavernEmit 事件管道。
     * 原生侧主动推送（ChatEventPush.drainChatEvents 聊天事件、HtmlBubble/HtmlHostLayer 的
     * MVU 变量推送）以此接收端为依赖——必须独立于任何扩展制品存在，故从 FACADE_JS 拆出
     * 进预注入链；门面（TavernHelper/eventOn 别名）缺失时推送语句自带守卫静默跳过。
     */
    internal val EVENT_BUS_JS: String =
        """
        window.__tavernListeners = {};
        window.__tavernEmit = function (name, payloadJson) {
          (window.__tavernListeners[name] || []).forEach(function (fn) {
            try { fn(JSON.parse(payloadJson)); } catch (e) { console.error(e); }
          });
        };
        """.trimIndent()

    /** 高度回报：即时 + 延迟补报（onPageFinished 时布局/字体未稳定，scrollHeight 偏小）+ DOM 变化持续回报。 */
    internal val HEIGHT_REPORT_JS =
        """
        (function () {
          if (window.__tavernHeightObserver) return;
          // 可滚动性判定（嵌套滚动谁接管手势的依据）：文档或任一 overflow 容器纵向溢出即为 true。
          // 全量扫描较贵，用 800ms 时间窗去抖，复用高度回报的触发节奏。
          let lastScrollableCheck = 0;
          const computeScrollable = () => {
            const now = Date.now();
            if (now - lastScrollableCheck < 2500) return;
            lastScrollableCheck = now;
            const de = document.documentElement;
            // 有效可见性：沿祖先排除 display:none / visibility:hidden / 近零透明度 / pointer-events:none。
            // 卡片弹层常用 opacity:0 + pointer-events:none 表示「关闭」，不能算进可滚动内容。
            const isVisible = (el) => {
              let cur = el;
              while (cur && cur.nodeType === 1) {
                const st = getComputedStyle(cur);
                if (st.display === 'none' || st.visibility === 'hidden' ||
                    Number(st.opacity) < 0.05 || st.pointerEvents === 'none') return false;
                cur = cur.parentElement;
              }
              return true;
            };
            let scrollable = de.scrollHeight > window.innerHeight + 4;
            if (!scrollable) {
              scrollable = Array.from(document.querySelectorAll('*')).some(el => {
                // 先做零成本尺寸过滤（绝大多数元素在此被排除），再对少数候选走祖先可见性
                if (el.scrollHeight <= el.clientHeight + 4) return false;
                if (!isVisible(el)) return false;
                const oy = getComputedStyle(el).overflowY;
                return oy === 'auto' || oy === 'scroll';
              });
            }
            window.TavernBridge.reportScrollable(scrollable);
          };
          const report = () => {
            window.TavernBridge.reportHeight(document.documentElement.scrollHeight);
            computeScrollable();
          };
          report();
          setTimeout(report, 300);
          setTimeout(report, 1200);
          window.__tavernHeightObserver = new MutationObserver(report);
          window.__tavernHeightObserver.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
        })();
        """.trimIndent()
}
