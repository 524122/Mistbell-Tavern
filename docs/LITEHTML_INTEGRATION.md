# litehtml 对接说明

状态：已接入聊天气泡。只画不含脚本的 `style` / `table`。脚本卡片仍走 WebView。

上游：litehtml v0.10，提交 `9bc84b8b8d15a4e50f18b327aa30955048b441c2`，BSD-3-Clause。捆绑的 gumbo 为 Apache-2.0。源码在 `third_party/litehtml`，嵌套的 `.git` 已去掉，由本仓库直接跟踪。

关联文档：[主题框架设计](THEME_FRAMEWORK_DESIGN.md)（skiko + Yoga 的主题包引擎）、[Web 内容运行时](WEB_CONTENT_RUNTIME_DESIGN.md)（脚本卡片的 WebView）。

litehtml 只解析 HTML/CSS 并给出布局。它不画像素，也不执行 JavaScript。像素由 `AndroidContainer` 调 `LiteHtmlPaint`，画到 Android `Canvas` 上。

---

## 1. 三条路径

同一条消息先按 `HtmlCardDetector` 拆段，再决定内容体。气泡壳、长按菜单、横滑仍是 Compose。

| 内容 | 路径 | 结果 |
|---|---|---|
| 主题包白名单 HTML（div/span/p 等，且主题引擎开启） | `resolveRenderPath` → `EngineMessageContent` | skiko + Yoga 位图 |
| 含 `<style>` 或 `<table>`，且没有脚本运行面 | `LiteHtmlPolicy.isLayoutHtml` → `LiteHtmlMessage` | litehtml 位图 |
| `<script>`、`<iframe>`、`<object>`、`<embed>`、`on*` 事件、`javascript:` | `LiteHtmlPolicy.requiresScriptRuntime` → `HtmlBubble` | 现有 WebView。HTML 界面开关关闭时不执行，按原文显示 |

补充（2026-11-26）：脚本路径的启用条件有两项。`renderHtml = htmlUiEnabled && htmlHost != null && htmlParts 有 HtmlDoc 段`（`app/src/main/java/com/mistbell/tavern/android/ui/chat/MessageBubble.kt:178-182`），所以 HTML 界面开关关闭**或没有桥宿主（`htmlHost == null`）**时，脚本卡片都不执行，按原文显示（交给 `MessageMarkdown` 原样排字）。上表「HTML 界面开关关闭时不执行」只说了其中一项。

`style` 和 `table` 在主题引擎里属于危险元素，整段会被丢掉。所以 `resolveRenderPath` 仍把这种消息判为 Legacy，再由 Legacy 交给 litehtml。不要为了画表格去放宽 `TemplateParser` 的白名单。

主题引擎关闭时，只要片段被判定为布局 HTML，仍然走 litehtml，不依赖 HTML 界面开关。

HTML 界面开关**开启**时，判定顺序同样是先 litehtml、后 WebView：legacy 内容体里每个 HTML 段先判 `LiteHtmlPolicy.isLayoutHtml(part.html)`，为真就走 `LiteHtmlMessage`，`else if (renderHtml)` 才进 `HtmlBubble`（`app/src/main/java/com/mistbell/tavern/android/ui/chat/MessageBubble.kt:316-347`）。所以即使 `html_ui_enabled` 开启，含 `<style>` 的叙事拆段也走 litehtml 而不是 WebView——进 WebView 的分界是脚本运行面（`LiteHtmlPolicy.requiresScriptRuntime`，`LiteHtmlPolicy.kt:23`），不是布局 HTML。上文「主题引擎关闭时不依赖 HTML 界面开关」只写了开关关闭的一侧，此条补上开关开启的一侧（2026-11-26）。

自绘引擎已冻结：见 [THEME_FRAMEWORK_DESIGN.md](THEME_FRAMEWORK_DESIGN.md) 开头的状态块与 [ROADMAP.md](ROADMAP.md) 二、P1「自绘引擎冻结」。litehtml 自身「保持现状、不扩功能」，已知限制仍以 §8 为准（2026-11-26）。

