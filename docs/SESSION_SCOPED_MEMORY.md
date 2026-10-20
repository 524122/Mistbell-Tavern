# 会话内长期记忆设计说明

状态：设计决策

适用版本：Mistbell Tavern Android

## 1. 决策

本项目采用**会话内长期记忆**，不实现跨会话记忆。

这里的“长期”表示记忆会被持久化到本地数据库，可以跨越页面退出、应用重启和进程重建继续存在；它不表示记忆可以被其他聊天会话使用。

一条记忆的可见范围由 `sessionId` 决定：

| 场景 | 是否可见 |
|---|---|
| 同一会话的后续消息 | 可以 |
| 同一会话因上下文裁剪而暂时不在 prompt 中的旧内容 | 可以通过记忆召回 |
| 退出应用后重新打开同一会话 | 可以 |
| 同一个角色创建的新会话 | 不可以 |
| 其他角色的会话 | 不可以 |
| 同一用户的其他会话 | 不可以 |
| 群聊中的同一会话 | 可以，除非未来增加更细的 witness 规则 |

因此，本项目不把 `CHARACTER` 或 `OWNER` 作为实际召回范围，并且**永久不实现跨会话记忆**。`MemoryScope` 中的这些枚举值标记为废弃，仅为兼容旧代码和旧备份保留；产品设置不得暴露或启用它们，未来也不开放。

## 2. 与短期记忆的区别

会话内长期记忆和普通上下文历史不是同一件事：

- **上下文历史**是当前请求直接带给模型的最近消息，受 token 上限影响。
- **会话内长期记忆**是从同一会话的旧消息中提取出的结构化事实、摘要或向量索引，在历史被裁剪后按需召回。
- **跨会话记忆**会把 A 会话的内容带入 B 会话，本项目明确禁止这种行为。

例如，用户在会话 A 中说“我对海鲜过敏”，应用可以在会话 A 的历史被裁剪后继续提醒模型；用户新建会话 B 后，这条信息不会自动出现。

## 3. 当前代码基础

项目已经具备大部分基础设施：

- [StructuredMemoryEntity.kt](../app/src/main/java/com/mistbell/tavern/android/data/local/entity/StructuredMemoryEntity.kt) 使用 Room 持久化结构化记忆；
- [MemoryExtractionService.kt](../app/src/main/java/com/mistbell/tavern/android/service/MemoryExtractionService.kt) 从对话中提取候选记忆；
- [PendingExtractionStore.kt](../app/src/main/java/com/mistbell/tavern/android/data/repository/PendingExtractionStore.kt) 为未完成的抽取批次提供持久化缓冲；
- [PromptBuilder.kt](../app/src/main/java/com/mistbell/tavern/android/data/prompt/PromptBuilder.kt) 将结构化记忆、向量召回和词法召回注入 prompt；
- [MemoryScope.kt](../app/src/main/java/com/mistbell/tavern/android/data/repository/MemoryScope.kt) 和 [RecallWindow.kt](../app/src/main/java/com/mistbell/tavern/android/data/repository/RecallWindow.kt) 已有召回范围抽象；
- 向量索引支持本地 embedding，embedding 不可用时可以退回词法召回。

当前实现仍有两处与本决策冲突：

1. [StructuredMemory.kt](../app/src/main/java/com/mistbell/tavern/android/data/api/model/StructuredMemory.kt) 会把部分资料型记忆保存为 `sessionId = null`，形成角色级记忆；
2. SESSION 召回路径仍可能查询 `session_id IS NULL` 的记录。

所以不能只把设置项改成 `SESSION`，必须同时收紧写入、召回、删除和旧数据清理逻辑。

## 4. 不变量

实现完成后，以下规则必须始终成立：

1. 新建的自动记忆和手动记忆必须有有效的 `ownerId` 和 `sessionId`。
2. `sessionId` 是记忆的唯一可见性边界；`characterId` 只能描述来源角色或说话者，不能扩大召回范围。
3. 所有召回通道都只能访问当前 `sessionId`：结构化、向量和词法召回必须使用同一范围。
4. `session_id IS NULL` 的历史记录不能自动注入任何新会话。
5. 删除会话必须删除该会话的消息、结构化记忆、向量索引和待抽取任务。
6. 记忆正文在数据库中保存完整内容；只有注入 prompt 时才按 token 预算截断。
7. 记忆抽取失败可以重试，但失败任务不能因为会话已删除而重新写入记忆。

## 5. 数据模型

### 5.1 结构化记忆

