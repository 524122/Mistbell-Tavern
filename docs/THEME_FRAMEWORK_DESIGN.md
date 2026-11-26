# 主题框架 v0 设计：从 token 包到完整渲染定义权

> 状态：**冻结**（2026-11-26，依据 [ROADMAP.md](ROADMAP.md) 二、P1「自绘引擎冻结」）。
> 冻结的含义：**代码保留、路由不变**——引擎只有在「原生渲染引擎开关开启 + 当前主题包声明
> `capabilities=layout` + 模板版本受支持 + 引擎已引导 + 卡段可渲染」时才接管消息内容体，
> 否则整条走 legacy（核对：`app/src/main/java/com/mistbell/tavern/android/ui/chat/render/EngineMessageContent.kt:108-125`
> 的 `resolveRenderPath`，`app/src/main/java/com/mistbell/tavern/android/ui/chat/MessageBubble.kt:201-213`
> 的 `enginePath` 与 `:496-510` 的接管分支）。冻结范围内不再扩展 CSS 白名单、选择器、交互
> 与作者工具；缺陷修复不受冻结限制（例：P0 已做的「渲染缓存跨渲染复用」，
> commit `ab2b824`），但新能力一律不做。**解冻条件**（两项同时成立）：(1) 以 `HtmlBubble`
> （WebView）为对照，冷首帧、滚动掉帧率、常驻 10 个面板的内存增量三项均不超过 WebView
> 的 50% 且无泄漏；(2) 至少有 3 个非作者的 layout 主题包，或用户明确提出。解冻时才做
> 测量，规划原先预留的两天 go/no-go 测量在冻结期间不执行。现状判断：layout 包只有
> 作者自己的示例 `samples/theme-ink-novel/`（`samples/theme-pokemon-battle/` 是 cosmetic
> 包，不参与引擎），条件 (2) 不成立。
> ——以下为冻结前的状态与实施补记，原文保留：
>
> 状态：**M0-M6 全部已实施**（M1 依赖 spike → M2 协议解析层 → M3 渲染管线 → M4 聊天接线
> + M5 链接点击与长按选词 + M6 Room 迁移与示例包，go/no-go 出口达标：真实 MVU 面板渲染
> 正确、滚动/流式帧预算内、Bitmap 缓存无泄漏）。实施后补记（保留原设计正文，偏差以代码
> 与 CHANGELOG 为准）：
> - skiko 0.9.47 光栅回传为 **BGRA 字节序**，Android ARGB_8888 缓冲是 RGBA——M3 冒烟用彩色
>   徽章发现全图红蓝对调，`BitmapBridge.wrapToBitmap` 逐像素交换 R/B 修复（M1 黑白灰冒烟
>   未暴露此坑）。
> - CJK 在系统含中文字体的设备可用（M1 spike 结论维持：`FontMgr.default` 挂系统字体管理器，
>   拉丁硬可用；CJK 深度回退捆绑字体的方案未启用）。
> - 面板渲染基准 **约 6.6ms**（模拟器 x86_64，MVU 面板全管线含布局+光栅+回传），远低于帧预算。
> - M4 聊天接线实测：引擎路径渲染 MVU 面板与水墨叙述排版见 `screenshots/render-mvu-panel.png`、
>   `screenshots/render-narrative.png`（CJK 可用性以截图为证）。
> - M6 落地：theme_packs 升 v39（MIGRATION_38_39 纯加列 capabilities/template_version，
>   v38 存量库模拟器实测升级无损）；示例布局主题包 `samples/theme-ink-novel/`。
> - M5 落地：渲染后保留 `RenderSession`（每 TextBlock 的 skia Paragraph 引用 + 画布位置 +
>   span 字符区间映射），`linkHitAt`（a[href] 命中 → http/https 白名单 → ACTION_VIEW）与
>   `wordRangeAt`（getGlyphPositionAtCoordinate → getWordBoundary → getRectsForRange 选区
>   矩形两套坐标）纯几何查询 + 长按选词复制（照 pill 风格小弹出）。手势交互为"观察后接管"
>   自定义检测器而非 `detectTapGestures`——后者无条件 consume down 且长按超时路径不复查
>   消费，会把外层气泡长按 pill 永久屏蔽；接管面 = 文本块矩形内（空白区长按照常开 pill），
>   move 从不消费（列表滚动 / 气泡横滑零影响）。§8 限制表相应更新。
> 设计稿（2026-11-21，基于代码盘点 + 架构评审拍板；本文档只做已拍板决策的文档化，不引入新决策）
> 决策背景：主题系统现状是"token 包"（换色 + 背景图，结构不可动），HTML 卡片生态已跑通
> WebView 沙箱路径。方向已定：**主题从换肤升级为消息内容渲染的完整定义权，且渲染走原生
> 自绘引擎（skiko + Yoga），WebView 退居兜底**。配合 [EXTENSION_PLATFORM_DESIGN.md](EXTENSION_PLATFORM_DESIGN.md)（扩展
> 平台与信任边界惯例）、[WEB_CONTENT_RUNTIME_DESIGN.md](WEB_CONTENT_RUNTIME_DESIGN.md)（WebView 沙箱运行时）、[ROADMAP.md](ROADMAP.md) 阅读。

