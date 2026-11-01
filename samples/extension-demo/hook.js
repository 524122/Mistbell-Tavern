// 示例扩展包的宿主钩子脚本（manifest.hostHooks，P3 起在 Rhino 沙箱中执行）。
// 本包声明了 prompt:inject/reply:process/events:subscribe/messages:read +
// network:<host>，因此 tavern 上可用：log/onPrompt/onReply/on/getChatMessages/
// getLastMessageId（network:<host> 不是 tavern 方法，走 WebView CSP 放行）；
// 写消息/生成控制/变量方法未声明，在 tavern 对象上不存在。
// 完整演示见 samples/hosthook-demo/（含 onLoad 语义注释与未授权方法探针）。
function onPrompt(context) {
  return null;
}

function onReply(text, context) {
  return text;
}
