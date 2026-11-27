# 界面模式：简洁模式是执行边界

> P3 第 1、2、3 项（docs/ROADMAP.md「简洁模式是执行边界」一节 + 「P3 门面」第 1、2、3 项）。
> 本文档是这三项的规则表、测试矩阵与实现接法的唯一落地说明。
> §1–§3 是第 1 项（执行边界与闸门）；§4 是第 2 项（按模式收拢界面、缺省与升级判定）；
> §5 是第 3 项（导入层与导入报告）；§6 是第 4 项（首启与默认层）。

## 1. 规则

两种模式：

| 层 | 给谁 | 内容 |
|---|---|---|
| 简洁模式（`simple`） | 没用过酒馆的用户 | 三个 Tab 的默认层；本表「不执行」的能力一律关闭 |
| 高级模式（`advanced`） | 带酒馆资产来的用户 | 现有能力原样保留，含正则、脚本、HTML 界面、扩展钩子 |

**照常执行**（任何模式下都不闸）：

- **世界书四源**：会话书、人设书、角色书、全局书（`PromptBuilder.loadWorldBookEntries()`）。**不滤成只留角色书**——否则从高级切回简洁时，已经配好的书会静默消失。编辑入口收到高级模式（第 2 项）。
- 内置宏（`MacroEngine.render`）
- 开场白（`ChatSetupViewModel`）
- 会话记忆
- 自定义提示词（`CustomPromptExtension`，纯文本，经 `ExtensionRegistry`）
- 内置斜杠命令 `/help`、`/note`
- 快捷回复聚合（`QuickReplyRepository` 读原始缓存，不在执行闸门链上）

**不执行，也不在后台跑**：

| 能力 | 闸门（本项落地位置） |
|---|---|
| 卡级与全局正则（展示、用户输入、提示词三路） | 两处装配点：`PromptBuilder.loadCore()`（提示词路）与 `ChatViewModel` 的全局/卡级 Flow（展示、用户输入、HTML 界面脚本三路）。简洁模式返回空表；`apply` 拿空表是空操作，不要求零调用 |
| 规则包：提示词块、输入输出正则、包宏 | 包源上闸一次（`UiModeGatedRulePackSource` 装饰器），`RulePackExtension` 四个入口自然不生效 |
| 规则包脚本 | 同上——`ScriptRuntimeManager.source` 挂装饰器，简洁模式给 `reconcile` 空表，走现有 `unload`。只在 `dispatchEvent` 提前返回不够：已加载的脚本还在沙箱里，提示词与回复钩子照跑 |
| 扩展包钩子 | `ExtensionHookRuntimeManager` 的 `modeProvider` 并进 `start()` 的 combine，简洁模式给 `onPacksChanged` 空表，走现有 `unload` |
| 卡内脚本与 HTML 界面（WebView、桥、宿主层、悬浮球、预热） | 模式并进 `htmlUiEnabled` 这一个 Flow。气泡、宿主层、悬浮球、WebView 预热（`ChatScreen` 已只看它）都以它为条件，不写第二套判断。简洁模式下不得创建 WebView |
| MVU 变量更新 | 照常调用 `MvuEngine.process` 并返回它的 `cleanedText`；简洁模式不写 `ext.mvu.*`、不发 `VariablesUpdated`，且连读库也不做。**不新写剥离器，也绝不在 process 之前直接返回原文**——块会落库并送给模型 |
| 脚本注册的斜杠命令 | 随脚本卸载从 `ExtensionRegistry` 摘除（`unload` 里的既有注销逻辑）；内置命令保留 |

通用约定（全部已落实）：

- **所有闸门都是响应式的。** 从高级切回简洁，重新走一遍卸载、清空、回落；切回高级重新加载。没有一处「只在启动时判断」——设置值是 Flow / 每次装配读取，模式一变订阅处即重算。
- **模式未知时一律先不放行。** `AppContainer.uiMode` 是可空 StateFlow（`null` = 启动后 ui_mode 还没从数据库读回的几毫秒）：规则包源闸此刻发射 `null`（`awaitPacks` 自然等待，不误判为「无包」）、钩子管理器给 `onPacksChanged` 空表（先不加载）、MVU 的 `modeProvider().first()` 挂起等待——宁可多等一次数据库读，绝不让脚本/钩子在未知模式下抢先加载执行。
- **界面与闸门同一口径：模式未知时不渲染任何随模式变化的入口。** 界面统一读进程级常驻流 `AppContainer.uiMode`（`StateFlow<UiMode?>`，Eagerly、从不停止）；界面层不做自己的 `stateIn`（初值 + `WhileSubscribed(5000)` 会造成「未知按高级」且订阅断开 5 秒后反复重置的闪错）。null 期间：主导航不显示任何 Tab（空栏一帧）、设置页与聊天设置页不渲染模式相关入口、「界面模式」行显示「读取中…」占位——绝不闪错误的入口或落「高级」的缺省观感。`SettingsRepository.observeUiMode()`（非空、缺省高级）只作为常驻流的推导源与执行闸门的逐次读取，不再是界面的读取口。
- **单一真相**：`data.model.UiMode`（零依赖）+ `enabledCapabilities(mode)`。所有闸门只问它，不各自判断模式。
- 关掉后的兜底，不让原始内容裸露，剥 HTML 标签发生在 `MessageBubble.kt` 的**两处**分支：
  1. `legacyMessageBody` 逐段分支里 `LiteHtmlPolicy.isLayoutHtml` 为假且 `renderHtml` 为假的 else（原来把原始标签当纯文本显示）→ `HtmlPlainText.toPlainText(part.html)`；
  2. `legacyMessageBody` 最下面的 else（`renderHtml=false && hasLayoutHtml=false`，即纯脚本界面卡最常走的路径）→ `fallbackDisplayedContent`：文档段过 `toPlainText`、Markdown 段原样按序重拼；纯文本/Markdown（无 `HtmlDoc` 段）逐字节不变，仍是单个 `MessageMarkdown(displayedContent)`。
  带 `<style>`、`<table>` 且无脚本的片段仍走 litehtml，不经过兜底。包宏的 `{{x}}` 占位符原样留下，不处理（导入报告里说明，属第 3 项）。

