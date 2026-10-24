package com.mistbell.tavern.android.ui.chat.htmlui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView

/**
 * 楼层 HTML 界面 WebView 的统一创建：沙箱配置、桥、门面与卡片脚本注入。
 * 内嵌气泡（HtmlBubble）与全屏沉浸层（HtmlFullscreenDialog）共用，保证两处行为一致。
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
            // 桥/门面/卡片脚本的注入必须在页面加载完成后进行——
            // 过早注入会被新文档覆盖（此前高度回报与桥失效即由此而来）
            HtmlUiSandbox.configure(this, onExternalBlocked) { webView ->
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
                            android.util.Log.w("HtmlWebViewFactory", "脚本「${script.name}」注入失败：${it.message}")
                        }
                }
                webView.evaluateJavascript(HEIGHT_REPORT_JS, null)
            }
            loadDataWithBaseURL(
                HtmlUiSandbox.BASE_URL,
                // 先注入前置库（jQuery/lodash 垫片），再注 CSP——meta 落在前置脚本之前，
                // 保证 CSP 先于一切资源生效；卡片文档自身脚本解析期即有 $ / _ 可用
                HtmlUiSandbox.injectCsp(HtmlPrereqs.inject(context, html)),
                "text/html",
                "utf-8",
                null,
            )
        }

    /** 注入页面的 JS 门面：把桥包装成楼层界面熟悉的 API 形态（TavernHelper + Mvu 兼容层）。 */
    internal val FACADE_JS =
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
        // 酒馆助手同名全局函数别名（部分卡片直接用裸 eventOn）
        window.eventOn = window.TavernHelper.eventOn;
        // 悬浮控件拖动兼容：pointer 事件的 preventDefault 不影响 WebView 原生滚动，
        // 手势会被原生滚动抢走（pointercancel）。在首次 touchmove 取消后原生滚动不再启动，
        // 拖动全程交给卡片的 Pointer Events 实现；touchstart 不动，点击开面板不受影响。
        document.addEventListener('touchmove', function (ev) {
          if (ev.target.closest && ev.target.closest('#cx-floating-status-toggle, #cx-workshop-cf-button')) {
            ev.preventDefault();
          }
        }, { passive: false, capture: true });
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
