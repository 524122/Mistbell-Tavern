# 路线图与工作规划

> 本文档回答两个问题：**现在处于什么状态、接下来按什么规划走**。
> 第一节是现状快照，第二节是 2026-11-26 批准的重新设计规划全文，第三节存档 2026-08 多代理代码审查的旧清单（仅供追溯，不再维护）。
> 配合 [CHANGELOG.md](../CHANGELOG.md) 使用：每个阶段完成后在 CHANGELOG 记录。

---

## 📌 一、现状快照（2026-11-26）

| 维度 | 现状 |
|---|---|
| 版本 / 规模 | v0.9.8-beta，单模块 `:app`，主代码约 6.45 万行 |
| 测试 | 单测 1183 例 / 118 个套件（本地 `checkAll` 与 CI #49 一致）；androidTest 12 个文件 / 82 例，已进 CI（托管设备，观察期） |
| 数据库 | Room schema v39（`exportSchema=true`，每个版本都有迁移与迁移测试；迁移清单 `AppDatabase.ALL_MIGRATIONS` 是生产与测试共用的唯一数据源） |
| 结构债 | `TavernApplication.instance` 51 处 / 30 个文件；包组间互相依赖 11 对（`data`、`ui` 按二级包分组，其余按顶层包） |
| 功能缺口 | 界面模式尚未实现（规划 P3）；会话记忆默认关闭 |
| CI | GitHub Actions 全绿（2026-11-26，CI #49）：`verify`（构建 / 单测 / detekt / ktlint / licenseGuard）与 `instrumented`（托管设备 androidTest）。此前 #26–#48 连续失败。`instrumented` 处于观察期：只报告不拦截，连续 7 次绿后改为必须通过 |
| release 构建 | 可正常启动并通过 14 项冒烟（2026-11-26）：修复了自引入 skiko 起的冷启动崩溃（R8 按名访问），并移除 Compose 整包 keep（每个 APK 省约 4.9MB） |

一句话诊断：**工程底子扎实（64.5k 行、1183 个单测、39 版迁移链、默认全拒的安全模型），但重心偏离了产品。**

---

## 🧭 二、重新设计规划（2026-11-26 批准）

### Context

审阅结论：工程底子扎实（64.5k 行、1163 个单测、39 版迁移链、默认全拒的安全模型），但重心偏离了产品。

- 约 9400 行手写代码（主题框架 v0、litehtml、遗留链路清理）自 11-02 起没提交，只在一块磁盘上。
- 门面是酒馆的：底部有「世界书」「正则」Tab，首启第一句是「导入 SillyTavern 生态的角色卡」，自带内容只有 2 个示例角色。
- 主张「会话记忆」默认关闭，没有质量数字，没有事实生命周期（`supersede`、`factKey` 零命中），在聊天里看不见。
- 一条消息四条渲染路径；包组间 11 对互相依赖；`TavernApplication.instance` 全仓库 51 处；CI 不跑 82 个 androidTest；README 和 ROADMAP 停留在旧数字。

已拍板：主张是长局连续性；自绘引擎封顶保留；原地渐进，不重写。

**关键路径**：P0 落袋 → P2 评测 → P4 生命周期与默认开启。
- P1 的棘轮排在 P3、P4 之前，因为子智能体最容易把依赖加回去。
- P3 是支线，排在 P2 与 P4 之间只为版本顺序。
- 0.10.0 是换门面，0.11.0 才是主张发版。

#### 目标形态

| 层 | 给谁 | 内容 |
|---|---|---|
| 默认层 | 没用过酒馆的用户 | 简洁模式：三个 Tab、四个内置角色、三屏首启、会话记忆默认开且看得见 |
| 导入层 | 带着酒馆资产来的用户 | 一个「导入」入口，报告写明哪些能力要切到高级模式才生效 |
| 高级层 | 酒馆重度用户 | 高级模式：现有能力原样保留，含正则、脚本、HTML 界面、扩展钩子 |

老用户升级后不丢任何入口，行为不变。

#### 简洁模式是执行边界

**照常执行**：
- 世界书四源：会话书、人设书、角色书、全局书（`PromptBuilder.loadWorldBookEntries()`，`:1013-1034`）。不滤成只留角色书，否则从高级切回简洁时，已经配好的书会静默消失。编辑入口收到高级模式。
- 内置宏（`MacroEngine.render`）
- 开场白（`ChatSetupViewModel.kt:226-255`）
- 会话记忆
- 自定义提示词（`CustomPromptExtension`，纯文本，经 `ExtensionRegistry`）
- 内置斜杠命令 `/help`、`/note`

**不执行，也不在后台跑**：