**缺省行为**：`ui_mode` 未设置或无法识别 → `ADVANCED`（执行闸门的缺省，第 1 项棘轮，勿改）。
缺省之上的设备级判定是第 2 项：全新安装显式落 `simple`、升级首启弹一次选择，见 §4.3。

## 2. 能力 × 模式 × 验证方式（测试矩阵）

| 能力 | 简洁 | 高级 | 验证（单测） | 验证（模拟器实测，后续任务） |
|---|---|---|---|---|
| 世界书四源注入 | ✔ | ✔ | `PromptBuilderUiModeWorldBookTest`（四源内容进提示词） | 探测卡 + 全局/人设书，查看提示词 |
| 卡级与全局正则·展示 | ✘ | ✔ | `ChatViewModelUiModeGateTest`、`PromptBuilderUiModeRegexGateTest`（逐字节） | 气泡无改写痕迹 |
| 卡级与全局正则·用户输入 | ✘ | ✔ | 同上（applyToUserInput 逐字节） | 发送的消息原文进上下文 |
| 卡级与全局正则·提示词 | ✘ | ✔ | `PromptBuilderUiModeWorldBookTest`（真实装配链）+ `PromptBuilderUiModeRegexGateTest` | 查看提示词无改写 |
| 规则包提示词块 | ✘ | ✔ | `RulePackExtensionUiModeGateTest`（decorator 真件） | 查看提示词无规则包块 |
| 规则包输入/输出正则 | ✘ | ✔ | 同上 | 回复与用户消息无改写 |
| 规则包包宏 | ✘ | ✔ | 同上（resolve 返回 null） | `{{宏}}` 原样留在文本里 |
| 规则包脚本 | ✘ | ✔ | `ScriptRuntimeManagerUiModeGateTest`（加载→卸载→恢复） | 脚本钩子不注入、onReply 不改写 |
| 脚本注册的斜杠命令 | ✘ | ✔ | 同上（注册表摘除/恢复） | `/脚本命令` 提示未注册 |
| 扩展包钩子 | ✘ | ✔ | `ExtensionHookRuntimeManagerUiModeGateTest`（加载→卸载→恢复） | 钩子不注入 |
| 卡内脚本与 HTML 界面 | ✘ | ✔ | `ChatViewModelUiModeGateTest.htmlUiEnabledForUiMode`（设置开+简洁=假） | logcat 无 WebView 创建；气泡走兜底文本 |
| MVU 变量更新 | ✘ | ✔ | `MvuExtensionUiModeGateTest`（cleanedText、无写库、无事件、块不外漏） | 无 `ext.mvu.*` 键、HTML 界面无变量推送 |
| 切回简洁的响应式卸载 | — | — | 两个 RuntimeManager 测试的「切回简洁」段 | 实测第 3 步：高级→简洁后脚本与钩子被卸载 |
| 设置项切换 | — | — | 界面可见性断言见 §4.6（`UserModeConfigVisibilityTest` 等） | 设置 → 界面模式，改写即时生效 |

## 5. 导入报告（第 3 项：导入层）

> P3 第 3 项（docs/ROADMAP.md「导入报告」小节 +「P3 门面」第 3 项）。执行边界是 §1–§3，
> 本节只回答一件事：**导入时如实告诉用户哪些能力要切到高级模式才生效**。本项不写任何闸门。

### 5.1 页面与到达入口

新页面「导入」（路由 `import`，`ui/import/ImportScreen.kt` + `ImportViewModel.kt`），五类入口全部
复用现有导入实现，不复制逻辑：

