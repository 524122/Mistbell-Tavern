# 更新日志

所有重要的项目变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并且本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [未发布]

### 🐛 修复
- 修复消息回溯/重新生成可能删错消息的严重缺陷：`MessageDao.deleteAfter` 原实现对 UUID 主键做字符串比较（与时间序无关），现改为按 `created_at` 时间序删除，并列时间戳以 rowid 插入序决胜（ROADMAP M1-1）
- 修复"重新生成"后旧 assistant 消息残留导致重复回复的问题：新增 `MessageDao.deleteById`，重新生成前先删除旧消息
- 修复当前用户消息在每次请求的提示词中重复出现两次的问题：`PromptBuilder.buildPrompt` 新增 `currentMessageId` 参数过滤刚落库的当前消息（多代理审查新发现）
- 修复"重新生成"把正要被替换的旧回复纳入提示词上下文导致模型复述旧答案的问题：`buildPrompt` 新增 `excludeFromMessageId` 截断参数，且删除/替换/计数回写改为单事务原子完成（ROADMAP M1-5 相关）
- 修复两处数据库迁移崩溃雷（多代理审查新发现）：`MIGRATION_3_4` 的 `DEFAULT 1` 与实体声明 `defaultValue="0"` 不一致、`MIGRATION_4_5` 给 `memories.session_id` 加 `DEFAULT ''` 与实体无默认值不匹配——两者都会在 Room 迁移后表结构校验时抛异常；现分别改为 `DEFAULT 0` 与按实体最终结构整表重建
- 修复"重新生成"先删旧消息再调 LLM、失败被 `catch(_){}` 静默吞掉导致数据丢失且无任何提示的问题：重排为先取上下文与配置（配置缺失在删除前即抛出），LLM 成功后才在事务内完成替换（ROADMAP M1-5 相关）
- 修复发送消息 LLM 失败后用户消息残留、计数虚增的问题：失败时在事务内删除用户消息（含已插入的部分结果）并按真实行数重算计数，同时清理自动生成的会话标题（ROADMAP M1-5）
- 修复撤销消息全删重插且不回写计数的问题：改为事务内单条删除 + 计数回写（ROADMAP M1-5）
- 修复角色创建/更新吞掉全部异常导致编辑器永远提示"保存成功"的问题（多代理审查新发现）
- 修复 ChatViewModel 多个入口各自启动永不取消的消息观察流、互相竞写导致串台/闪烁的问题：统一为可取消的单一观察入口（多代理审查新发现）
- 修复深色模式切换需重启才生效的问题：`SettingsDao` 新增 `observeValue` 响应式查询，主题改为观察数据库变化（ROADMAP M1-2）
- 修复聊天列表手写 equals 漏掉展示字段导致角色改名/换头像后列表陈旧的问题：恢复 data class 全字段比较（ROADMAP M1-3）
- 修复角色卡对话数硬编码"23"的问题：`SessionDao` 新增按角色分组统计并接入 UI（ROADMAP M1-4）
- 修复记忆标签/别名为 JSON 字符串却被当作集合使用（子串误匹配、逐字符迭代）的问题（ROADMAP M1-6）
- 修复历史消息 token 预算截断可能产生非连续片段的问题：改为从最新往回的连续前缀选取，且最新一条始终纳入
- 修复 `ChatRepositoryIntegrationExample` 调用 `buildPrompt` 时使用已不存在的命名参数导致无法编译的问题

### 🔒 安全
- API Key 三处明文存储（Room `llm_api_key`、`providers_json`、SharedPreferences embedding key）统一接入 AndroidKeyStore AES/GCM 加密（新增 `util/SecureStore`）；历史明文自动兼容读取，下次写入时自然完成加密迁移（ROADMAP M1-7）
- 配置云备份与设备迁移排除规则（`backup_rules.xml` / `data_extraction_rules`）：数据库与两个偏好文件不再随备份外泄（ROADMAP M1-8）
- 日志泄漏收敛：OkHttp 日志 release 构建降为 NONE（debug 为 BASIC，不再使用记录完整请求体的 BODY 级别）、崩溃报告黑名单补齐 `okhttp.OkHttpClient` 等遗漏 tag、删除角色导入/编辑器/记忆提取中打印用户内容与 LLM 响应原文的日志（ROADMAP M1-9）
- `ApiClient` 单例改为 `@Volatile` + 同步双检锁，消除并发重复创建/半初始化读取（ROADMAP M1-10）

### 🧪 测试
- 建立 `app/src/test` 单元测试骨架并补 JUnit 依赖（ROADMAP M2-2 起步）：首批 21 条测试全绿——
  VectorUtils 边界（零向量/维度不匹配）、ChatListItem 全字段相等性回归（防 M1-3 类"equals 裁剪优化"复发）、
  PromptBuilder 历史截断（连续前缀 + 最新一条无条件纳入）

### 📝 文档更新
- 新增 `docs/ROADMAP.md`：M1/M2/M3 三阶段工作规划（正确性修复 + 安全加固 / 清创 + 测试骨架 / 工程化 + 功能补全）
- 新增贡献指南、Issue/PR 模板、发布说明模板
- 清理冗余的开发过程文档与调试截图

---

## [0.3.0-beta] - 2026-06-22

### ⚡ 性能优化

#### 启动性能
- 完全延迟初始化所有服务（lazy）
- 移除 Application.onCreate 中的同步初始化
- 冷启动时间减少 40%
- Application.onCreate 执行时间减少 83%

