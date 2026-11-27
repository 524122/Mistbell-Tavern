# 界面模式：简洁模式是执行边界

> P3 第 1 项（docs/ROADMAP.md「简洁模式是执行边界」一节 + 「P3 门面」第 1 项）。
> 本文档是这一项的规则表、测试矩阵与实现接法的唯一落地说明。

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
- **模式未知时一律先不放行。** `AppContainer.uiMode` 是可空 StateFlow（`null` = 启动后 ui_mode 还没从数据库读回的几毫秒）：规则包源闸此刻发射 `null`（`awaitPacks` 自然等待，不误判为「无包」）、钩子管理器给 `onPacksChanged` 空表（先不加载）、MVU 的 `modeProvider().first()` 挂起等待——宁可多等一次数据库读，绝不让脚本/钩子在未知模式下抢先加载执行。设置页展示用的 `observeUiMode()` 保持非空、缺省高级。
- **单一真相**：`data.model.UiMode`（零依赖）+ `enabledCapabilities(mode)`。所有闸门只问它，不各自判断模式。
- 关掉后的兜底，不让原始内容裸露，剥 HTML 标签发生在 `MessageBubble.kt` 的**两处**分支：
  1. `legacyMessageBody` 逐段分支里 `LiteHtmlPolicy.isLayoutHtml` 为假且 `renderHtml` 为假的 else（原来把原始标签当纯文本显示）→ `HtmlPlainText.toPlainText(part.html)`；
  2. `legacyMessageBody` 最下面的 else（`renderHtml=false && hasLayoutHtml=false`，即纯脚本界面卡最常走的路径）→ `fallbackDisplayedContent`：文档段过 `toPlainText`、Markdown 段原样按序重拼；纯文本/Markdown（无 `HtmlDoc` 段）逐字节不变，仍是单个 `MessageMarkdown(displayedContent)`。
  带 `<style>`、`<table>` 且无脚本的片段仍走 litehtml，不经过兜底。包宏的 `{{x}}` 占位符原样留下，不处理（导入报告里说明，属第 3 项）。

**缺省行为**：`ui_mode` 未设置或无法识别 → `ADVANCED`。本项落地后，升级用户与全新安装行为与历史版本完全一致（全新安装默认简洁、升级首启弹选择是第 2 项）。

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
| 设置项切换 | — | — | （UI，无 JVM 断言） | 设置 → 界面模式，改写即时生效 |

## 3. 实现接法（闸门在哪些文件）

单一真相 `app/src/main/java/com/mistbell/tavern/android/data/model/UiMode.kt`（零依赖）：
`enum UiMode { SIMPLE, ADVANCED }`、`fromSetting(raw)`（缺省/无法识别→ADVANCED）、
`enabledCapabilities(mode)`（能力清单，六项，与上表一一对应）。

| 闸门 | 文件 | 位置 |
|---|---|---|
| 设置项 | `data/repository/SettingsRepository.kt` | `observeUiMode()` / `setUiMode()`，键 `ui_mode`，存小写名字 |
| 进程级读取口 | `di/AppContainer.kt` | `uiMode: StateFlow<UiMode>`（stateIn，常驻 scope） |
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

## 4. 第 2 项待办（本次未做）

- 按模式收拢 `MainScreen` 的 Tab 与各入口（世界书、附加指令、正则、提示词、规则包、扩展包、实验功能、渲染引擎开关只在高级出现）。
- 全新安装默认 `SIMPLE`；升级后首次启动弹模式选择（有生态信号时预选高级）；`UserModeConfig`（界面可见性）在该项启用。
- 导入层「需要高级模式」报告。
