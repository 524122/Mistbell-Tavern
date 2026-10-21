# 会话内长期记忆实现状态

状态：会话隔离、崩溃持久化恢复、真实聊天模型及真实本地 ONNX embedding 均已验收
最后更新：2026-10-19

本文件记录实现进度、设计取舍与验证结果，供 Codex 从文件独立核查。对应设计文档：docs/SESSION_SCOPED_MEMORY.md；前序审查：docs/LONG_TERM_MEMORY_CODE_REVIEW.md；本轮可重复验收：docs/SESSION_MEMORY_RECOVERY_ACCEPTANCE.md；历史 Codex 审查：review-round-1..12.md（监督目录）。

## 2026-10-21：召回可观测性（RecallDiagnostics）

### 实现

- **诊断值对象（`RecallDiagnostics`）**：一轮长期记忆召回的来源/条数/耗时/缓存命中，沿调用链以返回值传递（不引入单例可变状态），挂在 `PromptTrace` 上——真实发送与「查看提示词」预览看到同一次召回的实情，不改变实际发出的消息。
- **逐层出口**：`CachedVectorStore.lastSearchCacheHit`（缓存命中单点出口）；`VectorMemoryService.SearchOutcome` 增加 storeCandidates/cacheHit/latencyMs（默认值向后兼容）；`PromptBuilder` 装配时记录结构化条数与预算、两路候选数、融合数、去重后注入行数、两路耗时，召回块未注入时给出六种原因之一（闸门未过/缺会话边界/历史未裁剪/无命中/被结构化块覆盖/检索异常）。
- **呈现**：预览参数栏新增「记忆召回」行（`summaryLine()` 一行摘要）；复制提示词全文附带该行；注入点来源标签拆分为「会话记忆·结构化」「会话记忆·召回」；`PromptDiag` log 同步追加。
- 至此 `SESSION_SCOPED_MEMORY.md` 13.3 全部事项落地。

### 本轮实测结果

| 项目 | 实际结果 |
|---|---|
| JVM 单测 | **625 项 / 0 失败 / 0 错误**（新增 RecallDiagnosticsTest 6 项；既有召回渲染测试适配 RecallRender 返回类型并补注入条数断言） |

## 2026-10-20：混合召回、孤儿对账补全与 sqlite-vec 存储引擎

### 实现

- **混合召回（`RecallRanker`）**：有 embedding 时向量路与词法路同时检索，按 RRF（Reciprocal Rank Fusion，k=60）融合；仅向量命中且低于阈值仍丢弃，有词法印证的低分候选保留，纯词法命中保留并标注 `(keyword match)`（不伪造相似度）。时间衰减（半衰期 14 天、权重 0.15）与 summary ×1.1 仅作有界平局裁决；归一化内容去重替代完整 MMR。无 embedding 的词法回退路径不变。
- **阈值修复**：召回格式化层硬编码的 `score > 0.5` 下限移除——用户可配阈值（默认 0.35）此前被实际钳到 0.5；阈值现在只在 RecallRanker 统一应用一次。
- **消息级孤儿对账（`reconcileOrphanedSourcesWithDatabase`）**：每次启动在会话级对账后追加存在性核查（消息/记忆两个命名空间，500 条分块 IN 查询，源表为空则跳过该命名空间），孤儿向量按 `deleteByFilters` 集合匹配删除。收口的是"删除落盘被强杀打断而复活"的残留（召回时 Validator 兜底之外的预算治理）。
- **sqlite-vec 存储引擎（`SqliteVecStore`）**：vec0 虚拟表（按维度分表，元数据列可下推 KNN 过滤，`+content` 辅助列存载荷）+ requery sqlite-android（系统 SQLite 不能加载扩展），独立 `vector_index.db`，WAL + incremental auto-vacuum。增量持久化取代整文件 JSON 重写；向量离堆；`flush`/`reload` 退化为真值/no-op。字节预算淘汰复用纯函数 `VectorEviction`。装配期 `probe()` 探测扩展可用性，失败自动回退 `InMemoryVectorStore`（JSON 引擎保留）；旧 `vector_store.json` 在 sqlite 库为空时自动迁移（原文件改名 `.legacy` 兜底）。备份 zip 条目名与线格式不变（`VectorIndexJson.kt` 共享序列化），`SessionMemoryReset` 清零同时覆盖两种引擎的索引文件。
- **打包**：jniLibs 内置 4 个 ABI 的 `vec0.so`（sha256 经 GitHub release 校验）；`packagingOptions.jniLibs.useLegacyPackaging = true`（sqlite3_load_extension 需要文件系统路径）。