| 入口 | 复用的现有实现 | 说明 |
|---|---|---|
| 角色卡 | `CharacterImporter.importFromUri`（PNG 埋卡 / CHARX / JSON 按文件头识别）+ 共享落库 `CharacterImportPersist` | 落库核心从 `CharacterListViewModel.importCharacter` 提取，角色 Tab 直接导入与导入页共用一份（世界书先行、按名匹配原地更新） |
| 酒馆数据文件夹 | `StSyncImporter.syncFromFolder` | 与角色 Tab 的批量导入同一实现 |
| 世界书 | `WorldBookImporter.importFromUri` | 从 `WorldBookListScreen` 的内联导入分支提取，世界书 Tab 与导入页共用一份 |
| 聊天记录 | `SessionImportPersist.importFromUri` | 从 `ChatListViewModel` 的导入分支提取，聊天 Tab 与导入页共用一份 |
| 备份 | `BackupManager.restoreFromUri` | 与设置页数据备份同一实现 |

三个到达入口：角色 Tab 顶栏新增「导入中心」按钮（在现有直接导入按钮**旁**——高级模式下
原有的直接导入与批量导入路径保持可达）；聊天列表空态的「从酒馆导入…」次要按钮；
首启引导第 1 步的「导入我的角色卡」按钮（改为进入导入页，引导其余内容不动）。

### 5.2 「需要高级模式」的判定

判定是零依赖纯函数 `util/AdvancedCapabilityDetector.kt`（唯一换算点；核心只吃
`extensions + 文本语料`，再提供三种输入适配）。`CharacterImportResult` 与 `SyncReport` 各有
默认为「无」的字段（`AdvancedCapabilityProfile` / `AdvancedCapabilityTotals`）：

| 字段 | 判定口径 |
|---|---|
| `regexCount` | `extensions.regex_scripts` 的条目数（缺字段 / 非数组计 0） |
| `scriptCount` | `extensions.tavern_helper.scripts` 的段数（数组取长度、对象取键数） |
| `hasHtmlUi` | 开场白（含 `alternate_greetings`）、示例对话、角色描述、世界书条目里出现 `HtmlCardText.containsHtml` 口径的片段。该判定已从 `HtmlCardDetector.containsHtml` 下沉为 `util/HtmlCardText`（零依赖），楼层检测器改为委托它——导入判定与楼层拆分共用同一口径，行为逐字节不变 |
| `hasMvuUpdates` | 同一语料里出现 `<UpdateVariable>` 标记（忽略大小写） |

单卡报告按数量列出（正则 N 条 / 卡内脚本 N 段 / HTML 界面 / 状态栏变量更新）；批量
（`SyncReport.advancedCapabilities`）给聚合：正则 / 脚本按条数（段数）累加，HTML / MVU 按命中
角色计数，并附涉及角色名（去重保序）。

原来只进 logcat 的导入诊断提示（「N 条提示」那批）在导入页的报告明细里**全部可见**
（诊断提示 / 同名跳过 / 跳过文件 / 失败文件，超 50 条截断并提示总数）。

### 5.3 两个选择的语义

导入完成后报告同页展示。当前简洁且确有高级能力（`hasAny`）时，报告下方给两个按钮
（`ImportReportCoordinator.modeChoiceFor` 是唯一换算点，模式未知不渲染）：

- **切换到高级模式**：只写 `ui_mode = advanced`（`SettingsRepository.setUiMode`），
  报告保留；执行闸门（§1–§3）与 Tab 收拢（§4.1）经既有 Flow 响应式生效，能力立即恢复。
- **保持简洁**：**不写任何设置**，只关掉报告。上表能力保持关闭，不在后台悄悄跑
  （闸门在 §1，本项不再新增任何闸门）。

当前已是高级模式时只做说明（「以上能力照常运行」），不给切换按钮。模式未知（null）
期间不渲染选择区，与 §1「未知不渲染」同一口径。

包宏的 `{{x}}` 占位符会原样留在文本里，不处理——导入报告的能力栏下方固定附一句说明。

### 5.4 「含高级能力，未启用」标记（不落库）

角色列表在**简洁模式**下给带高级能力的卡显示小标记「含高级能力，未启用」。标记**不落库、
无 schema 变更**：列表按需用同一个 `AdvancedCapabilityDetector.detect(领域模型)` 现场计算
（与导入时 `detect(实体, 条目)` 是同一核心、同一口径，同一张卡两种表示判定一致，单测背书）。
高级模式不显示（能力已启用）；模式未知不显示（§1 口径）。

### 5.5 测试矩阵（第 3 项）

| 验证点 | 单测 | 装机实测（后续任务） |
|---|---|---|
| 四字段命中 / 不命中、世界书条目 HTML、默认值、extensions 容错 | `AdvancedCapabilityDetectorTest` | 探测卡四类能力各导入一次，报告逐栏核对 |
| 三种输入一致（导入结果 = 实体+条目 = 领域模型） | 同上（`CardParser填充能力概况…`、`同一张卡实体与领域模型判定一致`） | 简洁模式下角色卡出现「含高级能力，未启用」标记 |
| 批量聚合（数量累加、角色名去重保序、空批为无） | 同上 | 文件夹同步后批量报告的数量与角色名 |
| 报告字段缺省为「无」（既有测试兼容） | 同上（`SyncReport新字段默认为无`）+ 既有 `CardParserTest` / `StSyncImporterTest` 全绿 | — |
| 两个选择的状态机 | `ImportReportCoordinatorTest` | 简洁导入探测卡 → 出现两按钮；切换后正则 / 脚本 / HTML / MVU 生效；再导入一张 → 无切换按钮只有说明；「保持简洁」后设置里 ui_mode 不变 |
| 楼层检测器口径不漂移 | `HtmlCardDetectorTest`（原样通过）+ `下沉后的HtmlCardText与楼层检测器同一口径` | — |

