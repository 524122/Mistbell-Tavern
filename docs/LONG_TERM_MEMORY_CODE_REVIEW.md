# 长期记忆系统代码质量审查

日期：2026-10-04
对象：当前 feature 工作树中的长期记忆修改，包括未跟踪的新实现和测试文件。
对照：HEAD `1bdde33`，并参考本次对话中修改前的代码检查结果。
结论：暂不建议按“长期记忆问题已全部修复”验收。正常流程有所改善，但范围隔离、失败恢复、并发删除、备份与重建仍有问题。

## 审查边界与验证

本报告是代码审查，不是设备性能评测。以下触发路径由当前源码和调用关系确认；未进行真机故障注入、进程强杀或文件系统失败实验。

执行与检查结果：

| 检查 | 结果 |
|---|---|
| `gradlew.bat checkAll` | 失败；detekt 报告 40 项，另有许可证报告任务的配置缓存问题 |
| `:app:assembleDebug` | 通过，生成 Debug APK |
| `:app:testDebugUnitTest` | Gradle 判定 UP-TO-DATE；现有 XML 报告 43 个测试类、419 项测试、0 失败、0 跳过 |
| `:app:ktlintCheck` | 失败，主要是主源码格式问题 |
| `licenseGuard` | 通过 |
| `git diff --check` | 通过（不包含未跟踪文件） |
| Android instrumentation | 未执行；新增 StructuredMemoryScopeTest 的设备端结果未验证 |

detekt/ktlint 的失败包含原有代码，例如 HEAD 已存在的 WorldInfoRuntime.activate 和其他未修改文件，不能全部归因于这次长期记忆修改。当前工作树整体仍未满足 checkAll 门禁。

## 已确认的改善

- 会话向量删除及主要调用入口已改为 owner + session，可以覆盖群聊所有说话者。
- 向量写入有 fingerprint，搜索有指纹过滤和维度守卫。
- 新增写入延迟合并窗口，并关闭 JSON prettyPrint。
- 淘汰时优先保留 summary，普通消息按时间排序。
- 结构化召回增加明确的 scope 查询和渲染预算。
- 未满批次的抽取缓冲可以写入文件，并由 AppContainer 共享。
- 增加文件持久化、群聊删除、指纹、重建和 scope 的测试。

以下问题涉及这些机制之间的衔接；拥有对应类和单元测试不等于整条调用链已经满足契约。

## 问题清单

P1：影响数据完整性或召回范围，建议验收前修复。
P2：常规优先级缺陷，也应有明确修复或产品边界。

### R01 · P1 · CHARACTER 词法召回退化为 OWNER，三条通道的范围仍不一致

位置：
- `data/repository/LexicalMemoryService.kt:56`
- `data/local/dao/MessageDao.kt:192`
- `data/prompt/PromptBuilder.kt:950`
- `data/local/dao/StructuredMemoryDao.kt:35`

以上及后续简写路径均相对 `app/src/main/java/com/mistbell/tavern/android/`。

触发：默认 CHARACTER 档、embedding 不可用，角色 A 与角色 B 的不同会话都包含同一个关键词。

词法层把所有非 SESSION 档都转换为 sessionId=null，DAO 随后只按 owner 检索，没有通过 sessions/参与角色集合收窄。A 的会话历史被裁剪时，B 的内容即可注入 A 的提示词。“消息的 character_id 只是说话者元数据”只能说明应按会话确定归属，不能据此移除跨会话的角色边界。

此外，向量 CHARACTER 档会搜索该角色所有会话的向量，而结构化 CHARACTER 档只允许本会话或 session_id=NULL 的角色级记忆；向量 SESSION 档仍传主角色过滤，会漏掉同会话 NPC 向量。因此选择同一 scope 的实际结果取决于走哪条召回通道。

建议：先把 scope 解析为统一的允许会话/角色级记忆集合，再供三条通道使用；SESSION 查询不加说话者过滤。补上 A/B 角色、同一群聊多说话者、显式会话级记忆的跨通道测试。

### R02 · P1 · 新增跨会话召回后，旧裁剪开关仍把结果全部丢弃

位置：`data/prompt/PromptBuilder.kt:828`、`:946`。

触发：选择 CHARACTER/OWNER，在短会话或新会话询问其他会话中的内容。

召回现在可以来自其他会话，但 MemoryContext.messagesFor 仍仅在 historyTruncated=true 时保留 recallBlock。当前历史没有被裁剪，就会丢弃全部向量/词法结果。其他会话的内容并没有出现在当前历史里，旧的“都是重复内容”假设已失效。

建议：保留召回条目的 messageId/sessionId，按最终已选历史 ID 去重；不能用“当前会话是否裁剪”决定其他会话内容是否可注入。