### 本轮实测结果

| 项目 | 实际结果 |
|---|---|
| JVM 单测 | **619 项 / 0 失败 / 0 错误**（含新增 RecallRankerTest 12 项、VectorOrphanReconcileTest 8 项、VectorIndexJsonTest 5 项） |
| Android 契约测试（Pixel_10 模拟器，x86_64） | `SqliteVecStoreContractTest` **9 项 / 0 失败**：扩展探测、KNN + 等值/IN 过滤、cosine→score 映射、身份收敛、删除闸门、purge、import/export 往返、重开持久化、未知过滤键 |
| `assembleDebug` | 通过；模拟器安装启动无崩溃，logcat 确认 `Vector store: sqlite-vec`（探测成功、生产装配走新引擎） |

### 已知边界

- vec0 是精确暴力搜索（非 ANN），万级向量在 C 层毫秒级，对本应用规模足够；
- 删除意图闸门仍为内存态（与 JSON 实现同一设计决策），进程结束由启动对账兜底；
- vec0 TEXT 元数据列拒绝 NULL，缺键统一落为空串，读回时 `source_revision` 空串还原为"键缺失"（Validator 的 revision 语义依赖 null）；
- KNN 查询中 `k` 与 `LIMIT` 互斥（vec0 约束），实现只用 `k`。


## 2026-10-19：持久化恢复与真实验收

### 实现

- **Room v27 + 持久化 outbox**：新增 `vector_sync_outbox`，每项包含 operationId、owner/session、sourceId、vectorMessageId、UPSERT/DELETE、sourceRevision、重试次数、下次重试时间和错误状态；无外键，删除源记录后仍保留完整索引删除身份。
- **源版本与原子意图**：`structured_memory` 和 `messages` 增加 `vector_revision`。生产 SQLite 触发器在 INSERT/影响索引内容的 UPDATE/DELETE 时，与源变更同一事务写入 outbox；Repository 再显式补齐等价待办，覆盖手动 CRUD、自动抽取、重要性变化、批量删除和备份合并。
- **恢复协议**：`VectorSyncRecovery` 在会话锁外 embedding，锁内重新读取 Room 当前版本；旧操作不能覆盖或删除新版本。只有索引写入/删除后的 `flush()` 成功才确认待办。最新操作成功只收敛同来源较老待办，不影响其他会话或更新操作。取消协程会向上传播，未确认操作可在下次启动继续。
- **可靠文件写入**：`InMemoryVectorStore` 串行化整文件替换，并在写前重取最新快照，避免旧快照晚到覆盖新状态；写盘、flush 失败不确认完成。容量满时明确跳过，避免补建—淘汰—再补建循环。
- **召回 fail-closed**：生产 `VectorMemoryService` 在 `CachedVectorStore` 返回后（包括缓存命中）再按 Room 当前归属、正文、重要性和 `sourceRevision` 过滤。旧/已删摘要不能进入最终 prompt；模型指纹不一致的向量不混搜。
- **升级与重建**：26→27 迁移保留业务数据并播种存量摘要待办；同时兼容旧版全新安装缺少 API 配置默认值/索引的 v26 形态。`VectorIndexRecoveryReconciler` 只做一次存量对账，不每次启动盲目重建。手动重建同时覆盖普通消息和高重要度摘要，并复用 outbox/version 规则。备份合并在同一 Room 事务产生恢复意图，不导入旧 outbox。
- **一次性清零**：`SessionMemoryReset` 的 DONE 语义不变；DAO `@Transaction` 同时清除记忆与 outbox。DONE 用户升级只安排正常同步，不再次清零。