## 3. 实现接法（闸门在哪些文件）

单一真相 `app/src/main/java/com/mistbell/tavern/android/data/model/UiMode.kt`（零依赖）：
`enum UiMode { SIMPLE, ADVANCED }`、`fromSetting(raw)`（缺省/无法识别→ADVANCED）、
`enabledCapabilities(mode)`（能力清单，六项，与上表一一对应）。

| 闸门 | 文件 | 位置 |
|---|---|---|
| 设置项 | `data/repository/SettingsRepository.kt` | `observeUiMode()` / `setUiMode()`，键 `ui_mode`，存小写名字 |
| 进程级读取口 | `di/AppContainer.kt` | `uiMode: StateFlow<UiMode?>`（`observeUiMode` 经 stateIn(Eagerly, 初值 null)，常驻 scope）——执行闸门与**界面**共用的读取口 |
| 规则包源闸（装饰器） | `data/repository/UiModeGatedRulePackSource.kt` | `packs = combine(委托.packs, uiMode)`；能力关→空表，null（首次加载未完成）原样透传 |
| 规则包脚本卸载 | `di/AppContainer.kt` → `extension/scripting/ScriptRuntimeManager.kt`（未改） | `scriptRuntimeManager.source = uiModeGatedRulePackSource`；模式变→装饰器发空表→`reconcile` 走现有 `unload`（注销沙箱注册与动态斜杠命令）→ 切回高级自动重载 |
| 扩展包钩子 | `extension/scripting/ExtensionHookRuntimeManager.kt` | 构造参数 `modeProvider`（默认恒高级，既有测试不动）；`start()` 里 `combine(observeEnabled(), modeProvider())`，简洁发空表给 `onPacksChanged` |
| MVU | `extension/builtin/MvuExtension.kt` | 构造参数 `modeProvider`（每次 process 取值）+ `settingsDaoProvider`；能力关→不读不写库、不发事件，仍返回 `MvuEngine.process` 的 `cleanedText` |
| 提示词正则 | `data/prompt/PromptBuilder.kt` | `loadCore()` 读 `settingsDao.getValue("ui_mode")`，经纯函数 `gatedRegexScriptsForUiMode` 置空两份脚本 |
| 聊天页正则 | `ui/chat/ChatViewModel.kt` | `effectiveGlobalRegexScripts` / `effectiveCardRegexScripts`（各自与模式 combine），`activeRegexScripts`、`getTavernRegexesJson`、`sendMessage` 都吃生效版；纯函数 `regexScriptsForUiMode` |
| HTML 界面 | `ui/chat/ChatViewModel.kt` | `htmlUiEnabled = combine(设置 Flow, uiMode)`，纯函数 `htmlUiEnabledForUiMode`；全 UI 只看这一个 Flow |
| 兜底 | `util/HtmlPlainText.kt` + `ui/chat/MessageBubble.kt` | **两处**：逐段分支里非 layout HTML 且 renderHtml 为假的 else（`toPlainText(part.html)`）；`legacyMessageBody` 最下面的 else（`fallbackDisplayedContent`，HtmlDoc 段过 `toPlainText` 按序重拼，无 HtmlDoc 段逐字节不变）。块级/`<br>` 换行、去标签、还原实体、style/script 内容删除 |
| 设置界面 | `ui/settings/ModernSettingsScreen.kt` + `UserModeComponents.kt` + `SettingsViewModel.kt` | 列表最前「界面模式」项，`UserModeSelectionDialog` 切换；文案含两句规划原文 |

不在闸门上的相关读点（**故意保留**）：规则包管理页读 `RulePackRepository`（DAO）；快捷回复聚合读原始 `rulePackCache`；`CardRegexDocument` 只服务正则编辑器，不在聊天链上。

## 4. 界面可见性（第 2 项：按模式收拢界面）

执行边界（§1）决定能力**是否运行**；本节决定入口**是否出现**。两套真相互不代替：
简洁模式下世界书四源、自定义提示词、附加指令等照常执行，只是改它们的入口收进高级模式。
总原则：**老用户升级后（保持高级）不丢任何入口，行为不变；高级模式下所有既有入口必须可用。**

### 4.1 主导航 Tab

`ui/main/MainTab.kt`（零 Android 依赖，可 JVM 单测）：

- `MainTab(id)`：聊天 `chat` / 角色 `characters` / 世界书 `world_book` / 正则 `regex` / 设置 `settings`。
  选中态 `rememberSaveable` 存**稳定 id**，保存键换名为 `main_selected_tab_id_v2`——旧版本存的是
  列表下标 0–4，不换键的话升级后会恢复出指错 Tab 的旧值；未知 id（含下标残值）一律回落聊天。
- `visibleMainTabs(mode)`（唯一换算点）：简洁 = 聊天、角色、设置；高级 = 现状五个；
  **null（模式未知）→ 空列表**（导航栏空一帧，见 §1「界面与闸门同一口径」）。