测试缺口：现有 PromptBuilderMemoryLayoutTest 仍只验证旧的整块丢弃规则，没有覆盖跨会话来源。

### R03 · P1 · 写盘失败已增加退避重试，但删除一致性仍需持久化对账

位置：`data/vector/InMemoryVectorStore.kt:385`、`:408`、`:420`。

触发：写入窗口遇到磁盘写入失败；或删除立即保存失败，随后重试也失败。

当前窗口写入失败会按退避重新排队，flush 会返回失败并重新排队；删除类操作也会进入重试窗口。仍然缺少持久化删除意图和启动对账：如果进程在内存删除后、重试成功前终止，Room 已删而磁盘旧向量仍可能复活。

因此注释中“直到与内存一致”的保证并未实现。Room 已删、磁盘旧文件仍在时，关闭进程会丢失唯一的待删内存状态，重启可以加载旧向量。VectorMemoryService 的返回值仍不能可靠表达持久化失败，也没有持久化删除任务或启动对账。

建议：保留 dirty/delete journal 或启动时按 Room 消息与索引 message_id 对账；删除接口向上返回持久化状态，不能只依赖活着的进程重试。

### R04 · P1 · fire-and-forget 向量写入可在回滚或删除后重新插入

位置：`data/repository/ChatRepository.kt:159`、`:266`；`data/vector/VectorMemoryService.kt:61`、`:82`。

可发生的时序：

1. 用户消息入库后，后台协程开始慢速 embedding。
2. LLM 请求快速失败，Room 回滚并调用 deleteMessageVectors；此时向量尚未存在。
3. embedding 完成，独立 backgroundScope 继续 add。
4. 已删除的消息重新出现在向量文件中；新加入的跨会话召回会扩大它被读到的机会。

清空/删除会话也存在相同竞态。当前没有关联 Job 取消等待、源消息版本检查或删除标记。

建议：把写入与删除放入按消息或会话排序的索引任务协议；使用源消息版本/删除标记，并防止已完成删除的消息再次提交索引。单独在 add 前查一次数据库仍有检查与提交之间的竞态。

### R05 · P1 · 备份恢复采用“Room 合并、索引重建/导入”策略

位置：`data/repository/BackupManager.kt:257`、`:293`、`:319`、`:368`。

触发：本机已有新会话或编辑过的记忆，恢复一份较旧备份或来自另一设备的备份。

当前实现已增加内容集合和内容签名比较：Room 合并后若本机消息或结构化记忆集合/正文发生差异，会清空并按合并后的 Room 重建消息和重要记忆索引；集合完全一致时才通过 VectorStore 的锁内导入接口使用备份索引。仍需设备端验证自增记忆 ID 冲突、重建耗时和跨设备恢复。

建议：补充跨设备相同自增 ID 但内容不同的恢复测试，并将重建进度和失败状态反馈给 UI。

这项风险的首版修复已落地；单独把“文件不存在才恢复”改成“总是替换”仍然不足，因此现在通过内容签名决定导入还是重建。

### R06 · P1 · 备份导入已移入 VectorStore 锁，仍需设备端失败注入

位置：`data/repository/BackupManager.kt:343`、`data/vector/InMemoryVectorStore.kt:312`。

当前 BackupManager 通过 `VectorStore.importIndex()` 导入，校验、写盘、内存重载均在存储自己的互斥锁内完成，已移除调用方直接改 `.tmp` 的竞态路径。

剩余风险是 Android 文件系统上 rename/覆盖失败的真实行为、备份写入中断以及导入和后台写入的设备级验证；代码层面已集中到同一存储锁。

建议：增加真实文件失败/进程中止测试，并确保 rename 失败时旧文件仍可恢复；BackupManager 继续只调用 VectorStore API。

### R07 · P1 · 为控制提示词长度而截断持久化正文，造成不可逆数据丢失

位置：`data/repository/StructuredMemoryRepository.kt:111`、`:134`；`ui/memory/StructuredMemoryScreen.kt:393`。

触发：保存超过 300 字的手动记忆，或仅修改已有长记忆的重要度/标签后保存。

编辑框接受完整正文，仓库却把 content 截成前 300 字加省略号后写回 Room。用户看到“保存成功”，重新打开时后半段已经丢失。提示词装配本身已有截断，因此持久化截断不是实现注入预算的必要条件。

此外，同步向量时传入的仍是未截断的 memory 参数，可能导致 Room 与 summary 向量正文不一致。

建议：保留权威正文，只在提示词投影中按预算截断；如果产品确实限制输入长度，应在编辑器中显式校验并保留原文，不得静默截掉旧数据。

### R08 · P2 · 重建已按 embedding 指纹跳过，仍需覆盖部分旧索引场景

