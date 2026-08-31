URL: https://docs.tavoai.dev/cn/guides/tool-calling/
STATUS: 200

用户手册

复制本页

工具调用

自 v1.0.0 起

Tavo 可以向兼容的聊天模型提供内置 TavoJS 与原生应用能力目录，包括文件存储、tavo_web_fetch 和 tavo_web_search 等能力。原生协调、服务商协议处理、工具发现和审计记录在 Dart 中运行，TavoJS 业务工具则在当前聊天的 WebView 中执行。原生 Dart 工具要求兼容的服务商 codec、全局工具使用开关、相关工具所需的完整默认连接和原生网络传输。只有 WebView 承载的 TavoJS、角色脚本、消息脚本和插件界面需要高级渲染。即使切换到其他聊天，原聊天的回复也可以继续运行。

支持的协议包括 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 和兼容 Gemini generateContent 的接口。工具使用默认关闭。

启用工具

打开 设置。

进入 工具。

开启 工具使用。

建议保持 动态加载工具 开启，除非当前模型无法可靠使用 tavo_tool_search。

开启动态加载后，第一次模型请求会先包含 tavo_tool_search，然后包含常驻的业务工具 tavo_ask_user 和 tavo_web_fetch。配置完整的网页搜索 API 还会加入 tavo_web_search。关闭动态加载后，第一次请求就会发送全部内置工具。如果服务商拒绝工具请求，Tavo 会直接显示错误，不会静默改成无工具重试。

每一轮模型请求只要包含工具，就会收到一段简短的通用提示，要求在可见工具能够直接完成请求时使用该工具。tavo_tool_search、extension_tool_search 和 tavo_ask_user 的具体使用指引只保存在各自的工具 description 中。没有工具的轮次不会收到工具使用提示。

发现工具

tavo_tool_search 搜索尚未显示的 Tavo 内置工具，extension_tool_search
搜索已安装插件或已连接 MCP 服务提供的工具。匹配的工具会在下一轮变为可见并可调用。

参数
必填
类型
说明

query
是
string
用英文描述所需能力，最多 256 个 Unicode 标量值。

limit
否
integer
返回 1 到 8 个匹配项，默认为 8。

```
{
  "tool": "tavo_tool_search",
  "arguments": {
    "query": "list files in the current chat",
    "limit": 5
  }
}
```

可用能力

内置工具包括：

变量、消息和当前聊天

角色、用户身份、聊天主题、预设、世界书和正则

记忆、图片生成和 TTS 播放

输入框编辑和用户提问

通过 tavo_file_save、tavo_file_load、tavo_file_delete、tavo_file_exists 和 tavo_file_list 使用聊天与全局文件存储

配置完成后，使用 tavo_web_search 搜索当前网页来源摘要

使用 tavo_web_fetch 将 HTTP(S) 页面读取为 Markdown

完整的公开 TavoJS 函数签名和示例请参阅 TavoJS API。本页主要说明模型侧更严格的 schema 与运行行为，不重复列出每个 TavoJS 操作。

同一次模型响应里的调用会按服务商返回的顺序执行。各写操作会在自身契约要求时使用 Tavo 的确认设置。调用上限和超时时间可在 设置 > 工具 > 执行设置 中调整。