- `settledMainTab(selected, mode)`：模式切换后选中 Tab 被隐藏时回落到聊天，模式未知时同样
  回落聊天（只影响显示；切回高级后原选中恢复）。`MainScreen` 的 NavigationBar 与
  NavigationRail 两个容器都按可见列表 + 回落后的选中渲染，读的是常驻流（§1）。

### 4.2 可见性表（UserModeConfig）

`ui/settings/UserModeComponents.kt` 的 `UserModeConfig` 是界面可见性配置（`UiMode.toConfig()` 是
唯一换算点；字段语义见其 KDoc，单测 `UserModeConfigVisibilityTest` 即书面记录）。

| 入口 | 简洁 | 高级 | 归属 |
|---|---|---|---|
| 主导航 Tab：世界书 / 正则 | 隐藏 | 显示 | MainTab（§4.1） |
| 设置 →「⚙️ 高级设置」折叠组（自定义提示词 / 规则包 / 扩展包 + 全局提示词模板卡） | 隐藏 | 显示 | showAdvancedSettings |
| 设置 →「🔬 实验性功能」整组（含 HTML 界面开关） | 隐藏 | 显示 | showExperimentalFeatures |
| 聊天设置 → 会话世界书绑定 | 隐藏 | 显示 | showTechnicalDetails |
| 聊天设置 → 附加指令（author note + 深度/角色） | 隐藏 | 显示 | showTechnicalDetails |
| 聊天设置 → 原生渲染引擎（布局主题）开关 | 隐藏 | 显示 | showTechnicalDetails |
| 设置 → API 连接 | 显示 | 显示 | 恒可见（执行无关） |
| 设置 → 模型参数 | 显示 | 显示 | 恒可见（照常执行） |
| 设置 → 对话设置（默认上下文长度） | 显示 | 显示 | 恒可见（照常执行） |
| 设置 → 外观与主题 | 显示 | 显示 | 恒可见（执行无关） |
| 设置 → 快捷回复 | 显示 | 显示 | 恒可见（不在执行闸门链上） |
| 设置 → 数据备份 / 关于 / 界面模式本身 | 显示 | 显示 | 恒可见（执行无关） |
| 聊天设置 → 角色 / 模型 / 主题 / 会话记忆开关与记忆管理入口 / 上下文长度 | 显示 | 显示 | 恒可见（照常执行或执行无关） |

取舍说明：规划点名的八类之外一律恒可见；快捷回复与模型参数因此移出「高级设置」折叠组
（快捷回复单独成组），高级模式下入口一个不少，仅折叠位置不同。正则入口即主导航 Tab；
设置页与聊天设置页没有独立正则项。

### 4.3 缺省与升级判定

纯函数 `data/model/UiModeStartup.kt`（零依赖，可 JVM 单测；`UiMode.fromSetting(null) → ADVANCED`
的执行闸门缺省**不变**——「全新安装默认简洁」是显式把 `ui_mode` 写成 simple，不是改缺省）：

```
读 settings：ui_mode 原值 / onboarding_done / ui_mode_prompted + 生态信号
│
├─ ui_mode 可识别（simple/advanced，忽略大小写与首尾空白）
│      → Keep(该值)：不写库、不弹窗（显式选择最大）
│      （无法识别的残值如旧稿枚举 "beginner" 从未接线落库 → 视为从未设置，走下行）
│
├─ 引导未完成（onboarding_done 缺失 = 全新安装）
│      → FreshInstallSimple：不弹窗，ui_mode 落 simple（不写 prompted）
│      正常路径：OnboardingViewModel.markDone 在引导完成时写 both；
│      判定兜底再写一次是幂等的（写入者只有一个，竞态殊途同归）
│
├─ 已弹过（ui_mode_prompted = 1）
│      → Keep(ADVANCED)：防御分支（值缺失理论不应发生），保守高级、不再弹
│
└─ 其余（引导已完成、从未设置、从未弹过 = 升级用户首启）
       → Prompt(preselect)：弹一次模式选择
       preselect = 任一生态信号为真 ? ADVANCED : SIMPLE
```

弹层（`ui/main/UiModePromptViewModel.kt` 的 `UiModePromptCoordinator` + `UiModePromptViewModel`，
形态照 `SessionMemoryResetNoticeViewModel` + `AlertDialog` 挂主导航层）：内容复用
`UserModeSelectionDialog`（含规划要求的两句说明——简洁模式下不运行的能力清单；全局书、
人设书和自定义提示词仍会进提示词，只是不能在简洁模式里改），预选项默认选中。
**确认 = 写所选；关掉（点外部/返回/「完成」未改动）= 写预选项**——两者都写
`ui_mode` + 一次性标记 `ui_mode_prompted`（`SettingsRepository.markUiModePrompted()`），
绝不悄悄变成简洁；标记保证跨进程只弹一次。落库后执行闸门（§3）与 Tab 收拢（§4.1）
经既有 Flow 响应式生效。