| 能力 | 闸门 |
|---|---|
| 卡级与全局正则（展示、用户输入、提示词三路） | 两处装配点收口成一个解析函数，简洁模式返回空表（`ChatViewModel.kt:204-207` 的 `activeRegexScripts`、`PromptBuilder.kt:844-854`）；展示路径吃的是前一条 Flow |
| 规则包：提示词块、输入输出正则、包宏 | 包源在简洁模式给空表（`RulePackSource`、`EnabledRulePacksCache`），`RulePackExtension` 三个入口自然不生效 |
| 规则包脚本 | `ScriptRuntimeManager` 的订阅处并入模式，简洁模式给 `reconcile` 空表，走现有的 `unload`。只在 `dispatchEvent` 提前返回不够：已加载的脚本还在 `loadedScripts` 里，提示词与回复钩子照跑 |
| 扩展包钩子 | `ExtensionHookRuntimeManager` 的订阅处并入模式，简洁模式给 `onPacksChanged` 空表，走现有的 `unload` |
| 卡内脚本与 HTML 界面（WebView、桥、宿主层、悬浮球） | 模式并进 `htmlUiEnabled` 这个 Flow（`ChatViewModel.kt:169`）。气泡、宿主层、悬浮球、WebView 预热（`ChatScreen.kt:251-253`，现在无条件执行）都以它为条件，不写第二套判断 |
| MVU 变量更新 | 照常调用 `MvuEngine.process` 并返回它的 `cleanedText`；简洁模式不写 `ext.mvu.*`、不发 `VariablesUpdated` |
| 脚本注册的斜杠命令 | 随脚本卸载从 `ExtensionRegistry` 摘除；内置命令保留 |

通用约定：
- **所有闸门都是响应式的。** 从高级切回简洁，必须重新走一遍卸载、清空、回落，不能只在当次启动时判断。
- 新增纯函数 `enabledCapabilities(mode)`，放在零依赖的 `data.model`，作为唯一真相。所有闸门只问它，不各自判断模式。
- MVU 不新写剥离器，也不在 `process` 之前直接返回原文，否则 `<UpdateVariable>` 块会落库并送给模型。

两处界定，批准时可以否决：
- 「HTML」指 HTML 界面，即 WebView、桥和卡内脚本。带 `<style>`、`<table>` 的静态片段仍由 litehtml 画出，它不执行脚本、不联网，我把它算作渲染。
- MVU 归在「卡片脚本」一类，简洁模式不应用。

关掉后的兜底，不让原始内容裸露：
- 剥 HTML 标签只发生在 `LiteHtmlPolicy.isLayoutHtml` 为假的分支（`MessageBubble.kt:338-345`，现在这里把原始标签当纯文本显示）。带 `<style>`、`<table>` 且无脚本的片段仍走 litehtml。
- 包宏的 `{{x}}` 占位符会原样留下，不处理，在导入报告里说明。

单测与实测：
- 每种不执行的能力一条单测。
  - 正则：断言简洁模式下输出与输入逐字节相同。`apply` 拿空表是空操作，不要求零调用。`CardRegexDocument` 只服务编辑器，不在聊天链上。
  - 脚本与钩子：先在高级模式加载，再切到简洁，断言已卸载、动态斜杠命令已摘除。
- 一条模拟器实测：探测卡带卡内正则、脚本、HTML、更新块，另启用一个规则包（规则包是另一条源，卡内字段覆盖不到）。确认四件事：
  1. 简洁模式下没有 WebView 被创建，气泡与提示词里没有改写痕迹。
  2. 切到高级模式后全部生效。
  3. 再切回简洁后，脚本与钩子被卸载。
  4. 全局书、人设书在简洁模式下仍进提示词。
- 规则表与测试矩阵写进 `docs/UI_MODE.md`。

导入报告：
- `CharacterImportResult` 和批量导入的 `SyncReport` 增加「需要高级模式」一栏：正则 N 条、卡内脚本 N 段、HTML 界面、状态栏变量更新。
- 判定字段：`extensions.regex_scripts`、`extensions.tavern_helper.scripts`、开场白与示例与世界书条目里的 HTML（`HtmlCardDetector.containsHtml`）、`<UpdateVariable>` 标记。
- 现在单卡导入只显示「N 条提示」，明细进 logcat（`CharacterListViewModel.kt:261-273`）。导入页要真正把这一栏列出来。
- 报告后给两个选择：切换到高级模式，或保持简洁。保持简洁时，上表能力保持关闭；角色卡上留一个小标记「含高级能力，未启用」。

### P0 落袋与止血（3–4 天）

1. **落袋（当天完成，仅此一次适用下面的拆法）**
   - `.gitignore` 补 `screenshots/`、`samples/*.zip`、`.cursor/`。
   - 先打一个本地安全提交，留本地标签兜底。
   - 本地 `checkAll` 转绿后拆成三个规范提交：遗留链路清理、主题框架 v0、litehtml 对照路径。最终树与安全标签逐字节一致才推送；拆不干净就少拆，不硬拆。
   - 补 CHANGELOG `[Unreleased]`，推送 `origin` 和 `github` 的 `feature`。推送不等托管设备 CI。
   - **批准本规划即授权这次提交与推送。**
   - 此后每个提交必须可构建，不把「先落袋再整理」变成习惯。
   - 已完成（2026-11-26）：拆为 9ab04bb（清理重构）与 74256b5（主题框架 v0 与 litehtml），两个远程已推送