位置：`data/repository/VectorIndexRebuilder.kt:68`；`data/vector/InMemoryVectorStore.kt:264`；`data/vector/VectorMemoryService.kt:86`。

触发：旧索引覆盖了待重建的最近 200 条消息，切换 embedding 模型并重启，然后点击重建。

当前 VectorStore 的 indexedMessageIds 已要求传入当前 fingerprint，旧模型向量不会再被判定为已索引；新测试也覆盖了全量旧指纹场景。仍需验证“部分候选当前指纹、部分候选旧指纹”时，旧指纹清理与待重建快照的顺序。

建议：在重建开始前按当前指纹清理或建立候选快照，并增加“旧模型全覆盖候选”和“部分覆盖候选”两种用例。

### R09 · P2 · 生产容量和全量重建策略已调整，仍需容量治理

位置：`data/vector/InMemoryVectorStore.kt:143`；`data/repository/VectorIndexRebuilder.kt:68`、`:117`。

生产 AppContainer 已将 InMemoryVectorStore 的容量参数设为 0，表示不启用静默条数淘汰；VectorIndexRebuilder 的默认上限改为 Int.MAX_VALUE，并在备份重建路径补建重要结构化记忆摘要。受限测试仍可传正数验证淘汰策略。仍需制定真正的磁盘容量治理、分页重建和进度/取消策略，避免无限索引在低存储设备上增长。

如果后续调用方重新传入正容量，仍可能出现淘汰后的静默丢失；当前生产配置只是关闭了该路径，没有实现磁盘空间上限和可观测的淘汰策略。

建议：实现按磁盘预算的可观测淘汰、按 owner/session 的分片和分页重建；将重建由一次性 Int.MAX_VALUE 调用改成可取消的分批任务。

### R10 · P2 · 抽取批次已增加 inflight/ack/nack，但仍需真实失败验证

位置：`data/repository/PendingExtractionStore.kt:82`、`:114`；`data/repository/ChatRepository.kt:1180`、`:1199`。

当前满批或 drainAll 会转入持久化 inflight；抽取成功后 ack，配置不可用或异常时 nack 退回 pending；进程重启时 inflight 会恢复到 pending。批次还会在执行前校验源消息仍存在，减少删除会话后的悬空写入。仍需真实 LLM 失败、进程中止和重复 ack/nack 验证，并为成功后的候选写入增加幂等键。

建议：增加失败注入测试和幂等抽取键；若应用需要跨进程可靠执行，再将文件队列迁移到 WorkManager 或 Room。

当前专项测试已覆盖 ack/nack 的持久化行为，但尚未覆盖 LLM 成功后部分候选写入失败、进程中止和真实后台任务重启。

### R11 · P1 · 列表删除已清理持久化抽取缓冲，仍受已启动任务竞态影响

位置：`ui/chatlist/ChatListViewModel.kt:251`、`:271`；`data/repository/ChatRepository.kt:670`、`:779`。

触发路径：某会话留下未满批次的持久化缓冲 → 应用进程结束 → 重开后在聊天列表删除该会话 → 打开其他聊天触发 drainAll。

列表删除和 ChatRepository 现在都会调用共享 `PendingExtractionStore.discard`，任务执行前也会检查源消息是否仍存在。已经进入 LLM 请求的任务仍可能在删除之后完成；当前需要依靠执行前检查，尚未有任务取消或版本标记。

建议：把删除和抽取任务取消/版本校验收拢到统一会话服务，确保已启动任务的结果也不能提交。

### R12 · P2 · 备份导出已接入 flush，仍需设备端验证失败策略

位置：`data/repository/BackupManager.kt:179`、`:214`；`data/vector/VectorStore.kt:97`。

`exportToUri` 现在在采集备份前要求 flush 成功，因此正常路径不会读取落后的索引；flush 失败会阻止备份继续。仍需设备端验证磁盘失败提示、用户重试和备份文件完整性。

建议：保持由存储提供 flush/快照结果，不要在 BackupManager 直接读取并猜测文件状态。

### R13 · P2 · 记忆管理列表把所有角色级记忆都列为本会话可见

位置：`data/local/dao/StructuredMemoryDao.kt:64`；`ui/memory/StructuredMemoryViewModel.kt:46`。

新查询允许当前 owner 的所有 session_id=NULL 记录，未限定角色或群聊参与者。A 的记忆管理页会看到 B 的角色级记忆，且没有角色标签帮助区分。用户还可以在 A 页面将 B 的记忆切为“仅本会话”；updateMemory 会更换 sessionId，但保留 B 的 characterId，形成错位记录。

建议：列表沿用与召回一致的角色/会话集合，查询上下文包含 character/participants；对跨归属编辑做明确处理。新增管理页测试需包含“其他角色的角色级记忆”。

