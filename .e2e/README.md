# .e2e — 模拟器端到端实测基建

跨功能复用的装机实测工具（不属于应用源码，不进 APK）。项目约定：每个功能合并前必须在
模拟器上走通真实功能路径（构建/单测绿不算完成），本目录是做这件事的标准装备。

## 组成

| 文件 | 用途 |
|---|---|
| `mock-llm.js` | OpenAI 兼容 mock LLM（`/v1/chat/completions`，SSE 流式 + thinking 段 + 非流式）。环境变量 `MOCK_CHUNK_MS` 控制 chunk 节奏（默认 120ms），`MOCK_THINK_HOLD` 在 thinking 后停顿（模拟取消停在思维链段内）。 |
| `probe-script.js` | 探测脚本源码：注入 WebView 后自动订阅五类聊天事件、渲染结果面板、把每项结果以 `PROBE ` 前缀打进 logcat（tag `TavernBridge`）。改完跑 `build_card.py` 重新生成探测卡。 |
| `build_card.py` | 把 `probe-script.js` 组装进 v2 角色卡 JSON（`data.extensions.tavern_helper.scripts`）→ `probe-card.json`。 |
| `probe-card.json` | 生成好的探测卡（ProbeBot），经应用角色导入 UI（SAF）导入。 |

## 标准流程

```bash
node .e2e/mock-llm.js &                      # 1. 起 mock（宿主 127.0.0.1:18765）
adb reverse tcp:18765 tcp:18765              # 2. 模拟器内 127.0.0.1:18765 → 宿主（应用内 API 端点按 127.0.0.1:18765/v1 配置）
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-*.apk
                                             # 3. 装机（-r 覆盖保留数据）
# 4. 应用内：设置 → 高级设置 → 实验性功能 → 开「HTML 界面」；导入 probe-card.json；进会话
adb logcat | grep -E "PROBE|TavernJsBridge|AndroidRuntime"   # 5. 取证（优先 logcat/元素树，截图可能返回缓存假象）
```

探测卡面板提供按钮驱动（改写/追加/删除消息、generate、stop 等），事件计数器实时显示；
「旧事件不重放」语义验证依赖：先操作再挂页面（或反之）按需安排顺序。

## 约定

- 一次性取证产物（截图 `shot*.png`、拉库副本 `tavern-*.db`、`ui.xml`、`*.log`）不进仓库
  （.gitignore 已覆盖），测完可删。
- 新功能实测扩展本目录时：探测项进 `probe-script.js`（保持 PROBE 前缀约定），mock 行为
  进 `mock-llm.js` 并加环境变量开关，不硬编码新端口。