主要新增文件：`VectorSyncOutboxEntity.kt`、`VectorSyncOutboxDao.kt`、`VectorSyncTriggers.kt`、`VectorSyncOutboxScheduler.kt`、`VectorSyncRecovery.kt`、`VectorRecallValidator.kt`、`VectorIndexRecoveryReconciler.kt`、`PersistentVectorIndexRebuilder.kt`、`VectorSyncRecoveryContractTest.kt`、Room 27 schema。同步修改 `AppDatabase.kt`、`StructuredMemoryRepository.kt`、`ChatRepository(VectorExt).kt`、`VectorMemoryService.kt`、`InMemoryVectorStore.kt`、`CachedVectorStore.kt`、`BackupManager.kt`、`SessionMemoryReset.kt`、`VectorIndexRebuilder.kt`、`AppContainer.kt`、`TavernApplication.kt` 及迁移测试。

### 本轮实测结果

| 项目 | 实际结果 |
|---|---|
| JVM 单测 | **552 项 / 0 失败 / 0 错误 / 0 跳过** |
| Android 契约与恢复测试 | **60 项 / 0 失败 / 0 错误 / 0 跳过** |
| `assembleDebug` | 通过 |
| `licenseGuard` | 通过（139 个依赖） |
| `detekt` | 未通过：119 weighted，恢复实现路径无本轮新增告警 |
| `ktlint` | 未通过：main 353 / test 77 / androidTest 0；恢复相关文件 0 告警 |
| 真实 UI | 已执行：新建、编辑、删除、重启保留、同角色双会话隔离、无崩溃/错误完成态 |
| 真实进程中断 | 已执行：force-stop 后重开，旧任务跳过、最新离线待办继续，未伪报完成 |
| 隔离升级 | 已执行：v26→v27 同包同签名覆盖；清理失败重试；DONE 用户正常记忆保留 |
| 真实 LLM 抽取与裁剪召回 | **已执行**：原应用非隔离、风见幽香；3 条当前 `auto_extract` 记忆来源有效，裁剪后的最终 prompt 仅由记忆块提供合成过敏事实，同角色第二会话无泄漏 |
| 真实 embedding（本地 ONNX） | **已执行**：23 条 512 维真实向量落盘，两次重启一致，outbox=0；`Relevant Past Conversations` 召回、缓存 miss→hit、会话过滤和 local/auto/local 指纹对照通过；远程 API embedding 未执行 |

证据索引与复跑步骤见 `docs/SESSION_MEMORY_RECOVERY_ACCEPTANCE.md` 和 `build/agent-logs/session-memory-recovery/`。第 3-4 节隔离验收期间原应用未覆盖安装、未清数据、未卸载，隔离包验收后已移除。随后按用户明确要求进行非隔离测试；测试运行器曾移除原应用数据，用户确认“以空数据继续”后仅用手工 UI/ADB，未再对原包运行 `connectedDebugAndroidTest`。真实模型证据见 `nonisolated-yuuka-llm-evidence.txt`，真实本地 embedding 证据见 `nonisolated-embedding-evidence.txt`。

## 约束与边界

- 工作树存在约 106 个用户未提交改动，全部保留；不 reset、不 commit、不 push、不全仓格式化。
- 初始设备验收使用隔离环境；后续非隔离测试是用户明确变更范围后的单独阶段，数据事件和空数据续测授权均在验收文档如实记录。
- 真实聊天模型与真实本地 ONNX embedding 均已验收；远程 API embedding 未单独执行，不把两类提供商标成同一个通过项。

## 已完成的实现（按模块）

### A. scope 统一与召回隔离（文档第 1-8 章）

