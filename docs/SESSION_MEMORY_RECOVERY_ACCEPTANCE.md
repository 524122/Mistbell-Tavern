# 会话内长期记忆：持久化恢复验收说明

最后更新：2026-10-19

本文档给出可重复执行的验收环境、步骤、预期和本次实际结果。历史 validation-06 的 484 JVM / 42 Android 数字仅作历史记录，本文数字全部来自本轮最新报告。

## 1. 环境与隔离边界

- 工作目录：`C:/Users/20109/Desktop/chat/mistbell-tavern-android-main`。未使用旧 Codex worktree。
- 设备：唯一在线设备 `emulator-5554`，`Pixel_10(AVD) - 17`，x86_64。
- 原应用：`com.mistbell.tavern.android`，versionCode 14 / 0.9.3-beta。第 3-4 节隔离验收期间未覆盖安装、未清数据、未卸载，当时 lastUpdateTime 为 `2026-10-18 10:29:28`。
- 非隔离补充阶段：按用户后续明确要求改用原应用。测试运行器曾移除原应用数据，根目录 `tavern_backup.db` 不是有效备份；用户明确选择“以空数据继续”。此后只用手工 UI/ADB，未再对原包运行 `connectedDebugAndroidTest`；当前 firstInstallTime / lastUpdateTime 为 `2026-10-18 18:26:07`。
- 常规隔离包：`com.mistbell.tavern.android.memoryvalidation`，由 `-PisolatedMemoryValidation` 生成。
- 升级隔离包：旧版和本轮均为 `com.mistbell.tavern.android.memoryvalidation06`，versionCode 14 / 0.9.3-beta，debug 签名 SHA-256 均为 `9beaf3f6f948a78c4cc1f7dec9b4971fda2c7bc153173cca0c8f2ffcb9dc2ca3`。
- 旧 APK：历史监督目录 `kimi-supervision/validation-06/app/build/outputs/apk/debug/app-x86_64-debug.apk`（v26）；本轮 APK：工作目录 `app/build/outputs/apk/debug/app-x86_64-debug.apk`（v27）。
- 所有输入均为合成事实。未读取其他应用密钥，未把密钥、Authorization 或真实聊天内容写入日志、截图或报告。隔离包验收后已卸载。
- 开工基线：`build/agent-logs/session-memory-recovery/baseline-summary.txt`、`baseline-status.txt`、`baseline-worktree.diff`。用户原有未提交改动均保留，未 reset/commit/push/全仓格式化。

## 2. 构建、JVM、Android 与静态检查

