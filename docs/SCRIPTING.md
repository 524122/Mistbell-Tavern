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
  // 支持：message:sent, message:received, generation:started, generation:ended, chat:changed, variables:updated
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

## MVU 变量兼容层（MVU-lite）

净室实现的 MagVarUpdate 兼容层：解析 AI 回复中的变量更新指令，维护按会话隔离的变量树，供 HTML 界面与脚本包读写。**不是**酒馆助手 MVU 的移植——仅契约兼容（指令格式、事件名、门面方法名），实现自研。

### 回复指令格式（模型输出侧）

回复落库前，`MvuExtension`（回复后处理链 loadingOrder=400）扫描并剥离：

```
<UpdateVariable><Analysis>…模型分析…</Analysis><JSONPatch>
[
  {"op": "replace", "path": "/主角状态/修为/当前境界", "value": "筑基六层"},
  {"op": "delta",   "path": "/主角状态/修为/进度百分比", "value": 10},
  {"op": "remove",  "path": "/临时状态/护盾"}
]
</JSONPatch></UpdateVariable>
```

- `replace`：直接写入（缺键自动建路径）；`delta`：数值增量（已有值为非数值则跳过，缺键从 0 起）；`remove`：删除节点
- 路径按 `/` 分段，段内禁止 `..`、`__proto__` 与协议符，非法 patch 整条跳过不影响其余
- 变量树持久化在 settings 表（`ext.mvu.<sessionId>`），按会话隔离；版本号单调递增（`ext.mvu.ver.<sessionId>`）
- 应用成功后发射 `TavernEvent.VariablesUpdated` → 脚本包收到 `variables:updated` 事件、HTML 界面收到 `mvu_variable_update_ended` 事件
- 剥离后的正文不含 `<UpdateVariable>` 块，正常渲染

### HTML 界面门面（TavernHelper）

```js
// 读整棵变量树（JSON 字符串）
var vars = JSON.parse(TavernHelper.getVariables() || "{}");
// 顶层键浅合并写入；返回新版本号
TavernHelper.insertOrAssignVariables({"主角状态": {"修为": {"进度百分比": 42}}});
// 事件订阅（酒馆助手事件名直通）
TavernHelper.eventOn("mvu_variable_update_ended", function (varsJson) { … });
TavernHelper.eventOn("mvu_command_parsed", …);   // patch 解析完成时也会派发
// 触发斜杠命令
TavernHelper.triggerSlash("/note 提示");
// window.Mvu 别名：Mvu.events.COMMAND_PARSED / VARIABLE_UPDATE_ENDED 常量可用
```

### 边界

- 不支持：MVU 的 zod schema 校验（`registerMvuSchema` 为空操作占位）、增量 diff 通知细粒度路径（事件携带整树）
- 后续可在此基础上推出自有格式，指令解析器与存储层已解耦（`data/mvu/MvuEngine` 纯 JVM，可独立单测）
