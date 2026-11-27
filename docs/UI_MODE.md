# 界面模式：简洁模式是执行边界

> P3 第 1、2 项（docs/ROADMAP.md「简洁模式是执行边界」一节 + 「P3 门面」第 1、2 项）。
> 本文档是这两项的规则表、测试矩阵与实现接法的唯一落地说明。
> §1–§3 是第 1 项（执行边界与闸门）；§4 起是第 2 项（按模式收拢界面、缺省与升级判定）。

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
| 导入过酒馆卡 | **近似：角色表非空。** 导入路径不落来源标记、角色 id 是导入时生成的 UUID，事后无法区分用户卡/示例卡/手建角色；且内置示例卡本身就是酒馆 v2 格式经同一路径导入。「存在角色」是当前可获得的最接近近似；手建角色也命中（偏向预选高级 = 保持升级前现状，符合总原则）。P3 第 4 项落地真正内置角色时，需把它们排除出本查询 |

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