复跑命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest -PisolatedMemoryValidation
./gradlew :app:assembleDebug
./gradlew :app:detekt
./gradlew :app:ktlintCheck
./gradlew licenseGuard
```

预期：编译、测试、debug 与 licenseGuard 通过；静态问题按本轮基线归因，不通过格式化、skip 或扩大 baseline 获得全绿。

本次实际结果：

| 项目 | 结果 | 证据 |
|---|---|---|
| Kotlin / AndroidTest 编译 | 通过 | 上述命令日志 |
| JVM | **552 项，0 失败/错误/跳过** | `app/build/test-results/testDebugUnitTest/` |
| Android | **60 项，0 失败/错误/跳过** | `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10(AVD) - 17-_app-.xml` |
| `assembleDebug` | 通过 | 上述命令日志 |
| `licenseGuard` | 通过，139 个依赖 | `build/reports/licenses/index.json` |
| `detekt` | 未通过，119 weighted；恢复实现路径无本轮新增告警 | 命令输出；既有业务文件债务 |
| `ktlint` | 未通过：main 353 / test 77 / androidTest 0；恢复相关文件 0 告警 | `app/build/reports/ktlint/`；按文件+位置+规则去重统计 |
| 汇总 | 数字由脚本从 XML/报告生成 | `build/agent-logs/session-memory-recovery/verification-summary.json` |

`VectorSyncRecoveryContractTest` 另有 11 项恢复用例，包含在 60 项 Android 总数内。用例覆盖创建/编辑/删除后中断、落盘后未确认中断、flush 失败、离线与模型切换、批量删除、owner/session 隔离、并发/重复/取消、旧备份合并和普通消息恢复。所有“重启”用例均关闭并重新创建 Room、Store、恢复器，重新读取持久化文件。

## 3. 真实 UI 验收

先探索真实 UI，再用 `build/agent-logs/session-memory-recovery/android_ui_driver.py` 按文本/content-desc 动态定位并等待状态；不使用随机 sleep 判断完成。

步骤与结果：

1. 在隔离包完成 onboarding，创建角色 `RecoveryRole` 和启用会话记忆的聊天。
2. 进入聊天设置 → 记忆管理，新建内容 `memory-alpha-4f7c`、重要性 9；随后编辑为 `memory-alpha-4f7c-beta-8a1e`。
   - 预期/实际：创建和编辑均成功，列表内容正确；没有“仅本会话”“不会同步到其他聊天”等重复提示。
   - 证据：`ui-memory-created.xml`、`ui-memory-edited.xml`、对应 PNG。
3. 对该应用执行 `am force-stop` 和重新启动，重新进入记忆管理。
   - 预期/实际：记忆仍在，界面无卡死/崩溃/错误完成状态。
   - 中断时 Room 有创建和编辑待办，索引为空；重开后旧 create 待办被跳过，最新 edit 待办尝试次数增加并继续保留，未伪报成功。
   - 证据：`room-before-kill.txt`、`process-interrupt.txt`、`room-after-restart.txt`、`ui-memory-after-process-restart.png`。
4. 删除该记忆并确认。
   - 预期/实际：列表回到“暂无记忆”，Room 无该记录。
   - 证据：`ui-memory-deleted.xml`、`room-after-delete.txt`。
5. 在升级隔离包中以同角色创建第二个聊天并打开记忆管理。
   - 预期/实际：第二个聊天为“暂无记忆”；返回第一个聊天仍只见其自身 `normal-memory-keep`。
   - 证据：`ui-second-chat-isolated.xml`、`ui-first-chat-memory-only.xml`。
6. 删除第一个聊天记忆。
   - 预期/实际：第一个聊天的旧待办与 DELETE 一并收敛，第二个聊天的 UPSERT 不受影响。
   - 证据：`ui-ack-after-delete.txt`。

## 4. v26 → v27 真实覆盖升级

旧版与本轮使用同包名、同 versionCode、兼容签名，升级均使用 `adb install -r`，不是全新安装冒充升级。

### A. 旧数据 + 清理故障重试

1. 安装旧 APK，准备 v26 数据库：1 个角色、1 个会话、2 条消息、1 个世界书条目、1 条旧记忆、1 个无密钥的合成 API 配置。数据定义见 `upgrade-a-fixture.sql`。
2. 将 `vector_store.json` 设为非空目录，作为仅测试环境的删除失败注入；安装本轮 APK 并启动。
   - 预期/实际：Room 迁移到 27；清理失败，无 DONE；旧记忆和 outbox 被清理，旧索引没有被读取/重放；角色、消息、世界书、API 配置保留。
   - 证据：`upgrade-a-failed-reset-evidence.txt`、`upgrade-a-reset-logcat-latest.txt`。
3. 删除故障目录并重启。
   - 预期/实际：清理成功并写入 DONE；业务数据仍保留，outbox 为空。
   - 证据：`upgrade-a-retry-evidence.txt`。

### B. 已有 DONE 标记

1. 重新准备旧版数据，保留旧版 DONE，并写入正常记忆 `normal-memory-keep`；覆盖安装本轮 APK。
   - 预期/实际：正常记忆不被再次清零；Room 生成该记忆的 UPSERT 和相同 sourceRevision；非记忆数据保留。
   - 证据：`upgrade-b-evidence.txt`。
2. 继续完成第 3 节第 5、6 步的双会话隔离和删除验证。

## 5. 真实 LLM 与本地 embedding 链路（非隔离，已执行）

本节按用户后续明确要求，在原应用 `com.mistbell.tavern.android` 中使用“风见幽香”执行，不使用隔离包，也不再对原包运行 `connectedDebugAndroidTest`。模型配置由用户在原应用中重新提供；未读取或记录 base URL、密钥、Authorization 或其他聊天内容，所有发送内容均为合成文本。

### A. 早期未通过尝试

早期 `adb shell input text` 未逐个转义空格，在输入截断的情况下，真实抽取生成了“语言沟通障碍”“害羞温和”等与目标事实无关的记忆。该轮明确判为**未通过**，不计入后面的成功结果；证据为 `yuuka-extracted-memory.txt`。后续改为逐空格转义，并在发送前核对完整草稿。

### B. 修正后的真实抽取

在主会话 `nonisolated-yuuka-session` 连续发送以下三条消息，每轮均等待真实回复，消息数依次达到 2、4、6：

```text
I am severely allergic to a fictional fruit named Yuuka-zest-91bd.
This is a long-term medical boundary, not a temporary preference.
Please keep that allergy in long-term memory.
```

预期/实际：

- 第 6 条消息后生成两条 `auto_extract` 记忆，后续批次再生成一条；没有手工补写这些记忆。
- 三条当前记忆都准确包含 `Yuuka-zest-91bd` 过敏事实，`related_message_ids` 非空；自动复核确认引用的 6/6、6/6、2/2 条来源消息都真实存在且属于主会话。
- 主会话最终有 16 条合成消息；有 3 条 `auto_extract` 记忆和 1 条早期手动 `Severe` 合成记录，后者不计入真实抽取断言。
- 汇总及来源 ID：`nonisolated-yuuka-llm-evidence.txt`。

### C. 重启与历史裁剪后的最终 prompt

将主会话预算调整为数据层合法的 1024，并加入无关合成长消息制造确定的裁剪；期间执行过 `am force-stop` 和重新启动。最终探针要求模型只回复 `OK`，断言针对 prompt 本身，不根据模型是否答对记忆题。

预期/实际：

- `nonisolated-yuuka-memory-only-recall-final.xml` 显示 **5 条消息、约 129 tokens、已裁剪**。
- 保留下来的两条历史都只有 `OK`，当前问题也不含 `Yuuka-zest-91bd`。
- 最初的完整英文过敏陈述已被裁掉；`Yuuka-zest-91bd` 与“严重过敏”只出现在 `system / 会话记忆` 块。
- 自动断言通过：最终 prompt 在历史不含答案时仍由结构化记忆提供该事实。
- 证据：`nonisolated-yuuka-memory-only-recall-final.xml`、同名 PNG。

### D. 同角色第二会话隔离

通过真实 UI 创建同角色第二会话 `4ebbc0fb-746b-4985-aad0-328534fa5560`，显式开启会话记忆，再发送不包含答案的探针。

预期/实际：测试时第二会话 `structured_memory` 为 0；最终 prompt 为 4 条消息、约 41 tokens，不含 `Yuuka-zest-91bd` 或“严重过敏”，且没有“会话记忆”块。证据：`nonisolated-yuuka-second-chat-prompt.xml`、同名 PNG。

### E. 真实 embedding（本地 ONNX）

初始 `embedding_source` 未设置，两个合成会话的向量条目为 0，持久化 outbox 分别有 20、3 条待办。通过真实 UI 选择“本地 ONNX（实验）”，使用应用内置的真实 `bge-small-zh-v1.5` INT8 ONNX，不使用 Mock/fake，也不重新编辑记忆。

预期/实际：

- 强停并重启后，23 条待办全部完成且 outbox 为 0；落盘 23 条向量，主会话 20 条、第二会话 3 条。全部为 512 维、L2 范数 1.0，指纹为 `local:bge-small-zh-v1.5-q.onnx:512`。
- 再次强停重启前后，向量规范化内容的 SHA-256 均为 `531a7a93ce53d1567878347b3ee7e0258674d3575660603e20b8085aca81612e`，outbox 均为 0。
- 同一进程连续装配两次主会话 prompt，日志为先 `Cache miss`、后 `Cache hit`；两次完整 prompt 一致，均为 6 条消息、约 252 tokens、已裁剪，并包含 `## Relevant Past Conversations` 真实向量召回块。
- 向量块包含 5 条相关历史（100%、100%、94%、85%、71%）；保留历史仍只有两条 `OK`，水果名由结构化记忆提供，向量块与结构化块职责没有混淆。
- 第二会话相同查询只返回自己的 3 条候选，主会话返回 10 条；相同查询向量在第二会话为 `Cache miss`，证明会话过滤属于缓存键。
- `local → auto` 负对照下，旧 local 指纹向量不产生向量召回块；恢复 `local` 并重启后，23 条原向量及真实召回恢复，outbox 仍为 0。`auto` 只作对照，不算真实 embedding 通过项。
- 汇总与原始日志：`nonisolated-embedding-evidence.txt`、`nonisolated-embedding-cache-logcat.txt`；UI 证据见 `nonisolated-embedding-*.xml` 和同名 PNG。