---

## 1. 目标与定位

### 1.1 现状与升级方向

现状：主题系统是 **token 包**——10 个颜色 token + 背景图（`app/src/main/java/com/mistbell/tavern/android/data/theme/ThemeModels.kt:8-29` 的 `ThemeColors`/`ThemeTokens`），
typography/shapes 编译期静态。主题只能换色，消息内容的排版结构完全写死在 Compose 里。

v0 升级方向：主题获得**完整渲染定义权**——消息内容的排版结构（template.html）与视觉样式
（style.css）由主题包声明，宿主引擎负责确定性渲染。

### 1.2 自由四档

| 自由档 | 含义 | v0 交付 |
|---|---|---|
| 样式自由 | CSS 子集控制颜色/间距/排版/布局 | 交付 |
| 结构自由 | 模板（HTML 子集 + 槽位）声明内容排版结构 | 交付 |
| 绑定自由 | data-bind 读取会话状态渲染 | 轻量交付（只读两条绑定） |
| 行为自由 | 动画原语/交互脚本 | **二期，v0 不做** |

### 1.3 与 TauriTavern / 酒馆 CSS 生态的差异

- **自建协议白名单制**，不是"随便塞 CSS/HTML 然后祈祷浏览器能渲染"。核心原则：
  **主题是声明不是代码**——模板与样式表里不存在可执行语义，白名单外的构造静默丢弃。
- **自由的确定性**：自由度被帧预算与白名单约束，引擎对任意合法主题包的渲染成本可预算、
  可兜底；而不是"先给自由、出事再堵"。

---

## 2. 渲染架构（已拍板）

### 2.1 渲染管线

```
消息 → HtmlCardDetector.Part 分段 → 绑定解析 → 模板实例化（slot 填充）
     → 样式级联（CSS 白名单）→ Yoga 布局（SkParagraph 测量）→ skiko 光栅化
     → Bitmap 缓存 → Compose 气泡内容体 blit
```

### 2.2 集成模型 v0 = 气泡内容级自绘

- `MessageBubble` 的气泡壳、手势、长按 pill、横滑、时间戳**保持 Compose**，只有内容体
  换引擎渲染。内容体缝合点即现有 HTML 分支所在（`app/src/main/java/com/mistbell/tavern/android/ui/chat/MessageBubble.kt:401-446`）。
- 引擎输出为 skiko 光栅化的 **Bitmap**，Compose Canvas 按
  `(messageId, 内容hash, variablesVersion, 宽度)` 四元组缓存 blit；变量版本或宽度变化才重光栅。
- **LazyColumn 回收、流式节流、IME 行为全部保留**——引擎只替换 item 内容体的绘制方式，
  不触碰列表层。
- **整聊天区单 SurfaceView 是二期**：协议不变，换宿主层（气泡内容体 → 整区画布）。

### 2.3 后端决策记录