- `MemoryScope.fromRaw` 一切输入归一化 SESSION；CHARACTER/OWNER @Deprecated（枚举保留兼容旧设置值/备份）。
- `ChatSettingsResolver.memoryScope()` 恒 SESSION；`RecallWindow.resolve` 固定当前会话窗口（sessionIds={当前会话}、characterId=null、includeUnassigned=false）。
- 三通道统一 owner+session：PromptBuilder.recallCandidates 只走 `getPromptCandidatesInSessions(owner, {当前会话})`；VectorMemoryService/LexicalMemoryService 缺 owner/session 拒绝召回（空结果，不退化），窗口含非当前会话值时收窄并告警；不按 characterId 过滤（群聊 NPC 可召回）。
- `MemoryContext.recallSpansSessions` 移除：召回块只按 historyTruncated 决定（文档 7.5）。
- 写入强制：`MemoryType.extractedSessionId` 恒返回 sourceSessionId；`StructuredMemoryRepository` create/update/delete 校验 owner+session 归属（会话存在且 ownerId 匹配），updateMemory 拒绝 sessionId 变更（归属不可变）。
- 抽取去重限定当前会话（新 DAO `getByOwnerAndSession` 替换跨会话查重）。

### B. 互斥与版本协议（文档第 4、6、10 章不变量）

- `SessionMutationLocks`：按 sessionId 的 Mutex 注册表（引用计数清理）。锁顺序纪律：会话锁 → store 内部锁，禁止反向。
- 进锁路径：deleteSession、clearConversation（补删结构化记忆）、ChatListViewModel 单删/多选删/deleteAllSessions（逐会话顺序取锁，无嵌套）、抽取保存、消息变更（回滚/撤回/回退/swipe/续写/regenerate/取消部分回复）、用户消息向量 fire-and-forget。
- 抽取批次版本一致性：PendingExtractionTurn 携带 messageSignatures（content+thinking 的 SHA-256，旧 JSON 缺省降级）；launchMemoryExtraction 锁内逐轮核对存在性+签名，任一轮失效整批候选作废、对有效轮次重抽；已删会话 ack 丢弃（不 nack 防死循环）；relatedMessageIds 只用有效轮次 ID。
- 慢路径锁内源校验：appendContinuation/persistSwipeCandidate/swipeMessage/regenerate/取消回复在锁内复查会话+目标消息有效，失效放弃写入（删除后不复活）。
- 向量摘要提交原子性：Repository 拆 prepareEmbedding（锁外）/ commitPrepared（锁内）；当前源修订号、最新 outbox 操作和索引提交/删除在同一会话锁临界区内复核，索引 flush 成功后才确认待办。
- replacePrevious 真替换：InMemoryVectorStore 按 (owner,session,message_id) 身份移除全部旧项再写入（同身份至多一条）；loadFromDisk 对旧文件同身份多条收敛为最后一条。
- deleteAllSessions owner 边界：全表 deleteAll 改为逐会话删除 + deleteByOwner 孤儿清理，不再越界删其他 owner。

### C. 升级一次性清零（文档第 9 章）

- `SessionMemoryReset.runIfNeeded`：清 structured_memory 全表 → 删 vector_store.json(.tmp)/pending_extraction.json(.tmp) → commit DONE 标记 → 挂提示 KV。顺序保证"提示只在清零+标记落盘成功后"。
- 失败语义：文件删除失败（存在但 delete false）抛 IOException 整体失败；commit 失败时撤销 SharedPreferences 内存态（防止把未持久化的 DONE 当就绪凭证）；失败不缓存，下次闸门调用真正重试（gate 直接调 runIfNeeded，不缓存 Deferred）。
- 闸门 fail-closed：未完成抛 MemoryResetPendingException；覆盖两个文件 Store（加载拒绝）、StructuredMemoryRepository 全部 suspend 入口（含 *Locked）、PromptBuilder 召回（降级为空）、BackupManager 导入导出（拒绝）。Flow 观察型入口（UI 列表）豁免，理由见类注释。
- 提示 UI：SessionMemoryResetNoticeViewModel 用 observeValue 持续观察（reset 晚于 UI 加载也能弹）；仅用户确认后清标记；失败清理不会显示"已清空"（pending=1 只在 commit 成功后写入）。