本项通过口径是**真实聊天模型抽取、结构化记忆召回，以及真实本地 ONNX embedding 的生成、持久化、缓存召回和会话/指纹隔离**。远程 OpenAI 兼容 API embedding 未单独执行，不包含在通过声明内。

## 6. 证据目录与完成口径

全部证据位于 `build/agent-logs/session-memory-recovery/`。核心文件：

- `verification-summary.json`：本轮测试与静态检查汇总；
- `final-package-state.txt`：隔离验收阶段的原应用与隔离包状态；
- `room-before-kill.txt`、`room-after-restart.txt`：真实中断前后 Room/outbox；
- `upgrade-a-*.txt`、`upgrade-b-*.txt`：升级、清理失败重试和 DONE 保留；
- `nonisolated-yuuka-llm-evidence.txt`：非隔离真实模型抽取、来源、裁剪召回和第二会话断言汇总；
- `nonisolated-embedding-evidence.txt`、`nonisolated-embedding-cache-logcat.txt`：真实本地 ONNX 向量、重启、缓存和指纹对照汇总及日志；
- `nonisolated-yuuka-memory-only-recall-final.*`、`nonisolated-yuuka-second-chat-prompt.*`、`nonisolated-embedding-vector-recall-cache-*.*`：最终 prompt 原始 UI 证据；
- `final-increment.txt`：隔离实现收尾时的工作树状态；未提交、未推送。

完成口径：关闭应用、强停进程或设备重启后，不需要再次编辑记忆；条件恢复时继续同步。恢复期间旧/已删摘要不进入 prompt，DONE 用户不被重复清零。真实聊天模型抽取、结构化记忆召回和真实本地 ONNX embedding 均已实测；远程 API embedding 仍是单独的未执行项。
