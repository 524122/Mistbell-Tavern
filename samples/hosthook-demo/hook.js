// 宿主钩子示例扩展包（manifest.hostHooks，P3 起在 Rhino 沙箱中真实执行）。
//
// 权限裁剪（PermissionBroker）：本包 manifest.permissions 只声明了
// messages:read / events:subscribe / prompt:inject，因此 tavern 对象上**存在**的方法是
//   log / getChatMessages / getLastMessageId / on / onPrompt
// 未声明能力的方法（setChatMessages、createChatMessage、deleteChatMessages、
// triggerGenerate、stopGeneration、onReply、getVar、setVar、getVariables、
// insertOrAssignVariables）在 tavern 对象上**不存在**——typeof 为 "undefined"，
// 调用直接 TypeError（VS Code 命名空间供给模式），而不是运行时报「无权限」。
// 想验证：取消下方 DIAGNOSTIC 注册的注释，导入后看 logcat（TAG: JSEXT·hosthook-demo）。
//
// 执行模型与规则包脚本一致：每包独立作用域、JSON 字符串出入参、
// 初始化 50 万 / 单次调用 20 万指令预算、3 秒墙钟；钩子异常/超时按「无产出」降级。

// 生成前注入：读当前会话消息，把「本轮可见消息条数」注入系统段尾。
tavern.onPrompt(function (ctxJson) {
  var ctx = JSON.parse(ctxJson);                 // {sessionId, char, user}
  var messages = JSON.parse(tavern.getChatMessages(10)); // [{id, role, content}]
  return [{
    position: "SYSTEM_END",                      // SYSTEM_START/SYSTEM_END/USER_PREFIX/USER_SUFFIX/ASSISTANT_PREFIX
    name: "message-census",
    content:
      "[宿主钩子示例] 会话 " + ctx.sessionId + " 最近可见消息 " + messages.length +
      " 条，最新角色：" + (messages.length ? messages[messages.length - 1].role : "无"),
  }];
  // 返回 null / [] / 抛异常 = 本轮不注入（无产出降级，不影响生成）
});

// 事件订阅：AI 回复落库后在 logcat 留痕（TAG: JSEXT·hosthook-demo）。
// 支持事件与规则包相同：message:sent / message:received / generation:started /
// generation:ended / chat:changed / variables:updated
tavern.on("message:received", function (payloadJson) {
  var payload = JSON.parse(payloadJson);         // {sessionId, characterId?}
  var lastId = tavern.getLastMessageId();        // messages:read 授权的方法
  tavern.log("message:received sessionId=" + payload.sessionId + " lastId=" + lastId);
});

// ---- DIAGNOSTIC（可选）：验证未授权方法确实不存在 ----
// tavern.on("message:sent", function () {
//   var probe = {
//     hasWrite: typeof tavern.setChatMessages,        // → "undefined"（未声明 messages:write）
//   };
//   try {
//     tavern.setChatMessages("[]");                   // → TypeError，方法不在对象上
//     probe.writeCall = "unexpected-ok";
//   } catch (e) {
//     probe.writeCall = e instanceof TypeError ? "typeerror" : String(e);
//   }
//   tavern.log(JSON.stringify(probe));                // → {"hasWrite":"undefined","writeCall":"typeerror"}
// });