### D. 备份恢复（文档第 9、10 章）

- 归属校验 `isAttachableMemory`：sessionId 非 blank + 合并后 sessions 表存在 + ownerId 一致；mergeTables 与 shouldRebuildIndex 同源判定。
- 同 ID 冲突两阶段插入：显式 id 的 fresh 先落库，冲突项之后自增重插（修复"冲突项自增 id 被稍后 REPLACE 覆盖"）；同内容签名全表查重，重复恢复同一备份不制造副本。
- 任何偏离（过滤丢弃/冲突重插/签名不符）→ 强制重建索引，禁止 importIndex 旧文件带回已过滤记忆或 "cross_session" 哨兵。

### E. UI 与设置（文档第 8 章）

- 记忆管理页：移除"本角色"档位（MemoryLevelSelector 删除）、profile 自动选档、"角色级"标签；编辑对话框固定"仅本会话"。
- 设置页：移除三档 scope 选择器（MemoryScopeChips），固定说明"记忆仅对当前会话可见"。
- 文案：用户可见"长期记忆"→"会话记忆"（ModernSettingsScreen/ChatSettingsScreen/ChatSetupScreen/SettingsDataCards/OnboardingScreen）。
- CHANGELOG.md 新增 [Unreleased]：升级须知（清空历史记忆与向量索引）+ Changed/Removed/Fixed。

### F. 文档（第 13 章规则修正）

- 精确去重：relatedMessageIds 必须是单条事实实际来源的最小集合；整批共用 ID 不能证明覆盖；不确定时按"未覆盖"处理。
- superseded 同时约束摘要向量；三元组去重与属性冲突/事件身份分开（"喜欢咖啡"与"不喝咖啡"可同时成立）。
- 延迟索引标注首次裁剪空档为已知问题，本轮保留即时索引。

## Codex 审查项处置表

