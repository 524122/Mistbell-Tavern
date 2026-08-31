# 地基补全规划（FOUNDATION）

> 状态：选型定稿。本文回答"哪些缺口用什么开源方案补"，与 [ROADMAP.md](ROADMAP.md)（质量债 M1/M2）和 [MODES.md](MODES.md)（玩法/主题架构）互补。
> 结论基于 2026-08 的多代理调研（含网络核实），个别项标注"未核实"，引入前自查最新版。
> **许可证红线**：项目开源友好定位，GPL/AGPL 代码不可引入；SillyTavern（AGPL）只能参考其**格式与协议**做互操作，不能拷任何代码。

---

## 选型总表

| # | 缺口 | 首选方案 | 许可证 | 成本 | 备注 |
|---|---|---|---|---|---|
| 1 | CI 质量门 | GitHub Actions：`gradle/actions/setup-gradle` + `android-actions/setup-android`（固定 SDK 版本） | 平台/Apache/MIT | 低 | Gitee 侧用官方"Android 在线构建"出 APK；Gitee Go 长期 Beta 仅备用，不作唯一 CI |
| 2 | 依赖许可证审计 | jk1/Gradle-License-Report + 自定义 step（扫到 GPL/AGPL 即失败） | Apache-2.0 | 低 | 对标 ST 生态（AGPL 云集），红线必须自动化；cashapp/licensee 留作后期强校验 |
| 3 | 静态检查 | detekt 1.23.x + ktlint 1.3.x，baseline.xml 渐进清零 | Apache-2.0 / MIT | 低 | detekt 2.0 alpha 有破坏性变更，锁 1.23 |
| 4 | SSE 流式输出 | **okhttp-sse 4.12.0**（官方模块，与现有 OkHttp 同版本）保留手写 LlmClient | Apache-2.0 | 低 | 取消双保险：协程 cancel + `eventSource.call.cancel()`；个别兼容网关回 `application/json` 会拒收，需拦截器改写或降级整包 |
| 5 | 崩溃上报 | ACRA（acra-http 指向自建端点 或 acra-mail） | Apache-2.0 | 中 | 默认关闭 + 首启征同意 + 字段脱敏；Sentry 已转 BSL/FSL 非开源且云版违背隐私定位，排除 |
| 6 | 角色卡/世界书互操作 | **自研薄解析层**（kotlinx DTO 数百行）+ **pngj** 读写 PNG tEXt chunk | MIT | 低-中 | 规范基准：malfoyslastname/character-card-spec-v2 + ST Docs worldinfo；无成熟 JVM 库，TS/Rust 生态库仅作对照 |
| 7 | 本地语义向量 | **onnxruntime-android + 量化 MiniLM**（英文 all-MiniLM-L6-v2 int8 ≈23MB/384 维；中文 bge-small-zh-v1.5 ≈24MB，MIT） | MIT / Apache-2.0 | 中 | 需自移植 WordPiece 分词（或 onnxruntime-extensions Tokenizer op）；MediaPipe TextEmbedder 维护放缓、.task 生态封闭，不作长期地基 |
| 8 | 向量持久化 | 近期：Room 存 blob + 暴力余弦（万级内毫秒级）<br>升级：**sqlite-vec**（MIT）+ requery/sqlite-android 自定义 SQLite 与 Room 共存 | MIT | 近期低 / 升级中 | ObjectBox 向量搜索：Java 绑定 Apache-2.0 但原生核许可需复审 + 引入第二数据库，暂不选 |
| 9 | Markdown 渲染 | 暂保持手写解析器；升级时换 compose-richtext（halilozercan，基于 commonmark） | MIT | 中 | 现有正则解析够用；流式输出上线后渲染压力上升再换 |

**被否决/降级记录**：openai-kotlin 4.x（MIT，流式好但引入 Ktor 双 HTTP 栈，自定义端点 API 未核实——仅当彻底重写客户端再议）；Ktor SSE（取消不断连的历史 issue）；heremaps/oksse（停更）；MediaPipe TextEmbedder（维护放缓）；llama.cpp embedding（内存/冷启动重，仅当引入本地生成引擎时一并考虑）；Sentry（许可证+隐私双否）。

---

## 分批落地顺序

```
F0 防护网先行（本周）    CI + 许可证扫描 + detekt/ktlint baseline
F1 真流式（体验分水岭）  okhttp-sse + LlmClient 流式化 + 停止按钮（顺带解决请求不可取消）
F2 生态互通              pngj + v2/v1 卡片容错导入导出 + 世界书 JSON 格式 + extensions 透传
F3 记忆确定性            ONNX 本地向量替换 BM25 伪向量（与 ROADMAP M2-5 合并为同一件事）
F4 规模升级（按需）      sqlite-vec 向量存储（消息过万再动）；ACRA 崩溃上报
F5 打磨（按需）          compose-richtext 替换手写 Markdown
```

排序理由：F0 给后面所有改动上保险；F1 是用户可感知的最大体验缺口且解锁 T2 交互主题的实时刷新；F2 打开"直接用 ST 生态存量卡"的入口（用户增长杠杆）；F3 是记忆系统的正确性前提（MODES 记忆域设计依赖真向量）；F4/F5 皆是规模/质量驱动，不阻塞主线。

## 关键互通格式要点（F2 实施依据）

- **卡（CCv2）**：`spec="chara_card_v2"` 可选勿强求；标准字段 name/description/personality/scenario/first_mes/mes_example/system_prompt/post_history_instructions/alternate_greetings[]/tags[]/creator/character_version/**extensions（未知字段一律透传保真）**/character_book（**entries 为数组**）
- **世界书条目**：uid/key[]（老卡是单字符串须规整）/keysecondary/constant/enabled/insertion_order/position/probability/depth/extensions；独立 WI 文件的 entries 是**按 uid 的 map**——与卡内嵌结构不同，导入需分别处理
- **旧版兜底**：TavernAI v1 键名（char_name/char_persona/world_scenario/char_greeting…）映射到 v2；缺 name 用文件名
- **PNG 埋卡**：tEXt chunk 关键字 `chara`，值 = base64(JSON)；导出始终写 v2 `chara` 保证全生态可读；v3（`ccv3` chunk / data 包裹结构）只做容错导入，不承诺导出
- **实现纪律**：只按文档实现格式；连宽松许可的参考实现（character-foundry/chara_card/airi）也只看字段划分不抄代码

## 待定决策（需要时再定）

1. CI 双托管策略：GitHub 主 + Gitee 镜像（默认）还是反过来（若用户群在 Gitee）
2. ACRA 后端形态：自建 HTTP 端点 / 邮件 / 纯本地（acra-notification）
3. 本地向量模型：英文 MiniLM 还是中文 bge-small-zh（或双模型按语言切换）
4. F3 之前 BM25 的去留：建议先在设置里把向量记忆标注为"实验性"并默认关闭，直到 ONNX 路线上线
