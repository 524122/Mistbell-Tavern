# 会话记忆召回评测：骨架 → 夹具 的拼装与校验

本目录包含「会话记忆」评测用的两个确定性脚本与骨架输入。评测目标：长对话早期说过的 30 条事实，在上下文截断后能否被召回注入提示词；其中 10 条后来被更新（旧状态不应再注入）；另有若干条被换说法重述（不应被误判为状态更新）。探针是用户在特定楼层的提问，与事实原话完全换说法。

骨架的数据格式、硬性数量与探针「换说法」的机器判定算法见规范：`SPINE_SCHEMA.md`（本目录；12 个骨架都是按它写的）。

## 文件

| 路径 | 说明 |
| --- | --- |
| `build_fixtures.py` | 拼装脚本：把骨架铺成 300 楼完整对话夹具 |
| `validate_fixtures.py` | 校验脚本：校验骨架与拼装产物，违规即非零退出 |
| `spines/s01.json`–`s12.json` | 正式骨架（12 个会话，由写手提供） |
| `spines/_sample.json` | 样例骨架（自测用；下划线开头 = 不属于正式 12 个） |
| `SPINE_SCHEMA.md` | 骨架的数据格式与写手规范（字段、硬性数量、探针换说法的判定算法） |
| `<仓库根>/app/src/sharedTest/resources/eval/memory/sessions/sXX.json` | 拼装产物（每会话一个，脚本创建） |
| `<仓库根>/app/src/sharedTest/resources/eval/memory/manifest.json` | 会话清单（与 sessions 目录同级，脚本创建） |

产物放在 `sharedTest`：这个目录同时挂在 `test` 与 `androidTest` 两个源集上，JVM 回放（单测）与设备侧评测（androidTest，真 ONNX + sqlite-vec）读的是同一份夹具。

## 用法

```bash
# 拼装（默认路径按脚本位置推导仓库根，不写死绝对路径）
python -I .e2e/eval/memory/build_fixtures.py                 # 只处理 s01–s12 中已存在的
python -I .e2e/eval/memory/build_fixtures.py --include-sample
python -I .e2e/eval/memory/build_fixtures.py --spines <骨架目录> --out <输出目录>

# 校验
python -I .e2e/eval/memory/validate_fixtures.py              # 骨架 + 夹具 + manifest
python -I .e2e/eval/memory/validate_fixtures.py --spines-only
python -I .e2e/eval/memory/validate_fixtures.py --fixtures-only
python -I .e2e/eval/memory/validate_fixtures.py --spines <目录> --fixtures <目录>
```

任何违规都会按 `[违规] <文件> :: <位置> :: <原因>` 逐行打印并以退出码 1 结束。骨架缺失时拼装脚本打印提示并跳过；`--include-sample` 只额外处理 `_sample.json`。

## 布局规则（拼装脚本实现）

**楼层/轮次**：共 300 楼。第 1 楼是 `greeting`（assistant）；第 t 轮（t = 1…149）占两楼：用户消息在第 `2t` 楼、角色回复在第 `2t+1` 楼；第 300 楼是最后一条探针（user），无回复。

**探针轮（12 个）**：t = 13, 25, 38, 50, 63, 75, 88, 100, 113, 125, 138（即第 26, 50, 76, 100, 126, 150, 176, 200, 226, 250, 276 楼），外加第 300 楼。探针楼的用户消息 = 探针原话；其角色回复 = `deflections[i]`（第 i 个探针用第 i 条）。角色绝不回答探针，避免答案污染后续历史。第 300 楼无回复，因此 `deflections[11]` 不会出现在任何消息里（清单仍要求 12 条）。

**事实轮**：30 条原话、所有非 null 重述、全部更新各占一轮；`speaker=user` 时用户消息 = 原话（无前后缀）、角色回复 = `acknowledgements[k]` 循环取用；`speaker=character` 时用户消息 = `leadIns[k]` 循环取用、角色消息 = 原话。约束：

- 原话轮在 3–90 轮之间，同轮只放一条，均匀铺开（理想位 + ±1 抖动，撞轮就近挪）。
- 重述轮 ≥ 原话轮 + 8；更新轮 ≥ 原话（或重述）轮 + 20。
- 探针指向的版本必须在该探针轮 3 轮之前出现（即楼层差 ≥ 6）。

**探针指向**（每条事实整体最多被探一次）：

- 前段 5 个（26/50/76/100/126 楼）：指向 3 轮前已说出、未被探过的**不带更新**的事实原话版本，按事实 id 顺序轮流。实现上取「按 id 排序的前 5 条不带更新事实」，并把它们的原话轮 deadline 压在 10/22/35/47/60 轮（= 探针轮 − 3）。
- 中段 3 个（150/176/200 楼）：指向按 id 排序的前 3 条带更新事实的**原话版本**（其更新轮被安排在该探针轮之后，问的仍是旧状态），`absentFactIds=[]`。第 150 楼那条事实的原话 deadline 压在 72 轮。
- 后段 4 个（226/250/276/300 楼）：指向第 4–7 条带更新事实的**新版本**（`update.probe`），`absentFactIds=[该事实 id]`（旧版本）。其更新轮被强制安排在第 74 轮及之前（楼层 ≤ 148 < 150），同时恰好满足「至少 7 条更新发生在第 150 楼之前」。

