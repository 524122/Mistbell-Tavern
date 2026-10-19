# Web 内容运行时设计说明

状态：设计提案

适用项目：Mistbell Tavern Android

关联文档：

- [Agent 运行时设计说明](AGENT_RUNTIME_DESIGN.md)
- [会话内长期记忆设计说明](SESSION_SCOPED_MEMORY.md)
- [SillyTavern 能力实现计划](SILLYTAVERN_ANDROID_IMPLEMENTATION_PLAN.md)

## 1. 目标

项目需要支持作者制作地图、手机、背包、任务和自定义聊天界面。若每个作者都通过 Compose 编写 Android 页面，开发和维护成本会随着作者内容快速增长。

本项目采用：

> **原生 Android 宿主 + Web 内容模块 + 统一状态和动作桥**

Web 负责作者内容和界面表现，原生负责数据、Agent、工具、权限和持久化。这个方案降低作者开发成本，同时保持聊天和游戏状态的一致性。

## 2. 方案选择

### 2.1 保留原生界面

以下页面优先保留在原生 Android：

- 设置；
- API 配置；
- 角色管理；
- 备份和恢复；
- 权限；
- 导入导出；
- 系统级错误和诊断。

### 2.2 Web 内容面板

适合放在 WebView 的作者内容：

- 地图；
- 手机；
- 背包；
- 任务；
- 关系面板；
- 作者自定义交互；
- 动画和游戏内 HUD。

### 2.3 Web ChatSurface

当聊天界面只需要换主题时，继续使用 Compose；当聊天结构需要大幅变化时，允许内容包提供 Web ChatSurface：

- 自定义消息气泡；
- 消息内地图或手机卡片；
- 工具调用卡片；
- 剧情选项按钮；
- 多角色布局；
- 对话和游戏 HUD 组合布局。

Web ChatSurface 是渲染层，不负责 LLM、Room 或 Agent 状态。

### 2.4 完整 Web 套壳

完整 Web 套壳需要为数据库、LLM、权限、备份、通知和 Android 生命周期维护一层桥接。当前项目不采用它作为第一阶段方案。

如果未来需要 PWA 或桌面 Web 版本，可以复用状态、工具和事件协议，再实现新的宿主。

## 3. 总体架构

~~~mermaid
flowchart LR
    WEB[Web ChatSurface / Panels] -->|dispatchAction| HOST[Native Host]
    HOST --> STATE[Session State Store]
    HOST --> DB[Room]
    HOST --> AGENT[Agent Runtime]
    HOST --> LLM[LLM / SSE]
    HOST -->|state.snapshot / state.patch| WEB
    HOST -->|message.delta / tool.event| WEB
    USER[User Input] --> WEB
~~~

原生宿主负责 Room 消息和会话数据、PromptBuilder、LLM 请求和 SSE、Agent 工具调用、记忆、游戏状态和事件日志、Android 权限、备份和删除。

Web 模块负责 HTML/CSS/JavaScript 界面、消息和状态可视化、作者定义的交互和动画、发送动作请求，以及面板展开和滚动等局部 UI 状态。

## 4. 内容包结构

作者内容以 ZIP 内容包导入：

~~~text
game-pack/
├── manifest.json
├── state-schema.json
├── tools/
├── rules/
├── prompts/
├── chat/
│   ├── index.html
│   ├── app.js
│   └── style.css
├── panels/
│   ├── map/
│   ├── phone/
│   └── quest/
├── assets/
└── migrations/
~~~

manifest 至少声明 ID、版本、最低应用版本、capability 和 UI 入口：

~~~json
{
  "id": "mistbell-city",
  "version": "1.0.0",
  "minAppVersion": "0.10.0",
  "capabilities": ["map", "phone", "quest"],
  "ui": {
    "chatSurface": "chat/index.html",
    "panels": {
      "map": "panels/map/index.html",
      "phone": "panels/phone/index.html"
    }
  }
}
~~~

内容包导入时必须校验 manifest、版本、路径、文件大小和数量、JSON 深度、资源类型、UI 入口、工具 ID 冲突和状态 schema。

## 5. 桥接协议

### 5.1 原生推送给 Web

~~~text
state.snapshot
state.patch
session.changed
message.added
message.updated
message.delta
message.deleted
generation.started
generation.completed
generation.failed
tool.started
tool.completed
tool.rejected
event.emitted
ui.notice
~~~

### 5.2 Web 发送给原生

~~~text
ui.ready
chat.send
chat.stop
chat.regenerate
chat.continue
chat.swipe
chat.backtrack
action.dispatch
draft.set
panel.open
panel.close
history.load_older
~~~

原生到 Web 的事件和 Web 到原生的动作应包含 protocolVersion、sessionId、requestId、timestamp 和 payload。

### 5.3 统一动作路径

Web 页面不直接修改状态，只发送动作请求：

~~~json
{
  "type": "action.dispatch",
  "protocolVersion": 1,
  "sessionId": "session-123",
  "requestId": "req-456",
  "payload": {
    "actionId": "phone.send_message",
    "arguments": {
      "contactId": "aria",
      "content": "你现在在哪里？"
    }
  }
}
~~~

原生运行时负责：

1. 校验 sessionId 是否为当前会话；
2. 检查 action 是否由当前内容包声明；
3. 校验参数 schema；
4. 检查权限和当前状态；
5. 执行状态效果；
6. 写入事件日志；
7. 返回成功、失败或需要确认的结果。

Agent 工具调用必须使用相同的执行路径，不能为 Web 再实现一份逻辑。

## 6. Web ChatSurface

### 6.1 结构化消息