**生态信号**（`UiModeEcosystemSignals`，查询 `data/repository/UiModeEcosystemProbe.kt`，suspend，
首启判定只跑一次；任一为真即预选高级）：

| 信号 | 判定 |
|---|---|
| 有正则规则 | `global_regex_rules` 表有行，或任一角色卡 `data_json → extensions.regex_scripts` 非空数组（停用也算——同样说明带酒馆资产） |
| 有扩展包 | 任一启用的**非内置**扩展包（`is_builtin = 0`；官方内置 tavernhelper-compat 随启动种子落库、每台设备都有，不算），或任一启用的规则包 |
| HTML 界面开着 | settings `html_ui_enabled == "1"` |
| 导入过酒馆卡 | 角色表里存在**非内置**角色（P3 第 4 项定稿）。内置角色以稳定 id `builtin_*` 落库（单一真相 `util/BuiltInCharacters.isBuiltIn`，与内置落库同一口径），本查询一律排除——全新安装（只有内置角色）时为假。用户自己导入的卡是导入路径的随机 UUID，照常命中；升级前旧版示例卡的历史副本（同为随机 UUID）也照常命中（偏向预选高级 = 保持升级前现状，符合总原则） |

### 4.4 第 2 项实现接法

| 块 | 文件 | 说明 |
|---|---|---|
| Tab 模型与可见性 | `ui/main/MainTab.kt` | `visibleMainTabs` / `settledMainTab` / `fromId` / 保存键常量（模式参数可空：null → 空列表 / 回落聊天） |
| Tab 接线 | `ui/main/MainScreen.kt` | 主导航层自持 `SettingsViewModel`，模式直通常驻流 `AppContainer.uiMode`；`rememberSaveable(MainTab.SAVE_KEY)` 存 id |
| 可见性配置 | `ui/settings/UserModeComponents.kt` | `UserModeConfig`（字段语义 KDoc）+ `toConfig()` + 三个 AnimatedVisibility 包装器（配置可空：null 不渲染，谓词 `advancedVisible` 等可 JVM 单测） |
| 界面模式读取口 | `ui/settings/SettingsViewModel.kt` + `ui/chat/ChatSettingsScreen.kt`(VM) | `uiMode: StateFlow<UiMode?>` 直接暴露 `AppContainer.uiMode`，不做本页 stateIn（§1 同一口径）；设置页「界面模式」行未知期间显示「读取中…」、不开弹窗 |
| 设置页收拢 | `ui/settings/ModernSettingsScreen.kt` | 恒可见区（模型参数/快捷回复）+ 仅高级折叠组 |
| 聊天设置收拢 | `ui/chat/ChatSettingsScreen.kt` | 世界书/附加指令/渲染引擎开关走 `TechnicalDetailFeature`（附加指令卡拆 `AuthorNoteSection`） |
| 缺省与升级判定 | `data/model/UiModeStartup.kt` | `resolveStartupUiMode` 纯函数 + 三种结果 + 信号值 |
| 生态信号查询 | `data/repository/UiModeEcosystemProbe.kt` | suspend，一次性 |
| 一次性标记 | `data/repository/SettingsRepository.kt` | `KEY_UI_MODE_PROMPTED` / `markUiModePrompted()` |
| 引导完成写简洁 | `ui/onboarding/OnboardingScreen.kt` | `markDone()` 同时写 `ui_mode = simple` |
| 弹层 | `ui/main/UiModePromptViewModel.kt` | `UiModePromptCoordinator`（零 Android 依赖）+ VM 薄壳 |

### 4.5 第 2 项清理记录

- **「扮演」占位入口已移除**（`ChatSetupScreen.kt`）：连同只为它存在的 `ModeSpec.badge`、
  置灰常量与 `ModeOptionLabelRow` 徽标分支一并删除；`data/api/model/Models.kt` 的
  `SESSION_MODE_*` 常量与注释不动（实装时引用同一常量，不做空入口）。
- **两条死路由已删除**（`navigation/AppNavigation.kt`，grep 核实无任何 `navigate(...)` 调用方、
  也不是 Tab 承载）：`Routes.SETTINGS`（"settings"，设置已是主导航 Tab）与
  `Routes.WORLD_BOOK_LIST`（"world_book_list"，世界书已是主导航 Tab），连同各自的
  `composable(...)` 块与只为前者存在的 `ModernSettingsScreen` import。

### 4.6 测试矩阵（第 2 项）

