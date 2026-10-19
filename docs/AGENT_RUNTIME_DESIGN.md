# Agent 运行时设计说明

状态：设计提案

适用项目：Mistbell Tavern Android

关联文档：

- [会话内长期记忆设计说明](SESSION_SCOPED_MEMORY.md)
- [Web 内容运行时设计说明](WEB_CONTENT_RUNTIME_DESIGN.md)
- [SillyTavern 能力实现计划](SILLYTAVERN_ANDROID_IMPLEMENTATION_PLAN.md)

## 1. 目标

项目后续的 Agent 方向不是把聊天请求包装成更复杂的循环，而是让 AI 角色生活在一个有状态的游戏世界中：角色可以观察当前世界，调用地图、手机、任务和物品等设施，根据工具结果继续行动，并通过界面把行动呈现给用户。

目标链路：

~~~text
观察会话状态
→ 理解用户意图和当前剧情
→ 选择受控工具
→ 执行并校验状态变化
→ 获取工具结果
→ 继续行动或生成角色叙事
→ 记录事件并更新界面
~~~

Agent 的价值是完成普通单轮聊天无法可靠完成的多步任务，例如查看当前位置、读取游戏内手机、发送消息、移动到地点、查询任务和物品、根据规则推进剧情。

## 2. 设计决策

### 2.1 状态优先于记忆

确定性的游戏事实必须存储为会话状态，不能依赖 LLM 记忆或向量检索推断：

~~~text
world.currentLocation 角色当前位置
phone.threads          手机会话和未读消息
quests.*.status        任务状态
inventory.items        背包物品
flags.*                世界状态标记
~~~

结构化记忆用于保存叙事事实，向量记忆用于找回旧消息；二者不能替代游戏状态。

### 2.2 所有状态默认为会话级

本项目采用会话内记忆，不使用跨会话记忆。Agent 可访问的状态、工具和事件必须绑定当前会话：

- 新建会话拥有独立的状态空间；
- 同一角色的新会话不会自动继承旧会话状态；
- 工具不能查询其他会话；
- 结构化记忆和向量召回只允许当前会话；
- 未来若需要章节或存档共享，应引入明确的 campaign 或存档机制。

### 2.3 Agent 不直接访问基础设施

模型、作者脚本和 Web 页面不能直接访问 Room、文件系统、API Key 或 Android 原生对象。所有操作必须经过宿主提供的工具和动作接口。

## 3. 总体架构

~~~mermaid
flowchart LR
    UI[地图 / 手机 / 任务 / 聊天 UI] -->|dispatchAction| RT[Agent Runtime]
    USER[用户消息] --> RT
    RT --> OBS[Session Observer]
    OBS --> STATE[Session State]
    RT --> MODEL[LLM Provider]
    MODEL --> CALLS[Tool Calls]
    CALLS --> VALIDATE[Schema + Permission Validation]
    VALIDATE --> EFFECTS[State Effects]
    EFFECTS --> EVENTS[Event Log]
    EVENTS --> STATE
    STATE --> UI
    RT --> PROMPT[PromptBuilder / Memory]
    PROMPT --> MODEL
~~~

运行时建议由以下模块组成：

~~~text
AgentRuntime           回合协调和循环控制
SessionStateStore      当前会话状态读写
SessionEventStore      事件追加、查询和撤销
ToolRegistry           工具声明和查找
ToolExecutor           参数校验、权限校验和执行
EffectExecutor         set/append/emit 等基础效果
AgentProviderAdapter   原生 tool calling / JSON fallback 适配
AgentTraceStore        调试、预览和行动记录
~~~

## 4. 会话状态和事件

### 4.1 状态快照

第一版可以使用会话状态快照保存当前值：

~~~text
session_game_state
- session_id
- schema_version
- state_json
- updated_at
~~~

状态 JSON 通过作者内容包中的 schema 校验。状态路径使用命名空间，例如 world.currentLocation、world.time、phone.threads、quests.active、inventory.items、relationships 和 flags。

