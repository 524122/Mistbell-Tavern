# 水墨小说（布局主题示例）

M4/M6 示例主题包：演示"主题三件套"（manifest.json + template.html + style.css）中
`capabilities: "layout"` 的布局主题——消息内容的排版结构由模板声明、观感由样式表声明，
由原生渲染引擎（skiko/Yoga）绘制。纯数据包，无任何可执行语义。

## 包结构

```text
theme-ink-novel/
├── manifest.json    # 包描述：capabilities=layout、templateVersion=1（v0 冻结值）
├── theme.json       # 颜色 tokens（宣纸/夜墨两套；样式表经 --mt-* 取用，深浅色自动联动）
├── template.html    # 模板：div.msg 结构 + narrative/panels 两个 slot
├── style.css        # 样式：水墨纸感（serif 字族、宽行距、--mt-* token）
└── README.md        # 本文件（不参与打包也可以）
```

## 模板协议速览

- `<slot name="narrative">`：承接消息里的 Markdown 叙述段。
- `<slot name="panels">`：承接消息里的 HTML 卡片段（```html 围栏 / 裸 HTML）。
- 白名单外元素/属性被引擎静默丢弃（详见 `docs/THEME_FRAMEWORK_DESIGN.md` §4）。
- 文本节点可用 `{{char}}` 宏与 `{{state.路径}}` 状态插值（MVU 变量）。

## 打包与导入

1. 把本目录（除 README.md 外）打成一个 zip：zip 根必须直接是四件套
   （manifest.json / theme.json / template.html / style.css），不要多套一层目录。

   ```bash
   # 在 samples/theme-ink-novel/ 目录下执行（导入按 zip 根的文件名扫描，目录名会被忽略）：
   zip -r ../theme-ink-novel.zip manifest.json theme.json template.html style.css
   ```

   Windows 资源管理器：选中 `manifest.json`、`theme.json`、`template.html`、`style.css`
   → 右键"压缩为 ZIP 文件"，重命名为 `theme-ink-novel.zip`。

   注：`theme.json`（颜色 tokens）目前是导入的必备文件（与存量 cosmetic 包同一校验链）；
   布局主题可以不实际使用其色板（样式表用回退值），但文件必须在。

2. 打开应用：**聊天页 → 右上角设置 → 聊天设置 → 主题**，导入 `theme-ink-novel.zip`
   （同 id 覆盖安装；导入即校验 manifest/theme.json/模板存在性）。
3. 启用主题：在**会话**或**角色**上把主题指到「水墨小说」，或设为全局主题
   （应用链：会话 → 角色 → 全局）。
4. 确认 **聊天设置 → 原生渲染引擎（布局主题）** 开关为开（默认开）。
5. 任意一条含叙述文本/HTML 卡片的消息即以水墨排版渲染；关闭开关立即整条
   回到原有渲染（legacy），无重启。