| 轮次 | 项 | 状态 | 证据 |
|---|---|---|---|
| R1-1 | delete 返回 false 仍写 DONE | 已修 | SessionMemoryReset.kt deleteWithTemp：存在且 delete false → IOException |
| R1-2 | gate 丢弃 false 继续读旧文件 | 已修 | AppContainer.kt gate：false → 抛 MemoryResetPendingException；直接调 runIfNeeded（不缓存 Deferred，失败可重试） |
| R1-3 | apply() 异步标记 | 已修 | commit() + 返回值检查 + 失败撤销内存态 |
| R1-4 | 闸门覆盖面 | 已修 | Repository 13 个 suspend 入口 + PromptBuilder 召回 + BackupManager 导入导出 |
| R1-5 | 备份归属/ID 冲突 | 已修 | isAttachableMemory + 两阶段插入 + 签名查重 |
| R1-6 | 批次版本一致性 | 已修 | messageSignatures 快照 + 锁内逐轮复核 + 整批作废重抽 |
| R1-7 | Repository 手动 CRUD 互斥 | 已修 | 公开入口取会话锁 + *Locked 变体；deleteMemory 需 owner+session |
| R1-8 | 锁内慢 embedding | 已修 | prepare/commit 拆分；commitCheck+提交在会话锁内（R4-1 进一步闭合原子性） |
| R1-9 | discard 短路 | 已修 | PendingExtractionStore.discard 分别删除后合并 |
| R2-1 | 锁外摘要三竞态 | 已修 | 修订签名 + 会话锁内核验提交（R4-1 闭合） |
| R2-2 | 提示漏弹 | 已修 | observeValue 持续观察；pending=1 晚于 DONE commit 写入 |
| R2-4 | 编辑路径同协议 | 已修 | 全部消息变更路径进会话锁 + 锁内源有效性复查 |
| R3-A | Deferred 缓存 false | 已修 | gate 直接调 runIfNeeded |
| R3-B | commit(false) 内存态 | 已修 | commit 失败 remove 撤销 |
| R3-C | 备份重编号覆盖 | 已修 | 两阶段插入（主会话直接修复） |
| R4-1 | commitCheck 非原子 | 已修 | 核验+提交并入会话锁临界区 |
| R4-2 | replacePrevious 不替换 | 已修 | putReplacingIdentityLocked + loadFromDisk 收敛 |
| R4-3 | 续写/swipe 锁内未复查 | 已修 | 锁内会话+消息有效性复查 |
| R5-通知 | DONE 后通知写失败永久漏提示 | 已修 | DONE 短路分支按 seen 键补挂 pending；确认才写 seen |
| R5-测试 | 11 项旧契约断言 + ChatProtocolTest | 已修 | 旧契约已对齐；ChatProtocolTest 预期修正并归因（见验证记录） |
| R8-删除 | deleteVectorCopy/deleteSummaryCopies 以 available 为前置，关闭 embedding 删除记忆留旧摘要 | 已修 | 两个删除入口移除 available 前置（生成新向量才需要 embedding）；StructuredMemoryVectorSyncContractTest 新增场景 e/f（available=false 删除后重开 store 摘要不复活、按会话批量删除同口径） |
| R8-lazy | 注入参数构造期求值破坏 lazy | 已修 | vectorMemoryService 注入缝改为可空参数 + by lazy 首次使用解析，记忆列表页不再连带初始化 embedding/ONNX |
| R10-缓存 | CachedVectorStore 无条件回填：删除前启动的搜索把旧结果回填到删除后的缓存 | 已修 | 缓存 generation 协议：失效+代递增在变更返回前完成，search 回填前锁内复查世代；覆盖 add/delete/clear/importIndex/reload；CachedVectorStoreContractTest 5 用例（真实 LruCache + 门闩 delegate，R11 修复了门闩未放行/快照未冻结/无有界等待三处测试 bug） |
| R12-离线编辑 | 离线/异常编辑高重要度记忆残留旧摘要（Sync 待办在 available=false 或 embedding 抛错时静默丢弃，旧副本不移除） | 已修 | SyncPrep 三态：Ready/StaleCopy/Abandon——无法生成新向量时降级为版本校验的 DeleteCopy（签名匹配才删旧副本，晚到的失败旧 Sync 不误删新版本）；新增 3 个 Repository 设备用例（离线编辑失效/抛错失效/晚到不误删——晚到用例按 Codex 复核改为"新摘要先落地再放行旧失败"，能区分有无守卫）；全部 9 个门闩用例纳入 withTimeout 有界等待 |
| R9-许可证 | generateLicenseReport 在 Gradle 9.4.1 下跨项目解析失败 | 已修 | jk1 插件 2.9→3.1.4（官方 3.1.2 起测试 Gradle 9.4.1），插件移到 :app 模块内应用（消除跨项目解析）；licenseGuard 真实通过（扫描 index.json 输出 OK），validation-05 独立复验通过（139 依赖） |

## 最终质量门（Codex validation-06 独立快照口径，10-14）

| 门 | 结果 | 说明 |
|---|---|---|
| JVM 单测 | **484 项 / 0 失败 / 0 错误 / 0 跳过** | validation-06 独立复跑最新源码 |
| Android 契约测试 | **42 项 / 0 失败 / 0 错误 / 0 跳过** | 隔离包名执行，原应用未安装/未变 |
| `assembleDebug` | 通过 | validation-06 |
| `licenseGuard` | 通过 | 139 个依赖无 copyleft 红线命中，validation-06 复验 |
| `detekt` | 未通过（78 weighted） | 存量静态债务（详见"静态检查收尾状态"），不声称全绿 |
| `ktlintCheck` | 未通过（main 89 / test 51） | 同上，存量债务 |
| `checkAll` | **未通过** | 阻塞项为 detekt/ktlint 存量静态债务 |
| **未验证边界（validation-06 当时口径）** | — | 完整 UI 流程、真实 LLM 抽取链路、真实生产迁移/升级当时均未在设备验证；当时进程中断可能残留旧/已删摘要且启动对账不修复 |