`structured_memory` 表继续保存以下主要字段：

- `owner_id`：本地用户隔离；
- `session_id`：必需的会话归属；
- `character_id`：产生或归因的角色，可为空或保存主角色 ID；
- `memory_type`：身份、偏好、关系、事件、目标等类型；
- `content`：完整正文；
- `importance`、`tags`、`keywords`：排序和检索元数据；
- `related_message_ids`：来源消息；
- `source_type`：手动创建、自动抽取或导入。

现阶段可以保留 `session_id` 的可空数据库字段，以兼容旧数据库结构和旧备份格式；但 Repository 不得再创建新的空值记录。升级清零（见第 9 章）完成后，可以通过 Room migration 把该字段改为非空。

### 5.2 记忆类型不再决定作用域

身份、偏好、关系和事件仍然可以使用不同的 `memoryType` 来影响排序、展示和抽取优先级，但它们都保存到当前会话：

```text
当前会话 + 用户身份       -> session_id = 当前会话
当前会话 + 用户偏好       -> session_id = 当前会话
当前会话 + 角色关系       -> session_id = 当前会话
当前会话 + 剧情事件       -> session_id = 当前会话
```

`MemoryType.PROFILE_TYPES` 可以继续作为“资料型内容”的分类集合，但不能再用于把 `sessionId` 改成 `null`。

## 6. 写入流程

### 6.1 自动抽取

消息落库后，按照现有的批处理机制把用户消息和 AI 回复加入当前会话的抽取队列。抽取完成后，每个候选记忆必须使用来源批次的 `sessionId`：

```text
convertToMemoryDomain(..., sourceSessionId)
    -> memory.sessionId = sourceSessionId
```

去重和冲突检查也只在同一个会话内执行。不同会话即使内容完全相同，也不应因为跨会话去重而互相影响。

### 6.2 手动创建和编辑

从聊天页面进入记忆管理时，必须先通过 `sessionId` 读取会话，并验证 `ownerId`。保存前拒绝以下情况：

- `sessionId` 为空；
- `sessionId` 不属于当前用户；
- 试图用另一个会话的记忆 ID 更新当前会话内容。

编辑记忆时保留原记录的 `sessionId`。**不提供跨会话移动或复制功能**：记忆的归属会话在创建时确定，之后不可变更。用户如需在另一个会话拥有相同内容，只能在该会话中手动重新创建。

### 6.3 批处理和失败恢复

[PendingExtractionStore.kt](../app/src/main/java/com/mistbell/tavern/android/data/repository/PendingExtractionStore.kt) 的按会话缓冲策略可以保留。需要确保：

- 批次快照中保存 `sessionId`；
- 抽取执行前确认来源消息仍属于该会话；
- 会话删除时清理 pending 和 inflight 批次；
- 抽取完成写入前再次检查会话版本或删除标记；
- 成功写入后才执行 `ack`，失败则 `nack` 并等待重试。

## 7. 召回流程

### 7.1 统一召回窗口

所有请求都使用以下语义：

```text
scope              = SESSION
currentSessionId   = 当前会话 ID
sessionIds         = { 当前会话 ID }
characterId        = 不用于扩大召回范围
includeUnassigned  = false
```

`RecallWindow.resolve()` 不应再查询角色名下或用户名下的其他会话。

### 7.2 结构化召回

结构化 DAO 的生产查询只允许匹配当前会话：

```sql
SELECT * FROM structured_memory
WHERE owner_id = :ownerId
  AND session_id = :sessionId
ORDER BY importance DESC, created_at DESC
```

应移除 SESSION 路径对以下查询的依赖：

- `session_id IS NULL`；
- `character_id = 当前角色 AND session_id IS NULL`；
- 当前角色所有会话的集合查询。

### 7.3 向量召回

普通消息向量和结构化记忆摘要向量都必须带有当前会话的 `sessionId`。会话内设计不再需要用角色级哨兵值表示“跨会话可见”的摘要。

向量搜索的过滤条件应至少包含：

```text
ownerId  == 当前用户
sessionId == 当前会话
```

同一群聊会话内的消息归属由 `sessionId` 决定，不应再用主角色的 `characterId` 过滤向量结果，否则其他 NPC 发言不会被召回。角色级 witness 可见性若未来实现，应作为独立规则叠加在会话边界内。

向量索引只是 Room 记忆和消息的检索投影，不能成为独立的权威数据源。Room 删除或回滚后，旧向量不得重新出现。

### 7.4 词法召回

