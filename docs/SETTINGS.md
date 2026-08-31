# 设置项规划（SETTINGS）

> 状态：规划定稿（2026-08，基于 SillyTavern 官方文档 + RisuAI/Agnai/ChatterUI/PocketPal 四家同类开源项目的设置面调研）。
> 原则（延续 ROADMAP）：**每个设置必须有真实功能支撑；无支撑的入口不做**（本轮已砍四个空入口）。
> 关联：[ROADMAP.md](ROADMAP.md)（总路线）、[MODES.md](MODES.md)（模式/主题）、[FOUNDATION.md](FOUNDATION.md)（地基批次）。

---

## 调研结论速览

**移动端同行共识（四家全有）**：采样参数块、模型/API 管理、角色卡导入、记忆三件套（lorebook/摘要/RAG）、主题、导出备份。
**桌面端专属（移动端普遍收敛）**：Context Template 块级编辑、Advanced Formatting 深度定制、脚本/插件系统、token bias 细调、多用户服务端。
**我们的差异化定位**：主题走主题包体系（对标并超越"自定义 CSS"）、模式走 MODES 配置化（对标并收敛"预设地狱"）。

主要来源：[ST Common Settings](https://docs.sillytavern.app/usage/common-settings/) / [World Info](https://docs.sillytavern.app/usage/worldinfo/) / [Summarize](https://docs.sillytavern.app/extensions/summarize/) / [Chat Vectorization](https://docs.sillytavern.app/extensions/chat-vectorization/) / [RisuAI Settings](https://github.com/kwaroran/RisuAI) / [Agnai Chat Settings](https://agnai.guide/docs/chat-settings/) / [ChatterUI](https://github.com/Vali-98/ChatterUI) / [PocketPal AI](https://github.com/if-ai/pocketpal-ai)。

## 现状盘点（已具备）

提供商管理（endpoint/key/model）、流式开关、新会话默认上下文长度/默认长记忆、深色模式、
主题包三层应用链（会话/角色/全局）、会话级上下文长度与世界书绑定、记忆提取提示词、
反馈日志导出、角色/会话导出、PNG/JSON 卡互通导入导出。

## 分批路线

### S1 近期（低成本、功能已支撑）
| 设置 | 依据 | 落点 |
|---|---|---|
| 请求超时/重试次数 | 现硬编码 30/90/30s 与固定重试；ChatterUI/PocketPal 均可配 | LlmClient 读取设置 |
| **采样参数块**：top_p / top_k / 重复惩罚 / 最大回复长度 | 四家同行全有，我们只有 temperature/maxTokens；LlmConfig 已是透传结构 | ProviderEditor 加滑条 + LlmConfig 扩展字段 |
| **采样预设三档**（创意/平衡/精确）+ 自定义预设保存 | ST 调研结论："移动端只透出核心参数，其余收敛为预设模板" | 设置页/提供商页 |
| 会话级"附加指令"（Author's Note 简化版：一段文本+注入深度） | ST 核心特色；移动端简化共识 | ChatSettings + PromptBuilder 注入 |

### S2 随 F3（记忆工作一并）
| 设置 | 依据 |
|---|---|
| 摘要记忆：自动触发阈值（每 N 轮）、摘要模板 | 我们 `summary_json` 空置列 + MODES 滚动摘要设计；ST Summarize 共识配置 |
| 向量记忆：embedding 源选择（API/本地 ONNX）、召回数 top-k、相似度阈值 | F3 ONNX 落地的配套设置面（ST Vector Storage 同款精简） |
| 世界书：扫描深度（近 N 轮才触发）、token 预算 | ST World Info 移动端共识配置（正则触发/递归不做） |

### S3 随 MODES 模式工作
| 设置 | 依据 |
|---|---|
| **Persona 用户人物卡**：多 persona 管理 + 按会话绑定 | 四家共识；且与模式②（我扮演角色卡）同架构——persona 槽位即 MODES speakers/personas 配置 |
| Swipes / Continue 的行为设置 | ROADMAP 已列实装（现为空壳）；Agnai/RisuAI 的聊天级交互设置共识 |

### S4 数据安全
| 设置 | 依据 |
|---|---|
| 全量备份/恢复（角色+会话+设置+主题包 单 zip） | 移动端离线优先的刚需（调研共识"移动端更强调导入导出与备份"） |

## 设置页信息架构（页面调研结论与改造规划）

> 2026-08 补充调研：SillyTavern / RisuAI / Agnai（Web 前辈）+ ChatterUI / PocketPal（原生移动）+ AOSP/Material 设置范式。

**同行共识**：设置不占一级导航（齿轮二级页）；全局 ≠ 提供商级 ≠ 会话级三层参数分离；预设是一等公民；稠密子域下钻二级页而非平铺；高级项折叠；移动端=全屏分组卡片（Material You 范式）。

**我们的目标 IA**（S1 落地时随同重组；入口保持现有底部"设置"标签——已固化的导航习惯，且角色扮演用户调参频率高，不必强改）：

```
设置页（分组卡片，单页长滚）
├─ 生成与采样（S1）      预设三档入口行（创意/平衡/精确/自定义）
│                        + 核心滑条折叠组（temperature/maxTokens）
│                        + 流式输出开关（已有）
│   └─ 下钻：提供商管理（已有独立页）＝提供商级细参数（top_p/top_k/重复惩罚滑条）
├─ 对话（已具备）        默认上下文长度 / 默认长期记忆（现"对话生成"区块并入此组）
├─ 外观（已具备）        主题管理入口 / 深色模式
├─ 记忆（S2 启用）       记忆提取提示词（已有）；S2 后下钻：摘要阈值/向量源/世界书扫描深度
├─ 数据（S4 启用）       全量备份下钻 + 各类导出
└─ 关于（已具备）        版本日志 / 反馈日志 / 关于
```

**分层原则**（与 MODES 三层应用链同构）：全局页只放**默认值**；会话级覆盖（上下文长度/世界书绑定/附加指令/主题）留在聊天设置内；提供商级参数在提供商编辑页。同一参数只在一处主编辑，其余层级是覆盖。

**实现要点**：分组卡片 + 两行行（图标/标题/副行说明/尾部控件）对齐 Material You；设置项副行说明文字为标配；S1 重组时把现"对话生成"区块并入"对话"组、"提供商管理"入口并入"生成与采样"组；搜索与"恢复默认"待设置项超过 ~40 再引入。

## 明确不做（含理由）

- **Context Template 块级编辑 / Advanced Formatting**：桌面重度功能；我们用固定 PromptBuilder 结构 + S1 的"附加指令"覆盖 90% 需求（调研佐证：移动端同行全部收敛）
- **脚本/宏/插件系统（STscript、RisuAI module）**：生态包袱重；我们的可扩展性走主题包/规则包制品线
- **自定义 CSS 注入**：已被主题包体系替代（更安全、更结构化）
- **token bias / 采样器顺序 / grammar**：开发者级细调，移动端无人做
- **多用户/服务端管理**：产品定位本地优先单机
- **本地 GGUF 模型管理（PocketPal 路线）**：近期不做——我们走"远程 API 优先"；llama.cpp 仅在 F3 备选为本地 embedding 引擎，生成级本地模型留远期评估