## 验证记录

- `./gradlew :app:compileDebugKotlin`：各轮修复后均通过。
- `./gradlew :app:compileDebugAndroidTestKotlin`：通过（含备份合并器契约测试与夹具修复后）。
- **单元测试（10-14 01:3x，工作树实测）**：全量 `./gradlew :app:testDebugUnitTest` **484 项 / 0 失败 / 0 跳过**（51 个测试类）；SessionMemoryResetTest 定向 5/5（幂等单用例化、DONE+seen=1 不补、DONE+未 seen 补挂三用例独立，FixMethodOrder 已移除，commit 调用计数有断言）。
- 旧契约测试已全部对齐新预期（MemoryScope/RecallWindow/MemoryTypeScope/VectorMemoryServiceScope 等，更新断言未删除未 skip）；净增 25+ 新契约用例（scope 归一化、群聊 NPC 召回、空 session 拒绝、discard 双清、签名批次持久化、向量身份替换、逆序提交、reset 失败注入等）。
- 报告路径：`app/build/test-results/testDebugUnitTest/`（XML）、`app/build/reports/tests/testDebugUnitTest/index.html`。
- **Codex 隔离设备验收（validation-02，10-14 01:25 后）**：独立包名 `com.mistbell.tavern.android.memoryvalidation` 快照执行 `connectedDebugAndroidTest`，**28/28 通过**（StructuredMemoryScope 8、SessionContract 8、BackupRestore 12）；原包未安装/未清理，运行前后安装版本与时间一致。该结果只证明冻结快照的三类 Room 契约，不代表完整 UI、模型抽取或最新并发测试已验收。
- **Codex 隔离设备验收（validation-04，10-14 02:0x）**：同一隔离快照 34 项 Android 记忆测试全过（含 R8 离线删除 2 项、Repository 并发 4 项），原包无变化，临时包已清理。注意：validation-04 快照中单测编译失败源是 SessionMemoryResetTest:128 的 KDoc 笔误（主目录已修），快照的设备测试通过不覆盖最新工作树的单测状态。
- **Codex 独立快照（validation-05，10-14）**：484 JVM 单测 + 39 项 Android 契约全通过（0 失败/错误/跳过），assembleDebug 通过，licenseGuard 独立通过（139 依赖），缓存反向对照证实 generation 守卫测试有效（绕过守卫恰 1 项失败，守卫版 5/5）；原应用安装信息未变。
- **Codex 收尾复核（validation-06，10-14，最新 R12 源码）**：484 JVM + 42 Android 全通过，assembleDebug、licenseGuard 通过；checkAll 因 detekt 78、ktlint main 89 / test 51 存量静态债务未通过。verify 明细：监督目录 validation-06/verification-results.json。
- **Codex 独立快照（validation-01，10-14 00:45）**：457 项 / 445 通过 / 12 失败——11 项旧契约断言已在测试阶段更新；1 项 ChatProtocolTest 归因如下。
- **ChatProtocolTest:131 归因**（已本地复现 + 修复预期，Codex R6 勘误确认失败断言是 stream 而非 temperature）：`Json { explicitNulls=false }` 未开 encodeDefaults，`stream: Boolean = false` 取默认值时不编码；扩展 JSON 的 `stream:true` 又被保护字段拦截，请求体不含 stream 键。该测试属用户未提交的 API 扩展字段特性（git diff 确认为新增），与记忆改造无关；协议语义上省略 stream=false 与显式 false 等价。断言已改为"扩展不得注入 stream/messages"，保护意图不变弱。
- **validation-02 当时未执行**：该阶段不对 emulator-5554 安装/清库，真实迁移与备份恢复的真机失败注入未做，完整 UI/抽取链路也未在设备验证；当时仅在隔离包名快照跑通 Room 契约 instrumentation。后续本轮实测结果以第 8-37 行及验收文档为准。