2. **CI 跑 androidTest（P0 最可能超期的一项，限时两天）**
   - 新增并行 job：JDK 17、SDK、NDK 缓存、Gradle Managed Device（aosp x86_64 镜像），显式设置 `testInstrumentationRunner`。
   - 不自动重试，重试会把不稳定藏起来。
   - 前一周 job 只报告、不拦截。连续 7 次绿之后，才改成必须通过。
   - 两天内没跑通就保持只报告状态，不阻塞后面的阶段。
   - 已完成（2026-11-26）：CI #49 全绿，`verify`（构建 / 单测 / detekt / ktlint / licenseGuard）与 `instrumented`（托管设备 androidTest）都过。修了五轮才通：#44–#48 的失败全部出在环境搭建脚本自身（runner 上 `sdkmanager` 不在 PATH、我引入的 errexit 问题、平台包名写错），不涉及项目代码；#46 首次跑到 Gradle 时暴露并修掉了两个真实问题（单测在干净构建下编不过 f17aa28、迁移完整性测试自 v36 起失效 fb577d4）。平台包名是带小版本号的 `platforms;android-37.0`（稳定通道里没有不带小版本号的 `platforms;android-37`），与本地实际编译用的一致。未做 NDK 缓存，单次运行约 7 分钟。日志需要登录才能读，脚本把诊断写成了注解。`instrumented` 观察期从 #49 起计：1/7。
3. **文档校准**
   - README 去掉会过期的数字（测试数、schema 数、baseline 数、「30k 行再议拆分」）和无出处的性能表。
   - 把批准后的本规划写进 `docs/ROADMAP.md`，替换 v0.3.0-beta 快照。
   - `.research_tavo` 移出版本库并修 `.gitignore`（文件留在本地；是否改历史由你定）。
   - 已完成（2026-11-26）：7eabbb7
4. **便宜缺陷**
   - `sendMessage` 与桥追加、删除的计数回写进事务并取会话锁（`ChatRepository.kt:154-186`、`:1964-2023`）。
   - `PanelRenderer` 单例化（`EngineMessageContent.kt:231`）。
   - JNI 类引用只建一次（`android_container.cpp:50`）；litehtml 位图高度封顶。
   - 删除或清空会话时清掉 `ext.mvu.*` 键。
   - WebView 预热改为只看 `htmlUiEnabled`。
   - `-keep class androidx.compose.**` 做一次带与不带的对比：省 1MB 以上且 release 冒烟通过才移除，否则保留并写明原因。
   - 已完成（2026-11-26）：六处修复 ab2b824，模拟器实测六项全部验证通过。keep 对比实验 fb4dc82：universal 包省 5,120,518 字节且 release 冒烟 14 项通过，按规则移除。
   - 计划外发现并修复：开启混淆的 release 包冷启动即崩（af2b960，四层「native 代码或反射按名找类、R8 改了名」的问题，自引入 skiko 起，没有随任何已发布版本流出）。keep 实验第一次做的时候 release 包本身起不来，结论无法判定，是修好之后重做的。

**验收**：`feature` 已在两个远程上；verify job 绿；`git status` 干净；ROADMAP 与现状一致。

### P1 立规矩与冻结（1.5–2 天）

1. **自绘引擎冻结**
   - 现在就把「冻结」写进 `docs/THEME_FRAMEWORK_DESIGN.md`，不花两天测量。
   - 解冻条件：以 `HtmlBubble` 为对照，冷首帧、滚动掉帧率、常驻 10 个面板的内存增量三项均不超过 WebView 的 50% 且无泄漏；并且至少有 3 个非作者的 layout 主题包，或用户明确提出。
   - 以现在的采用情况，这个条件基本不会成立。解冻时才做那两天的测量。
   - litehtml 保持现状，不扩功能。
   - 已完成（2026-11-26）：df5e8c1
2. **依赖棘轮**
   - 新增 JVM 单测 `ArchitectureRulesTest`：扫描 import 与全限定名，基线存 `app/src/test/resources/architecture-baseline.json`，只减不增，不引新依赖。
   - `render` 不依赖 `ui.*`：先修 `MarkdownToNodes.kt` 的两条反向依赖。
   - `data.*`、`extension` 不依赖 `ui.*`。
   - `ui.*` 不 import DAO；entity import 只减不增（现 25）。
   - `TavernApplication.instance` 只减不增（现 51）；包组互赖对只减不增（现 11）。
   - 不拆 Gradle 模块，拆分条件改为「棘轮归零」。
3. **基线棘轮**：CI 加一步，detekt 与 ktlint 的 baseline 条数不得超过上限文件（detekt 927、ktlint 229）。

**验收**：冻结决定与解冻条件有书面记录；三条基线进 CI；`checkAll` 绿。

### P2 先量它：评测基线与记忆可见（6–7 天，关键路径）

1. **评测夹具**
   - 12 个合成长会话，各约 300 楼，中文。
   - 每个会话埋 30 条可核对事实，其中约 10 条后续被更新；每 25 楼一个探针提问。
   - 探针提问必须换说法，与事实原文不做词面重叠，由脚本校验。
   - 用真实模型录制一次抽取输出作为回放夹具。录制由你在本机执行一条命令，密钥走环境变量，不入库。