- **直奔 skiko + Yoga 自绘**（用户拍板）——否决了"Compose 解释器过渡路线"（在 Compose
  里逐节点映射模板再过渡到自绘）。理由：过渡层本身是一套完整实现，删除成本高于直接做对。
- **HTML 解析用 jsoup**：容错解析脏 HTML，不自行写容错解析器。

---

## 3. 主题包三件套协议

### 3.1 包结构

沿用 `ThemePackRepository` 的 zip 通道（`app/src/main/java/com/mistbell/tavern/android/data/repository/ThemePackRepository.kt:67`
`importFromUri`，含路径穿越拒绝/坏包中文报错/同 id 覆盖安装语义，全部不变）。现有
`manifest.json + theme.json + assets/` 结构之上，**zip 根新增 `template.html` 与 `style.css`**：

```jsonc
theme-pack.zip
├── manifest.json      // 包描述 + 能力声明（§3.2）
├── theme.json         // 存量颜色 tokens（§5.4 桥接为 CSS 变量）
├── template.html      // 新增：模板（HTML 子集 + slot，§4）
├── style.css          // 新增：样式表（CSS 子集，§5）
└── assets/            // 存量：背景图等资产
```

### 3.2 manifest 扩展

现有 `ThemeManifest`（`ThemePackRepository.kt:26-33`）的 `id`/`name`/`author`/`version`
字段语义不变。扩展两点：

```jsonc
{
  "id": "my-theme",
  "name": "…", "author": "…", "version": "1.0.0",
  "capabilities": "layout",     // "cosmetic" = 存量取值（行为不变）| "layout" = 新取值
  "templateVersion": 1          // 新增字段：目标模板版本，v0 冻结为 1
}
```

- `capabilities` 字段已在 manifest 模型中（现默认 `"cosmetic"`，仅作为声明存在）；v0 赋予
  第二个取值 `"layout"`：包携带 template.html/style.css，走新渲染引擎。`"cosmetic"` 包行为完全不变。
- `templateVersion` 不匹配引擎支持版本（v0 即 1）的包**拒绝启用**（§4.5）。
- **存量 T0 主题包（纯 theme.json）不受影响、不迁移**：无 template.html 即不参与引擎，
  继续走 token 应用链。

---

## 4. 模板语言 v0

### 4.1 HTML 子集 + 槽位模型

自写解析器，元素白名单：

| 元素 | 允许的属性 |
|---|---|
| `div` `span` `p` `br` | `class` `style` `data-bind:*` |
| `b`/`strong` `i`/`em` `u` `s` | `class` `style` `data-bind:*` |
| `a` | `class` `style` `data-bind:*` `href` |
| `img` | `class` `style` `data-bind:*` `src` `alt` |
| `hr` | `class` `style` |
| `ul`/`ol`/`li` | `class` `style` `data-bind:*` |
| `h1`-`h6` | `class` `style` `data-bind:*` |
| `code` `pre` | `class` `style` `data-bind:*` |
| `slot` | `name` |

### 4.2 slot 槽位

- `<slot name="narrative">`：承接 **Markdown 叙述段**（`HtmlCardDetector.Part.Markdown`）。
- `<slot name="panels">`：承接 **HTML 卡片段**（`Part.HtmlDoc`）。
- 分段来源即现有 `HtmlCardDetector`（`app/src/main/java/com/mistbell/tavern/android/ui/chat/htmlui/HtmlCardDetector.kt:13-17`
  `Part` 密封接口）——分段逻辑复用，只是消费方从 WebView/Markdown 换成模板引擎。
- **容错**：内容中出现模板未声明的槽位时，按默认顺序（narrative → panels）追加在模板尾部，
  不丢内容。

### 4.3 白名单外静默丢弃

白名单外的元素与属性**静默丢弃**（不留占位、不报错、不降级）。这是"主题是声明不是代码"
（§1.3）的直接体现：主题作者写 `<table>` 或 `onclick`，引擎看到的是空构造，而非执行面。

### 4.4 宏插值与状态插值

- `{{char}}` / `{{user}}`：**复用现有 substituteParams 机制**（`app/src/main/java/com/mistbell/tavern/android/util/MacroEngine.kt`
  引擎 + `ui/chat/htmlui/HtmlFacadeContract.kt:105` `substituteParamsLimited` 同款上下文）。
