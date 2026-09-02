# 路线图与工作规划

> 本文档回答两个问题：**当前需要做什么、未来需要做什么**。
> 全部条目来自 2025 年对本仓库（v0.3.0-beta）的多代理代码审查，14 条高危问题均经二次复核证实，文件与行号可直接定位。
> 配合 [CHANGELOG.md](../CHANGELOG.md) 使用：每个里程碑完成后在 CHANGELOG 记录。

---

## 📌 现状快照

| 维度 | 现状 |
|---|---|
| 版本 / 规模 | v0.3.0-beta，单模块 `:app`，117 个 Kotlin 文件，约 9000 行 |
| 测试 | **零**（无 `src/test`、无 `src/androidTest`，构建脚本未声明测试依赖） |
| 提交历史 | 仅 2 个大提交，无 CI |
| 高危问题 | 14 条（全部复核证实），另有 medium/low 约 40 条 |
| 子系统评分 | 数据层 6 / 向量记忆 5 / 对话链路 5 / UI 层 5 / 横切面 5（满分 10） |

一句话诊断：**架构骨架正确（MVVM + Repository + Flow、协程纪律好、Room 设计规范），但缺自动回归保护，正确性漏洞与僵尸代码集中爆发。**

---

## 🧭 总原则

1. **先修正确性 → 再补防护网（测试）→ 再做结构优化 → 最后新功能**。
2. 每个条目必须有验收标准；未经验证不宣称完成。
3. 结构手术（DI、合并双轨）必须排在死代码清创之后——不给要删的代码做注入。

---

## 🔥 当前阶段（M1 · 正确性修复 + 安全加固）

> 目标：把会出错的问题修掉，把泄密通道堵上。预计 1~2 周。

### Bug 修复

#### M1-1 回溯/重新生成会删错消息（最高优先级）
- **位置**：`app/src/main/java/com/mistbell/tavern/android/data/local/dao/MessageDao.kt:30`
- **问题**：`DELETE ... AND id > :afterMessageId` 把随机 UUID 字符串按字典序比较，与时间序无关。调用方 `ChatRepository.backtrackToMessage`（192-200 行）与 `regenerateMessage`（202-236 行）实际会删除任意子集。
- **修法**：改按 `created_at > (SELECT created_at FROM messages WHERE id = :afterMessageId)` 删除，或为消息加单调递增序号列。
- **验收**：回溯与重新生成只删目标消息之后的内容；为该 DAO 方法补单元测试（M2 测试骨架落地前先手测记录）。

#### M1-2 深色模式切换需重启才能生效
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/theme/Theme.kt:80-82`
- **问题**：`flow { emit(db.settingsDao().getValue("dark_mode") ...) }` 是一次性发射，不观察数据库变化；设置页切换后根主题永不重组。
- **修法**：`SettingsDao` 增加 `observeValue(key): Flow<String?>`，主题处改用该 Flow。
- **验收**：设置页切换深/浅/跟随系统，界面即时重绘，无需重启。

#### M1-3 聊天列表显示陈旧数据
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/chatlist/ChatListViewModel.kt:39-60`
- **问题**：`ChatListItem` 手写 equals 漏掉 characterName、头像、参与者、lastMessageSender 等**正在展示的字段**。此问题由 0.2.2 的"列表重组次数减少约 30%"优化引入（见 CHANGELOG），是典型误优化。
- **修法**：equals/hashCode 覆盖全部展示字段，或直接删掉手写实现回归 data class 默认行为。
- **验收**：角色改名/换头像/改色后，聊天列表行立即刷新。

#### M1-4 角色卡对话数写死
- **位置**：`app/src/main/java/com/mistbell/tavern/android/ui/character/CharacterListScreen.kt:417`
- **问题**：`Text(text = "23 对话")` 硬编码，所有角色一律显示 23。
- **修法**：CharacterListViewModel 按 characterId 统计会话数（一条 GROUP BY 查询）并传入 `CharacterCardItem`。
- **验收**：每个角色显示真实会话数。

#### M1-5 会话计数漂移 + 撤销非原子 + 发送失败残留
- **位置**：`app/src/main/java/com/mistbell/tavern/android/data/repository/ChatRepository.kt:70-190`
- **问题**：`undoLastMessage`（180-190 行）全删再插却不回写 `message_count`，计数只增不减；`sendMessage` 落库用户消息后若 LLM 失败无回滚，残留孤儿消息且计数虚增。
- **修法**：撤销改为 `@Transaction` 内单条 DELETE + 回写计数；发送失败时在同一事务中删除用户消息并减计数。
- **验收**：撤销后计数与真实消息数一致；断网发送失败后重试不产生重复消息。