2. **三种证据分开看，各证各的**

   | 证据 | 证明什么 | 怎么跑 | 什么时候跑 |
   |---|---|---|---|
   | 回放夹具 | 管线还在：抽取、去重、召回、注入没有回归 | JVM 单测，录制的抽取输出加确定性 embedding | 每次 CI |
   | 真 ONNX | 召回：注入命中率、过期注入率、噪声率 | androidTest，真 sqlite-vec，真机优先 | P2 出基线、P4 验收、每次发版 |
   | 20 条真实探针 | 抽取还行 | 真实模型，手工触发 | 每次发版 |

   - 抽取召回率来自录制夹具，反映录制时真实模型的表现。
   - 指标共五个：抽取召回率、注入命中率、过期注入率、噪声率、每轮记忆 token 成本。
   - 报告落 `build/reports/memory-eval/`，基线写进 `docs/MEMORY_EVAL.md`，每个数字标注来自哪种证据。
3. **闸门**：抽取召回率低于 60%，或注入命中率低于 50% 时，只修一轮管线错误，然后重录基线、继续。不改提示词语义，不调参刷分，不开没有期限的阶段。
4. **记忆可见 v0**
   - 用了会话记忆的 AI 回复下方加轻标记「记得 N 件」。
   - 点开是底部弹层，列出本轮注入的记忆，可编辑、可忘记、可进记忆管理。
   - 沿用 `PromptTrace` 已有数据，不落库。

**验收**：有一份带数字的基线报告和 5 个具体失败样例；开启记忆的会话里标记可见，忘记后下一轮不再注入（模拟器实测）。评测闸门不在任何工期下砍掉。

### P3 门面：执行边界与导入层（8–10 天，支线）

工期紧时按这个顺序砍：先砍第 4 项，再砍第 5 项。前三项是规则本身，不砍。

1. **界面模式与闸门（3 天）**
   - 设置项 `ui_mode`，沿用 `SettingsRepository.observeLayoutRendererEnabled` 的写法。
   - 复用零引用的 `UserModeComponents.kt`。
   - 落实上面的闸门、兜底、单测和模拟器实测。
2. **按模式收拢界面（1.5 天）**
   - `MainScreen.kt` 的 Tab 下标改成稳定 id。简洁模式是聊天、角色、设置；高级模式保持现状五个。
   - 世界书、附加指令、正则、提示词、规则包、扩展包、实验功能、渲染引擎开关的入口只在高级模式出现。
   - 隐藏未实装的「扮演」入口；清掉两条无人跳转的死路由。
   - 全新安装默认简洁。
   - 升级后首次启动弹一次模式选择，有生态信号（正则规则、扩展包、HTML 界面、导入过酒馆卡）时预选高级。
   - 关掉弹窗等于保持预选项，不能变成简洁。
   - 弹窗写明两件事：
     1. 简洁模式下哪些能力不运行。
     2. 全局书、人设书和自定义提示词仍会进提示词，只是不能在简洁模式里改。
3. **导入层（2.5 天）**
   - 新页面「导入」，从角色 Tab、聊天空态、首启的次要按钮可达：角色卡、酒馆数据文件夹、世界书、聊天记录、备份，全部复用现有导入代码。
   - 报告按上面的要求列出「需要高级模式」的能力。
4. **首启与内容（2 天，先砍）**
   - 首启三屏：欢迎、选一位角色、连接 AI；酒馆导入退为次要按钮。
   - 新增 2 个内置角色，开场白里预埋可被记住的细节。
5. **通用层去产品化（1.5 天，第二个砍）**
   - 苍玄界的域名和选择器从 `HtmlUiSandbox`、`HtmlHostLayer` 移到内置兼容包的声明里，用户无感。
   - HTML 界面的风险确认文案改为准确描述。

**验收**：全新安装 5 分钟内不出现酒馆术语；简洁模式下探测卡与规则包的能力均不执行；高级模式下所有既有入口可用。两种模式各在模拟器上走一遍。

### P4 主张落地：事实生命周期与默认开启（10–13 天，关键路径）

开工前给一页实施细化，确认一次。细化里必须写明：
- schema v40 的列变更；
- 重述、更新、失效三条判定规则；
- 用户改过的记忆不被后续抽取自动取代（编辑即锁定的标记）；
- 重述负例清单；
- 过期注入率的绝对上限。

步骤：
1. **单条来源（2 天）**
   - 抽取提示词按轮标注，每条事实带来源轮次，写入现有 `related_message_ids`。缺失时回落为整批，不比现在差。
   - 记忆项显示「来自第 N 楼」，点击跳到该消息。
2. **事实生命周期（5–7 天）**
   - `structured_memory` 加 `status`、`fact_key`、`superseded_by`。
   - 同键重述不新增。
   - 同主体、同关系、可变关系、取值不同：旧的转 `superseded`，摘要向量同步失效，复用现有 DeleteCopy 协议。
   - 文本相似度只作没有三元组时的兜底，且不丢弃判定为更新的候选。
   - 来源消息全部被删除或重生成的自动记忆转为失效：不注入，可恢复。
   - 召回与注入只取有效记忆。
   - 迁移测试，加上用上一版 APK 的存量库升级实测。
3. **默认开启与成本可见（1.5 天）**
   - 新会话默认开会话记忆，开启处写明「每 3 轮用你的模型整理一次，消耗少量 token」。
   - 可单独指定更便宜的抽取模型。
   - 现有会话保持关闭，超过 60 楼时提示一次。
   - 留一个全局开关，可一键回退。
4. **「它记得」面板（1.5 天）**
   - 把 P2 的标记升级为会话记忆面板。
   - 分四块：本轮用到、全部有效、已被取代与失效、来源跳转与纠正。