### R14 · P2 · 新的“本角色”手动记忆入口未确保 characterId 非空

位置：`ui/memory/StructuredMemoryViewModel.kt:135`；调用依据 `navigation/AppNavigation.kt:401`、`:497`。

从聊天设置进入记忆管理时，路由只传 sessionId；ViewModel 不从会话读取角色，直接把可空 characterId 写入。选择“本角色”后得到 characterId=NULL、sessionId=NULL 的记录。

默认 CHARACTER 查询要求 character_id=当前角色，向量侧把空角色写成 unknown，也不会被当前角色查询命中。界面提示“该角色在任何会话里都记得”，实际默认范围召回不到。

建议：由 sessionId 解析并验证所属角色/参与者，再开放角色级保存；全局用户记忆如要支持，应以单独作用域表达，不能借缺失 characterId 隐式创建。

### R15 · P2 · 渲染前先按类型分组，预算裁剪破坏了重要度优先级

位置：`data/prompt/PromptBuilder.kt:1067`、`:1088`。

选择器虽然按重要度排序，渲染又按固定类型顺序分组：“用户信息”先于“重要事件”等。在小预算下，低重要度 preference 可以先占满预算，让高重要度 event/core 被整体拒绝。代码注释“裁掉最不重要的尾巴”不成立。

建议：先在全局排序的候选中分配 token 预算，确定实际保留项，再对保留项分组展示。测试要混合类型与重要度；当前预算测试全部使用 fact，无法发现此问题。

## 测试质量观察

1. 有价值的新增测试：真实临时文件的正常保存/重载、群聊删除保留其他用户、指纹过滤、DAO 作用域。
2. InMemoryVectorStoreTest 没有写盘失败、连续重试失败和恢复同时写入的用例。
3. VectorIndexRebuilderTest 为已有记录主动补上当前 fingerprint，未覆盖升级前无指纹和换模型场景。
4. PendingExtractionStoreTest 把 drain 即删除当作预期，没有处理确认或删除会话后的恢复测试。
5. MemoryTokenBudgetTest 只测同类型排序；ScopeTest 只测枚举值和结构化 DAO，没有跨通道集合一致性验证。
6. testOptions.returnDefaultValues=true 让 Android stub 静默返回默认值，不能据 JVM 测试推断真实 Android LruCache、生命周期或 Room 行为已验证。

## 原问题修复状态

| 原问题 | 当前判断 |
|---|---|
| 群聊删除角色过滤 | 主要调用入口已修正；并发与失败一致性另见 R03/R04/R11 |
| 不同维度混搜 | 有指纹与维度守卫；重建已按当前指纹跳过，部分旧指纹混合场景仍需验证，见 R08 |
| 每次 add 同步整文件写入 | 改成 1.5 秒合并窗口和紧凑 JSON；单次保存仍持锁全量写，未实测设备延迟 |
| 1000 条 FIFO 静默遗忘 | **R09 已实现首版**：生产装配取消静默条数淘汰，重建上限改为全量；仍需磁盘容量治理 |
| 删除失败无一致性保障 | 已增加退避重试和 flush 返回值；持久化删除对账仍未完成，见 R03 |
| 召回范围统一 | 未实现跨通道一致，见 R01/R02/R13/R14 |
| 记忆 token 预算 | 部分实现；持久化丢正文、分组优先级有回归，见 R07/R15 |
| 待抽取缓冲持久化 | **R10 已实现首版**：批次有 inflight/ack/nack，成功后确认，失败或进程重启可退回重试；仍需补真实失败注入和任务去重验证 |
| 备份与运行时索引一致 | **R05 已实现首版**：恢复在存储锁内导入；Room 合并集合或内容发生变化时清空并按当前 Room 重建，避免直接灌回旧索引；仍需设备端恢复验证 |
| 向量容量与早期记忆恢复 | **R09 已实现首版**：生产装配取消静默 1000 条淘汰，重建上限改为全量；受限测试构造仍可保留容量淘汰策略 |

## 建议修复顺序

1. 修复 P1 的作用域、删除竞态、写盘错误处理和恢复一致性。
2. 保留完整记忆正文，修正跨会话注入与管理页归属。
3. 为 **R05/R09/R10** 增加设备端和故障注入验证：备份合并、全量重建、磁盘失败、LLM 失败、进程中止和重复 ack/nack。
4. 增加对应失败场景/交互场景验证，遵循仓库要求在编写设备交互测试前先做 ARTEMIS 探索。
5. 通过质量门后再验收；对既有静态检查债务单独说明，不应靠扩大 suppress/baseline 掩盖新问题。

本次没有修改业务源码、现有测试或此前的未提交改动。报告与构建检查日志属于本次审查产物。