#### M1-6 记忆标签检索完全失效
- **位置**：`app/src/main/java/com/mistbell/tavern/android/service/LocalMemoryService.kt:102-104、180-189`
- **问题**：`MemoryEntity.tags/aliases` 是 JSON 编码字符串（`MemoryEntity.kt:24-25`），代码却对其调 `.contains()`（子串误匹配）和 `.count{}`（逐字符迭代，匹配对象退化成 Char）。
- **修法**：先 `toDomain()` 解码为 `List<String>` 再做集合运算。
- **验收**：按标签/别名检索只命中真实包含该标签的记忆。

### 安全加固

#### M1-7 API Key 明文存储三处收敛为一条加密路径
- **位置**：`SettingsRepository.kt:25,35`（llm_api_key 明文入 Room）、`ProviderRepository.kt:44-47,55`（providers_json 内嵌 apiKey）、`TavernApplication.kt:67-69,86-89,104-108`（embedding key 明文入 SharedPreferences）
- **修法**：统一走 Android Keystore 加密（EncryptedSharedPreferences 或自封装），三处读写全部改道；旧明文数据做一次迁移后清除。
- **验收**：数据库文件与 prefs 中 grep 不到明文 key。

#### M1-8 关闭敏感数据的云备份
- **位置**：`app/src/main/AndroidManifest.xml:9`
- **问题**：`allowBackup="true"` 且无排除规则，明文密钥随 Auto Backup 上云。
- **修法**：配置 `android:dataExtractionRules`（API 31+）与 `fullBackupContent`（API ≤30），排除 Room 数据库与 `tavern_settings`、`mistbell_android` 两个 prefs。
- **验收**：`adb backup` 产物中不含密钥文件。

#### M1-9 日志泄漏聊天内容
- **位置**：`ApiClient.kt:33-35`（`Level.BODY` 记录全部请求体）；`CrashLogger.kt:45-53`（导出黑名单漏了 OkHttp 默认 tag `okhttp.OkHttpClient`，导致"已自动过滤聊天内容"承诺失效）；`ChatViewModel.kt:192`（打印用户消息全文）；`MemoryExtractionService.kt:259`（打印 LLM 响应前 500 字符）。
- **修法**：release 构建日志级别降为 NONE / BASIC（用 `BuildConfig.DEBUG` 区分）；黑名单补 okhttp tag；删除消息全文打印。
- **验收**：导出诊断报告后人工检查，无聊天内容与 API Key。

#### M1-10 ApiClient 单例线程安全
- **位置**：`ApiClient.kt:18-19,27-28,59-65`
- **问题**：`retrofit/api` 是普通 var，`getApi` 为 check-then-act，并发下可能重复创建或读到半初始化实例。
- **修法**：`@Volatile` + synchronized（或 Mutex）。
- **验收**：代码评审通过；无功能回归。

### M1 验收清单

- [ ] 14 条已证实高危问题关闭 ≥10 条（其余 4 条在 M2 处理）
- [ ] 深色模式即时生效、回溯删除正确、列表实时刷新、计数准确
- [ ] 数据库/prefs/备份产物中无明文密钥
- [ ] `assembleDebug` 与 `assembleRelease` 构建通过

---

## 🧹 近期阶段（M2 · 清创 + 测试骨架 + 结构定向）

> 目标：删掉不该在的，建起防护网，修好依赖方向。预计 2~3 周，在 M1 验收后开始。

### M2-1 死代码清创（约 3000 行）

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

### M2-2 测试骨架（防护网）

- 新建 `app/src/test/`、`app/src/androidTest/`；`app/build.gradle` 补 JUnit、kotlinx-coroutines-test、Room-testing（in-memory）依赖与 testInstrumentationRunner。
- 首批单测对象（与 M1 修复一一对应，防回归）：
  1. `MessageDao.deleteAfter` — 时间序删除正确性
  2. `ChatRepository` — 撤销计数一致、发送失败回滚
  3. `VectorUtils` — 余弦相似度零向量/维度不符边界
  4. `PromptBuilder.selectHistoryWithinBudget` — token 预算截断
- **验收**：单测 ≥ 15 条全绿，`gradlew test` 纳入日常验证。

### M2-3 AppContainer 手工依赖注入