- `{{state.路径}}`：文本节点状态插值，数据源见 §6。

### 4.5 默认模板 v1（事实规范）

- 内置**默认模板 v1**：复刻当前气泡内容观感（叙述段 Markdown 排版、面板全宽 full-bleed），
  `templateVersion = 1` **冻结**——它即是 v0 模板语言的事实规范与回归基线。
- 主题在 `manifest.templateVersion` 声明目标版本；与引擎支持版本不匹配的包**拒绝启用**
  （导入时可检、启用时再检，双闸）。模板语言演进 = 冻结新版本号 + 引擎同时支持新旧版本，
  不做不兼容就地改。

---

## 5. 样式语言 v0

### 5.1 解析器与级联

- **自写 CSS 子集解析器**。规则块：选择器支持 class/id/元素选择器 + 逗号分组。
- 特异性排序：`(id, class, element)` 三元组比较，同特异性按声明顺序。
- **不支持**：媒体查询、伪类、`@` 规则（`@media`/`@keyframes`/`@import` 等一律丢弃）。

### 5.2 属性白名单

| CSS 属性 | 映射语义 |
|---|---|
| `display` | `block` \| `flex` \| `none`（Yoga 节点类型） |
| `flex-direction` / `flex-wrap` | Flex 主轴方向 / 换行 |
| `gap` | Flex 间距 |
| `justify-content` / `align-items` | 主轴 / 交叉轴对齐 |
| `margin`（`-top/-right/-bottom/-left`） | 外边距 |
| `padding`（`-*` 同上） | 内边距 |
| `width` / `height` / `min-width` / `max-width` / `min-height` / `max-height` | 尺寸 |
| `border`（`-width` / `-color` / `-style`，style 仅 `solid`） | 边框 |
| `border-radius` | 圆角 |
| `background-color` / `color` / `opacity` | 填充 / 前景色 / 透明度 |
| `font-size` / `font-weight`（100-900）/ `font-family` / `line-height` | 文字度量（family 仅内置字库白名单） |
| `text-align` | `left` \| `center` \| `right` |
| `overflow` | `hidden` \| `visible` |
| `visibility` | 可见性 |

白名单外属性静默丢弃（同 §4.3 原则）。

### 5.3 单位

- 支持：`px` 与 `dp` **等价**（1px = 1dp，引擎统一按 dp 光栅）、`%`（相对父容器）、
  `em`（仅 font-size 场景相对父级字号）、`auto`。
- 不支持：`vh`/`vw`、`calc()` 及 `var()` 以外的任何函数。

### 5.4 主题 token 桥

`ThemeTokens` 的 10 色注入为 CSS 变量，样式表可用 `var(--mt-*)`：

| 变量 | token |
|---|---|
| `--mt-primary` / `--mt-background` / `--mt-surface` / `--mt-surface-variant` | `ThemeColors` 同名字段 |
| `--mt-user-bubble` / `--mt-assistant-bubble` | 同上 |
| `--mt-on-user-bubble` / `--mt-on-assistant-bubble` / `--mt-on-background` / `--mt-on-surface` | 同上 |

- **深色模式**：`theme.json` 的 `dark` 覆盖块（`ThemeModels.kt:27`）注入**同名变量**，运行时
  按当前深浅模式取值注入——样式表无需感知模式，与现有 `resolved(isDark)` 语义对齐。
- token 未设置的色位不注入该变量（var 回退值语义交由样式表自行处理）。

---

## 6. 绑定层 v0

### 6.1 绑定语法（只读两条）

| 语法 | 语义 |
|---|---|
| `data-bind:text="state.路径"` | 元素文本内容绑定到状态值 |
| `data-bind:visible="state.路径"` | 真值显隐（假值 `display:none` 语义） |
| 文本节点 `{{state.路径}}` | 插值为状态值字符串 |

### 6.2 数据源（现有变量链，过渡桥）

- 状态读取走**现有 MVU 会话 KV**：`ext.mvu.<sessionId>`（stat_data JSON）+ 版本号
  （`app/src/main/java/com/mistbell/tavern/android/extension/builtin/MvuExtension.kt:16,41-60`）。
