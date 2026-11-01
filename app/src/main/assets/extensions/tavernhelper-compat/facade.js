// TavernHelper 兼容层门面（官方内置扩展包 tavernhelper-compat 的 webviewPreload 制品）。
//
// 注入时序：随文档 <head> 组装期注入（解析期可用，先于卡片自身脚本）——与酒馆助手上游
// predefine 行为对齐。引擎原生部分由宿主提供且不随本包启停：
//   - TavernBridge 桥（addJavascriptInterface，先于文档加载注册）
//   - __tavernListeners / __tavernEmit 事件管道（原生 EVENT_BUS_JS 段）
//   - 高度/滚动回报、CSP 组装
// 本制品停用/删除时上述全局不存在，卡片自身脚本报错属预期，宿主不受影响。
//
// 净室边界：本文件为自有表达，仅依据公开行为文档与既有宿主契约搬迁，不含任何上游拷贝。

(function () {
  'use strict';
  if (window.TavernHelper) return; // 重复注入守卫（覆盖安装/多包场景幂等）
  if (!window.TavernBridge) {
    // 引擎桥缺失（理论不可达）：静默退出，不抛错拖垮文档解析
    return;
  }

  // 事件监听注册表：原生段已初始化 window.__tavernListeners；此处的兜底初始化保证
  // 本制品被注入到没有原生段的环境（如 iframe shim 链）时 eventOn 依然可用
  var listenersFor = function (key) {
    var store = (window.__tavernListeners = window.__tavernListeners || {});
    return (store[key] = store[key] || []);
  };

  window.TavernHelper = {
    getVar: (k) => window.TavernBridge.getVar(k),
    setVar: (k, v) => window.TavernBridge.setVar(k, String(v)),
    getChatMessages: (n) => Promise.resolve(JSON.parse(window.TavernBridge.getChatMessages(n || 20))),
    sendMessage: (t) => window.TavernBridge.sendMessage(String(t)),
    eventEmit: (t, p) => window.TavernBridge.eventEmit(String(t), JSON.stringify(p ?? null)),
    eventOn: (name, fn) => {
      const key = String(name);
      listenersFor(key).push(fn);
    },
    log: (m) => window.TavernBridge.log(String(m)),
    getVariables: () => Promise.resolve(JSON.parse(window.TavernBridge.getMvuVariables())),
    insertOrAssignVariables: (obj) => Promise.resolve(window.TavernBridge.insertMvuVariables(JSON.stringify(obj ?? {}))),
    registerMvuSchema: (schema) => window.TavernBridge.log("registerMvuSchema 占位（v1 不做 schema 校验）"),
    triggerSlash: (cmd) => window.TavernBridge.triggerSlash(String(cmd)),
    getTavernRegexes: () => Promise.resolve(JSON.parse(window.TavernBridge.getTavernRegexes())),
    getLastMessageId: () => window.TavernBridge.getLastMessageId(),
    setChatMessages: (list) => Promise.resolve(JSON.parse(window.TavernBridge.setChatMessages(JSON.stringify(list ?? [])))),
    createChatMessage: (msg) => Promise.resolve(JSON.parse(window.TavernBridge.createChatMessage(JSON.stringify(msg ?? {})))),
    deleteChatMessages: (ids) => Promise.resolve(JSON.parse(window.TavernBridge.deleteChatMessages(JSON.stringify(ids ?? [])))),
    // 生成控制：generate 不插入用户消息直接生成一条回复（{started:true} 或 {error:...}）；
    // stopGeneration 与输入框「停止生成」同一通道
    generate: () => Promise.resolve(JSON.parse(window.TavernBridge.triggerGenerate())),
    stopGeneration: () => window.TavernBridge.stopGeneration(),
    // 宏替换：{{char}}/{{user}} 等宏按当前角色与用户名渲染（超长输入原样返回）
    substituteParams: (s) => window.TavernBridge.substituteParams(String(s)),
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
})();
