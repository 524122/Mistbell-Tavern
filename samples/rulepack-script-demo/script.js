// 示例脚本包：演示 tavern 宿主 API 的四种钩子 + 事件 + 包级变量
// 详细 API 见 docs/SCRIPTING.md

// 1) 提示词注入：返回注入块数组（position 五落点之一）
tavern.onPrompt(function (ctxJson) {
  var ctx = JSON.parse(ctxJson);
  // 注意：getVar 返回 Java 字符串对象（空串也是真值），先 String() 转换再判空；
  // isNaN 防御：历史脏数据（如旧版本脚本写坏的值）自动归零
  var raw = String(tavern.getVar("promptCount") || "");
  var parsed = parseInt(raw === "" ? "0" : raw, 10);
  var count = (isNaN(parsed) ? 0 : parsed) + 1;
  tavern.setVar("promptCount", String(count));
  return [{
    position: "SYSTEM_END",
    name: "script-demo",
    content: "【脚本示例】这是脚本包第 " + count + " 次生成提示词，当前会话 " + ctx.sessionId + "。"
  }];
});

// 2) 回复处理：把"笨蛋"替换为加粗（返回新文本）
tavern.onReply(function (text, ctxJson) {
  return text.replace(/笨蛋/g, "**笨蛋**");
});

// 3) 斜杠命令：/js-demo [参数]
tavern.registerCommand("js-demo", function (args, ctxJson) {
  var count = tavern.getVar("promptCount") || "0";
  return "示例脚本包在线：已注入提示词 " + count + " 次";
});

// 4) 自定义宏：{{jsdemo}} → 当前注入计数
tavern.registerMacro("jsdemo", function (ctxJson) {
  return tavern.getVar("promptCount") || "0";
});

// 5) 事件：生成结束时记日志
tavern.on("generation:ended", function (payloadJson) {
  tavern.log("generation ended: " + payloadJson);
});