---

## 2. 消息怎么拆

`HtmlCardDetector.split` 的顺序：

1. 没有 HTML 特征：整段 Markdown。
2. 以 `<!DOCTYPE html` 或 `<html` 开头、且没有围栏：整段一个 HTML 文档。
3. 含 `<style>` 或带 `class="` 的 `span`/`div`，且没有围栏：按 `<style>` 切成叙事文档。样式块合并进共享 `<head>`。
4. 只含闭合的 `<table>…</table>`，且没有围栏：表格单独成段，前后文字仍是 Markdown。未闭合的 `<table>` 留在 Markdown 里。
5. 其余：按 ` ```html ` 围栏拆。

有围栏时不走第 3、4 步，避免把围栏里的示例拆碎。

---

## 3. Kotlin 调用

```kotlin
val bitmap = LiteHtmlRenderer.render(
    html = html,
    widthCssPx = widthCssPx,
    density = density,
)
```

- `widthCssPx` 是 CSS 像素，与 dp 1:1。气泡里用 `圆整(可用像素 / density)`，至少 1。
- `density` 只决定位图像素：`像素 = ceil(cssPx * density)`。画布先 `scale(density, density)`，litehtml 的坐标保持 CSS 像素。
- 返回 `ARGB_8888` 位图，背景透明。调用方用 `Image` + `ContentScale.FillWidth` 贴上。
- 库在第一次碰到 `LiteHtmlRenderer` 时 `System.loadLibrary("litehtmlbridge")`，启动时不加载。
- `render` 用同一把锁串行进入 native。结果放 12MB 的 `LruCache`，键是 `宽度|density|html`。缓存不 `recycle`，避免 Compose 还在显示时位图被释放。
- 渲染抛 `RuntimeException` 时，`LiteHtmlMessage` 把原文留在气泡里，不改走 WebView。

气泡文字色由 `LiteHtmlPolicy.withBubbleTextColor` 写成 `<style>body{color:#RRGGBB}</style>`，插在作者样式之前。作者的 `p{color:red}` 仍能覆盖。没有 `<head>` 时这段样式放在文档最前面。

---

## 4. 容器契约

`AndroidContainer` 实现 `litehtml::document_container`。`JNIEnv` 只在一次 `nativeRender` 里有效，不能留到别的线程。

| 项目 | 约定 |
|---|---|
| 默认字体 | 16px，`sans-serif`。度量来自 Android `Paint`，API 28+ 用可变字重，否则字重 ≥ 600 当粗体 |
| `pt` | `pt * 96 / 72` |
| 文本基线 | `pos.y + font.ascent` |
| 视口高度 | 常量 `100000`，给媒体查询用 |
| `resolution` | `96 * density` |
| 语言 | `zh` / `zh-CN` |
| `create_element` | 返回空。不接自定义元素 |
| `import_css` | 清空文本。不加载外部样式表 |
| 图片 | `load_image` / `draw_image` 空操作，`get_image_size` 为 0 |
| 链接点击 | 空操作。litehtml 位图上没有点击命中 |
| 边框 | 只描一条。优先用上边，上边宽度为 0 再用左边。`none` / `hidden` 跳过 |
| 裁剪 | 只按矩形 `save` / `restore`，圆角裁剪参数不用 |
| 渐变 | 线性、径向、锥形走 Android shader，裁到圆角盒里 |
| 列表标记 | `square` 填矩形，其他非 `none` 画椭圆 |

`LiteHtmlPaint` 的方法必须是 `@JvmStatic`，签名与 `android_container.cpp` 里的 `GetStaticMethodID` 逐字一致。查找失败要停，不能在异常还挂着时继续调 JNI，否则进程会被系统中止。`fillSweep` 是 7 个 float：`left, top, right, bottom, radius, cx, cy`，再加颜色数组和停止点数组。

绘制回调里的 Java 异常要清掉，让一次绘制失败不打断整页布局。

Release 混淆必须保留这两个类，见 `app/proguard-rules.pro`：

```
-keep class com.mistbell.tavern.android.render.litehtml.LiteHtmlRenderer { *; }
-keep class com.mistbell.tavern.android.render.litehtml.LiteHtmlPaint { *; }
```

---

## 5. 构建

- NDK `28.2.13676358`，CMake `3.22.1`，C++17。
- `app/src/main/cpp/CMakeLists.txt` 编出 `liblitehtmlbridge.so`，并编进 litehtml。`LITEHTML_BUILD_TESTING` 关闭。
- 不要设置 `ndk.abiFilters`。ABI 分包已经包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 和 universal，改过滤会影响 onnx / sqlite 的分包。
- 调试对照页只在 debug 源集：`adb shell am start -n com.mistbell.tavern.android/.render.litehtml.LiteHtmlProofActivity`。它画三段固定 HTML，并把 PNG 写到应用外部目录 `litehtml-proof/`。

---

## 6. 设备上已看到的结果

模拟器 `emulator-5554`，宽度 360 CSS px：

- `<style>p{color:red}</style><p>hello-native-fallback</p>`：这行字是红色。
- 同一条 `p` 规则套在 `<div class="c">` 上：文字仍是默认色。选择器不命中就不会上色。
- `<table><tr><td>cell-a</td></tr></table>`：单元格文字单独成行，标签不会原文打出来。没有边框，也没有格子。

所以内嵌 `<style>` 可用。表格目前只完成排字，边框还不是表格线。

---

## 7. 文件

| 文件 | 作用 |
|---|---|
| `third_party/litehtml` | 上游解析和布局 |
| `app/src/main/cpp/CMakeLists.txt` | 编 `litehtmlbridge` |
| `app/src/main/cpp/litehtml_jni.cpp` | `nativeRender`：解析、布局、建位图、缩放、绘制 |
| `app/src/main/cpp/android_container.cpp` | 容器回调 |
| `app/src/main/java/.../litehtml/LiteHtmlRenderer.kt` | Kotlin 入口与位图缓存 |
| `app/src/main/java/.../litehtml/LiteHtmlPaint.kt` | 字体和 Canvas 绘制 |
| `app/src/main/java/.../litehtml/LiteHtmlPolicy.kt` | 分流与默认文字色 |
| `app/src/main/java/.../ui/chat/render/LiteHtmlMessage.kt` | 气泡内容体 |
| `app/src/main/java/.../ui/chat/htmlui/HtmlCardDetector.kt` | 拆段，含行内表格 |
| `app/src/main/java/.../ui/chat/MessageBubble.kt` | 布局 HTML 与脚本卡片分叉 |
| `app/src/debug/.../LiteHtmlProofActivity.kt` | debug 对照页 |

单测：`LiteHtmlPolicyTest`、`HtmlCardDetectorTest`、`EngineRenderPathTest`。

---

## 8. 继续改时的边界

> 更正（2026-11-26）：自绘引擎冻结期间，litehtml 保持现状、不扩功能，本节所述「继续改」暂缓；以下边界保留原文，供解冻后参考（冻结状态见 [THEME_FRAMEWORK_DESIGN.md](THEME_FRAMEWORK_DESIGN.md) 开头状态块）。defects 修复不受此限。

- 缺的布局能力改 `third_party/litehtml` 或 `AndroidContainer`，不要把 `TemplateParser` 的白名单扩成浏览器。
- 不把 `<style>` 当可执行代码。不把 `<script>`、事件处理和 `javascript:` 送进 litehtml。
- 不复制 SillyTavern 的前端代码。
- 图片、逐边边框、伪元素、阴影、点击命中都还没接。接图片时从 `load_image` / `get_image_size` / `draw_image` 三个空实现一起补，尺寸仍为 0 时表格和背景图会挤没。
