# 扩展平台设计：兼容层插件化

> 状态：P1/P2 已实施（P1 制品与生命周期 4b1c4a5、P2 预注入与门面迁出 2950430，均经模拟器 E2E）；
> P3/P4 未实施。实施与设计的偏差以代码与 CHANGELOG 为准（两处值得一提：权限「扩权回退」的
> UI 路径例外见 P1 提交；extension_packs 最终为 v38 而非 v37 就地改——「未发布 schema 也不可
> 原地改」的教训见迁移对齐测试）。
> 设计稿（2026-10-31，基于代码盘点 + 上游先例调研）
> 决策背景：酒馆助手类兼容层后续不能再靠内置硬编码随发版追赶（FACADE_JS/桥写死在
> `HtmlWebViewFactory`/`ChatViewModel` 里）。方向已定：**引擎保持原生，门面层做成可安装制品**。
> 配合 [SCRIPTING.md](SCRIPTING.md)（现有门面契约）与 [WEB_CONTENT_RUNTIME_DESIGN.md](WEB_CONTENT_RUNTIME_DESIGN.md)（沙箱边界）阅读。

---

## 1. 调研结论（为什么这个分层成立）

### 1.1 上游本来就是「扩展提供门面」

酒馆助手（JS-Slash-Runner v4.11.3）在 SillyTavern 里的真实形态（源码核实）：

- 它是**标准 ST UI 扩展**：`manifest.json` 声明 `js: dist/index.js` + `hooks.activate`；
- 客户端层在 ST 页面上下文执行，62 处 `from '@sillytavern/script'` 消费核心单例，
  把 `variables/worldbook/lorebook/chat_message/generate/character/persona/preset/slash/…`
  共 26 个模块约 200+ 函数组装为 `globalThis.TavernHelper`；
- 前端界面 iframe 用 `srcdoc` 注入、**无 sandbox 属性（与 ST 页面同源）**，
  `predefine.js` 直接从 `window.parent` 拷贝函数引用重绑到 iframe 的 `window`
  （**直接求值，不是 postMessage RPC**——postMessage 仅用于视口高度通知）；
- 脚本前端（script frontend）各自跑在独立 iframe runtime，事件经包装器注册回父页 `eventSource`。

也就是说：**ST 核心只提供引擎 API（eventSource / getContext / SlashCommandParser），
TavernHelper 门面是扩展在其上组装的**。我们要做的正是补上这一层分层——
引擎（消息库/生成/事件/变量/正则/桥）原生，门面（契约组装）可安装。

### 1.2 与上游的关键差异：信任边界

ST 的扩展是全信任同上下文 JS（官方文档原话「几乎不受限制的访问」，无沙箱无权限）。
移动端不采纳这个模型，改为**按声明裁剪**（见 §4 权限模型）——
注入机制保持契约语义（直绑而非 RPC），但每个扩展只拿到声明且被批准的能力。

### 1.3 许可证红线（净室边界收紧）

| 对象 | 许可证 | 结论 |
|---|---|---|
| SillyTavern 本体 | AGPL-3.0 | 任何文件不可拷贝/移植 |
| 酒馆助手（含 `@types/` 契约声明文件） | PolyForm Noncommercial 1.0.0 | **连 `.d.ts` 都不可拷贝**；净室依据只能是公开行为文档（JS-Slash-Runner-Doc 文档站、ST 官方文档）与黑盒行为 |
| QuickJS / quickjs-ng / Hermes | MIT | 宿主脚本引擎候选，合规 |
| quickjs-kt / zipline 等绑定 | Apache-2.0 | 合规 |

官方仓库只分发**自有净室表达**的兼容扩展；第三方若自行移植酒馆助手源码安装，责任在安装者。

---

## 2. 分层模型

```
卡片 / HTML 内容（不可信，WebView 沙箱 —— 现状不变）
      ↑ 门面 API（TavernHelper / Mvu 契约，由扩展提供）
兼容扩展（可安装制品：门面 JS 预注入 + 宿主钩子脚本 + 权限声明）
      ↑ 能力代理（PermissionBroker，按扩展过滤）
引擎（原生，唯一可信计算基：消息库 / 生成管线 / 事件总线 / 变量 / 正则 / 桥）
```

关键洞察：**门面 90% 是「预注入进 WebView 的 JS」**——不需要新的 JS 引擎就能起步，
只需要预注入机制（§5）+ 能力代理（§4）。QuickJS 仅在宿主钩子需要现代 JS 时才引入（§7，P4 可选）。

---

## 3. 制品格式（扩展包）

zip 制品，`manifest.json` 在规则包字段基础上扩展（解析复用 `RulePackParser` 骨架）：

