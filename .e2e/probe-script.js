(function () {
  'use strict';
  // ---- 状态 ----
  var counters = { message_sent: 0, message_received: 0, generation_started: 0, generation_ended: 0, chat_changed: 0 };
  var logLines = [];
  var startedAt = Date.now();

  var panel = null;
  var out = null;

  function render() {
    if (!out) return;
    var lines = ['probe up ' + Math.round((Date.now() - startedAt) / 1000) + 's'];
    var names = Object.keys(counters);
    for (var i = 0; i < names.length; i++) {
      lines.push(names[i] + '=' + counters[names[i]]);
    }
    lines.push('--- log (tail 16) ---');
    lines.push(logLines.slice(-16).join('\n'));
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
    var defs = [['RUN WRITE', runWrite], ['GENERATE', runGenerate], ['STOP', runStop]];
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

    // 让宿主层把面板矩形上报为可交互区域（否则触摸穿透收不到点击）
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

  // ---- 事件订阅（加载即订阅，先于一切操作） ----
  function subscribe() {
    var names = ['message_sent', 'message_received', 'generation_started', 'generation_ended', 'chat_changed'];
    for (var i = 0; i < names.length; i++) {
      (function (name) {
        try {
          window.TavernHelper.eventOn(name, function (payload) {
            counters[name]++;
            var detail = '';
            try { detail = JSON.stringify(payload); } catch (e) { detail = String(payload); }
            log('EVENT ' + name + ' #' + counters[name] + ' ' + detail);
          });
          log('SUB OK ' + name);
        } catch (e) {
          log('SUB FAIL ' + name + ' ' + e);
        }
      })(names[i]);
    }
  }

  function T() { return window.TavernHelper; }

  // ---- 写 API 探测 ----
  var createdId = null;
  function runWrite() {
    var steps = [];
    function step(name, fn) {
      steps.push(function () {
        return Promise.resolve()
          .then(fn)
          .then(function (r) { log(name + '=' + JSON.stringify(r)); })
          .catch(function (e) { log(name + ' ERROR ' + ((e && e.message) || e)); });
      });
    }

    step('getLastMessageId', function () { return T().getLastMessageId(); });

    step('getChatMessages(5)', function () {
      return T().getChatMessages(5).then(function (msgs) {
        var ids = [];
        for (var i = 0; i < msgs.length; i++) ids.push(msgs[i] && msgs[i].id);
        log('getChatMessages(5).len=' + msgs.length);
        log('getChatMessages(5).ids=' + JSON.stringify(ids));
        var allHaveId = msgs.length > 0;
        for (var j = 0; j < msgs.length; j++) {
          if (typeof msgs[j].id !== 'string' || msgs[j].id.length === 0) allHaveId = false;
        }
        log('getChatMessages(5).allHaveId=' + allHaveId);
        log('getChatMessages(5).lastContentHead=' + JSON.stringify(String(msgs[msgs.length - 1] && msgs[msgs.length - 1].content || '').slice(0, 40)));
        return { len: msgs.length, ids: ids };
      });
    });

    step('setChatMessages(rewrite last)', function () {
      var lastId = T().getLastMessageId();
      return T().setChatMessages([{ id: lastId, content: '[PROBE改写] ' + new Date().toISOString() }]).then(function (r) {
        log('rewriteTargetId=' + JSON.stringify(lastId));
        return r;
      });
    });

    step('createChatMessage(user PROBE追加)', function () {
      return T().createChatMessage({ role: 'user', content: 'PROBE追加' }).then(function (r) {
        if (r && r.id) createdId = r.id;
        return r;
      });
    });

    step('deleteChatMessages(createdId)', function () {
      if (!createdId) return Promise.resolve({ skipped: 'no createdId' });
      return T().deleteChatMessages([createdId]);
    });

    step('setChatMessages(content 20001)', function () {
      var lastId = T().getLastMessageId();
      var big = new Array(20002).join('X'); // 20001 chars
      return T().setChatMessages([{ id: lastId, content: big }]);
    });

    step('deleteChatMessages(missing id)', function () {
      return T().deleteChatMessages(['no-such-id-000']);
    });

    step('substituteParams', function () {
      return T().substituteParams('{{char}}与{{user}}');
    });

    step('substituteParams(20006 chars)', function () {
      var big = '{{user}}' + new Array(20001).join('Y'); // 20006 chars
      var r = T().substituteParams(big);
      return Promise.resolve({ len: r.length, head: r.slice(0, 14), keptLiteral: r.indexOf('{{user}}') === 0 });
    });

    var i = 0;
    function next() {
      if (i >= steps.length) { log('WRITE DONE'); return; }
      steps[i++]().then(next, next);
    }
    next();
  }

  function runGenerate() {
    Promise.resolve()
      .then(function () { return T().generate(); })
      .then(function (r) { log('generate=' + JSON.stringify(r)); })
      .catch(function (e) { log('generate ERROR ' + ((e && e.message) || e)); });
  }

  function runStop() {
    try { T().stopGeneration(); log('stopGeneration called'); } catch (e) { log('stop ERROR ' + e); }
  }

  // ---- 启动：轮询桥就绪 ----
  var tries = 0;
  function boot() {
    tries++;
    if (window.TavernBridge && window.TavernHelper && document.body) {
      panelInit();
      subscribe();
      log('BOOT OK tries=' + tries);
      // 轻量初始探测（此时可能还没有消息）
      Promise.resolve()
        .then(function () { return T().getLastMessageId(); })
        .then(function (id) { log('initial getLastMessageId=' + JSON.stringify(id)); })
        .catch(function (e) { log('initial probe ERROR ' + e); });
    } else if (tries < 60) {
      setTimeout(boot, 250);
    } else {
      log('BOOT FAIL: bridge not ready');
    }
  }
  boot();
})();