词法召回只搜索当前会话的消息，并继续排除当前 prompt 已经包含的最近消息。embedding 不可用时，词法召回可以作为同一会话内的降级方案，不能退回 owner 级或 character 级搜索。

### 7.5 Prompt 注入

当前会话的历史未被裁剪时，旧消息已经在 prompt 中，召回片段可以跳过，避免重复消耗 token；历史被裁剪后，再注入同一会话的结构化事实和相关旧消息。

无论历史是否被裁剪，召回结果都不能来自其他会话，因此不能用“当前会话是否裁剪”作为跨会话内容的隐式放行条件。

### 7.6 崩溃一致性与持久化恢复

Room 是权威数据，向量文件只是可重建投影。结构化记忆和普通消息的写入、修改、删除都不能只依赖进程内待办：

1. **源与意图同事务**：`vector_sync_outbox` 与源行在同一个 Room 事务/SQLite 触发器中记录。outbox 不带源表外键，删除会话或记忆后仍保留 owner、session、sourceId、vectorMessageId 和修订号。
2. **持久化修订**：源表的 `vector_revision` 在每次影响向量正文、召回资格或归属的变更时重新生成。旧操作只可按自己的 operationId 结束，不能删除新版本或期间新增的待办；备份重插和 ID 重用会得到新修订。
3. **重放以当前 Room 为准**：恢复器不照抄 outbox 旧正文。embedding 在会话锁外完成，提交/删除前在 `SessionMutationLocks` 内重新确认当前源版本、会话归属、重要性和最新操作。旧任务晚到只能跳过，不能覆盖新摘要或误删新摘要。
4. **落盘后确认**：内存写入不等于完成。只有索引新增/替换/删除后的 `flush()` 成功，才确认 outbox。索引已落盘但确认前崩溃时，重放按来源身份替换，不产生重复向量。写盘失败、flush 失败、取消或进程中断都保留可重试状态。
5. **召回时再次校验**：即使结果来自 `CachedVectorStore`，也必须回查 Room 当前归属、正文和修订号。恢复期间可以暂时缺少摘要，但不能把已删或旧版本摘要注入 prompt。embedding 指纹同时限制向量空间，模型切换不得混搜。
6. **后台与资源约束**：启动恢复和运行期触发共享同一恢复器，按批处理并限制并发。embedding 不可用、限流或异常时保留指数退避待办；删除过期索引不依赖 embedding。容量满时明确放弃本次补建，避免补建—淘汰—再补建。
7. **一次性对账**：升级迁移为存量摘要安排同步，索引中的孤儿/陈旧条目只清理一次。不得把数据库查询失败当成“数据为空”并全删索引；不得每次启动盲目全量 embedding。
8. **清零与手动操作**：一次性 SessionMemoryReset 会先清理 outbox，旧任务不能复活已清数据。用户主动删除记忆与主动重建索引是不同意图；已有 DONE 标记的用户不得被再次清零。

## 8. UI 和设置

建议将设置和记忆管理页面中的文案从“长期记忆”改为“本会话记忆”或“会话记忆”，避免用户误以为信息会自动同步到其他聊天。

设置层可以按以下方式迁移：

```text
旧设置缺省值 / character / owner -> session
旧设置 session                  -> session
```

不显示作用域选择器。本项目永久不开放 CHARACTER 或 OWNER 作用域，无需为未来开放预留设计。

记忆管理页面只列出：

- 当前会话的记忆；
- 当前会话中产生的自动记忆；
- 当前会话可编辑和可删除的记录。

页面不应显示其他角色或其他会话的角色级记录。

## 9. 旧数据处理：一次性清零

本项目不迁移旧记忆数据。版本升级时执行一次性清理：

1. 清空 `structured_memory` 表的全部记录（包括会话内记录和 `session_id IS NULL` 的角色级记录）；
2. 删除全部向量索引文件（消息向量和摘要向量）；
3. 清空 PendingExtractionStore 的 pending 和 inflight 批次；
4. 旧设置中的 character / owner 档位静默映射为 session。

清理完成后系统从空白状态按本方案运行：不做归属追溯，不保留"待处理"记录，不把任何旧记录挂到当前角色或当前会话。

恢复旧备份时应用同样规则：备份中 `session_id IS NULL` 的记忆记录在导入时丢弃，不进入新库；会话内记忆仍按其 `sessionId` 正常恢复。

必须在更新日志和应用内一次性提示中告知用户：本次升级重构了记忆系统，历史记忆已全部清空，且此后每个会话的记忆相互独立。