- 新增 `di/AppContainer.kt`：集中构造 database、api、各 repository/service；`TavernApplication` 只负责创建容器。
- 改造约 20 个文件：data/service 层删除对 `TavernApplication.instance` 的直接访问（含 `ChatRepositoryVectorExt` 顶层函数、`Theme.kt` 组合期摸库）。
- **验收**：除 Application 外全库 grep `TavernApplication.instance` 零命中。

### M2-4 合并双轨提示词链路

- `data/prompt/PromptBuilder.kt`（已实现记忆注入）与 `service/LocalPromptService.kt:48,50`（记忆注入仍是 TODO 空串）为两套并行实现。保留 PromptBuilder 一条链路，删除或对齐另一条。
- **验收**：全库只有一条提示词构建路径。

### M2-5 BM25 向量修复

- **位置**：`data/vector/BM25EmbeddingService.kt:24-35,73-81`（embed 每次污染语料、清空 IDF 缓存 → 同一文本向量非确定）；86-105 行 IDF 在锁外读写非线程安全 Map（竞态）；重启后语料归零但向量库保留旧向量（统计错位）。
- **修法**：embed 改为只读语料快照、IDF 确定性；或短期先在设置中默认禁用向量记忆，直至修复。
- **验收**：同一文本多次 embed 结果一致；并发 embed 无竞态（写入语料快照后可用单测验证确定性）。

### M2-6 决策点：SyncManager 去留

- 现状：`pushPending()`（SyncManager.kt:85-96）只删队列不重放，全库无任何代码向 PendingSync 表插入数据——整套离线同步从未生效。
- **二选一**：实装重放（从 payloadJson 重建 API 调用 + 重试/去重）→ 转为正式功能；或整体删除（含 DAO、Entity、迁移），未来真需要时重新设计。
- 不允许维持现状（半死代码误导维护者）。

### M2 验收清单

- [ ] 僵尸代码清零，APK 体积下降有记录
- [ ] 单测 ≥ 15 条全绿
- [ ] `instance` 直接引用清零
- [ ] 提示词单链路、BM25 确定性
- [ ] SyncManager 二选一完成

---

## 🚀 未来阶段（M3+ · 工程化与新功能）

> 目标：可维护性与体验升级。按需排期，条目之间无强依赖。

### 工程化

- **地基选型与分批（F0~F5）**：见 [FOUNDATION.md](FOUNDATION.md)——CI/许可证审计/静态检查、okhttp-sse 真流式、pngj 卡片互通、ONNX 本地向量（合并 M2-5 BM25 修复）、sqlite-vec 规模升级、ACRA 崩溃上报。所有选型已核许可证（GPL/AGPL 红线）。

- **文案资源化**：strings.xml 目前只有 app_name 一条，250+ 处文案硬编码（中英混用，如 CharacterEditorScreen 的 "Discard changes?"）。先建分类骨架，随改随迁，不搞一次性大迁移。
- **Ext 文件收回**：`ChatRepositoryVectorExt` / `StructuredMemoryRepositoryExt` / `LocalPromptServiceExt` 是类被拆成顶层函数的痕迹（隐式依赖 `instance` 与文件级 `backgroundScope`），在 M2-3 完成后回归为类成员。
- **CI**：GitHub Actions / Gitee Go，流水线 = `gradlew assembleDebug test`，PR 必须绿。
- **数据库防静默清库**：`AppDatabase.kt:103` 的 `fallbackToDestructiveMigration` 会在升级遗漏迁移时清空全部数据。改 `exportSchema=true` + Room 迁移测试，最终移除 destructive fallback。
- **提交规范**：小步提交（现状仅 2 个大提交），feature 分支 + PR。
- **Deprecation 清理**（2026-08 盘点，约 37 处编译警告）：`Icons.Filled.ArrowBack/Chat/Undo/VolumeOff/KeyboardArrowRight/ArrowForward` → AutoMirrored 版本（~12 处，纯改名）；`Modifier.menuAnchor()` → 带 MenuAnchorType 的新重载（~15 处）；`outlinedButtonBorder` → 带 enabled 参数版本（~7 处）；协程 `@OptIn(ExperimentalCoroutinesApi)` 缺失 2 处（StructuredMemoryViewModel/WorldBookEditorViewModel）；ExportViewModel 重复创建 Json 实例 1 处提为复用；`ChatListScreen.kt:379` SearchBar 旧 overload → inputField 新结构（唯一需要小重构的）。LocalTavernService 恒真条件随 M2-1 死代码清创一并消失。与 CI 批次同做，清零后可在 CI 开 `-Werror` 防回潮。