**验收**：
- 过期注入率不超过绝对上限（建议 5%），重述负例零误杀，更新事实的探针处旧版本零注入。不用「相对下降 80%」。
- 注入命中率不低于 P2 基线。
- 新会话不手动设置就有记忆；v39 存量库升级后记忆完好。

### P5 入口：状态内核（不在本规划的实施范围）

P4 发版且评测健康后，先写 ADR，评审通过再另立规划。ADR 要定：变量路径语法冻结、坐标等于楼层序号加 swipe 分支、祖先链可见性、快照表、写入校验门、MVU 适配与导入翻译器。

### 排期（按每周 5 个专注日估）

| 阶段 | 天数 | 发版 |
|---|---|---|
| P0 落袋与止血 | 3–4 | 0.9.9-beta |
| P1 规矩与冻结 | 1.5–2 | 无 |
| P2 评测与可见 | 6–7 | 0.9.10-beta |
| P3 门面（支线） | 8–10，可砍到 6 | 0.10.0，换门面 |
| P4 主张落地 | 10–13 | 0.11.0，主张发版 |

合计 29–36 个专注日；P3 砍到位是 25–32 个，约 5–7 周。版本号与发版时机由你定。

### 明确不做

- **不合并 MVU 变量树与结构化记忆。** 高级模式里那些长局卡的连续性仍由卡片脚本负责。「记得 N 件」只代表会话记忆；P4 的过期注入率只量会话记忆，不代表整个产品已经连续。
- 拆 Gradle 模块
- 全量字符串迁移
- 英文界面
- 改名
- 扩展 CSS 或自绘引擎
- 桥权限分级与图片外发治理（列入高级线待办，另立）
- 发现页
- 跨会话记忆
- Agent 运行时
- 扮演与导演模式

### 风险与对策

| 风险 | 对策 |
|---|---|
| 拆提交时中间提交编不过，废掉 bisect（仅这一次落袋适用） | 最终树与安全标签逐字节一致；拆不干净就少拆；此后每个提交必须可构建 |
| 托管设备 CI 超期或不稳 | 限时两天；不自动重试；前一周只报告不拦截；连续 7 次绿才改成必须通过；P2 的质量数字先从本地模拟器出 |
| 评测不真实 | 三种证据分开，各证各的；探针换说法并由脚本校验；录制夹具来自真实模型 |
| 事实更新规则误杀重述 | 只对可变关系、同主体同关系判定；重述负例零误杀才验收；被取代的可恢复；用户改过的不自动取代 |
| 默认开启带来 token 成本投诉 | 开启处写明成本；可指定便宜的抽取模型；全局开关可回退 |
| 范围再次膨胀 | 每阶段的「不做」写进 ROADMAP；引擎直接冻结；P3 按砍序压缩；P5 不进本规划 |
| 闸门只做了「停转发」，脚本与钩子其实还挂着 | 给 `reconcile` 和 `onPacksChanged` 空表走现有 `unload`；单测覆盖高级切回简洁；探测时另启用一个规则包 |

### 验证方式

- 每阶段：`./gradlew checkAll` 绿；androidTest 在本地模拟器上过。
- 每个功能装机实测：`.e2e/mock-llm.js` 加 `adb reverse`；中文输入用 ASCII，返回用页面内按钮。
- schema 变更：迁移测试，加上用上一版 APK 的存量库升级实测。
- Compose 改动过滚动审核清单：同向嵌套滚动、超高内容裁剪。
- 重写的界面文案进 `strings.xml`，存量文案不动。

### 执行方式

- 子智能体实现，我审核 diff 和实测结果。
- 批准后当天做 P0 第 1 项。
- P4 开工前确认一次实施细化。

---

## 🗃 三、历史清单（存档）

> 以下为 2026-08 多代理代码审查形成的清单，文件行号已过期；完成情况不在本节维护，现状以上面两节为准。

### 🧭 总原则

1. **先修正确性 → 再补防护网（测试）→ 再做结构优化 → 最后新功能**。
2. 每个条目必须有验收标准；未经验证不宣称完成。
3. 结构手术（DI、合并双轨）必须排在死代码清创之后——不给要删的代码做注入。

---

### 🔥 当前阶段（M1 · 正确性修复 + 安全加固）

> 目标：把会出错的问题修掉，把泄密通道堵上。预计 1~2 周。

#### Bug 修复

##### M1-1 回溯/重新生成会删错消息（最高优先级）
- **位置**：`app/src/main/java/com/mistbell/tavern/android/data/local/dao/MessageDao.kt:30`
- **问题**：`DELETE ... AND id > :afterMessageId` 把随机 UUID 字符串按字典序比较，与时间序无关。调用方 `ChatRepository.backtrackToMessage`（192-200 行）与 `regenerateMessage`（202-236 行）实际会删除任意子集。
- **修法**：改按 `created_at > (SELECT created_at FROM messages WHERE id = :afterMessageId)` 删除，或为消息加单调递增序号列。
- **验收**：回溯与重新生成只删目标消息之后的内容；为该 DAO 方法补单元测试（M2 测试骨架落地前先手测记录）。

