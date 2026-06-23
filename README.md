# 🎭 Mistbell Tavern Android

<div align="center">

[![Version](https://img.shields.io/badge/version-0.3.0--beta-blue.svg)](https://gitee.com/Wan2010/mistbell-tavern-android/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-orange.svg)](LICENSE)

**高性能的 AI 角色聊天 Android 应用**

[下载体验](#-下载安装) • [功能特性](#-功能特性) • [性能优化](#-性能优化) • [技术栈](#-技术栈)

</div>

---

## 📱 应用介绍

Mistbell Tavern 是一个功能完整的 Android 原生 AI 角色聊天应用。采用 Jetpack Compose 构建现代化 UI，支持自定义角色、长期记忆系统和多种 LLM 提供商，经过系统化性能优化，提供流畅的用户体验。

### ✨ 核心特点

- 🎨 **原生 Compose UI** - 流畅的现代化界面
- 🧠 **长期记忆系统** - 基于向量检索的语义记忆
- 🚀 **高性能架构** - 冷启动提升 40%，数据库查询提升 2-10 倍
- 🔌 **多 LLM 支持** - OpenAI、Claude 等多种 API
- 📦 **完整本地存储** - 所有数据本地保存

---

## 📥 下载安装

### 最低要求
- Android 8.0 (API 26) 或更高
- 50 MB 存储空间

### 下载方式
1. 从 [Releases](https://gitee.com/Wan2010/mistbell-tavern-android/releases) 下载最新 APK
2. 或自行构建（见[构建指南](#-构建项目)）

### 首次使用
1. 安装应用
2. 进入设置配置 LLM 提供商
3. 创建或导入角色
4. 开始聊天

---

## 🎯 功能特性

### 角色管理
- 自定义角色创建和编辑
- 角色头像、性格、背景设定
- 导入/导出角色配置

### 智能对话
- 流畅的聊天界面
- 消息高亮（引号、动作）
- 多角色群聊支持
- 会话置顶和静音

### 长期记忆
- 自动提取关键信息
- 语义相似度检索
- 记忆重要度评分

### LLM 集成
- 多提供商配置
- 模型参数调整
- Embedding API 支持

---

## ⚡ 性能优化

经过三个版本的系统优化，应用性能全面提升：

| 优化项 | 提升幅度 |
|--------|---------|
| 冷启动速度 | **40%** ↓ |
| 数据库查询 | **2-10 倍** ↑ |
| UI 流畅度 | **20-30%** ↑ |
| 内存占用 | **30-70MB** ↓ |
| 向量搜索 | **99%** ↓ |
| 网络成功率 | **29%** ↑ |

### 主要优化

**数据库优化** - 添加 7 个关键索引，修复 N+1 查询  
**计算优化** - LRU 缓存，向量搜索加速  
**网络优化** - 智能重试机制，指数退避策略  
**UI 优化** - 减少重组，时间戳缓存  
**内存优化** - 延迟加载，内存限制  
**启动优化** - 完全 lazy 初始化

详见：[更新日志](CHANGELOG.md)

---

## 🛠️ 技术栈

### 核心框架
- **Kotlin** - 100% Kotlin 代码
- **Jetpack Compose** - 声明式 UI
- **Coroutines & Flow** - 异步处理
- **ViewModel** - MVVM 架构

### 数据层
- **Room Database** - 本地存储
- **DataStore** - 键值存储
- **向量存储** - 语义检索

### 网络层
- **Retrofit** - REST API
- **OkHttp** - HTTP 客户端
- **Kotlinx Serialization** - JSON 序列化

### 性能
- **Paging 3** - 分页加载
- **R8** - 代码压缩混淆
- **LRU Cache** - 内存缓存

---

## 🔨 构建项目

### 环境要求
- JDK 17+
- Android Studio Hedgehog (2023.1.1) 或更高
- Gradle 8.13

### 构建步骤

```bash
# 克隆仓库
git clone https://gitee.com/Wan2010/mistbell-tavern-android.git
cd mistbell-tavern-android

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug
```

APK 输出路径：`app/build/outputs/apk/`

---

## 📖 文档

- [更新日志](CHANGELOG.md) - 完整的版本历史
- [贡献指南](CONTRIBUTING.md) - 如何参与开发
- [发布说明模板](docs/RELEASE_TEMPLATE.md) - 版本发布格式

---

## 📁 项目结构

```
app/src/main/java/com/mistbell/tavern/android/
├── data/                 # 数据层
│   ├── api/             # LLM API 客户端
│   ├── local/           # Room 数据库（dao / entity）
│   ├── model/           # 数据模型
│   ├── network/         # 网络监控
│   ├── prompt/          # 提示词构建
│   ├── repository/      # 数据仓库
│   ├── sync/            # 数据同步
│   └── vector/          # 向量存储
├── navigation/          # 导航
├── service/             # 后台服务
├── ui/                  # UI 层（Compose）
│   ├── character/      # 角色管理
│   ├── chat/           # 聊天界面
│   ├── chatlist/       # 会话列表
│   ├── components/     # 通用组件
│   ├── drawer/         # 侧边抽屉
│   ├── export/         # 聊天导出
│   ├── memory/         # 记忆管理
│   ├── prompt/         # 提示词编辑
│   ├── provider/       # LLM 提供商配置
│   ├── settings/       # 设置页面
│   ├── theme/          # 主题样式
│   └── worldbook/      # 世界书
└── util/               # 工具类
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
- 使用有意义的命名
- 添加必要的注释
- 保持代码整洁

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源。

---

## 🙏 致谢

感谢以下开源项目：
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [OkHttp](https://square.github.io/okhttp/)
- [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)

---

## 📞 联系方式

- **作者：** Wan
- **仓库：** https://gitee.com/Wan2010/mistbell-tavern-android
- **反馈：** [提交 Issue](https://gitee.com/Wan2010/mistbell-tavern-android/issues)

---

## 🗺️ 开发路线

### v0.4.0（计划中）
- [ ] 消息分页加载
- [ ] 暗色模式
- [ ] 聊天记录导出

### 未来计划
- [ ] 云端同步
- [ ] 角色市场
- [ ] 语音交互

---

<div align="center">

**如果这个项目对你有帮助，请给它一个 ⭐ Star！**

Made with ❤️ by Wan

</div>
