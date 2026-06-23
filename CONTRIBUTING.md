# 贡献指南

感谢你对 Mistbell Tavern Android 的关注！欢迎以任何形式参与贡献。

## 行为准则

请保持友善、尊重和建设性的态度。我们希望为所有参与者营造一个开放和包容的环境。

## 如何贡献

### 报告问题（Issue）

提交 Issue 前请先：

1. 搜索 [现有 Issue](https://gitee.com/Wan2010/mistbell-tavern-android/issues)，避免重复
2. 使用 Issue 模板，提供尽可能完整的信息
3. Bug 报告请附上复现步骤、设备型号、Android 版本和应用版本

### 提交代码（Pull Request）

1. Fork 本仓库
2. 从 `main` 创建特性分支：`git checkout -b feature/your-feature`
3. 进行修改并提交
4. 推送到你的 Fork：`git push origin feature/your-feature`
5. 在 gitee 上发起 Pull Request，关联相关 Issue

## 开发环境

| 工具 | 版本要求 |
|------|---------|
| JDK | 17+ |
| Android Studio | Hedgehog (2023.1.1) 或更高 |
| Gradle | 8.13 |
| Android SDK | compileSdk 35 / minSdk 26 |

构建与安装：

```bash
./gradlew assembleDebug    # 构建 Debug APK
./gradlew installDebug     # 安装到设备
./gradlew test             # 运行单元测试
```

## 分支与提交规范

### 分支命名

- `feature/xxx` — 新功能
- `fix/xxx` — Bug 修复
- `refactor/xxx` — 重构
- `docs/xxx` — 文档

### 提交信息

采用 [约定式提交](https://www.conventionalcommits.org/zh-hans/)：

```
<类型>(<范围>): <简短描述>

[可选的正文]
```

常用类型：`feat`、`fix`、`perf`、`refactor`、`docs`、`style`、`test`、`chore`

示例：

```
feat(chat): 支持多角色群聊消息分流
perf(db): 为 sessions 表添加复合索引
fix(memory): 修复向量缓存写后未失效问题
```

## 代码规范

- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用有意义的命名，避免缩写
- UI 使用 Jetpack Compose，遵循单向数据流（MVVM）
- 为公共 API 和复杂逻辑添加注释
- 提交前确保 `./gradlew assembleDebug` 通过

## 项目架构

代码按分层组织（`com.mistbell.tavern.android`）：

- `data/` — 数据层（api、local、repository、vector、prompt、sync）
- `ui/` — UI 层（按功能模块划分）
- `service/` — 后台服务
- `navigation/` — 导航
- `util/` — 工具类

## 许可

提交代码即表示你同意以 [MIT License](LICENSE) 授权你的贡献。
