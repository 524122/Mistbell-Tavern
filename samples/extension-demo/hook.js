// 示例扩展包的宿主钩子脚本（manifest.hostHooks）。
// P1 仅做存在性与大小校验（≤256KB）并随包落盘到 filesDir/extensions/<id>/，
// 不解析、不挂接钩子链——宿主钩子执行接线在 P2/P3。
function onPrompt(context) {
  return null;
}

function onReply(text, context) {
  return text;
}