| 验证点 | 单测 | 模拟器实测（后续装机任务） |
|---|---|---|
| 模式 → 可见 Tab | `MainTabTest` | 简洁模式底栏/侧栏只有聊天/角色/设置，无世界书/正则；高级五个齐全 |
| 模式未知不渲染（§1 同一口径） | `MainTabTest`（null → 空列表 / 回落聊天）、`UserModeConfigVisibilityTest`（null 谓词全假） | 简洁模式冷启动：底栏先空一帧再出三 Tab，**无五个 Tab 闪现**；从聊天返回主界面/进设置页与聊天设置页无高级入口闪现；「界面模式」行「读取中…」后变真实值 |
| 选中被隐藏回落聊天 | `MainTabTest` | 高级停在世界书 → 切简洁落回聊天 → 切回高级世界书仍在 |
| 设置页入口可见性 | `UserModeConfigVisibilityTest` | 简洁模式无高级设置折叠组/实验功能/渲染引擎开关，快捷回复与模型参数仍在 |
| 缺省判定三种结果 | `UiModeStartupTest` | 见下两条装机路径 |
| 四个生态信号预选 | `UiModeStartupTest` / `UiModeEcosystemProbeTest` | 装有规则包的库首启预选高级 |
| 信号查询口径 | `UiModeEcosystemProbeTest`（Proxy 替身库） | — |
| 弹层确认/关掉语义 | `UiModePromptCoordinatorTest` | 升级路径弹一次：确认写所选、关掉保持预选、重启不再弹 |
| 全新安装默认简洁 | `UiModeStartupTest`（onboarding 写 simple） | 全新安装走完引导直接是简洁三 Tab，不弹窗 |

## 6. 首启与默认层（第 4 项：首启与内容）

> P3 第 4 项（docs/ROADMAP.md「首启与内容」小节 +「P3 门面」第 4 项）。
> 本节是首启三屏、内置角色、默认层措辞与守护测试的唯一落地说明。
> 总原则不变：老用户升级后不丢任何入口，行为不变。

### 6.1 首启三屏（`ui/onboarding/OnboardingScreen.kt`）

| 屏 | 内容 | 按钮 |
|---|---|---|
| 0 欢迎 | 这是什么应用、数据只在设备上（三条要点：选角色开聊 / 角色记住细节 / 数据只在设备，无账号不上传） | 主按钮「开始」 |
| 1 选一位角色 | 全部内置角色的可选卡片（名字 + 一句话介绍，点选高亮） | 主按钮「就这样，下一步」（未选时是「稍后再选」，**不写任何数据**）；次要按钮「导入已有角色」→「导入」页 |
| 2 连接 AI | OpenAI 兼容接口的三步说明 | 「进入应用」/「稍后配置，先进入应用」 |

推进规则抽为零依赖纯函数 `OnboardingFlow`（`next` / `shouldInstallBuiltIns`，单测背书）；
三屏仍是软性步骤，任何一步可跳过。落库内容抽为 `OnboardingCompletion.settingsWrites()`
（= `onboarding_done=1` + `ui_mode=simple`，与 P3 第 2 项一致）；`markDone` 照旧写这两行。
酒馆导入退为选角色页的**次要按钮**，用中性措辞「导入已有角色」，点了进「导入」页
（进入之后才谈老格式，术语只出现在那里）。

### 6.2 内置角色清单（单一真相 `util/BuiltInCharacters.kt`）

随 APK 分发在 `assets/sample_characters/`，共四位（v2 卡，均带背景世界书，均无高级能力）：

| id | 资产 | 名字 | 题材 |
|---|---|---|---|
| `builtin_aria` | `aria.json` | 阿莉雅 | 海雾小镇「夜航灯」的老板娘（自带「雾铃镇」世界书） |
| `builtin_kael` | `kael.json` | 凯尔 | 星际货运船「游隼号」的领航员（自带「星轨」世界书） |
| `builtin_qingwu` | `qingwu.json` | 沈青梧 | 民国十六年水乡栖溪镇的修表匠，接了父亲的「顺昌钟表行」（自带「栖溪镇」世界书） |
| `builtin_chengye` | `chengye.json` | 程野 | 现代山巅「云顶气象站」驻站观测员，驻站第三年（自带「云顶站」世界书） |

**开场白预埋可被记住的细节**（P4 演示会话记忆用，具体、可核对、彼此不重复）：

- 沈青梧：来历（父亲沈顺昌前年病故、她从省城学徒位回来接店）、习惯（修表先听表的故事、
  目镜、麂皮擦手）、在意的人（弟弟沈砚生在省城读中学，学费靠店）、在意的物（停在父亲去世
  那晚的黑木落地钟）、心愿（敢修好那座钟就算出师）；开场白反问「表是怎么不走的」「对你要紧吗」。
- 程野：来历（气象学院毕业驻站第三年、一人一站）、习惯（6/14/22 点三次人工读数记纸质观测簿、
  搪瓷缸浓茶）、在意的物（前站长留下的狗「雷达」、姐姐每月寄的辣椒酱）、心愿（拍全一次雷暴
  从起云到收尾的延时素材，投给省气象局科普刊物）；开场白问「你是怎么上来的」「山下变天没有」。

两卡（连同 aria/kael）由单测守住：能被 `CardParser.parse` 解析、稳定 id 生效、
`AdvancedCapabilityDetector` 判定**无任何高级能力**（无 regex_scripts / 卡内脚本 / HTML / MVU）。

### 6.3 落库行为（全新安装 vs 老用户）