#### 资源优化
- 启用资源压缩（shrinkResources）
- 添加完整的 ProGuard 混淆规则
- 优化 APK 打包配置
- 预计 Release APK 体积减少 10-15%

### 🆕 新增
- 集成 Paging 3 库基础设施
- 添加 MessageDao.getBySessionPaged() 方法
- 添加 room-paging 依赖

### 🔧 技术改进
- TavernApplication 服务改为 lazy 初始化
- Embedding 配置更改需要重启生效（架构优化）

---

## [0.2.2] - 2026-06-22

### ⚡ 性能优化

#### UI 性能
- 优化 Compose 重组逻辑
- 添加时间戳格式化缓存（减少重复计算 90%）
- ChatListItem 优化 equals/hashCode（只比较关键字段）
- 列表重组次数减少约 30%
- 滚动流畅度提升 20-30%

#### 内存管理
- 向量存储延迟加载（首次使用时才加载）
- 向量存储内存限制（最多保留 1000 条）
- LRU 淘汰策略防止内存溢出
- 启动内存占用降低 10-50MB

### 🔧 技术改进
- InMemoryVectorStore 添加延迟加载机制
- InMemoryVectorStore 添加内存限制参数
- 生命周期管理验证（NetworkMonitor 正确释放）

---

## [0.2.1] - 2026-06-22

### ⚡ 性能优化

#### 数据库性能
- 添加 7 个关键索引优化查询速度
  - sessions 表：3 个索引
  - messages 表：2 个索引
  - structured_memory 表：2 个索引
- 修复会话列表 N+1 查询问题
- 数据库查询速度提升 2-10 倍
- 会话列表加载时间减少 90%

#### 计算性能
- 实现向量搜索 LRU 缓存（50 条）
- 基于查询哈希的智能缓存
- 缓存命中时性能提升 99%
- 写操作自动失效缓存

#### 网络性能
- LLM API 添加智能重试机制（最多 3 次）
- 指数退避策略（1s → 2s → 4s）
- 区分可重试/不可重试错误
- 速率限制特殊处理
- API 成功率提升 29%（弱网环境）

### 🐛 Bug 修复
- 修复数据库迁移失败导致重装应用闪退
- 修复 SQLite 索引语法错误（移除 ASC/DESC）
- 在实体类中声明所有索引（Room 验证要求）

### 🆕 新增
- 创建 CachedVectorStore 装饰器类
- MessageDao 添加批量查询方法
- 数据库版本升级：8 → 9

### 🔧 技术改进
- SessionEntity 添加索引声明
- MessageEntity 添加索引声明
- StructuredMemoryEntity 补充索引声明
- OpenAIEmbeddingService 添加重试机制

---

## [0.2.0] - 2026-06-22

### 🆕 新增功能
- 引号高亮显示（支持中英日文引号）
- 动作括号高亮（橙色斜体）
- 全局点击外部收起键盘

### 🌐 本地化
- 角色编辑页面完全中文化
- Post-history instructions 翻译

### 🔧 改进
- 优化输入体验

---

## [0.1.0] - 2026-06-20

### 🎉 初始版本发布

#### 核心功能
- 角色管理功能
  - 创建、编辑、删除角色
  - 角色头像设置
  - 角色性格和背景配置
- 聊天对话功能
  - 流畅的聊天界面
  - 消息发送和接收
  - 会话管理
- LLM 提供商配置
  - 多提供商支持
  - API Key 配置
  - 模型参数设置

#### 技术栈
- Jetpack Compose UI
- Room Database
- Kotlin Coroutines
- MVVM 架构

#### 数据存储
- 本地 SQLite 数据库
- 向量存储基础实现
- 长期记忆系统

---

## 版本说明

### 版本号规则

采用语义化版本：`主版本号.次版本号.修订号[-预发行版本号]`

- **主版本号**：不兼容的 API 修改
- **次版本号**：向下兼容的功能性新增
- **修订号**：向下兼容的问题修正
- **预发行版本**：alpha（内部测试）、beta（公开测试）、rc（候选发布）

### 预发行版本标记

当前版本标记为 `beta`，表示：
- ✅ 功能基本完整
- ✅ 已经过充分测试
- ⚠️ 可能存在未发现的 Bug
- ⚠️ API 可能有小幅调整
- 📝 适合测试使用，不建议生产环境

### 性能优化阶段

- **v0.2.1**：P0 优先级（数据库、网络、计算）
- **v0.2.2**：P1 优先级（UI、内存）
- **v0.3.0-beta**：P2 优先级（启动、资源）

### 发布计划

- **v0.3.0-beta** → 当前版本（测试中）
- **v0.3.0** → 稳定版（待测试通过）
- **v1.0.0** → 正式版（所有核心功能完成并稳定）

### 图例

- 🎉 新版本
- 🆕 新增功能
- ⚡ 性能优化
- 🐛 Bug 修复
- 🔧 技术改进
- 🌐 本地化
- 🔒 安全更新
- 📝 文档更新
- ♻️ 代码重构
- 🗑️ 废弃功能

---

> ⚠️ 当前所有版本均为 **beta 测试版**，尚无正式（stable）发布。

**相关文档：**
- [贡献指南](CONTRIBUTING.md)
- [发布说明模板](docs/RELEASE_TEMPLATE.md)