- 刷新时机走**现有 variablesVersion 推送**：`ChatViewModel` 的 `mvuVariablesVersion`
  （`app/src/main/java/com/mistbell/tavern/android/ui/chat/ChatViewModel.kt:208-214`，订阅
  `TavernEvent.VariablesUpdated`）——版本变化触发该消息 Bitmap 缓存失效重渲染（§2.2 四元组）。
- **渲染层只读，不写变量**：引擎没有任何写状态通道，写路径仍归 MVU 扩展（回复后处理链）。

### 6.3 失败语义

路径不存在 / 类型不符 → 插值为**空串**、`data-bind:visible` 按 **false**，记 debug 日志，
**永不崩**。绑定错误是主题包数据问题，不是宿主故障。

---

## 7. 降级与开关

三层兜底，逐级收窄爆炸半径：

1. **卡段级**：引擎解析某卡段失败 / 卡段含白名单外构造（如 `<table>`）→ 该卡段自动回落
   `HtmlBubble`（WebView 沙箱路径，`app/src/main/java/com/mistbell/tavern/android/ui/chat/htmlui/`）。
2. **消息级**：整条消息模板实例化失败 → 整条回落 legacy（`MessageMarkdown`）。
3. **总开关**：设置页（会话设置）提供**"新渲染器"开关**，关闭即全量回 legacy
   `MessageMarkdown`/`HtmlBubble`，引擎代码路径完全不参与。

**流式槽 v0 仍走 `MarkdownRenderer`**：流式不做 regex/HTML 拆分是现状，保持不变；引擎只
处理**最终落库消息**。流式过程中的观感与今天一致，落库后一次切换到引擎渲染。

---

## 8. 已知限制（v0 明示）

| 限制 | 说明 |
|---|---|
| 不支持 `<table>` | 白名单外，含表格的卡段自动回落 WebView（§7.1） |
| 流式槽不走引擎 | 流式期间仍 `MarkdownRenderer`，落库后才引擎渲染 |
| 文本选择 = 长按选词 + 复制 | 已实施（M5）：SkParagraph hit-test 选词 + 复制小弹出；无拖动扩展选择、无选择手柄，行间空隙按最近行吸附 |
| 链接仅 http/https 可点 | M5：scheme 白名单拒绝 intent/file/自定义 scheme（第三方主题包数据面防线） |
| 无障碍 = 子树 contentDescription 兜底 | 引擎画布整体一条语义描述，无逐节点语义树 |
| 行为自由（动画原语）无 | 二期（§1.2） |
| 单 SurfaceView 整区自绘无 | 二期（§2.2），v0 为气泡内容级 |

---

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 本文档 | 评审通过、入库 |
| M1 依赖 spike | jsoup + yoga + skiko 接入；skiko 光栅 CJK + Latin 文本；Yoga `calculateLayout`；SkParagraph 测-量（measure）桥；instrumented 冒烟 | 三依赖可在 Android 仪器环境跑通最小闭环：Yoga 布局一棵节点树并经 SkParagraph 测量、skiko 光栅成 Bitmap 上屏 |
| M2 协议解析层 | TemplateParser / CssParser + 级联 / TemplateValidator / BindingResolver | 纯 Kotlin、全单测：白名单丢弃、特异性排序、单位换算、绑定失败语义逐条覆盖 |
| M3 渲染管线 | YogaTreeBuilder / SkParagraph 测量桥 / Skia 绘制遍历 / Bitmap LruCache；Markdown 叙述映射复用 `parseMarkdown` 纯函数（`app/src/main/java/com/mistbell/tavern/android/ui/chat/MarkdownRenderer.kt:196`）并移植行内高亮（引号绿 / 动作括号橙 / 链接） | 真实 MVU 面板渲染正确；滚动/流式帧预算内；Bitmap 缓存无泄漏（**go/no-go 出口，见下**） |
| M4 聊天接线 | `MessageBubble.kt:401-446` 内容体缝接入引擎；主题能力解析链复用 `ThemeSupport.resolveTokens` 模式（`ThemeModels.kt:76`）扩展到 capabilities/templateVersion；设置开关；降级路由 | 开关三态行为正确（引擎/卡段回落/全量 legacy）；存量 cosmetic 包零回归 |
| M5 交互 | 链接 hit-test（`a[href]` 命中打开）；长按选词 + 复制（SkParagraph hit-test） | 链接可点、选词复制可用；长按 pill 手势与选词不冲突 |
| M6 Room 迁移 + 示例 | `theme_packs` 加 `capabilities` 列（schema 升版本 + Migration + androidTest 迁移完整性测试）；示例主题包进 `samples/`；CHANGELOG | 旧库升级不丢数据；示例包导入→启用→渲染→导出全链路通过 |