| 场景 | 行为 |
|---|---|
| 全新安装，选了角色 | 「下一步」时把**四位内置角色全部落库**（世界书先行，与导入路径同一顺序；id 幂等）——选中的那位落库即可直接开始聊天（角色 Tab 点开 → 新建会话），全新安装的「角色」Tab 不为空 |
| 全新安装，「稍后再选」 | 不写任何数据（`shouldInstallBuiltIns` 为假）；角色 Tab 空态有「导入」与「新建角色」入口，顶栏「添加内置角色」随时可补 |
| 老用户升级 | 启动路径**绝不**调用 install——不会被悄悄塞进新角色。可达途径：角色 Tab 顶栏「添加内置角色」（PersonAdd 图标）弹层，缺哪位补哪位 |
| 幂等口径 | install 跳过「已有同 id」**或**「同名」的角色——同名兜底覆盖升级前经旧引导导入的随机 id 示例卡，重复点击/多入口不会产生重复角色 |

### 6.4 生态信号（§4.3 表的「导入过酒馆卡」行）

`hasImportedTavernCard` = 角色表存在**非内置**角色。内置的稳定 id（`builtin_` 前缀）被排除：
全新安装（只有内置角色）为假；用户自己导入的卡（随机 UUID）为真；升级前旧示例卡的历史
副本（同为随机 UUID）也为真——保持升级前「偏向预选高级」的现状，符合总原则。
判定与落库共用 `BuiltInCharacters.isBuiltIn` 一个口径。

### 6.5 默认层措辞与守护测试（P3 验收：全新安装 5 分钟内不出现酒馆术语）

「默认层」= 全新安装、简洁模式下前几分钟能走到的界面：首启三屏、主界面三个 Tab、聊天列表
空态、角色 Tab、新建会话页、聊天页、记忆面板、设置页（简洁模式可见部分）。守护测试
`DefaultLayerWordingGuardTest` 把默认层**源文件清单 + 禁用词**都放在测试里：断言清单文件
（24 个）的**字符串字面量**（注释不算）不出现 SillyTavern / 酒馆 / Tavern / 角色卡 / 埋卡 /
世界书 / 正则 / 规则包 / 扩展包 / tavern_helper / MVU / 破甲 / STscript。例外逐条登记在测试的
ALLOWED 表里并注明理由（规划要求的模式说明文案、仅高级模式渲染的入口、仅导入动作后出现的
反馈、extensions 的 JSON 键名、应用名）。**有意不在清单里**：「导入」页（用户主动进入，可点名
酒馆生态）、SettingsViewModel（版本更新日志是历史记录）、角色编辑器（深层页面，其世界书/正则
入口收拢属第 2 项范畴）、各高级模式专属包。

本项清掉的措辞（原文 → 新文案）：

| 文件 | 原文 | 新文案 |
|---|---|---|
| OnboardingScreen（欢迎） | 口袋里的 AI 角色酒馆：… | 在这里和 AI 角色聊天：他们按各自的性格与你说话，也会记住你们聊过的事。 |
| OnboardingScreen（选角色） | 导入 SillyTavern 生态的角色卡（JSON 或 PNG 埋卡），或从内置示例开始。 | 点选一位作为你的第一位角色；也可以先不选，之后再挑。 |
| OnboardingScreen（次要按钮） | 导入我的角色卡 | 导入已有角色 |
| OnboardingScreen（阿莉雅 blurb） | 雾铃镇「夜航灯」酒馆的老板娘… | 雾铃镇临海老店「夜航灯」的女主人… |
| ChatListScreen（空态） | 从酒馆导入… | 导入角色或聊天记录… |
| ModernChatListItem（导出格式） | 酒馆 JSONL | 对话记录（JSONL） |
| MainScreen（记忆升级提示） | 聊天消息、角色和世界书均不受影响。 | 聊天消息、角色与其他数据均不受影响。 |
| CharacterListScreen（角色长按菜单） | 导出角色卡（JSON） | 导出角色（JSON 文件） |

新建会话页的「世界书」选择器收进高级模式（`TechnicalDetailFeature`，与聊天设置同一包装器）：
简洁模式下没手动选过时，选中角色的默认世界书照常自动绑定，行为不降级——这是第 2 项可见性
原则在本页的补漏（P3 验收里新建会话页属默认层）。

### 6.6 测试矩阵（第 4 项）

| 验证点 | 单测 | 装机实测（后续任务） |
|---|---|---|
| 三屏推进与封顶、稍后再选、完成落库内容 | `OnboardingFlowTest` | 全新安装走完三屏；逐屏核对无酒馆术语 |
| 目录与资产不漂移、稳定 id、无高级能力、预埋细节 | `BuiltInCharactersTest`、`SampleCharactersTest` | 选沈青梧 → 开聊 → 下次会话问「那座钟修好了吗」看记忆 |
| 全新安装四位落库、世界书先行、幂等、同名跳过 | `BuiltInCharactersTest`（Proxy 替身库） | 全新安装选一位 → 角色 Tab 四位齐全；重复点「添加」不出重复 |
| 老用户零自动写入、显式补齐入口 | 同上（空清单零写入）+ 角色列表 | 升级安装不弹任何写库；手动点「添加内置角色」补齐 |
| 生态信号排除内置 | `UiModeEcosystemProbeTest` | 装机见 §6.3 两条路径 |
| 默认层措辞 | `DefaultLayerWordingGuardTest` | 逐屏抓取可见文本核对（见 §6.5 清单） |