```jsonc
{
  // ---- 与规则包共用 ----
  "id": "tavernhelper-compat",        // ^[A-Za-z0-9_-]{1,64}$
  "name": "TavernHelper 兼容层",
  "author": "…", "version": "1.0.0",
  "loadingOrder": 100,
  // ---- 扩展包新增 ----
  "type": "extension",                // 区分于 rulepack/theme
  "provides": ["facade:tavernhelper"],// 门面提供者声明（互斥语义见 §5.3）
  "webviewPreload": "facade.js",      // 预注入每个卡片 WebView 的门面 JS（<64KB）
  "hostHooks": "hook.js",             // 宿主侧钩子脚本（onPrompt/onReply/on，走沙箱）
  "permissions": [                    // 安装时展示、用户批准（§4）
    "prompt:inject", "reply:process", "events:subscribe",
    "messages:read", "generation:control",
    "network:cangxuan-forum.cangxuanjie.workers.dev"
  ],
  "minimum_client_version": "0.9.7"
}
```

- **存储**：参照主题包目录模型（`filesDir/extensions/<id>/`，带 JS/资产文件；规则包的
  DB content_json 模型不适合多文件制品）。
- **导入/启停/删除/覆盖重载**：复用规则包管线（preview → 确认 → persist →
  `ScriptRuntimeManager.reconcile` 同款 hash 比对自动装卸）。
- 既有规则包/主题包格式不受影响（`type` 缺省 = 规则包，向后兼容）。

---

## 4. 权限模型（PermissionBroker）

权限门控的对象是**扩展在宿主进程内拿到的东西**，不是卡片（卡片在 WebView 里的
能力面是既有产品决策，现状不变）：

| 权限 | 门控什么 | 现状对照 |
|---|---|---|
| `prompt:inject` / `reply:process` | 宿主钩子进 PromptInterceptor / ReplyPostProcessor 链 | 脚本包已有，目前仅一次性导入确认 |
| `events:subscribe` | 订阅 TavernEventBus | 脚本包已有（白名单 6 事件） |
| `messages:read/write` | 宿主侧消息读写 API（`HtmlBridgeHost` 同款能力开进 `TavernHostApi`） | **脚本包完全没有，属新增面，必须权限门控** |
| `generation:control` | generate/stop | 同上 |
| `variables` | 包级变量 + MVU 树读写 | 包级已有，MVU 新增 |
| `network:<host>` | WebView CSP `connect-src` 追加声明域（安装时确认） | 白名单现为编译期常量，需数据驱动化 |

三层机制（借鉴浏览器扩展 + VS Code）：

1. **安装时**：manifest `permissions` 全量展示，用户一次批准（复用「包含可执行脚本」
   确认对话框先例，升级为权限清单形态）；授权结果随包持久化。
2. **注入侧裁剪**：为每个扩展构造**只含已授权能力**的宿主 API 实例（VS Code
   命名空间供给模式）——未声明的方法在代理层不存在，而非运行时报错。
3. **运行时请求**（P3+，可选）：`optional` 能力首次调用时弹窗（浏览器
   `optional_permissions` 模式）。

网络白名单改造：`ALLOWED_CONNECT_HOSTS` 从编译期 `setOf` 改为
「基础集 ∪ 已启用扩展的已授权 `network:` 域」，CSP 在 WebView 装配时动态拼装
（HtmlUiSandbox 注入点不变，只是常量变查询）。

---

## 5. 门面预注入机制

### 5.1 注入串改造（现状 → 目标）

现状（`HtmlWebViewFactory.create`）：前置垫片（jQuery/lodash/fetch/toastr，
`HtmlPrereqs.inject` 插 `<head>` 解析期可用）→ CSP meta → 卡片内容 →
**onPageFinished 后才 `evaluateJavascript(FACADE_JS)`**——卡片自身内联脚本
解析期看不到 `TavernHelper`（与酒馆助手原版行为有偏差：其 predefine 是内容前注入的）。

目标注入串（全部在文档组装期，即页面解析期之前）：

```
基础垫片（HtmlPrereqs，原生保留）
→ [已启用扩展的 webviewPreload JS，按 loadingOrder]   ← 门面层迁到这里
→ CSP meta（connect-src 按授权动态拼装）
→ 卡片内容
```

`HtmlPrereqs` 的 `<head>` 前插通道已存在且 iframe srcdoc 同步打补丁的先例也有——
这是**小改**不是新造。副作用是兼容性提升：卡片内联脚本解析期即可见门面，更贴近上游契约。

### 5.2 拆分：bootstrap 桥（原生保留）与门面（迁出）

- **原生保留**：`TavernBridge`（@JavascriptInterface 全集）、`__tavernEmit` 事件管道
  （有界队列 + 游标补推）、高度/滚动回报、CSP 组装。这是引擎的一部分，不插件化。
- **迁出为官方扩展制品**：现 `FACADE_JS`（`window.TavernHelper` / `window.Mvu` /
  `window.eventOn` 的组装、Promise 包装、契约糖）——成为 `tavernhelper-compat`
  扩展包的 `facade.js`，随仓库净室维护、独立于发版迭代。

### 5.3 提供者互斥

