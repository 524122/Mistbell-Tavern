# 🎭 Mistbell Tavern Android

<div align="center">

[![Version](https://img.shields.io/badge/version-0.9.2--beta-blue.svg)](https://gitee.com/Wan2010/mistbell-tavern-android/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-orange.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-320%20passed-success.svg)](app/src/test)

**高性能的 AI 角色聊天 Android 应用**

[下载体验](#-下载安装) • [功能特性](#-功能特性) • [工程实践](#-工程实践) • [技术栈](#-技术栈)

</div>

---

## 📱 应用介绍

Mistbell Tavern 是一个功能完整的 Android 原生 AI 角色聊天应用。采用 Jetpack Compose 构建现代化 UI，支持自定义角色、长期记忆系统、多种 LLM 提供商和完整的 SillyTavern 生态互通，经过系统化性能优化，提供流畅的用户体验。

所有数据（角色、聊天记录、长期记忆、API Key）**完全存储在本地**，应用不内置任何自有服务器，所有网络请求仅指向你自己配置的 LLM 端点。

### ✨ 核心特点

- 🎨 **原生 Compose UI** - 流畅的现代化界面
- 🧠 **本地语义记忆** - 内置 ONNX 中文向量模型，无需 API Key 即可语义检索
- 🔌 **多 LLM 支持** - OpenAI 兼容端点（OpenAI / 中转网关 / 自建推理服务）
- 🔄 **SillyTavern 生态互通** - 角色卡、世界书直接导入导出，无需手工转换
- 📦 **完整本地存储** - 所有数据本地保存，支持全量备份
- 🛡️ **密钥加固** - API Key 经 Android Keystore 加密存储

---

## 📥 下载安装

### 最低要求
- Android 8.0 (API 26) 或更高
- 50 MB 存储空间（内置 24MB 向量模型）

### 下载方式
1. 从 [Releases](https://gitee.com/Wan2010/mistbell-tavern-android/releases) 下载最新 APK
2. 或自行构建（见[构建指南](#-构建项目)）

APK 按 ABI 分包（arm64-v8a / armeabi-v7a / x86 / x86_64），另提供 universal 包；侧载用户可任选其一。

### 首次使用
1. 安装应用，按引导走完三步（欢迎 → 准备角色 → 连接 AI）
2. 内置示例角色「阿莉雅」「凯尔」可一键体验，或导入自己的 SillyTavern 角色卡
3. 在提供商页配置 API Key 与模型
4. 开始聊天

---

## 🎯 功能特性

### 角色管理
- 自定义角色创建和编辑（头像、性格、背景设定、颜色、主题）
- 导入/导出角色卡（SillyTavern PNG 埋卡格式，v1/v2/v3 全兼容）
- 宏引擎（`{{char}}` / `{{user}}` / `{{random}}` / `{{roll}}` / `{{#if}}`）
- 标签与生态扩展字段原样透传保真

### 智能对话
- 聊天界面 Markdown 渲染（引号、动作、代码块）
- **SSE 真流式输出**（逐 token 渲染，可中途停止）
- 开场白切换（支持 SillyTavern `alternate_greetings` 备用开场白）
- 多角色群聊（≤4 人，说话者标注与归属、@提及指定回应者、按说话者渲染气泡）
- 会话置顶和静音

### 长期记忆
- 自动提取关键信息（LLM 抽取，攒批降低调用成本）
- **本地语义向量检索**（ONNX 量化 BGE 中文模型，离线可用）
- 词法召回兜底（CJK bigram 分词）
- 记忆重要度评分、词条手动编辑
- 「往事回响」注入相关历史片段

> **已知边界**：群聊模式（模式③）目前只做最小闭环——说话者标注、回复归属、@提及已落地；**witness 记忆分账**（NPC 只召回自己在场时的记忆）列入下迭代，见 [docs/MODES.md](docs/MODES.md)。

### 世界书
- 独立世界书管理 + 角色卡内嵌世界书
- 6 个注入锚点 + `[系统]/[用户]/[AI]` 插入深度 @D（对齐 SillyTavern）
- 概率触发、关键词/次级关键词匹配、递归扫描
- v2 规范规范化导出（酒馆读卡不丢触发词）

### 生态互通
- **批量导入酒馆数据文件夹**（角色卡 PNG + 世界书 JSON 一键扫）
- 导入转化报告（新增/更新/跳过/失败明细）
- 卡片 DTO 层自研（按 CCv2/v3 规范实现，AGPL 零依赖）
- 上下文调试面板（提示词预览）

### LLM 集成
- 提供商配置与连接测试（拉取模型列表 / 最小请求探活）
- **OpenAI 兼容端点**——请求统一走 `{baseUrl}/chat/completions` + Bearer 鉴权，覆盖 OpenAI 及绝大多数兼容网关/中转/自建推理服务
- 多模型支持、采样参数三档预设（创意/平衡/精确）+ 提供商级覆盖
- Embedding API 支持（自动 / API / 本地 ONNX 三档）
- 请求超时与重试次数可配，指数退避

### 数据与主题
- 全量备份/恢复（单 zip 含角色/会话/记忆/世界书/设置/主题/向量库）
- 皮肤级主题包（zip 导入导出，纯数据 tokens 无代码执行）
- 聊天记录 JSON 导出

---

## ⚡ 性能优化

经过多个版本的系统优化，应用性能全面提升：

| 优化项 | 提升幅度 |
|--------|---------|
| 冷启动速度 | **40%** ↓ |
| 数据库查询 | **2-10 倍** ↑ |
| UI 流畅度 | **20-30%** ↑ |
| 内存占用 | **30-70MB** ↓ |
| 向量搜索 | **99%** ↓ |
| 网络成功率 | **29%** ↑ |

### 主要优化

**数据库优化** - 7+ 关键索引、窗口分页（首屏只观察最新 200 条消息）  
**计算优化** - LRU 缓存，向量搜索加速  
**网络优化** - 智能重试机制，指数退避策略  
**UI 优化** - 减少重组，时间戳缓存  
**内存优化** - 延迟加载，内存限制  
**启动优化** - 完全 lazy 初始化  
**成本优化** - 记忆抽取攒批（每 3 轮合并一次调用）、前缀缓存友好化

详见：[更新日志](CHANGELOG.md)

---

## 🛠️ 工程实践

这是一个单人项目，因此把工程纪律放在首位。

### 质量门

本地与 CI 同门，一条命令跑全部检查：

```bash
./gradlew checkAll
# = assembleDebug + testDebugUnitTest + detekt + ktlintCheck + licenseGuard
```

| 工具 | 用途 | 状态 |
|------|------|------|
| **JUnit** | 320 个单元测试 | 全绿 |
| **detekt** | 静态分析（baseline 767 条，新代码零容忍） | CI 强制 |
| **ktlint** | 代码风格 | CI 强制 |
| **licenseGuard** | 依赖许可证红线（禁 GPL/AGPL/SSPL/AFFERO） | CI 强制 |

### 架构

MVVM + Repository + Flow，Compose UI + ViewModel + Room。

- **无 Hilt** - AppContainer 手工 DI（团队 > 1 人或注入图 > 30 节点再议）
- **单模块** - 代码量超过 30k 行或需多团队并行再议拆分
- **Room 21 个 schema 版本** - exportSchema=true，21 个版本全部有迁移 + 迁移测试
- **密钥加固** - SecureStore（Keystore 加密）替代 androidx.security-crypto（已停更）

### 兼容性研究

项目对 SillyTavern 生态格式做了深度研究并写入文档（见 [docs/FOUNDATION.md](docs/FOUNDATION.md)），例如世界书条目的 `position` 枚举、probability 百分数换算、卡内嵌 vs 独立 WI 文件的 entries 形态差异——这些细节在官方文档中查不到，只有实测导入才能发现。

### 引用纪律

工具性依赖（okhttp-sse、onnxruntime、detekt）直接用、锁版本；观点性代码（卡片 DTO、SSE 解析、记忆排序、主题桥协议）一律"看设计、写自己的"。

---

## 🛠️ 技术栈

### 核心框架
- **Kotlin** - 100% Kotlin 代码
- **Jetpack Compose** - 声明式 UI（Material 3）
- **Coroutines & Flow** - 异步处理
- **ViewModel** - MVVM 架构

### 数据层
- **Room Database** - 本地存储（21 个 schema 版本）
- **DataStore** - 键值存储
- **SecureStore** - Keystore 加密密钥
- **onnxruntime-android** - 本地语义向量（MIT）
- **sqlite-vec**（计划） - 向量存储规模升级

### 网络层
- **OkHttp** - HTTP 客户端 + SSE（okhttp-sse 4.12），LLM 请求自研 `LlmClient`
- **Kotlinx Serialization** - JSON 序列化
- **Retrofit** - REST 客户端（保留的实验性远程 provider 通路，见 ROADMAP）

### 性能
- **R8** - 代码压缩混淆
- **LRU Cache** - 内存缓存
- **窗口分页** - DAO 层游标分页（非 Paging 3，见 ROADMAP 说明）

### 质量
- **JUnit** - 单元测试
- **detekt** + **ktlint** - 静态检查
- **Room Testing** - 迁移测试

---

## 🔨 构建项目

### 环境要求
- JDK 17+
- Android Studio Hedgehog (2023.1.1) 或更高
- Gradle 8.13
- compileSdk 35 / minSdk 26 / targetSdk 35

### 构建步骤

```bash
# 克隆仓库
git clone https://gitee.com/Wan2010/mistbell-tavern-android.git
cd mistbell-tavern-android

# 完整质量门（构建 + 测试 + 静态检查 + 许可证）
./gradlew checkAll

# 仅构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 未签名 Debug APK（用于自定义签名/分发）
./gradlew :app:assembleDebug -PbuildUnsigned

# 安装到设备
./gradlew installDebug
```

APK 输出路径：`app/build/outputs/apk/`

---

## 📖 文档

| 文档 | 内容 |
|------|------|
| [更新日志](CHANGELOG.md) | 完整的版本历史（v0.1.0 ~ v0.9.3-beta） |
| [路线图](docs/ROADMAP.md) | 现状快照、M1~M3 规划、明确不做的事 |
| [地基规划](docs/FOUNDATION.md) | 依赖选型、许可证红线、ST 生态格式要点 |
| [模式设计](docs/MODES.md) | 五种玩法模式的架构设计 |
| [前辈借鉴](docs/PREDECESSORS.md) | OMate/Tavo 调研与借鉴清单 |
| [贡献指南](CONTRIBUTING.md) | 如何参与开发 |
| [发布说明模板](docs/RELEASE_TEMPLATE.md) | 版本发布格式 |

---

## 📁 项目结构

```
app/src/main/java/com/mistbell/tavern/android/
├── data/                 # 数据层
│   ├── api/             # LLM API 客户端、采样预设
│   ├── local/           # Room 数据库（dao / entity / 迁移）
│   ├── model/           # 数据模型
│   ├── network/         # 网络监控
│   ├── prompt/          # 提示词构建、世界书位置解析
│   ├── repository/      # 数据仓库（备份/角色/聊天/世界书/向量）
│   ├── theme/           # 主题包数据模型
│   └── vector/          # 向量存储、本地 ONNX 推理、分词器
├── navigation/          # 导航
├── service/             # 后台服务（记忆抽取、本地提供商）
├── ui/                  # UI 层（Compose）
│   ├── character/      # 角色管理
│   ├── chat/           # 聊天界面
│   ├── chatlist/       # 会话列表
│   ├── common/         # 通用工具
│   ├── components/     # 通用组件
│   ├── export/         # 聊天导出
│   ├── main/           # 主界面
│   ├── memory/         # 记忆管理
│   ├── onboarding/     # 首启引导
│   ├── prompt/         # 提示词预览
│   ├── provider/       # LLM 提供商配置
│   ├── settings/       # 设置页面
│   ├── themepack/      # 主题包管理
│   └── worldbook/      # 世界书
└── util/               # 工具类（卡片解析、宏引擎、ST 互操作、备份）
```

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 贡献流程
1. Fork 本仓库
2. 创建特性分支
3. 提交更改
4. 推送到分支
5. 提交 Pull Request

### 代码规范
- 遵循 Kotlin 官方编码规范
- `gradlew checkAll` 必须全绿（detekt/ktlint 零新增告警）
- 新增业务逻辑须配单测
- 保持代码整洁

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源。

> ⚠️ **许可证红线**：本项目定位为开源友好替代，SillyTavern（AGPL）的代码**不可引入**；仅按其**格式与协议**做互操作（CCv2/v3 卡片规范、世界书格式），卡片 DTO 层自研。

---

## 🙏 致谢

感谢以下开源项目：

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [OkHttp](https://square.github.io/okhttp/)
- [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
- [onnxruntime-android](https://github.com/microsoft/onnxruntime) - 本地向量推理
- [bge-small-zh-v1.5](https://github.com/FlagOpen/bge-models) - 中文向量模型（MIT）

---

## 📞 联系方式

- **作者：** Wan
- **仓库：** https://gitee.com/Wan2010/mistbell-tavern-android
- **反馈：** [提交 Issue](https://gitee.com/Wan2010/mistbell-tavern-android/issues)

---

<div align="center">

**如果这个项目对你有帮助，请给它一个 ⭐ Star！**

Made with ❤️ by Wan

</div>