**Go/no-go（M3 出口）**：真实 MVU 面板渲染正确 + 滚动/流式帧预算内 + Bitmap 缓存无泄漏。
**不达标则引擎可用但默认不激活**（开关默认关），不阻塞其余阶段合入。每阶段按既有惯例：
单测 + 模拟器实测后提交。

> 状态更新（2026-11-26）：M3 出口已达标（见开头实施补记）；此后引擎整体进入**冻结**
> （文档开头状态块、[ROADMAP.md](ROADMAP.md) 二、P1），冻结期间不再执行 go/no-go 复测，
> 解冻条件以开头状态块为准。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| skiko Android 独立使用文档少 | M1 spike 先行验证（§9）；光栅路径起步，GL backend 二期再评估 |
| Yoga JVM 单测不可跑（JNI） | 节点图构建逻辑纯单测；`calculateLayout` 走 androidTest |
| 系统字体 / CJK 加载未知 | spike 验证；最坏方案为捆绑字体回退（APK 体积代价见下行） |
| APK 体积 +20-30MB（skiko 原生库等） | 如实记录；后续按需 abiFilters 裁剪 |
| 脏 HTML（作者手写/模型输出） | jsoup 容错解析 + 白名单静默丢弃（§4.3）+ HtmlBubble 兜底（§7.1），三层互备 |
| 选择/无障碍欠账 | v0 范围明示（§8），不隐瞒不假做 |

---

## 11. 许可证红线

| 依赖 | 许可证 | 结论 |
|---|---|---|
| jsoup | MIT | 允许商用闭源链接，无传染性 |
| Yoga | MIT | 同上 |
| skiko | Apache-2.0（其捆绑的 Skia 为 BSD-3 + 部分组件许可） | 同上，Apache-2.0 专利条款对闭源链接友好 |

- 全部新依赖**允许商用闭源链接、无传染性**，与现有依赖共存无冲突（现有：
  onnxruntime MIT、sqlite-android BSD-like、Rhino MPL-2.0）。
- 新依赖登记进本文档即可，无需改变现有 LICENSE 惯例；不存在 ST（AGPL）类红线对象。

---

## 12. 与既有架构的关系

- **扩展平台**（[EXTENSION_PLATFORM_DESIGN.md](EXTENSION_PLATFORM_DESIGN.md)）：主题**不是扩展**，
  走主题包通道（`ThemePackRepository` zip 制品），不进扩展权限模型。未来 MVU 兼容器/脚本层
  走扩展平台；主题引擎反过来为扩展事件订阅提供渲染面（变量更新 → 引擎重渲染即 §6.2 链路）。
- **状态内核**（后续纪要）：v0 绑定直读现有 MVU KV 是**过渡桥**（§6.2）；状态内核落地后，
  `BindingResolver` 数据源替换为 Schema 树，**绑定协议（data-bind 语法）不变**。
- **HTML 卡片**（[WEB_CONTENT_RUNTIME_DESIGN.md](WEB_CONTENT_RUNTIME_DESIGN.md)）：WebView 沙箱
  保留为**兜底与复杂卡路径**（JS 交互型卡片永远走 WebView）；纯展示型卡片逐步由引擎接管
  （降级路由即两者的分界，§7）。**冻结注记（2026-11-26）**：「逐步接管」暂停，引擎已冻结，
  见文档开头状态块。