### 功能补全

- **前辈借鉴（OMate/Tavo 调研）**：见 [PREDECESSORS.md](PREDECESSORS.md)——最高优先：**F2.1 宏引擎**（{{char}}/{{user}}/{{random}}/{{roll}}/{{#if}}，生态卡能跑的底线）+ 导入转化报告 + 上下文调试面板 + think 标签过滤；中期：FTS 词法检索先行并入 F3、预设覆盖栈并入 S1、撤销回滚记忆、卡底稿写回；远期：发现页开放协议（无后端卡片站）。

- **设置项路线（S1~S4）**：见 [SETTINGS.md](SETTINGS.md)（基于 ST/RisuAI/Agnai/ChatterUI/PocketPal 调研过滤）——S1 采样参数+预设三档+超时重试+会话附加指令；S2 摘要/向量/世界书设置（随 F3）；S3 Persona 与交互设置（随 MODES）；S4 全量备份。

- **玩法模式框架**：按 [MODES.md](MODES.md) 实施——近期做③群聊（说话者标注/轮转/witness 记忆分账，复用 participantCharacters）与②扮演反转（反转开关+叙事者模板（含代理权保护）+记忆 scope 重定向；骰子/状态等规则包不做）；④导演/⑤卡对卡仅做 schema 级骨架预留（ScopeKey 前缀格式 + 多 speaker 配置结构），不建空 UI 与死代码。
- **真流式输出**：当前全库无 SSE 解析，回复整包到达才渲染。改 OkHttp streaming + 逐 token 渲染，顺带解决请求不可取消问题。
- **消息长会话窗口分页（已完成，v16 性能修复）**：Paging 3 方案已废弃并移除依赖；现为 DAO 层窗口分页——`MessageDao.getLatestBySession`（只观察最新 200 条）+ `getOlderBySession`（上滚按 (created_at, id) 复合游标补加载一页），ViewModel 侧 `loadOlderMessages`/`mergeMessageWindow` 合并 prepend 缓存。若未来数据量再超阈值，优先扩展此方案（如更大窗口/懒加载页大小）而非引入 Paging 3。
- **导入功能**：世界书导入（WorldBookListScreen.kt:185）、聊天导入（ChatListScreen.kt:104）——UI 入口已存在但未实现。
- **continueMessage / swipeMessage 实装**（ChatRepository.kt:238-250 现为空函数体，UI 上可点无反应）。
- **记忆归属修复**：`MemoryRepository.loadFromServer/createMemory` 不落 session_id，服务端拉回的记忆按会话查询不到。
- **设置页空入口收敛**：SettingsScreen.kt:193-258 六个 onClick 为空的入口要么实装要么从界面隐藏。

### 明确不做（复议条件见括号）

- **Hilt**（团队 > 1 人或注入图 > 30 节点再议，AppContainer 手工 DI 足够）
- **多模块拆分**（代码 > 30k 行或多团队并行再议）

---

## 🗓 里程碑依赖关系

```
M1 正确性+安全 ──► M2 清创+测试+结构 ──► M3 工程化+功能
                    │
                    ├─ M2-1 清创 必须先于 M2-3 DI（不给死代码做注入）
                    └─ M2-2 测试骨架 必须先于 M2-3（手术前先有防护网）
```

M1 与 M2 的 M2-1/M2-2 之间无硬依赖，可以并行推进；M2-3 依赖 M2-1、M2-2。

---

## ⚠️ 风险与决策点汇总

| 决策点 | 说明 | 建议 |
|---|---|---|
| BM25 语义缺失 | 词袋伪向量无语义检索能力，是无 API Key 时的默认路径 | 产品上明确"关键词级召回"的预期，或尽快引导配置 embedding API |
| SyncManager 去留 | 空壳机制，见 M2-6 | 二选一，不留现状 |
| destructive fallback | 升级失误即全库清空且不可逆 | M1 内先 exportSchema=true，M3 落迁移测试 |
| equals 裁剪类"优化" | 0.2.2 曾以性能名义引入 M1-3 同类 bug | 回归标准：任何跳过 equals 字段的优化必须列出字段清单并说明"该字段不参与展示"的证据 |

---

*本文档由多代理代码审查（5 路深审 + 对抗性复核）结论整理而成；所有文件行号以 v0.3.0-beta 代码为准，后续修改代码时请同步更新引用。*