### 4.2 事件日志

所有状态变化追加一条事件：

~~~text
session_game_events
- id
- session_id
- event_type
- payload_json
- source
- tool_call_id
- created_at
~~~

事件类型包括 location_changed、phone_message_sent、quest_updated、item_acquired、relationship_changed 和 flag_changed。

状态快照用于快速读取，事件日志用于审计、调试、撤销和重建。第一版可以先实现快照加事件追加，之后再增加完整事件回放。

### 4.3 事务边界

一个工具调用造成的状态变化和事件写入必须在同一个事务内完成：

~~~text
校验参数
→ 计算状态变化
→ 写入事件
→ 更新状态快照
→ 提交事务
→ 推送 UI 事件
~~~

事务失败时，不能只更新 UI 或只写事件。

## 5. 工具接口

### 5.1 统一领域模型

原生工具可以使用以下接口形状：

~~~kotlin
interface ToolProvider {
    fun definitions(context: ToolContext): List<ToolDefinition>

    suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolResult
}

data class ToolDefinition(
    val id: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val readOnly: Boolean,
    val requiresConfirmation: Boolean,
)

data class ToolCall(
    val callId: String,
    val toolId: String,
    val arguments: JsonObject,
)

data class ToolResult(
    val callId: String,
    val success: Boolean,
    val output: JsonElement,
    val stateChanges: List<StateChange>,
)
~~~

ToolContext 至少包含当前 sessionId、用户身份、内容包 ID、当前角色和取消信号。工具不应从全局单例自行读取会话。

### 5.2 第一批内置工具

~~~text
world.get_current_time()
world.get_current_location()
map.inspect(locationId)
map.travel(locationId)
phone.list_threads()
phone.read_thread(threadId)
phone.send_message(threadId, content)
quest.list_active()
inventory.list_items()
relationship.get(characterId)
dice.roll(expression)
~~~

工具应返回结构化结果，不直接返回只供模型阅读的长文本。模型需要叙事时，再把结构化结果转换为角色语言。

### 5.3 工具权限

每个工具声明只读或写入、允许访问的状态路径、是否需要确认、是否允许 Agent 使用、是否允许 Web 使用、是否允许自动触发。

| 工具 | 访问范围 | 默认确认 |
|---|---|---:|
| 查看当前位置 | 当前会话状态 | 否 |
| 读取手机消息 | 当前会话状态 | 否 |
| 发送游戏内消息 | 当前会话状态 | 否 |
| 删除物品 | 当前会话状态 | 是 |
| 发送真实系统通知 | Android 外部能力 | 是 |
| 访问其他会话 | 禁止 | 不适用 |

游戏内手机消息和真实短信必须是不同能力。第一版只实现游戏内消息。

## 6. 作者内容包

普通作者不应被要求编写 Kotlin。作者使用内容包声明自己的状态、工具、规则、提示词和 Web UI：

~~~text
game-pack/
├── manifest.json
├── state-schema.json
├── tools/
├── rules/
├── prompts/
├── ui/
├── assets/
└── migrations/
~~~

manifest 声明内容包 ID、版本、最低应用版本、capability 清单和 UI 入口。

### 6.1 声明式工具

作者可以用 JSON 声明工具和基础效果：

~~~json
{
  "id": "phone.send_message",
  "description": "通过游戏内手机发送消息",
  "inputSchema": {
    "type": "object",
    "required": ["contactId", "content"],
    "properties": {
      "contactId": { "type": "string" },
      "content": { "type": "string", "maxLength": 500 }
    }
  },
  "effects": [
    {
      "type": "append",
      "path": "phone.threads.{contactId}.messages",
      "value": {
        "sender": "active_character",
        "content": "{content}"
      }
    },
    {
      "type": "emit",
      "event": "phone.message_sent"
    }
  ]
}
~~~

第一版基础效果控制在 set、append、remove、increment、decrement、emit、schedule 和 show_panel。