**带更新事实的两类排布**：第 1–3 条（中段指到）的更新排在对应探针轮之后；第 4–10 条（含未被探的）的更新必须 ≤ 74 轮，因此它们的原话轮 deadline 收紧到 48（不带重述）/44（带重述）、重述轮压在 54 轮前，重述与更新按「可用窗口最小优先」（EDF）装箱，保证任何合法骨架都能排下。

**闲聊填充**：其余轮次用 `fillerTurns` 按 seed 打乱后的顺序依次填充（用尽则循环，正常情况 30+10+≥8+11 = 最多 90 轮被占用，剩 ≤ 90 轮闲聊，骨架保证 ≥ 90 组）。

**确定性**：不写时间戳；所有随机数来自 `random.Random(会话编号整数)`（`s03` → 3，`_sample` → 0）。同一骨架输入永远得到逐字节相同的输出（已有哈希对比验证）。

## 输出格式

会话文件（UTF-8、LF、`ensure_ascii=False`、缩进 2、固定键序）：

```json
{
  "schemaVersion": 1,
  "id": "s03",
  "title": "…",
  "contextTokenLimit": 2048,
  "messages": [ { "floor": 1, "role": "assistant", "content": "…" }, … ],
  "facts": [
    { "id": "f01", "memoryType": "…", "subject": "…", "relation": "…", "object": "…",
      "speaker": "user", "statement": "…", "statementFloor": 12,
      "restatement": null, "restatementFloor": null,
      "update": null, "updateFloor": null }
  ],
  "probes": [
    { "floor": 26, "targetFactId": "f03", "targetVersion": "original",
      "absentFactIds": [], "text": "…" }
  ]
}
```

`update` 非空时为 `{ "statement": "…", "probe": "…" }`；`targetVersion` 取 `original` / `updated`。事实楼层：`speaker=user` 的原话在偶数楼（2t），`speaker=character` 的在奇数楼（2t+1），重述/更新同此人同规则。

`manifest.json`：

```json
{
  "schemaVersion": 1,
  "sessionCount": 12,
  "sessions": [ { "id": "s01", "floors": 300, "facts": 30, "updatedFacts": 10,
                  "restatedFacts": 8, "probes": 12, "probesOnUpdated": 3,
                  "totalChars": 12345 }, … ]
}
```

按 id 排序；`totalChars` = 该会话全部消息 `content` 的字符数之和；`probesOnUpdated` = `targetVersion == "updated"` 的探针数。

## 校验项（validate_fixtures.py）

骨架：

1. JSON 可解析；顶层字段齐全、类型正确（`memoryType` 枚举、`speaker` 枚举、`relation` 小写下划线串）。
2. 数量：facts 正好 30；带 update 正好 10；带 restatement ≥ 8；`event/identity/fact` 合计 ≥ 6 且都无 update；fillerTurns ≥ 90；deflections 正好 12 且互异；acknowledgements 正好 16 且互异；leadIns 正好 10 且互异；id 与文件名一致（`_` 开头文件豁免 s01–s12 限制）；事实 id 为 f01–f30 连续；probe / update.probe 彼此不同。
3. 长度：statement、restatement、update.statement、probe、update.probe ≤ 40 个汉字；restatement / update.statement 不得与 statement 逐字相同。
4. 探针换说法（与规范同一算法）：去人名 → 按固定顺序删 50 个豁免词 → 保留汉字与数字 → 连续两字符集合不相交；probe 对 statement 与 restatement，update.probe 对 update.statement。
5. 不泄露答案：probe / update.probe 不含该事实的 object。
6. 干扰内容与事实无关：greeting、fillerTurns、deflections、acknowledgements、leadIns 不含任何事实的 object。

夹具：

7. 300 楼、楼层连续；第 1 楼 assistant；2–299 楼偶 user / 奇 assistant；第 300 楼 user。
8. 每条事实的 statement / restatement / update.statement 在记录楼层逐字出现且说话角色与 speaker 一致；每条探针文本在其楼层逐字出现且该楼是 user。
9. 探针指向的版本在探针楼之前 ≥ 6 楼出现；`absentFactIds` 里的旧版本在此之前已被更新（updateFloor 存在且 < 探针楼）；指向新版本的探针必须把同一条事实记入 `absentFactIds`。
10. 探针之后的角色回复（第 300 楼除外）不含任何事实的 object。
11. 探针共 12 个且楼层与布局规则一致；`targetFactId` 互不重复；指向原话版本的事实其更新不得已发生在探针之前；manifest 与实际文件逐字段一致（含 sessionCount、排序、totalChars 等）。

## 自测流程（样例骨架）

```bash
python -I .e2e/eval/memory/build_fixtures.py --include-sample
python -I .e2e/eval/memory/validate_fixtures.py
```

已知取舍：校验器只执行上面列出的硬规则；greeting（20–60 字）、filler/ack/deflection 等的字数区间属于写手质量要求，未做机器检查。拼装脚本的布局常量（探针轮、deadline 等）以文件头部常量为准，与本 README 同步维护。