同一 `provides` 名（如 `facade:tavernhelper`）同时只允许一个扩展处于激活态，
管理界面可切换——这是「契约版本档」的解法（v1/v2 契约差异做成两个包，用户选择激活）。
未激活的提供者不参与注入串。

---

## 6. 能力代理：第一批开放清单

全部为已存在实现（`HtmlBridgeHost` + `ChatRepository.bridge*`），只做「开给扩展宿主 API」的
代理与权限过滤，**无新引擎能力**：

- 消息：`messages:read`（getChatMessages/getLastMessageId）、`messages:write`
  （setChatMessages/createChatMessage/deleteChatMessages，含既有上限契约）
- 生成：`generation:control`（generate/stop，复用 `launchGeneration` 状态机）
- 宏渲染：`substituteParams`（权限并入 `prompt:inject`）
- 变量：包级 getVar/setVar（已有）+ MVU 树读写（`variables`）
- 事件：`events:subscribe`（6 事件白名单，两套事件名风格在代理层归一）
- 正则：`getTavernRegexes`（只读，并入 `messages:read`）

> 当前工作区未提交批次（消息写 API / 聊天事件推送 / generate / substituteParams）
> 的能力面与此清单完全重合——该批次即是能力代理的第一批弹药，**不因架构演进而白做**。

暂不做：WebView 页面脚本参与 prompt/reply 钩子链（页面 → 宿主链路的反向调用，
复杂度高、需求未证实）；HtmlScript 的应用内启停管理 UI（另立小任务）。

---

## 7. 宿主脚本运行时

- **现阶段**：`hostHooks` 继续走 Rhino（onPrompt/onReply/事件订阅的既有能力足够，
  安全机器全部复用：ClassShutter 白名单、50 万/20 万指令预算、3s 墙钟、每包独立 scope）。
- **P4（可选）**：quickjs-ng（MIT，活跃）+ quickjs-kt（Apache-2.0，KMP，支持
  async/ESM）作为宿主运行时替换/并行，解锁 Promise 重度脚本——选型已核许可证。
  Hermes（MIT）支持独立嵌入但需自编 AAR，成本高收益低，不选。

---

## 8. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| P1 制品与生命周期 | `type: extension` 包格式、导入/启停/删除/覆盖重载（复用规则包管线）、权限清单确认 UI、filesDir 存储 | 导入含权限声明的扩展包可启停；未确认权限的包不激活 |
| P2 预注入机制 | 注入串改造（§5.1）、FACADE_JS 拆分为 bootstrap 桥 + 官方 tavernhelper-compat 扩展包、提供者互斥 | 卡片内联脚本解析期可见 TavernHelper；卸载扩展包后门面消失；模拟器实测回归既有卡片 |
| P3 能力代理完备 | PermissionBroker、按扩展裁剪宿主 API、网络白名单数据驱动、（可选）optional 运行时请求 | 声明了 messages:write 的扩展可经宿主钩子改消息；未声明者代理层无此方法 |
| P4（可选） | QuickJS 宿主运行时 | async 脚本可跑，安全预算等价 |

每阶段按既有惯例：单测 + 模拟器实测后提交。

---

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| 恶意扩展经 `messages:write` 篡改会话 | 权限清单安装时明示；官方扩展经仓库净室审查；能力上限契约（20000 字符/50 条）已在代理层保留 |
| 门面注入串变长拖慢 WebView 装配 | preload 限 64KB；注入串按「启用扩展集」缓存拼接结果，随启停失效 |
| 多扩展争抢全局名（window.TavernHelper） | provides 互斥 + loadingOrder 确定序；管理界面可见冲突 |
| 契约漂移（上游 v1/v2 差异） | 版本档制品并存、用户切换激活 |
| 净室边界滑坡 | ST（AGPL）与酒馆助手（PolyForm-NC，含 @types）零拷贝；净室依据限定公开文档与黑盒行为 |

---

## 10. 调研来源

- ST 扩展系统：docs.sillytavern.app/for-contributors/writing-extensions/；
  github.com/SillyTavern/SillyTavern（release 分支 extensions.js/events.js 源码核对）
- 酒馆助手：github.com/N0VI028/JS-Slash-Runner（main 分支全量源码分析：manifest、
  src/function/index.ts、src/iframe/predefine.js、@types/ 目录）；文档站
  n0vi028.github.io/JS-Slash-Runner-Doc；LICENSE（PolyForm-NC 1.0.0）
- JS 引擎：bellard.org/quickjs；github.com/quickjs-ng/quickjs；
  github.com/facebook/hermes（均 MIT）；绑定 github.com/dokar3/quickjs-kt、
  github.com/cashapp/zipline（Apache-2.0）
- 权限模式：MDN manifest.json/permissions；VS Code Activation Events / Capabilities 官方文档
- 代码盘点：本仓库 extension/、ui/chat/htmlui/、data/rulepack/ 全量（file:line 见调研记录）
