package com.mistbell.tavern.android.ui.chat.htmlui

import android.content.Context

/**
 * 楼层界面兼容前置库：注入 <head> 内、先于文档自身脚本执行。
 *
 * 酒馆助手环境下卡片默认有 jQuery / lodash(`_`) 可用，我们的沙箱 WebView 没有——
 * 卡片内联 <script> 在解析期就跑，遇到 `$`/`_` 未定义整块抛错（苍玄界地志/轮盘不渲染即因此）。
 * jQuery 3.7.1（MIT）随包内置；lodash 只补卡片常用的 get/set（净室最小实现，不引整库）。
 */
internal object HtmlPrereqs {
    private const val JQUERY_ASSET = "htmlui/jquery.min.js"

    @Volatile
    private var jqueryCache: String? = null

    /** 把 jQuery、lodash、存储、fetch/toastr 与初始化兼容垫片注入 <head>（无 <head> 则前置）。 */
    fun inject(
        context: Context,
        html: String,
    ): String =
        injectWithPrereq(
            html,
            "<script>${loadJquery(context)}</script>\n" +
                "<script>$LODASH_SHIM</script>\n" +
                "<script>$LOCALSTORAGE_SHIM</script>\n" +
                "<script>$FETCH_SHIM</script>\n" +
                "<script>$TOASTR_SHIM</script>\n" +
                "<script>$WAIT_GLOBAL_SHIM</script>\n" +
                "<script>${iframePatchJs(context)}</script>",
        )

    /** 注入结构（纯函数，便于单测）：<head> 内紧跟插入；无 <head> 则前置。 */
    internal fun injectWithPrereq(
        html: String,
        prereq: String,
    ): String {
        val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (headMatch != null) {
            html.replaceRange(headMatch.range.last + 1, headMatch.range.last + 1, "\n$prereq")
        } else {
            "$prereq\n$html"
        }
    }

