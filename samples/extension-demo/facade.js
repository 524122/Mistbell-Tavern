// 示例扩展包的门面预注入脚本（manifest.webviewPreload）。
// P1 仅做存在性与大小校验（≤64KB）并随包落盘到 filesDir/extensions/<id>/，
// 不解析、不注入、不执行——WebView 预注入接线在 P2。
window.TavernHelper = window.TavernHelper || {};
window.TavernHelper.extensionDemo = { greeting: "hello from extension-demo" };