Web 接收结构化消息，而不是未经说明的 HTML 字符串：

~~~json
{
  "id": "message-123",
  "role": "assistant",
  "speakerId": "aria",
  "content": "我已经到了图书馆。",
  "contentFormat": "markdown",
  "createdAt": "2026-10-11T10:00:00Z",
  "toolTrace": [],
  "attachments": []
}
~~~

Web 自己负责渲染样式，但模型输出不能未经处理就作为可执行 HTML。Markdown、HTML 标签和图片资源必须经过限制和清理。

### 6.2 流式输出

流式输出使用增量事件：

~~~json
{
  "type": "message.delta",
  "payload": {
    "messageId": "message-123",
    "delta": "我已经到了"
  }
}
~~~

不要每个 token 都推送完整会话快照。可以在原生侧聚合一小段时间后发送 delta，并在结束时推送完整消息状态。

### 6.3 工具调用展示

Agent 工具调用可以在聊天中显示为结构化卡片，包含工具名称、参数摘要、执行状态和结果。作者可以自定义卡片表现，但工具调用的真实结果由原生宿主提供。

### 6.4 大会话性能

Web ChatSurface 应使用虚拟列表：

- Room 保存完整消息；
- 原生只推送当前窗口；
- Web 只保留可见消息附近的 DOM；
- 向上滚动时发送 history.load_older；
- 流式消息只更新当前气泡；
- 附件使用安全引用，不把大文件复制进消息事件。

## 7. 状态同步

首次加载使用完整快照 state.snapshot，后续使用路径级补丁 state.patch：

~~~json
{
  "type": "state.patch",
  "payload": {
    "changes": [
      {
        "path": "world.currentLocation",
        "value": "library"
      },
      {
        "path": "phone.unreadCount",
        "value": 2
      }
    ]
  }
}
~~~

Web 断线或页面重载后发送 ui.ready，原生重新推送当前会话快照。Web 不能把自己的旧状态当作权威状态覆盖原生数据。

## 8. 安全边界

Web 内容包和页面必须遵守：

- 使用应用内资源加载器和固定来源；
- 禁止任意跨源请求；
- 禁止访问文件系统；
- 禁止读取 API Key；
- 禁止直接访问 Room；
- 禁止直接调用 Android 原生对象；
- 禁止通过消息内容执行脚本；
- 所有状态变更通过 action.dispatch；
- 所有 action 必须由当前内容包声明；
- 外部通知、真实短信和网络请求需要独立权限和确认。

桥接实现优先使用受控的消息通道或消息端口，不把拥有大量权限的对象直接暴露给 JavaScript。内容包中的 JavaScript 只负责界面和受限交互。

## 9. 作者扩展方式

### 9.1 数据扩展

作者通过 JSON 增加地图地点、路线和进入条件、联系人和手机线程、任务和目标、物品、关系值和事件规则。

### 9.2 Web 界面扩展

作者通过 HTML/CSS/JavaScript 增加自定义面板、聊天布局、动画、工具调用卡片和 HUD。

### 9.3 受限动作扩展

作者通过 manifest、参数 schema 和效果声明增加工具，不需要修改宿主 Kotlin 代码。

任意 JavaScript 逻辑、任意网络、动态加载 APK 或直接访问 Android API 不属于第一阶段能力。需要自定义算法时，再单独设计受限规则 DSL 或可信插件 SDK。

## 10. 原生回退和兼容

Web 内容包出现入口缺失、版本不兼容、桥协议不支持、页面加载失败、执行超时或崩溃时，应用应回退到原生聊天界面。

回退时，消息、状态和 Agent 行动仍然保存在原生运行时，不能因 Web 页面失败而丢失当前会话。

协议使用显式版本号。新增字段应保持向后兼容；破坏性变更需要增加协议主版本并提供迁移。

## 11. 实施顺序

### P0：桥接协议

- 定义 snapshot、patch、message 和 action 的 JSON schema；
- 定义 requestId、sessionId 和错误格式；
- 建立原生 ChatRuntime 与 Compose 的事件接口；
- 不改变现有聊天行为。

### P1：Web 预览聊天

- 实现只读 Web ChatSurface；
- 显示已有消息和角色信息；
- 支持 Markdown、流式 delta 和历史分页；
- 失败时自动回退 Compose。

### P2：输入和聊天动作

- 接入发送、停止、重生、继续生成、swipe 和回退；
- 保存草稿；
- 完善键盘、返回键和生命周期处理；
- 让 Web 和 Compose 共用同一个 ChatRuntime。

### P3：游戏面板

- 地图、手机、任务和背包面板；
- state.snapshot 和 state.patch；
- action.dispatch；
- Agent 工具事件显示。

### P4：作者内容包

- ZIP 导入和 manifest 校验；
- Web 资源沙箱；
- 状态 schema、工具 schema 和规则包；
- 内容包版本和迁移；
- 内容包与角色卡的绑定。

## 12. 验收标准

- Web ChatSurface 和 Compose 使用同一份消息、状态和 Agent 运行时；
- Web 页面不能直接访问 Room、API Key、文件和任意网络；
- 用户动作和 Agent 动作进入同一个原生校验路径；
- 流式输出、停止、重生、继续生成和回退行为一致；
- Web 页面刷新后可以通过 snapshot 恢复当前会话；
- Web 页面加载失败时消息和状态不丢失；
- 大会话不会因完整 DOM 渲染导致明显卡顿；
- 作者可以通过内容包增加地图、手机或任务界面；
- 内容包不能访问其他会话；
- Markdown、HTML、附件和消息内容经过安全处理；
- 协议升级不会破坏旧内容包，或提供明确迁移错误。