    private fun loadJquery(context: Context): String =
        jqueryCache ?: runCatching {
            context.assets.open(JQUERY_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("").also { jqueryCache = it }

    /** lodash get/set 最小垫片：路径支持 a.b.c 与 a[0].b 两种写法；get 带默认值兜底。 */
    private const val LODASH_SHIM =
        """
        window._ = window._ || {
          get: function (o, p, d) {
            try {
              const parts = Array.isArray(p) ? p : String(p).replace(/\[(\w+)\]/g, '.$1').split('.');
              let cur = o;
              for (const k of parts) { if (cur == null) return d; cur = cur[k]; }
              return cur === undefined ? d : cur;
            } catch (e) { return d; }
          },
          set: function (o, p, v) {
            const parts = Array.isArray(p) ? p : String(p).replace(/\[(\w+)\]/g, '.$1').split('.');
            let cur = o;
            for (let i = 0; i < parts.length - 1; i++) {
              const k = parts[i];
              if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = /^\d+$/.test(parts[i + 1]) ? [] : {};
              cur = cur[k];
            }
            cur[parts[parts.length - 1]] = v;
            return o;
          },
        };
        """

    /**
     * localStorage 垫片：domStorageEnabled=false 时原生 localStorage 访问直接抛
     * SecurityError，卡片脚本（悬浮球位置、状态栏缓存等）整块崩坏。
     * 用 JS 属性覆盖换成桥实现——读写走 TavernBridge getVar/setVar（ext.hui.<sessionId>），
     * 保持会话级隔离；key()/length 为 v1 空实现（不支持遍历键）。
     */
    private const val LOCALSTORAGE_SHIM =
        """
        (function () {
          const PREFIX = 'ls.';
          const shim = {
            getItem: function (k) {
              const v = window.TavernBridge.getVar(PREFIX + String(k));
              return v === '' ? null : v;
            },
            setItem: function (k, v) { window.TavernBridge.setVar(PREFIX + String(k), String(v)); },
            removeItem: function (k) { window.TavernBridge.setVar(PREFIX + String(k), ''); },
            clear: function () {},
            key: function () { return null; },
            get length() { return 0; },
          };
          try {
            Object.defineProperty(window, 'localStorage', { value: shim, configurable: true });
          } catch (e) {
            try { window.localStorage = shim; } catch (e2) { console.error('localStorage shim failed', e2); }
          }
        })();
        """

    private const val FETCH_SHIM =
        """
        (function () {
          if (window.__tavernFetchWithTimeout || typeof window.fetch !== 'function') return;
          const nativeFetch = window.fetch.bind(window);
          const hostOf = function (input) {
            try {
              const raw = typeof input === 'string' ? input : input && input.url;
              return new URL(raw, window.location.href).hostname;
            } catch (_) { return ''; }
          };
          window.__tavernFetchWithTimeout = function (fetcher, input, init, timeoutMs) {
            const options = Object.assign({}, init || {});
            const callerSignal = options.signal || (input && input.signal);
            if (callerSignal && callerSignal.aborted) {
              return Promise.reject(callerSignal.reason || new DOMException('Aborted', 'AbortError'));
            }
            const timeout = Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 15000;
            return new Promise((resolve, reject) => {
              const controller = new AbortController();
              let detach = null;
              let settled = false;
              const finish = (callback, value) => {
                if (settled) return;
                settled = true;
                window.clearTimeout(timer);
                if (detach) detach();
                callback(value);
              };
              const timer = window.setTimeout(() => {
                const host = hostOf(input);
                const error = new Error(
                  '连接远程服务超时（' + Math.round(timeout / 1000) + '秒）' +
                  (host ? '：' + host : '') + '，请检查网络或代理'
                );
                error.name = 'TimeoutError';
                controller.abort(error);
                finish(reject, error);
              }, timeout);
              if (callerSignal) {
                const abort = () => {
                  const reason = callerSignal.reason || new DOMException('Aborted', 'AbortError');
                  controller.abort(reason);
                  finish(reject, reason);
                };
                callerSignal.addEventListener('abort', abort, { once: true });
                detach = () => callerSignal.removeEventListener('abort', abort);
              }
              options.signal = controller.signal;
              Promise.resolve()
                .then(() => {
                  if (controller.signal.aborted) {
                    throw controller.signal.reason || new DOMException('Aborted', 'AbortError');
                  }
                  return fetcher(input, options);
                })
                .then(
                  value => finish(resolve, value),
                  error => finish(reject, error),
                );
            });
          };
          window.fetch = function (input, init) {
            const options = Object.assign({}, init || {});
            const timeout = options.tavernTimeoutMs;
            delete options.tavernTimeoutMs;
            return window.__tavernFetchWithTimeout(nativeFetch, input, options, timeout);
          };
        })();
        """

    private const val TOASTR_SHIM =
        """
        (function () {
          const show = function (message) {
            if (message === undefined || message === null || String(message).trim() === '') return;
            try {
              if (window.TavernBridge && typeof window.TavernBridge.showToast === 'function') {
                window.TavernBridge.showToast(String(message));
              } else {
                console.warn(String(message));
              }
            } catch (_) { console.warn(String(message)); }
          };
          window.toastr = window.toastr || {};
          ['info', 'success', 'warning', 'error', 'warn'].forEach(function (type) {
            if (typeof window.toastr[type] !== 'function') window.toastr[type] = show;
          });
        })();
        """

    private const val WAIT_GLOBAL_SHIM =
        "window.waitGlobalInitialized = window.waitGlobalInitialized || function () { return Promise.resolve(); };"

    /** 给卡片动态创建的 iframe srcdoc 打补丁：写入时把兼容垫片插到 <head>（无则前置），保证 waitGlobalInitialized 等在 iframe 脚本解析前存在。 */
    private fun iframePatchJs(context: Context): String {
        // 注入串用 '</scr'+'ipt>' 拼闭合标签，避免外层补丁脚本被提前闭合
        val shimCode =
            loadJquery(context) + LODASH_SHIM + LOCALSTORAGE_SHIM + FETCH_SHIM + TOASTR_SHIM + WAIT_GLOBAL_SHIM
        val shimJson = com.mistbell.tavern.android.extension.scripting.ScriptRuntimeManager.jsonString(shimCode)
        return """
            (function () {
              const desc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'srcdoc');
              if (!desc || !desc.set) return;
              const origSet = desc.set;
              const shim = $shimJson;
              const injection = '<script>' + shim + '</scr' + 'ipt>';
              Object.defineProperty(HTMLIFrameElement.prototype, 'srcdoc', {
                set: function (html) {
                  const m = /<head[^>]*>/i.exec(html);
                  if (m) {
                    const idx = m.index + m[0].length;
                    origSet.call(this, html.slice(0, idx) + injection + html.slice(idx));
                  } else {
                    origSet.call(this, injection + html);
                  }
                },
                get: desc.get,
                configurable: true,
              });
            })();
            """.trimIndent()
    }
}