## 10. 删除、备份与恢复

删除会话必须是一个完整操作：

```text
删除 pending extraction
删除结构化记忆
删除消息向量和摘要向量
删除消息
删除 session
```

备份中应同时保存消息、结构化记忆以及它们的 `sessionId`。恢复时：

- 如果保留原会话 ID，记忆可以直接恢复；
- 如果导入为新会话，必须把该会话关联的消息和记忆一起重映射到新 ID；
- 不能只恢复记忆正文而丢失 `sessionId`；
- 不能因为角色 ID 相同而把旧会话记忆合并到新会话。

## 11. 验收标准

### 必须通过的隔离测试

- 会话 A 和会话 B 使用同一个角色，A 的记忆不会出现在 B；
- 会话 A 和会话 B 使用不同角色，即使关键词相同，也不会互相召回；
- OWNER 和 CHARACTER 配置值迁移后不会产生跨会话结果；
- 结构化、向量、词法三种通道对同一测试数据返回同一范围。

### 必须通过的持久化测试

- 退出聊天页面后重新进入同一会话，记忆仍然存在；
- 应用重启后重新打开同一会话，记忆仍可召回；
- 当前会话历史被裁剪后，较早的同会话事实可以重新注入；
- 记忆抽取失败后，批次可重试且不会重复写入；
- 删除会话后，Room、向量索引和抽取队列中都没有该会话的记录。

### 必须通过的编辑和备份测试

- 手动记忆不能保存为空 `sessionId`；
- 记忆正文只在 prompt 投影中截断，数据库原文不丢失；
- 备份恢复后，记忆仍然绑定原会话；
- 跨设备或跨备份导入不会因相同角色 ID 产生跨会话召回；
- 版本升级后旧结构化记忆和向量索引全部清空；恢复旧备份时 `session_id IS NULL` 的记录被丢弃。

## 12. 实施顺序

建议按以下顺序落地：

1. 将新写入统一改为 `sessionId = sourceSessionId`，禁止 Repository 创建空会话记忆；
2. 将 `RecallWindow` 固定为 SESSION，关闭所有 `session_id IS NULL` 的生产召回；
3. 统一结构化、向量和词法通道的会话过滤；
4. 修正记忆管理页的查询和编辑校验；
5. 实现升级时的一次性清零和旧备份导入过滤；
6. 补齐删除竞态、抽取失败恢复和备份恢复测试；
7. 最后再调整设置文案、提示词预览和用户说明。

群聊中的 witness 记忆分账属于更细的“同一会话内按角色可见”问题，不属于本方案的跨会话范围。当前版本先保证同一会话可用、不同会话严格隔离。

## 13. 结构化记忆与向量记忆的优化

两种记忆应承担不同职责：

```text
结构化记忆 = 当前会话的可信事实库
向量记忆   = 当前会话旧消息的语义检索索引
Room       = 唯一权威数据源
```

结构化记忆保存已经确认的事实，向量记忆帮助找回历史消息。向量索引是 Room 数据的派生结果，不能成为事实的唯一来源。

### 13.1 结构化记忆

#### 作为权威记忆

结构化记忆应当可以脱离向量服务独立工作。向量索引丢失、embedding 不可用或索引重建失败时，Room 中的结构化记忆仍然可以注入 prompt。

当前 [StructuredMemoryRepository.kt](../app/src/main/java/com/mistbell/tavern/android/data/repository/StructuredMemoryRepository.kt) 会将高重要度结构化记忆同步为向量摘要。这个同步只属于检索优化，不能改变 Room 记录的权威性。

#### 会话内去重

抽取去重应使用以下组合键：

```text
ownerId + sessionId + factKey
```

`factKey` 可以由规范化后的 `subject + relation + object` 组成，例如：

```text
user | allergic_to | 海鲜
```

当前 [MemoryExtractionService.kt](../app/src/main/java/com/mistbell/tavern/android/service/MemoryExtractionService.kt) 的重复检查应限定在当前会话。不同会话的相同内容不互相去重，也不互相覆盖。

#### 从重复丢弃改为事实更新

对于用户偏好、目标、关系和剧情状态，应支持事实更新。建议为记忆增加状态：

```text
active       当前有效
superseded   已被新记忆取代
deleted      用户删除
```

`superseded` 必须同时约束摘要向量：被取代记忆的摘要向量副本要同步失效（删除或排除出召回），prompt 注入和向量召回都只返回 `active` 记录。Room 状态与向量投影必须一致，不允许"Room 已取代、向量索引仍召回旧摘要"。