聊天主题提供 all、get、find、create、update、import、export 和 delete 工具。导入、导出使用当前聊天的 files/chat/*.thm 路径。同名导入会询问覆盖或另存，官方主题只读。更新或重新绑定当前主题时，WebView 会实时更新配置，不刷新页面。

主题 create/update schema 会明确列出气泡、字体、背景、思考/状态标签、输入区和头像的全部合法嵌套字段。气泡填充使用 color，气泡文字颜色位于 userBubbleFont.textStyle.color 或 characterBubbleFont.textStyle.color，输入区背景和文字分别使用 color 与 fontColor，无需先读取现有主题猜测字段名。

tavo_file_import 和 tavo_file_export 仍是只在前台运行的 TavoJS/插件交互，不提供给模型 Tool Calling 或无界面运行时。tavo.file.url 仍是受信任的 TavoJS 辅助方法，没有对应的模型工具。tavo_utils_toast、tavo_utils_open_url、tavo_utils_export、tavo_utils_preview、tavo_utils_select、tavo_app_version 和 tavo_app_version_number 在适用情况下仍可供现有的受信任 TavoJS 调用方使用，但已从模型 Tool Calling 中移除。tavo_javascript_eval 不受支持。未来的 Programmatic Tool Calling 是独立基础设施，不会复用已删除的运行时。

正式回复及其原生工具循环由应用级生命周期持有，不再依赖当前聊天页面，因此切换聊天后仍可继续。���型请求若需要确认或其他平台交互，只会等待原聊天的界面，不会跨聊天弹出，并且等待时间不计入命名工具超时。这种生命周期分离无法跨应用进程重启恢复，不会在 WebView 销毁后维持 TavoJS Promise，也不等同于移动端后台执行或无界面 WebView 插件运行时。

角色写入支持裸 CC 兼容 data、完整 CCv2/CCv3 wrapper 和 SillyTavern 角色 wrapper。
世界书写入支持 CCv3 data、独立 lorebook_v3、SillyTavern World Info 和
Tavo 原生条目。预设和正则导入接受 SillyTavern 导出格式，create/update 使用
Tavo 原生字段。嵌套校验错误会指出 preset.entries[0].enabled 或
regex.entries[0].placements[0] 等路径，缺失的预设和正则目标返回
resource_not_found。长期记忆更新接受 memory.current 返回的对象，也接受只包含
enabled 和/或 memories 的部分对象。memory.append 可在不替换现有内容的情况下
追加一条或多条非空字符串，并且不会自动开启记忆注入。关闭长期记忆只会停止向提示词
注入记忆，不会从工具列表隐藏记忆管理工具。公开 TavoJS 和插件继续保持现有文件 API
的返回与软失败行为。
图片生成工具明确描述尺寸、宽高比、负面提示词、参考图、服务商扩展请求字段和产物
存储选项。嵌套参数错误会指出 options.referenceImages[0] 等路径。未配置图片端点
返回 resource_not_found，服务商拒绝返回 permission_denied，其他生成或产物写入
失败返回 internal_error，且不会暴露服务商响应正文或物理路径。成功生成的图片会直接
显示在对应工具调用之后，不再发送单独的 artifact 提示词。模型调用 TTS 时
必须显式选择角色或用户身份语音。目标或语音绑定不存在时返回
resource_not_found，播放启动失败时返回 internal_error。公开消息脚本仍可使用
当前消息说话人的语音回退。
变量名必须是非空路径，作用域可以明确选择聊天、全局、最新消息或指定消息。资源
get 和 delete 接受正整数 id 或 { "id": ... } 对象。find 要求非空名称，
支持 exact、contains、prefix 和 suffix，并拒绝未知的 selector 或 option
字段。get/delete 目标或消息变量写入目标不存在时返回 resource_not_found。输入框工具
只接受字符串，只编辑草稿而不会发送消息；意外的草稿访问失败返回不含内部细节的
internal_error。公开 TavoJS 和插件继续保持原有兼容行为。
模型调用成功时返回 { "ok": true, "result": ... }，失败时返回
{ "ok": false, "error": { "code": "...", "message": "...", "details": {...} } }，
没有结构化诊断时省略 details。校验详情可能包含 path、expected 和
actualType。角色卡和世界书导入内容中的值与 key 是用户可见内容，可能出现在
诊断信息中。这个模型错误结构不会改变部分公开或插件 TavoJS 调用的兼容软返回值。

使用已存文件

五个非 UI 文件工具在原生层运行，不需要高级渲染。聊天作用域为默认值，并绑定到发起当前回复的会话；即使回复运行期间切换聊天，也不会改变目标。全局作用域跨聊天共享，只应在明确需要跨聊天时使用。保存和读取支持 utf8、base64 和 dataUrl。保存同名文件会覆盖。保存和删除会直接执行，不弹出确认框；删除不存在的文件返回 resource_not_found。

工具
必填参数
可选参数

tavo_file_save
name、content
options.scope、options.encoding

tavo_file_load
name
options.scope、options.encoding

tavo_file_delete
name
options.scope

tavo_file_exists
name
options.scope

tavo_file_list
无
options.scope、options.limit、options.cursor

name 是不含路径分隔符、冒号或父目录片段的单个文件名。options.scope 默认为 chat，明确需要跨聊天时可设为 global。保存和读取的 options.encoding 默认为 utf8，也可使用 base64 或 dataUrl。

```
[
  {
    "tool": "tavo_file_save",
    "arguments": {
      "name": "notes.txt",
      "content": "由模型工具保存",
      "options": { "scope": "chat", "encoding": "utf8" }
    }
  },
  {
    "tool": "tavo_file_list",
    "arguments": { "options": { "scope": "chat", "limit": 10 } }
  },
  {
    "tool": "tavo_file_load",
    "arguments": { "name": "notes.txt" }
  },
  {
    "tool": "tavo_file_exists",
    "arguments": { "name": "notes.txt" }
  },
  {
    "tool": "tavo_file_delete",
    "arguments": { "name": "notes.txt" }
  }
]
```

tavo_file_list 接受可选 options：scope、范围为 1 到 200 的 limit（默认 100），以及上一页返回的不透明 cursor。结果包含 files 和可选 nextCursor。每个文件包含 path、name、字节数 size、mimeType 和 UTC modifiedAt。文件名按区分大小写的顺序排列。分页读取实时存储而不是快照，cursor 只能在原作用域继续使用。Agent 应先检查大小和 MIME 元数据，再决定是否读取大型或二进制文件。

常规的单结果 64 KiB 和单次回复累计 256 KiB 限制仍然生效。超大的文本读取仍成功返回 UTF-8 安全前缀，并在 Tavo 的结果状态中标记为已截断。文件列表等超大结构化结果会变成 { "truncated": true, "preview": "..." }。累计预算无法容纳下一个结果时，调用返回 result_too_large。

搜索网页

tavo_web_search 搜索当前来源并返回简洁摘要。要启用它，请打开 设置 > 工具 > 网页搜索 API，添加 Tavily API 并填写你自己的 Tavily API 密钥。Tavo 不提供共享的 Tavily 密钥。查询和筛选条件会从你的设备直接发送到 Tavily，不经过 Tavo 代理。你可以选择基础、高级、快速或极速搜索深度、每个来源 1 到 3 个片段，以及 1 到 20 条默认结果。极速搜索不会发送每个来源片段数。保存时只在本地校验字段，不提供“测试连接”，也不会发出其他网络请求。

你可以保存多个网页搜索 API。只要列表非空，就始终恰好有一个默认 API，并且只有该默认 API 可以执行。默认 API 不完整时，网页搜索工具会被隐藏，不会回退到其他已保存 API。Tavily 使用固定的官方 API 地址。

网页搜索面向原生 Android、iOS 和 macOS。发布构建验证会单独跟踪，本文不作已验证声明。Linux 和 Windows 使用可移植的原生网络传输，但仍不在当前验证矩阵内。Flutter Web 不受支持，也没有浏览器请求路径。

模型必须提供查询内容，也可以选择 1 到 20 条结果、通用/新闻/财经主题、一天/一周/一月/一年时间范围，以及包含或排除的域名列表。结果只包含标准化标题、URL、摘要、可选评分或发布时间和警告，不会暴露 Tavily 原始请求、API 密钥、服务商请求标识或连接身份，也不提供 cursor 或 artifact。

参数
必填
类型
说明

query
是
string
1 到 1,000 个字符的搜索查询。

max_results
否
integer
1 到 20 条结果，省略时使用连接默认值。

topic
否
string
general、news 或 finance。

time_range
否
string
day、week、month 或 year。

include_domains
否
string array
最多 20 个需要包含的域名。

exclude_domains
否
string array
最多 20 个需要排除的域名。

```
{
  "tool": "tavo_web_search",
  "arguments": {
    "query": "Dart 3.10 release notes",
    "max_results": 5,
    "topic": "general",
    "include_domains": ["dart.dev"]
  }
}
```

网页搜索只返回来源摘要，不会自动打开或下载任何结果。模型可以另外调用 tavo_web_fetch，完整读取选中的页面。

读取 URL

tavo_web_fetch 使用 GET 读取单个 HTTP(S) 资源，并返回便于模型阅读的 Markdown。它支持 HTML、Markdown、纯文本、JSON 和 XML。Web Fetch 只读取给定 URL，不负责搜索来源，独立的网页搜索工具会返回来源摘要。Web Fetch 不执行页面 JavaScript，不使用 WebView 渲染，不读取 PDF 或其他二进制媒体，不接受代理或客户端证书配置，也不会回退到 Tavo 服务端。疑似依赖 JavaScript 的页面会返回能够从静态内容提取到的结果，内容可能为空，同时附带警告，不会额外弹出失败提示。

首次请求接受 URL 和可选的任意字符串 header，包括 Authorization、Cookie 和 API key。同源重定向会保留这些 header，跨源重定向会在继续请求前移除调用方提供的全部 header，并返回警告。公网、localhost、回环地址、内网、link-local、内部主机名和自定义端口都可以访问。在 Android 和 iOS 上，localhost 指当前移动设备本身，不是你的桌面电脑。

参数
必填
类型
说明

url
首次读取时
string
从头读取的 HTTP(S) URL。

headers
否
object
仅用于首次读取的字符串 header。

cursor
续读时
string
上一页返回的不透明 cursor，存在时优先于 url 和 headers。

首次读取：

```
{
  "tool": "tavo_web_fetch",
  "arguments": {
    "url": "https://example.com/article",
    "headers": { "Accept-Language": "zh-CN" }
  }
}
```

续读：

```
{
  "tool": "tavo_web_fetch",
  "arguments": { "cursor": "<next_cursor>" }
}
```

长文档每次返回约 24 KiB，并提供不透明的续读 cursor。如果模型同时重复传入 URL 或 header，cursor 优先。续读使用同一个缓存快照，不会再次下载页面。cursor 无效或过期时直接失败，不会回退到 URL。cursor 和最多 24 MiB 的缓存只在当前回复期间存在。

默认上限为压缩传输 20 MiB、解压后 40 MiB、HTML 输入 25 MiB、单份文档提取后的 Markdown 10 MiB，以及最多 5 次重定向。现有工具超时和结果大小限制仍然生效。

提取出的 Markdown 会发送给当前聊天所配置的模型服务商。Tavo 的有界本地审计会保留完整提交 URL，包括 query 和 fragment，也会保留所有 header 名称和值，包括凭据。结果审计只保存最终 URL、HTTP 状态、MIME、内容字节数、耗时和警告码等元数据，不保存抓取正文、页面标题、续读 cursor 或请求 header。

向你提问

tavo_ask_user 的工具 description 会告诉模型：如果信息缺失、请求存在歧义，或需要你的确认和偏好且答案会实质影响结果，应调用该工具，而不是自行猜测或只在普通文本里追问。适合时模型应提供简洁选项，默认仍允许自定义输入；能够安全推断的次要细节则不应打断你。

常驻的 tavo_ask_user 工具可以暂停回复，并提供三种交互方式：

只提供 question 时，显示纯文本问题

提供推荐选项并保留自定义文本输入框，因为 allowOther 默认为 true

设置 allowOther: false 时，只允许选择给定选项

选项可以是非空字符串，也可以是包含 value、label 以及可选 description、meta 的对象。meta 用于提供选项的简短补充信息。问题、选项值和标签不能为空，标准化后的选项值不能重复，未知字段会被拒绝。严格模式必须提供选项，如果设置了 defaultValue，它必须匹配其中一个选项。placeholder 可自定义文本输入框提示。

参数
必填
类型
说明

question
是
string
显示给用户的非空问题。

options
否
array
非空字符串，或 { value, label, description?, meta? } 对象。

allowOther
否
boolean
是否允许自定义文本，默认为 true。

placeholder
否
string
自定义输入框提示。

defaultValue
否
string
初始选项值或自定义文本，严格模式下必须匹配一个选项。

```
{
  "tool": "tavo_ask_user",
  "arguments": {
    "question": "文件应该保存到哪个作用域？",
    "options": [
      { "value": "chat", "label": "当前聊天" },
      { "value": "global", "label": "所有聊天" }
    ],
    "allowOther": false,
    "defaultValue": "chat"
  }
}
```

点击选项会立即返回。自定义文本会去除首尾空白，并且必须显式提交。回答结果为 {"status":"answered","answer":"concise","source":"option"}，自定义文本使用相同结构和 source: "custom"。关闭问题会成功返回 {"status":"cancelled"}。Ask、确认框或选择器弹出后，等待你的时间不计入工具超时；弹窗会一直等待，直到你操作、停止生成或对应运行时失效。

旧的 tavo.utils.select(options, title?, defaultValue?) TavoJS API 仍可供受信任的直接调用方使用，但不再提供给模型 Tool Calling，因为 tavo_ask_user 已覆盖开放回答和严格选项两种交互。

切换聊天与确认

当工具需要确认时，来源聊天的回复会暂停并释放执行槽。等待期间可以切换聊天。Tavo 会在来源聊天上显示徽标，返回该聊天后即可允许、拒绝或取消操作。

目前这种续接只保存在内存中。如果回复已经进入工具协议或正在等待确认时 App 进程退出，Tavo 会在冷启动时取消该回复，不会重新播放模型轮次或重复执行副作用。当前版本尚未实现 iOS 和 Android 后台运行。

高级渲染与插件

原生 Dart 工具（包括 tavo_web_fetch、tavo_web_search 和 tavo_ask_user）不要求高级渲染，但仍要求兼容的服务商 codec、全局工具使用开关、相关工具所需的完整默认连接和原生网络传输。TavoJS 业务工具目录需要高级渲染和当前聊天的活跃 WebView。关闭高级渲染会保留工具使用设置，但会移除 WebView 承载的工具定义，直到 WebView 再次可用。角色卡脚本、消息脚本、插件 UI 和插件生命周期钩子同样由 WebView 承载。

插件目前还不能贡献模型工具。未来的插件工具系统会使用明确的 WebView executor 和 runtime readiness 契约，不会隐式混入内置 Dart runtime。外部 MCP Server 也尚未接入聊天内工具调用。

隐私与日志

聊天消息只保留精简的业务工具摘要。参数、结果、耗时和执行状态保存在辅助日志中，可通过 设置 > 存储空间 > 日志 清理，不会删除消息里的精简摘要。Web Fetch 记录采用上文说明的更窄本地投影。网页搜索日志可以保留有界查询和标准化摘要，但不会保留连接密钥或仅供服务商使用的载荷。

预览能力只保留给受信任的直接 TavoJS 调用，不提供给模型 Tool Calling。

高级前端渲染（Web）

开启 `高级前端渲染` (Advanced Rendering，以下简称 AR) 可以让聊天页面渲染标准的 HTML 与 CSS，以支持非常强大且灵活的页面美化。

TavoJS API

TavoJS API 是面向玩家及创作者提供的一套 JavaScript 接口，以方便用户在开启 JavaScript 支持时可以获得强大的功能和高可玩性。