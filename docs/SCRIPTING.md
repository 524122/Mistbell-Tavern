# 脚本包 API（JS 沙箱）

规则包可携带一个 JS 脚本（`manifest.json` 声明 `"script": "script.js"`），在 Rhino 沙箱中运行。

## 安全边界

- 引擎：Rhino 1.8（纯 JVM，解释模式），**无文件系统/网络/Java 互操作**（ClassShutter 全拒）
- 每包独立作用域；初始化 50 万指令、单次调用 20 万指令上限 + 3 秒墙钟超时
- 脚本只能访问本页描述的 `tavern` 对象；异常/超时按"该钩子无产出"降级，不影响聊天
- 导入含脚本的包需用户手动确认（代码执行风险提示）

## `tavern` 宿主对象

### 日志与变量

```js
tavern.log("message");                    // → logcat（TAG: JS·<packId>）
tavern.setVar("key", "value");            // 包级持久变量（跨重启保留）
var v = String(tavern.getVar("key"));     // 不存在返回 ""
// 注意：getVar 返回 Java 字符串对象，空串在 JS 中也是真值——先 String() 转换再判空
```

### 提示词注入（生成前）

```js
tavern.onPrompt(function (ctxJson) {
  var ctx = JSON.parse(ctxJson);   // {sessionId, char, user}
  return [{
    position: "SYSTEM_END",        // SYSTEM_START / SYSTEM_END / USER_PREFIX / USER_SUFFIX / ASSISTANT_PREFIX
    name: "my-block",              // 可选，预览来源标注用
    content: "注入的文本"
  }];
  // 返回 null 或 [] = 本轮不注入
});
```

### 回复处理

```js
tavern.onReply(function (text, ctxJson) {
  return text.replace(/foo/g, "bar");   // 返回新文本；多个脚本包链式处理
});
```

### 斜杠命令

```js
tavern.registerCommand("my-cmd", function (args, ctxJson) {
  return "展示给用户的文本";            // 用户在输入框输入 /my-cmd 参数
});
```

### 自定义宏

```js
tavern.registerMacro("mymacro", function (ctxJson) {
  var ctx = JSON.parse(ctxJson);   // {char, user}
  return "宏值";                    // 在世界书/提示词/角色卡里写 {{mymacro}}
});
```

### 事件

```js
tavern.on("generation:ended", function (payloadJson) {
  // 支持：message:sent, message:received, generation:started, generation:ended, chat:changed
  var payload = JSON.parse(payloadJson);   // {sessionId, characterId?}
});
```

## 生命周期

- 包**启用**时评估脚本（顶层应只做注册，避免重活——有 50 万指令预算）
- 包**禁用/删除**时拆除运行时：动态命令、宏、事件处理全部失效
- 覆盖安装同 id 包且脚本变化时自动重载

## 限制

- Rhino 1.8 的 ES6 子集（let/const、箭头函数、模板字符串大多可用；无 ES Module、无 async/await）
- 入参出参一律 JSON 字符串，请用 `JSON.parse` / `JSON.stringify`
- 命令名/宏名大小写不敏感（注册时归一小写）