三元组去重与属性冲突/事件身份是两件事，必须分开处理：

- `factKey` 去重只处理**完全相同**的 `subject + relation + object` 被重复抽取的情况；
- 同一主体在不同时点的相反陈述（如"喜欢咖啡"与"现在不喝咖啡"）不是重复，两者可以同时成立；是否构成"同一属性的状态更新"需要时间语义和事件身份判断，不能靠文本相似度把其中一条当重复丢掉；
- 事件身份（同一事件的不同描述）与属性冲突（同一属性的新旧取值）各自需要独立规则，不混入去重键。

例如用户先说"喜欢咖啡"，后来说明"现在不喝咖啡了"且语义上构成状态更新时，处理方式应是：

1. 保留旧记忆作为来源记录；
2. 将旧记忆标为 `superseded`（摘要向量同步失效）；
3. 保存新的有效记忆；
4. prompt 与向量召回只返回 `active` 记录。

这比单纯依赖相似度去重更适合处理会话中的状态变化。

#### 综合排序

结构化记忆不应只按重要度排序。可以使用可调的综合分数：

```text
finalScore = relevance + importance + recency + accessBonus
```

建议的排序因素如下：

- 用户明确确认的内容优先；
- 过敏、禁忌、边界、当前目标提高权重；
- 最近更新的有效事实优先；
- 被新记录取代的事实不参与排序；
- 很久未使用的普通事件逐步降低权重。

#### 精确去重

记忆是否已经出现在 prompt 中，应根据 `relatedMessageIds` 与最终选中的历史消息 ID 判断：

```text
来源消息已经在 prompt 中 -> 不重复注入
来源消息被历史裁剪     -> 允许注入记忆
```

不要只用“历史是否被裁剪”这一项布尔值决定所有记忆是否注入。当前会话内不同记忆的来源可能处于不同的历史窗口。

`relatedMessageIds` 必须是该事实**实际来源的最小消息集合**。整批抽取共用的消息 ID 列表不能证明单条事实已被 prompt 覆盖——批次里某条消息在 prompt 中，不代表从该批产生的每条事实都已被覆盖。无法确认单条事实来源时按"未覆盖"处理（允许注入）：宁可重复注入，也不错误抑制。

### 13.2 向量记忆

#### 向量是可选增强

向量记忆主要解决语义表达不同但含义相近的查找问题，例如：

```text
当前问题：我之前提过自己的饮食限制
历史内容：我对海鲜过敏
```

它不负责取代结构化事实，也不要求所有设备都配置 embedding API。没有 embedding 服务时，系统继续使用当前会话内的词法召回。

#### 混合召回

当前逻辑是“有 embedding 就只走向量，否则只走词法”。更好的路径是同时执行两种检索，再合并去重：

```text
语义检索：找到意思相近的消息
词法检索：找到角色名、地点名、物品名和精确短语
结果合并：按综合分数排序后注入
```

中文聊天中的专有名词和短语对词法匹配很敏感，向量检索和词法检索同时存在时，召回稳定性通常更好。

会话范围内的词法和向量搜索同样不能按主角色 `characterId` 过滤群聊消息。第一版以 `ownerId + sessionId` 限定范围，让该会话中的用户和 NPC 消息都可参与检索。

综合排序可以先采用可调参数：

```text
finalScore = semanticScore + lexicalScore + recencyBonus + typeBonus
```

具体权重应通过真实对话样本调整，不应在没有数据时固定成不可解释的常数。

#### 只在需要时搜索

当前 [PromptBuilder.kt](../app/src/main/java/com/mistbell/tavern/android/data/prompt/PromptBuilder.kt) 会在建立完整 prompt 时构建召回块，之后才知道历史是否被裁剪。对于历史完整的短会话，旧消息已经在 prompt 中，向量搜索可能没有价值。

建议采用以下规则：

```text
历史完整       -> 跳过旧消息的向量/词法召回
历史被裁剪     -> 执行当前会话的混合召回
手动结构化记忆 -> 按重要度和来源独立判断
```

这样可以减少无用 embedding 查询和重复 token。结构化记忆是否注入不必完全依赖这个规则，因为手动记忆和状态记忆可能不是历史消息的直接重复。

#### 减少重复索引

当前普通消息、重要结构化记忆和结构化摘要可能同时出现在向量索引中：

```text
普通消息向量
结构化记忆
结构化记忆摘要向量
```

建议选择一种明确策略：