### 6.2 复杂规则

规则通过 on_session_start、on_user_message、on_tool_called、on_location_enter、on_time_changed、on_phone_message 和 on_quest_completed 等事件触发。

规则可以读取当前状态、判断条件并产生效果，但不能直接访问 Android 或网络。

## 7. Agent 回合

一次 Agent 回合：

1. 读取当前会话状态；
2. 读取当前用户消息和必要的同会话历史；
3. 构建角色、世界规则、状态和可用工具的 prompt；
4. 调用 LLM；
5. 解析工具调用；
6. 校验 schema、权限和当前状态；
7. 执行工具并写入事件；
8. 将工具结果返回给 LLM；
9. 达到结束条件后生成角色回复；
10. 保存消息、状态和 Agent trace。

必须设置硬限制：每轮最大工具调用次数、单次工具超时、总回合超时、最大工具结果长度、最大循环次数。用户取消后立即停止，工具失败后不能无限重试。

### 7.1 Provider 适配

优先使用模型原生 tool calling；不支持的兼容网关使用严格 JSON action 作为回退：

~~~text
native tool call -> ToolCall
JSON action      -> ToolCall
ToolResult       -> provider-specific tool result
~~~

Agent Runtime 不应依赖某一家模型的专有字段。

### 7.2 行动记录

每轮记录用户输入摘要、使用的工具、参数和结果摘要、状态变化路径、失败原因和最终回复。记录默认脱敏，不保存 API Key 和不必要的完整隐私内容。Prompt Preview 可以显示行动来源和状态变化。

## 8. 与记忆系统的关系

Agent 使用三类上下文：

~~~text
当前会话游戏状态       确定性事实，优先级最高
当前会话结构化记忆     叙事事实和关系变化
当前会话向量/词法召回  找回被裁剪的旧消息
~~~

地图位置、任务状态和物品数量从状态读取，不从记忆推断。状态变化成功后，可以由记忆抽取管道生成当前会话记忆。

## 9. 安全边界

内容包和 Agent 工具不能读取其他会话、API Key、Room 或文件系统；不能默认访问网络；不能执行任意 Android API；不能绕过状态 schema 写入未知字段；不能把游戏内消息发送成真实短信。外部副作用必须经过用户授权。

## 10. 分阶段实施

### P0：宿主基础

- 定义 SessionStateStore 和 SessionEventStore；
- 定义 ToolDefinition、ToolCall、ToolResult；
- 实现工具 schema 和权限校验；
- 为当前会话增加 Agent trace；
- 固定所有状态和记忆使用 sessionId。

### P1：只读 Agent

- 读取时间、位置、手机和任务；
- 接入 Agent 回合协调器；
- 支持 native tool calling 和 JSON fallback；
- 完成取消、超时和最大调用次数限制。

### P2：会话内状态变更

- 实现移动、发送游戏内消息、任务更新和物品变化；
- 状态和事件使用事务写入；
- UI 订阅状态变化；
- 支持撤销或回退状态变化。

### P3：作者内容包

- manifest 和版本校验；
- 状态 schema；
- 声明式工具和规则；
- 资源校验和路径限制；
- 导入、导出和迁移。

### P4：复杂玩法

- 受限规则 DSL；
- 定时事件；
- 多角色可见性；
- 多 NPC Agent；
- 需要时再评估可信插件 SDK。

## 11. 验收标准

- Agent 只能访问当前会话状态；
- 用户操作和 Agent 操作使用同一动作执行路径；
- 工具参数错误不会破坏状态；
- 工具调用超过限制时会停止并给出可解释结果；
- 状态和事件写入具有一致的事务边界；
- 应用重启后同一会话状态可以恢复；
- 删除会话后状态、事件、记忆和索引全部清理；
- 作者可以不修改 Kotlin 代码就增加地图、手机或任务工具；
- Agent 失败时仍能回退为普通角色回复或明确提示失败原因。