##### M1-2 深色模式切换需重启才能生效
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/theme/Theme.kt:80-82`
- **问题**：`flow { emit(db.settingsDao().getValue("dark_mode") ...) }` 是一次性发射，不观察数据库变化；设置页切换后根主题永不重组。
- **修法**：`SettingsDao` 增加 `observeValue(key): Flow<String?>`，主题处改用该 Flow。
- **验收**：设置页切换深/浅/跟随系统，界面即时重绘，无需重启。

##### M1-3 聊天列表显示陈旧数据
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/chatlist/ChatListViewModel.kt:39-60`
- **问题**：`ChatListItem` 手写 equals 漏掉 characterName、头像、参与者、lastMessageSender 等**正在展示的字段**。此问题由 0.2.2 的"列表重组次数减少约 30%"优化引入（见 CHANGELOG），是典型误优化。
- **修法**：equals/hashCode 覆盖全部展示字段，或直接删掉手写实现回归 data class 默认行为。
- **验收**：角色改名/换头像/改色后，聊天列表行立即刷新。

##### M1-4 角色卡对话数写死
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/character/CharacterListScreen.kt:417`
- **问题**：`Text(text = "23 对话")` 硬编码，所有角色一律显示 23。
- **修法**：CharacterListViewModel 按 characterId 统计会话数（一条 GROUP BY 查询）并传入 `CharacterCardItem`。
- **验收**：每个角色显示真实会话数。

##### M1-5 会话计数漂移 + 撤销非原子 + 发送失败残留
- **位置**：`app/src/main/java/com/mistbell/tavern/android/data/repository/ChatRepository.kt:70-190`
- **问题**：`undoLastMessage`（180-190 行）全删再插却不回写 `message_count`，计数只增不减；`sendMessage` 落库用户消息后若 LLM 失败无回滚，残留孤儿消息且计数虚增。
- **修法**：撤销改为 `@Transaction` 内单条 DELETE + 回写计数；发送失败时在同一事务中删除用户消息并减计数。
- **验收**：撤销后计数与真实消息数一致；断网发送失败后重试不产生重复消息。

##### M1-6 记忆标签检索完全失效
- **位置**：`app/src/main/java/com/mistbell/tavern/android/service/LocalMemoryService.kt:102-104、180-189`
- **问题**：`MemoryEntity.tags/aliases` 是 JSON 编码字符串（`MemoryEntity.kt:24-25`），代码却对其调 `.contains()`（子串误匹配）和 `.count{}`（逐字符迭代，匹配对象退化成 Char）。
- **修法**：先 `toDomain()` 解码为 `List<String>` 再做集合运算。
- **验收**：按标签/别名检索只命中真实包含该标签的记忆。

#### 安全加固

##### M1-7 API Key 明文存储三处收敛为一条加密路径
- **位置**：`SettingsRepository.kt:25,35`（llm_api_key 明文入 Room）、`ProviderRepository.kt:44-47,55`（providers_json 内嵌 apiKey）、`TavernApplication.kt:67-69,86-89,104-108`（embedding key 明文入 SharedPreferences）
- **修法**：统一走 Android Keystore 加密（EncryptedSharedPreferences 或自封装），三处读写全部改道；旧明文数据做一次迁移后清除。
- **验收**：数据库文件与 prefs 中 grep 不到明文 key。

##### M1-8 关闭敏感数据的云备份
- **位置**：`app/src/main/AndroidManifest.xml:9`
- **问题**：`allowBackup="true"` 且无排除规则，明文密钥随 Auto Backup 上云。
- **修法**：配置 `android:dataExtractionRules`（API 31+）与 `fullBackupContent`（API ≤30），排除 Room 数据库与 `tavern_settings`、`mistbell_android` 两个 prefs。
- **验收**：`adb backup` 产物中不含密钥文件。

##### M1-9 日志泄漏聊天内容
- **位置**：`CrashLogger.kt:45-53`（导出黑名单漏了 OkHttp 默认 tag `okhttp.OkHttpClient`，导致"已自动过滤聊天内容"承诺失效）；`ChatViewModel.kt:192`（打印用户消息全文）；`MemoryExtractionService.kt:259`（打印 LLM 响应前 500 字符）。
- **修法**：release 构建日志级别降为 NONE / BASIC（用 `BuildConfig.DEBUG` 区分）；黑名单补 okhttp tag；删除消息全文打印。
- **验收**：导出诊断报告后人工检查，无聊天内容与 API Key。

#### M1 验收清单

- [ ] 14 条已证实高危问题关闭 ≥10 条（其余 4 条在 M2 处理）
- [ ] 深色模式即时生效、回溯删除正确、列表实时刷新、计数准确
- [ ] 数据库/prefs/备份产物中无明文密钥
- [ ] `assembleDebug` 与 `assembleRelease` 构建通过

---

### 🧹 近期阶段（M2 · 清创 + 测试骨架 + 结构定向）

> 目标：删掉不该在的，建起防护网，修好依赖方向。预计 2~3 周，在 M1 验收后开始。

#### M2-1 死代码清创（约 3000 行）

逐项删除，每删一项跑一次编译：

| 目标 | 约行数 | 证据 |
|---|---|---|
| `ui/chat/ChatScreenNew.kt` | 520 | 全库零引用，导航实际用 `ChatScreen` |
| `ui/memory/MemoryListScreen.kt` + `MemoryViewModel.kt` | 600 | MEMORY_LIST 路由实际进 StructuredMemoryScreen；残留硬编码角色 `"mira"` |
| `ui/drawer/DrawerContent.kt` | 390 | 无任何抽屉接入 |
| `ui/components` 中无人引用的 5 个设置组件 + `SearchBar`/`SectionCard` | 700 | SettingsScreen 自带私有版本 |
| `test/VectorMemoryTest.kt`（在 main 源码集） | 300 | 靠 Log 打 ✅❌ 的手动测试工具 |
| `data/repository/example/ChatRepositoryIntegrationExample.kt` | 243 | 126 行注释伪代码，引用不存在的符号 |
| `ChatScreen.kt` 内 Welcome 死块 + 永不可达的清除对话框 | 125 | `showClearChatDialog` 从未被置 true |

- **验收**：全库 grep 零残留引用；编译通过；记录 APK 体积前后对比。

#### M2-2 测试骨架（防护网）

- 新建 `app/src/test/`、`app/src/androidTest/`；`app/build.gradle` 补 JUnit、kotlinx-coroutines-test、Room-testing（in-memory）依赖与 testInstrumentationRunner。
- 首批单测对象（与 M1 修复一一对应，防回归）：
  1. `MessageDao.deleteAfter` — 时间序删除正确性
  2. `ChatRepository` — 撤销计数一致、发送失败回滚
  3. `VectorUtils` — 余弦相似度零向量/维度不符边界
  4. `PromptBuilder.selectHistoryWithinBudget` — token 预算截断
- **验收**：单测 ≥ 15 条全绿，`gradlew test` 纳入日常验证。

#### M2-3 AppContainer 手工依赖注入

- 新增 `di/AppContainer.kt`：集中构造 database、api、各 repository/service；`TavernApplication` 只负责创建容器。
- 改造约 20 个文件：data/service 层删除对 `TavernApplication.instance` 的直接访问（含 `ChatRepositoryVectorExt` 顶层函数、`Theme.kt` 组合期摸库）。
- **验收**：除 Application 外全库 grep `TavernApplication.instance` 零命中。

#### M2-4 合并双轨提示词链路

- `data/prompt/PromptBuilder.kt`（已实现记忆注入）与 `service/LocalPromptService.kt:48,50`（记忆注入仍是 TODO 空串）为两套并行实现。保留 PromptBuilder 一条链路，删除或对齐另一条。
- **验收**：全库只有一条提示词构建路径。

#### M2-5 BM25 向量修复

- **位置**：`data/vector/BM25EmbeddingService.kt:24-35,73-81`（embed 每次污染语料、清空 IDF 缓存 → 同一文本向量非确定）；86-105 行 IDF 在锁外读写非线程安全 Map（竞态）；重启后语料归零但向量库保留旧向量（统计错位）。
- **修法**：embed 改为只读语料快照、IDF 确定性；或短期先在设置中默认禁用向量记忆，直至修复。
- **验收**：同一文本多次 embed 结果一致；并发 embed 无竞态（写入语料快照后可用单测验证确定性）。

#### M2-6 决策点：SyncManager 去留

- 现状：`pushPending()`（SyncManager.kt:85-96）只删队列不重放，全库无任何代码向 PendingSync 表插入数据——整套离线同步从未生效。
- **二选一**：实装重放（从 payloadJson 重建 API 调用 + 重试/去重）→ 转为正式功能；或整体删除（含 DAO、Entity、迁移），未来真需要时重新设计。
- 不允许维持现状（半死代码误导维护者）。

#### M2 验收清单

- [ ] 僵尸代码清零，APK 体积下降有记录
- [ ] 单测 ≥ 15 条全绿
- [ ] `instance` 直接引用清零
- [ ] 提示词单链路、BM25 确定性
- [ ] SyncManager 二选一完成

---

### 🚀 未来阶段（M3+ · 工程化与新功能）

> 目标：可维护性与体验升级。按需排期，条目之间无强依赖。

#### 工程化

- **地基选型与分批（F0~F5）**：见 [FOUNDATION.md](FOUNDATION.md)——CI/许可证审计/静态检查、okhttp-sse 真流式、pngj 卡片互通、ONNX 本地向量（合并 M2-5 BM25 修复）、sqlite-vec 规模升级、ACRA 崩溃上报。所有选型已核许可证（GPL/AGPL 红线）。

- **文案资源化**：strings.xml 目前只有 app_name 一条，250+ 处文案硬编码（中英混用，如 CharacterEditorScreen 的 "Discard changes?"）。先建分类骨架，随改随迁，不搞一次性大迁移。
- **Ext 文件收回**：`ChatRepositoryVectorExt` / `StructuredMemoryRepositoryExt` / `LocalPromptServiceExt` 是类被拆成顶层函数的痕迹（隐式依赖 `instance` 与文件级 `backgroundScope`），在 M2-3 完成后回归为类成员。
- **CI**：GitHub Actions / Gitee Go，流水线 = `gradlew assembleDebug test`，PR 必须绿。
- **数据库防静默清库**：`AppDatabase.kt:103` 的 `fallbackToDestructiveMigration` 会在升级遗漏迁移时清空全部数据。改 `exportSchema=true` + Room 迁移测试，最终移除 destructive fallback。
- **提交规范**：小步提交（现状仅 2 个大提交），feature 分支 + PR。
- **Deprecation 清理**（2026-08 盘点，约 37 处编译警告）：`Icons.Filled.ArrowBack/Chat/Undo/VolumeOff/KeyboardArrowRight/ArrowForward` → AutoMirrored 版本（~12 处，纯改名）；`Modifier.menuAnchor()` → 带 MenuAnchorType 的新重载（~15 处）；`outlinedButtonBorder` → 带 enabled 参数版本（~7 处）；协程 `@OptIn(ExperimentalCoroutinesApi)` 缺失 2 处（StructuredMemoryViewModel/WorldBookEditorViewModel）；`ChatListScreen.kt:379` SearchBar 旧 overload → inputField 新结构（唯一需要小重构的）。LocalTavernService 恒真条件随 M2-1 死代码清创一并消失。与 CI 批次同做，清零后可在 CI 开 `-Werror` 防回潮。

#### 功能补全

- **前辈借鉴（OMate/Tavo 调研）**：见 [PREDECESSORS.md](PREDECESSORS.md)——最高优先：**F2.1 宏引擎**（{{char}}/{{user}}/{{random}}/{{roll}}/{{#if}}，生态卡能跑的底线）+ 导入转化报告 + 上下文调试面板 + think 标签过滤；中期：FTS 词法检索先行并入 F3、预设覆盖栈并入 S1、撤销回滚记忆、卡底稿写回；远期：发现页开放协议（无后端卡片站）。

- **设置项路线（S1~S4）**：见 [SETTINGS.md](SETTINGS.md)（基于 ST/RisuAI/Agnai/ChatterUI/PocketPal 调研过滤）——S1 采样参数+预设三档+超时重试+会话附加指令；S2 摘要/向量/世界书设置（随 F3）；S3 Persona 与交互设置（随 MODES）；S4 全量备份。

- **玩法模式框架**：按 [MODES.md](MODES.md) 实施——近期做③群聊（说话者标注/轮转/witness 记忆分账，复用 participantCharacters）与②扮演（反转开关+叙事者模板（含代理权保护）+记忆 scope 重定向；骰子/状态等规则包不做）；④导演/⑤卡对卡仅做 schema 级骨架预留（ScopeKey 前缀格式 + 多 speaker 配置结构），不建空 UI 与死代码。模式用户侧命名：经典 / 扮演 / 群聊 / 导演 / 卡对卡剧场。
- **真流式输出**：当前全库无 SSE 解析，回复整包到达才渲染。改 OkHttp streaming + 逐 token 渲染，顺带解决请求不可取消问题。
- **消息长会话窗口分页（已完成，v16 性能修复）**：Paging 3 方案已废弃并移除依赖；现为 DAO 层窗口分页——`MessageDao.getLatestBySession`（只观察最新 200 条）+ `getOlderBySession`（上滚按 (created_at, id) 复合游标补加载一页），ViewModel 侧 `loadOlderMessages`/`mergeMessageWindow` 合并 prepend 缓存。若未来数据量再超阈值，优先扩展此方案（如更大窗口/懒加载页大小）而非引入 Paging 3。
- **导入功能**：世界书导入（WorldBookListScreen.kt:185）、聊天导入（ChatListScreen.kt:104）——UI 入口已存在但未实现。
- **continueMessage / swipeMessage 实装**（ChatRepository.kt:238-250 现为空函数体，UI 上可点无反应）。
- **设置页空入口收敛**：SettingsScreen.kt:193-258 六个 onClick 为空的入口要么实装要么从界面隐藏。

#### 明确不做（复议条件见括号）

- **Hilt**（团队 > 1 人或注入图 > 30 节点再议，AppContainer 手工 DI 足够）
- **多模块拆分**（代码 > 30k 行或多团队并行再议）

---

### 🗓 里程碑依赖关系

```
M1 正确性+安全 ──► M2 清创+测试+结构 ──► M3 工程化+功能
                    │
                    ├─ M2-1 清创 必须先于 M2-3 DI（不给死代码做注入）
                    └─ M2-2 测试骨架 必须先于 M2-3（手术前先有防护网）
```

M1 与 M2 的 M2-1/M2-2 之间无硬依赖，可以并行推进；M2-3 依赖 M2-1、M2-2。

---

### ⚠️ 风险与决策点汇总

| 决策点 | 说明 | 建议 |
|---|---|---|
| BM25 语义缺失 | 词袋伪向量无语义检索能力，是无 API Key 时的默认路径 | 产品上明确"关键词级召回"的预期，或尽快引导配置 embedding API |
| SyncManager 去留 | 空壳机制，见 M2-6 | 二选一，不留现状 |
| destructive fallback | 升级失误即全库清空且不可逆 | M1 内先 exportSchema=true，M3 落迁移测试 |
| equals 裁剪类"优化" | 0.2.2 曾以性能名义引入 M1-3 同类 bug | 回归标准：任何跳过 equals 字段的优化必须列出字段清单并说明"该字段不参与展示"的证据 |

---

*本文档由多代理代码审查（5 路深审 + 对抗性复核）结论整理而成；所有文件行号以 v0.3.0-beta 代码为准，后续修改代码时请同步更新引用。*