- 普通旧消息进入向量索引，结构化记忆由 Room 负责；或
- 保留摘要向量，但在检索时按 `contentType` 分组并与结构化记忆去重。

检索后去重不能完全抵消重复索引的 embedding 计算和存储成本。

#### 延迟建立向量

最近消息本来就在上下文中，可以先不建立向量，等它离开最近历史窗口后再索引：

```text
新消息       -> 只保存 Room
离开近期窗口 -> 建立 embedding
```

这会减少快速撤回、重生、编辑和失败回复产生的无效 embedding，也能降低本地 ONNX 推理和 API 调用次数。

**已知问题（本轮不实施延迟索引）**：消息离开近期窗口到向量建立完成之间存在空档——首次裁剪发生时，这些消息既不在 prompt 中也没有向量索引，本轮召回不到。在空档被可靠覆盖（如按窗口滑动触发索引、失败可重试的索引水位）之前，保留可靠的即时索引，不做延迟索引。

#### 时间和类型权重

当前向量层主要按 cosine similarity 排序。可以在相似度之后增加：

- 时间衰减：同等相似时优先较新的内容；
- 消息类型权重：用户明确陈述优先于模型闲聊；
- 结构化覆盖惩罚：已被结构化记忆覆盖的内容降低优先级；
- 会话事件权重：剧情转折、承诺和目标比礼貌回复更值得召回。

#### 删除和重建一致性

向量写入任务应至少记录：

```text
sessionId
messageId
sourceMessageVersion
operation: add / replace / delete
```

删除会话时先记录持久化删除意图，后台 embedding 完成后再次检查消息版本和删除状态。应用启动时，根据 Room 中仍然存在的消息和记忆对向量索引进行对账。

这样可以避免以下竞态：

1. 消息进入 embedding；
2. 用户删除会话；
3. Room 删除成功；
4. embedding 完成后又把已删除消息写回向量索引。

#### 存储规模

~~当前 `InMemoryVectorStore` 使用内存集合和 JSON 持久化，并通过扫描完成相似度搜索。~~
**2026-10-20 已升级**：默认存储引擎为 `SqliteVecStore`（sqlite-vec v0.1.9 + requery sqlite-android，
独立 `vector_index.db`、按维度分 vec0 表、元数据过滤下推 C 层），增量持久化、离堆存储；
`InMemoryVectorStore`（JSON）保留为探测失败的回退路径。字节预算淘汰（VectorEviction）
与启动对账语义不变。仍待规模数据验证的后续项：

- 分页重建；
- 可取消的后台重建。

### 13.3 推荐实施顺序

#### 第一批：正确性和重复减少

1. 所有结构化和向量查询固定使用当前 `sessionId`；
2. 结构化抽取去重改为 `ownerId + sessionId + factKey`；
3. 禁止新记忆写入空 `sessionId`；
4. 使用来源消息 ID 判断 prompt 去重；
5. 删除会话后阻止后台任务重新写回索引。

#### 第二批：检索质量和成本

1. ~~向量与词法合并召回~~（2026-10-20 已完成：RRF 融合，见 `RecallRanker`）；
2. 历史完整时跳过旧消息召回（已完成：召回块仅历史被裁剪时注入）；
3. 普通消息离开近期窗口后再建立向量；
4. 结构化记忆与摘要向量分开预算；
5. ~~加入时间、消息类型和结构化覆盖权重~~（2026-10-20 已完成：14 天半衰期时间衰减 + summary 权重，均为有界平局裁决）。

#### 第三批：规模和运维

已完成（见 7.6）：持久化 outbox、启动时 Room 与向量索引对账、分批且可取消的索引恢复。
2026-10-20 追加完成：消息级孤儿向量的启动对账（`reconcileOrphanedSourcesWithDatabase`）、
sqlite-vec 存储引擎落地（`SqliteVecStore`，含旧 JSON 索引自动迁移与回退）。后续仅保留：

1. 在提示词预览中显示召回来源、条数、耗时和是否命中缓存。

### 13.4 优化后的职责边界

最终建议保持以下结构：

```text
结构化记忆：主路径，保存当前会话的可信事实
词法召回：默认回退，保证离线和专名检索可用
向量召回：可选增强，服务超长会话的语义找回
Room：事实和消息的权威存储
向量索引：可删除、可重建的检索投影
```

验收优化时，优先观察四项指标：

- 召回是否来自正确会话；
- 召回内容是否与历史或结构化记忆重复；
- 单轮 prompt 增加了多少 token；
- embedding、索引写入和召回耗时是否值得它带来的命中率提升。
