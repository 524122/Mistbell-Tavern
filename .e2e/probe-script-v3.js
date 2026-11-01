(function () {
  'use strict';
  // P2 探测脚本 v3：
  // - 事件订阅双通道：门面 eventOn（via=fx，随内置包启停存在/消失）+ 原生注册表
  //   __tavernListeners 直推（via=native，EVENT_BUS_JS 恒在）——T3 用 native 通道证明
  //   内置包停用时事件推送仍送达。
  // - generation_ended 处理器直接走 TavernBridge.getChatMessages 取末条，验证
  //   regenerate 修复的「Ended 时新回复已可见」。
  // - 面板按钮全部带门面守卫：内置包停用时打日志跳过，不抛错。
  var natCount = { message_sent: 0, message_received: 0, generation_started: 0, generation_ended: 0, chat_changed: 0 };
  var fxCount = { message_sent: 0, message_received: 0, generation_started: 0, generation_ended: 0, chat_changed: 0 };
  var logLines = [];
  var startedAt = Date.now();
  var panel = null;
  var out = null;
  var hasFx = typeof window.TavernHelper !== 'undefined';

  function render() {
    if (!out) return;
    var lines = ['probe3 up ' + Math.round((Date.now() - startedAt) / 1000) + 's fx=' + hasFx];
    var names = Object.keys(natCount);
    for (var i = 0; i < names.length; i++) {
      lines.push(names[i] + ' nat=' + natCount[names[i]] + ' fx=' + fxCount[names[i]]);
    }
    lines.push('--- log (tail 14) ---');
    lines.push(logLines.slice(-14).join('\n'));
    out.textContent = lines.join('\n');
  }

  function log(line) {
    logLines.push(line);
    try { window.TavernBridge.log('PROBE ' + line); } catch (e) { /* noop */ }
    render();
  }

  function panelInit() {
    panel = document.createElement('div');
    panel.id = 'probe-panel';
    panel.style.cssText = 'position:fixed;top:6px;right:6px;z-index:2147483647;background:rgba(10,12,10,0.85);color:#7CFC7C;font:11px/1.45 monospace;padding:8px;border:1px solid #2a5a2a;border-radius:8px;max-width:94vw;max-height:60vh;overflow:auto;';
    var controls = document.createElement('div');
    var defs = hasFx ? [['RUN WRITE', runWrite], ['GENERATE', runGenerate], ['STOP', runStop]] : [['RUN WRITE', function(){log('fx missing; skip');}],['GENERATE', function(){log('fx missing; skip');}]];
    for (var i = 0; i < defs.length; i++) {
      var b = document.createElement('button');
      b.textContent = defs[i][0];
      b.style.cssText = 'margin:2px;padding:3px 8px;font:11px monospace;background:#1d331d;color:#7CFC7C;border:1px solid #3a7a3a;border-radius:4px;';
      b.addEventListener('click', defs[i][1]);
      controls.appendChild(b);
    }
    panel.appendChild(controls);
    out = document.createElement('pre');
    out.id = 'probe-out';
    out.style.cssText = 'margin:6px 0 0;white-space:pre-wrap;color:#c8ffc8;';
    panel.appendChild(out);
    document.body.appendChild(panel);

    var origCollect = window.__tavernCollectRegions;
    window.__tavernCollectRegions = function () {
      var outJson;
      try {
        outJson = origCollect ? origCollect() : JSON.stringify({ vw: window.innerWidth, regions: [], fullscreen: false });
      } catch (e) {
        outJson = JSON.stringify({ vw: window.innerWidth, regions: [], fullscreen: false });
      }
      var rep;
      try { rep = JSON.parse(outJson); } catch (e) { rep = { vw: window.innerWidth, regions: [], fullscreen: false }; }
      try {
        var r = panel.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) rep.regions.push([r.left, r.top, r.right, r.bottom]);
      } catch (e) { /* noop */ }
      return JSON.stringify(rep);
    };
  }

  var NAMES = ['message_sent', 'message_received', 'generation_started', 'generation_ended', 'chat_changed'];

  function onEnded(payload, via) {
    // Ended 时取末条消息（直接走桥，不经门面）——验证 regenerate「Ended 时新回复已可见」
    function peek(tag) {
      try {
        var arr = JSON.parse(window.TavernBridge.getChatMessages(1));
        var last = arr[arr.length - 1];
        log('ENDED-PEEK(' + tag + ' via=' + via + ') role=' + (last && last.role) + ' content=' + JSON.stringify(String((last && last.content) || '').slice(0, 36)));
      } catch (e) { log('ENDED-PEEK ERROR ' + e); }
    }
    peek('t0');
    setTimeout(function () { peek('t600'); }, 600);
  }

  function subscribeNative() {
    for (var i = 0; i < NAMES.length; i++) {
      (function (name) {
        try {
          var store = (window.__tavernListeners = window.__tavernListeners || {});
          (store[name] = store[name] || []).push(function (payload) {
            natCount[name]++;
            var detail = '';
            try { detail = JSON.stringify(payload); } catch (e) { detail = String(payload); }
            log('EVENT ' + name + ' via=native #' + natCount[name] + ' ' + detail);
            if (name === 'generation_ended') onEnded(payload, 'native');
          });
          log('SUB native OK ' + name);
        } catch (e) { log('SUB native FAIL ' + name + ' ' + e); }
      })(NAMES[i]);
    }
  }

  function subscribeFacade() {
    if (typeof window.TavernHelper === 'undefined') return;
    for (var i = 0; i < NAMES.length; i++) {
      (function (name) {
        try {
          window.TavernHelper.eventOn(name, function (payload) {
            fxCount[name]++;
            var detail = '';
            try { detail = JSON.stringify(payload); } catch (e) { detail = String(payload); }
            log('EVENT ' + name + ' via=fx #' + fxCount[name] + ' ' + detail);
          });
          log('SUB fx OK ' + name);
        } catch (e) { log('SUB fx FAIL ' + name + ' ' + e); }
      })(NAMES[i]);
    }
  }

  function T() { return window.TavernHelper; }

  var createdId = null;
  function runWrite() {
    if (!hasFx) { log('fx missing; skip WRITE'); return; }
    var steps = [];
    function step(name, fn) {
      steps.push(function () {
        return Promise.resolve().then(fn)
          .then(function (r) { log(name + '=' + JSON.stringify(r)); })
          .catch(function (e) { log(name + ' ERROR ' + ((e && e.message) || e)); });
      });
    }
    step('getLastMessageId', function () { return T().getLastMessageId(); });
    step('getChatMessages(5)', function () {
      return T().getChatMessages(5).then(function (msgs) {
        var ids = [];
        for (var i = 0; i < msgs.length; i++) ids.push(msgs[i] && msgs[i].id);
        log('getChatMessages(5).len=' + msgs.length + ' ids=' + JSON.stringify(ids));
        return { len: msgs.length };
      });
    });
    step('setChatMessages(rewrite last)', function () {
      var lastId = T().getLastMessageId();
      return T().setChatMessages([{ id: lastId, content: '[P2改写] ' + new Date().toISOString() }]);
    });
    step('createChatMessage(user P2追加)', function () {
      return T().createChatMessage({ role: 'user', content: 'P2追加' }).then(function (r) {
        if (r && r.id) createdId = r.id;
        return r;
      });
    });
    step('deleteChatMessages(createdId)', function () {
      if (!createdId) return Promise.resolve({ skipped: 1 });
      return T().deleteChatMessages([createdId]);
    });
    step('setChatMessages(20001)', function () {
      var lastId = T().getLastMessageId();
      return T().setChatMessages([{ id: lastId, content: new Array(20002).join('X') }]);
    });
    step('deleteChatMessages(missing)', function () {
      return T().deleteChatMessages(['no-such-id-000']);
    });
    step('substituteParams', function () {
      return T().substituteParams('{{char}}与{{user}}');
    });
    var i = 0;
    function next() { if (i >= steps.length) { log('WRITE DONE'); return; } steps[i++]().then(next, next); }
    next();
  }

  function runGenerate() {
    if (!hasFx) { log('fx missing; skip GENERATE'); return; }
    Promise.resolve().then(function () { return T().generate(); })
      .then(function (r) { log('generate=' + JSON.stringify(r)); })
      .catch(function (e) { log('generate ERROR ' + ((e && e.message) || e)); });
  }

  function runStop() {
    try { T().stopGeneration(); log('stopGeneration called'); } catch (e) { log('stop ERROR ' + e); }
  }

  var tries = 0;
  function boot() {
    tries++;
    if (window.TavernBridge && document.body) {
      hasFx = typeof window.TavernHelper !== 'undefined';
      panelInit();
      subscribeNative();
      if (hasFx) {
        subscribeFacade();
        log('BOOT OK fx=present tries=' + tries);
      } else {
        log('BOOT DEGRADED: TavernHelper missing (native events only) tries=' + tries);
      }
      try { log('initial getLastMessageId=' + JSON.stringify(window.TavernBridge.getLastMessageId())); } catch (e) { log('init probe ERROR ' + e); }
    } else if (tries < 20) {
      setTimeout(boot, 250);
    } else {
      log('BOOT FAIL: bridge not ready');
    }
  }
  boot();
})();