## 第五轮新增修复

- **补通知可恢复**：SessionMemoryReset 在 DONE 短路分支检查 `session_memory_reset_notice_seen`：用户未确认过就重挂 pending="1"——提示写入失败不再永久漏提示，且不重做清理。NoticeViewModel.dismissNotice 确认时同时写 seen="1"。

### androidTest 契约覆盖（compileDebugAndroidTestKotlin 通过）

- StructuredMemoryScopeTest 重写为会话内契约（8 项）；StructuredMemorySessionContractTest（同角色不同会话/owner 隔离/空 session 不命中/deleteByOwner 不越界/重启保留）；空串 sessionId 用例已按 DAO 明确契约修正（IN 集合不含空串，生产入口拒绝由单元测试覆盖）。
- BackupRestoreMemoryContractTest：经可注入 db 的 `StructuredMemoryRestoreMerger`（从 BackupManager 抽出的生产合并组件，非测试副本）覆盖归属过滤、两阶段 ID 冲突反例（含 JSON 顺序颠倒）、重复恢复不制造副本、同 id 同内容跳过；解码层夹具已改为 serializer 真实产物（camelCase），不再被容错丢弃。
- StructuredMemoryVectorSyncContractTest（9 用例）：Repository 生产并发路径——逆序提交、旧 DeleteCopy 晚到、计算期间删除、顺序更新唯一副本、R8 离线删除 ×2、R12 离线/抛错编辑失效、晚到失败旧 Sync 不误删新版本；全部纳入 withTimeout 有界等待。CachedVectorStoreContractTest（5 用例）：真实 LruCache 下删除/update/clear/importIndex 后查询不命中旧缓存、无变更时缓存正常命中。
- 本轮迁移契约：Room 26→27 新增 outbox/schema；MigrationDataIntegrityTest 覆盖旧库保留与存量待办播种，MigrationSchemaAlignmentTest 校验迁移 SQL 不删除消息/记忆业务数据。

## 静态检查收尾状态（validation-06 最终口径）

- **detekt**：78 weighted issues，全部为存量债务（本会话新/改动行的告警已全部修复）。
- **ktlint**：main 89 项、test 51 项（test 侧全部在用户既有文件 ApiConfigDocumentTest.kt），本会话改动的文件零违规；androidTest 源零违规。
- **归因更正**：此前文档写的"本轮开工快照 145"不准确——145 是 validation-03 中间实现快照的报告数，开工 baseline manifest 并未包含 detekt 报告；"存量 vs 新增"以 validation-05 的 detekt-changed-lines.json / ktlint-origin-details.json 与 validation-06 复验为准。
- **存量债务（本会话从未触碰的文件，保持原样）**：ApiConfigEditorDialog.kt、ApiConfigDocument.kt、ApiConfigViewModel.kt、ChatViewModel.kt、ChatScreen.kt、SessionImporter/Exporter、LocalWorldBookService.kt、VectorIndexMaintenance.kt、ApiConfigMigration.kt 等的 ktlint/detekt 违规，以及 ApiConfigDocumentTest.kt。需单独治理，不在本轮范围。

## 已知遗留

1. 旧格式持久化批次（无签名）锁内只查存在性，编辑过的轮次可能漏检——过渡数据，重新入队自愈。
2. `VectorMemoryService.storeMessage` 的 commitCheck 参数暂无生产调用方，保留为测试挂点。
3. 锁内 Room 读是协程串行而非线程串行——对会话锁互斥语义足够。
4. 远程 OpenAI 兼容 API embedding 未单独验收：真实本地 ONNX 已完成生成、落盘、重启、缓存召回和指纹对照；该结果不覆盖远程 API 提供商路径，后者需要单独授权配置与合成数据验收。
5. 结构化记忆的 supersede/事实更新状态机、混合召回、延迟索引：文档第 13 章后续批次，本轮不实施。
