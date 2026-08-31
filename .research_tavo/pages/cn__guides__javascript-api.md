URL: https://docs.tavoai.dev/cn/guides/javascript-api/
STATUS: 200

用户手册

复制本页

🧩 TavoJS API

📖 概述

TavoJS API 是面向玩家及创作者提供的一套 JavaScript 接口，以方便用户在开启 JavaScript 支持时可以获得强大的功能和高可玩性。

插件生命周期边界： generation:prepare、generation:success、
generation:error 和 generation:cancelled 不是纯 TavoJS 或 tavo.events API。
只有已安装插件的 entry 脚本能通过 tavo.plugin.on(...) 注册；HTML 片段脚本不能注册。请参阅
插件开发。

✨ 氛围编程 (Vibe Coding) 友好

对于非专业编程玩家，我们建议将此文档复制给 AI ，然后让 AI 生成与 Tavo 结合紧密的高可玩性代码！

错误与聊天切换

被拒绝的 TavoJS 操作会以普通 JavaScript Error 失败，并可能提供稳定的
error.code 和结构化 error.details。聊天切换期间，尚未绑定当前聊天的调用会收到：

```
error.code === &#x27;conflict&#x27;
error.details.reason === &#x27;chat_runtime_not_ready&#x27;
error.details.retryable === true
```

这表示该次请求没有执行，可在当前聊天的新 runtime ready 后重新发起，并不表示
Tavo 会自动重试。插件 entry 脚本应等待新聊天的 chat:opened 后重新运行相关
逻辑，不要在旧 runtime 中立即循环重试。其他脚本应放弃本次调用，等待下一次生命周期
执行。

⚙️ 变量

变量用于存储数据，JavaScript 本身的变量只能存活于页面中，一旦刷新就会丢失，因此我们提供一组变量 API 来协助用户长期存储数据。

获取变量

tavo.get(<name>[, <scope>])

例如：

```
let age = tavo.get(&#x27;age&#x27;)  // 获取聊天变量中的 age
let bestScore = tavo.get(&#x27;bestScore&#x27;, &#x27;global&#x27;)  // 获取全局最高分
let hp = tavo.get(&#x27;hp&#x27;, &#x27;message&#x27;)  // 获取当前消息（楼层）上的 hp
```

作用域 (scope)

作用域是变量可用范围，我们支持以下几种：

chat: 聊天作用域，这是默认的作用域。变量仅在当前聊天内可访问，你应该总是优先考虑此作用域（可随聊天导出）

global: 全局作用域。变量将可以跨对话访问与保存（因此也需要格外小心命名冲突）

message: 消息作用域。变量挂在单条消息（楼层）上，随消息删除一并消失（详见下方「消息作用域」一节）

⚠️ 不同作用域中的变量完全不通，不存在跨作用域覆盖。

设置变量

tavo.set(<name>, <value>[, <scope>])

例如：

```
tavo.set(&#x27;age&#x27;, 16)  // 设置聊天变量 age = 16
tavo.set(&#x27;Lily_lover&#x27;, &#x27;Colin&#x27;, &#x27;global&#x27;)  // 设置全局变量，Lily 的爱人为 Colin
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 32, location: &#x27;Cave&#x27; })  // 设置当前聊天的状态为：生命值 100，魔法值 32，地点 洞穴
```

更新变量

tavo.update(<name>, <value>[, <scope>])

与 tavo.set(...) 最大的不同，是允许对 object 值进行部分更新，例如：

```
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 32 })  // status = { hp: 100, mp: 32 }
tavo.update(&#x27;status&#x27;, { hp: 70 })  // status = { hp: 70, mp: 32 }
tavo.update(&#x27;status&#x27;, { status: &#x27;poisoned&#x27; })  // status = { hp: 70, mp: 20, status: &#x27;poisoned&#x27; }
```

删除变量

tavo.unset(<name>[, <scope>])

例如：

```
tavo.set(&#x27;age&#x27;, 16)  // age = 16
tavo.unset(&#x27;age&#x27;)  // age = null
```

变量路径

当操作变量时，我们支持路径形式，例如：

```
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 50 })
tavo.get(&#x27;status.hp&#x27;)  // 100
tavo.unset(&#x27;status.hp&#x27;)  // status = { mp: 50 }
```

消息作用域

Since v0.88.0

除了 chat / global，变量还能挂到**单条消息（楼层）**上，随该消息删除一并消失。适合把状态绑定到具体某条回复——比如该楼角色的心情、HP、回合数。

scope 传字符串 &#x27;message&#x27;，指当前宿主楼层（执行此代码所在的那条消息；不在气泡环境中执行时按最后一层处理）。也可以传对象 { scope: &#x27;message&#x27;, id: n }，按消息 id（稳定主键，删楼不漂移）写到指定的某条消息。

```
tavo.set(&#x27;hp&#x27;, 100, &#x27;message&#x27;)                       // 写到当前楼层（宿主语义）
let hp = tavo.get(&#x27;hp&#x27;, &#x27;message&#x27;)                   // 读当前楼层的 hp
tavo.set(&#x27;hp&#x27;, 50, { scope: &#x27;message&#x27;, id: 2338 })   // 按消息 id 写到指定楼层
tavo.unset(&#x27;hp&#x27;, &#x27;message&#x27;)                          // 删当前楼层的 hp
```

在提示词中使用变量

可以通过提示词将变量发给模型，只需要在提示词中使用 宏 (macros) 即可：

{{getvar::<name>}} 获取变量（作用域为 chat，当前聊天）
{{getglobalvar::<name>}} 获取变量（作用域为 global，全局）

例如：

```
{{char}} 有了一个新名字 {{getvar::name}}
{{user}} 当前生命值 {{getvar::status.hp}}
全局历史最高分 {{getglobalvar::highestScore}}
```

更多的变量宏请参考 related page

💬 消息

可以通过此接口读取或改变消息，所有消息接口均为 tavo.message.<method>(...)

查找消息

await tavo.message.find(<indexRange>[, <filter>])

按照楼层范围 indexRange 和过滤器 filter 查找消息，返回值为数组，其中：

indexRange 类型 number | array: 楼层范围

当为 number 类型时：

获取指定楼层消息

楼层从 0 开始，第一条消息为 0，第二条消息为 1……以此类推

支持负数从尾部开始计算楼层， -1 为最后一条，-2 为倒数第二条……以此类推

当为 array 类型时：

[start, end] 例如 [2, 4]，会取出第 2、3、4 条记录（双侧闭区间）

[start] 代表从 start 开始到最后

[0, end] 代表从第 0 条开始到 end

[] | null | undefined 代表全部楼层

无论何种参数，总是返回数组，若指定楼层不存在，返回空数组 []

filter 类型 object: 过滤条件

role 类型 string 按角色过滤，可选值（默认为所有）：

&#x27;system&#x27; 系统消息

&#x27;assistant&#x27; 角色消息

&#x27;user&#x27; 用户消息

hidden 类型 boolean 是否包含隐藏消息，可选值（默认为所有）：

true 仅包含隐藏消息

false 仅包含非隐藏消息

characters 类型 array 角色 ID 数组，仅过滤传入的角色发出的消息

消息格式为：

```
{
  id: 2338,  // 消息 ID
  characterId: 34,  // 角色 ID（仅 assistant 消息会有）
  content: &#x27;Hello!&#x27;,  // 消息内容
  hidden: false,  // 是否是隐藏消息
  role: &#x27;assistant&#x27;  // 消息角色
}
```

例如：

```
await tavo.message.find(2)  // 获取第 3 层的消息
await tavo.message.find([3, 100])  // 获取第 3-100 条消息，若总共只有 50 层，则返回 3-50 层
await tavo.message.find(-1, { role: &#x27;user&#x27; })  // 用户发的最后一层消息
await tavo.message.find([10], { hidden: false })  // 未隐藏的消息，楼层 >= 10 的所有消息
```

获取单条消息

await tavo.message.get(<messageId>)

按消息 ID 获取单条消息，若 ID 无效或消息不存在则返回 null。

```
let msg = await tavo.message.get(2338)  // 获取 ID 为 2338 的消息
```

获取当前消息

await tavo.message.current()

获取执行此代码所在的那条消息对象，字段与上文「消息格式」及 tavo.message.get 一致。

典型用途：读取本条消息上的角色信息（tavo.character.get(currentMessage.characterId)），或是修改本条消息（调用 tavo.message.update 写回）。

```
const self = await tavo.message.current()
console.log(self)
```

获取消息总数

await tavo.message.count()

获取当前聊天中的消息总数（包含隐藏消息）。一般用来定位最后一条的楼层，第一条楼层为 0，最后一条楼层为 消息总数 - 1。

```
let lastIndex = await tavo.message.count() - 1
console.log(lastIndex)
```

追加消息

await tavo.message.append(<message>)

在当前聊天末尾追加一条消息，成功返回新消息 ID，失败返回 null。

message 类型 object，常见字段：

content 类型 string：消息内容（必填）

role 类型 string：&#x27;assistant&#x27; | &#x27;user&#x27;（默认按 &#x27;assistant&#x27; 处理），角色消息还是用户消息

characterId 类型 number：当 role = &#x27;assistant&#x27; 时可指定发言角色 ID（单聊中可不传，群聊必传）

hidden 类型 boolean：是否为隐藏消息（默认 false）

注意：

当 role = &#x27;assistant&#x27; 且未传 characterId 时，会按当前会话上下文自动推断角色

若无法推断角色，或角色不属于当前聊天，会创建失败并返回 null

例如：

```
let newId = await tavo.message.append({
  role: &#x27;assistant&#x27;,
  characterId: 34,
  content: &#x27;这是追加的一条消息&#x27;,
  hidden: false,
})
```

单聊中创建非隐藏消息时，可简化为：

```
let newId = await tavo.message.append({
  content: &#x27;这是追加的一条消息。role 默认为 assistant ，即角色消息；单聊时自动推断角色；hidden 默认为 false&#x27;,
})
```

更新消息

await tavo.message.update(<message>, <opts?>)

按消息 ID 更新一条已有消息，成功返回消息 ID，失败返回 null。

message 类型 object，常见字段：

id 类型 number：要更新的消息 ID（必填）

content 类型 string：更新后的消息内容（必填）

reasoning 类型 string：推理内容（可选，传空字符串会清空）

hidden 类型 boolean：是否隐藏（可选，默认按 false 处理）

opts 类型 object，可选字段：

reuseContext 类型 boolean：是否保留当前气泡的脚本执行环境。默认 false。

```
const lastMessage = (await tavo.message.find(-1))[0]  // 获得最后一层的消息 （参见 tavo.message.find 说明）
lastMessage.content = &#x27;更新后的内容&#x27;
lastMessage.reasoning = &#x27;可选推理内容&#x27;
lastMessage.hidden = true  // 改为隐藏消息
await tavo.message.update(lastMessage)  // 更新最后一条消息
```

reuseContext 说明

如果你的脚本调用 tavo.message.update 更新自己所在的气泡，并且希望更新后继续执行后续脚本，传 reuseContext: true：

```
// 脚本里调用，且更新的是自己所在的气泡
await tavo.message.update(self, { reuseContext: true })
console.log(&#x27;更新后继续执行&#x27;)
```

删除消息

await tavo.message.delete(<messageId>)

按消息 ID 删除消息，成功返回被删除的消息 ID，失败返回 null。

```
const count = await tavo.message.count();  // 获得总消息数
const midIndex = Math.floor(count / 2);
const midMessage = (await tavo.message.find(midIndex))[0]  // 获得中间一条消息
await tavo.message.delete(midMessage.id)  // 删除中间那条消息
```

🗨️ 聊天

可以通过此接口获取当前聊天信息，所有聊天接口均为 tavo.chat.<method>(...)

获取当前聊天

await tavo.chat.current()

获取当前正在进行的聊天信息，若当前没有聊天则返回 null。

例如：

```
let chat = await tavo.chat.current()
console.log(chat.name)        // 打印当前聊天名称
console.log(chat.characters[0]?.name)  // 打印第一个角色名称
console.log(chat.persona?.name)        // 打印当前用户身份名称（若有）
```

异步 await/async
TavoJS API 中，除变量操作外，几乎所有的 API 接口都需要使用异步调用。
异步调用就是调用时在前面写上 await，例如 let chat = await tavo.chat.current()，如果你忘了 await 写成了 let chat = tavo.chat.current()，就会出错（可以在侧边栏的 JavaScript 控制台中查看日志）。
而 await 只能在 async 函数（或模块顶层）中使用，比如：

```
async function demo() {
  let chat = await tavo.chat.current();
}
```

同样如果忘了在 function 前写 async 但函数内部又调用了 await 也会报错噢！

简言之：除变量操作外，所有 TavoJS API 调用前面必须加 await，而调用的函数要用 async 声明。

更新当前聊天

await tavo.chat.update(<chat>)

更新当前聊天。

可更新字段：

name：聊天标题

characters：角色 ID 或 { id } 数组（会直接替换当前聊天角色列表）

persona：用户身份 ID 或 { id }

preset：预设 ID 或 { id }

lorebooks：世界书 ID 或 { id } 数组（传 [] 清空）

regexes：正则 ID 或 { id } 数组（传 [] 清空）

background：聊天背景（会话级覆盖，不影响主题；详见下方）

theme：聊天主题 ID、{ id }，或 null（清除会话绑定并回落默认主题）

responseMode：回复模式，推荐传 natural、everyone、manual 或 scenario；也兼容索引 0–3

allowSelfResponses：是否允许角色消息之后继续触发角色回复

overrideScenario：当前聊天的场景覆盖，传空字符串清除覆盖

```
await tavo.chat.update({
  name: &#x27;新的聊天标题&#x27;,
  characters: [12, 34],
  persona: 5,
  preset: 9,
  lorebooks: [17],
  regexes: [3],
  responseMode: &#x27;scenario&#x27;,
  allowSelfResponses: true,
  overrideScenario: &#x27;由主持人根据当前线索决定下一位发言者。&#x27;,
})
```

注意：该接口仅更新“当前聊天”，不支持按聊天 ID 更新其他会话。

设置聊天背景 background

会话级背景覆盖独立于聊天主题，不会改动或创建主题。三种来源互斥，优先级为 useAvatar > image > color：

```
// 使用图片背景（URL，或 tavo.file.save 返回的相对路径，如 &#x27;files/chat/bg.png&#x27;）
await tavo.chat.update({ background: { image: &#x27;files/chat/bg.png&#x27;, opacity: 0.85 } })

// 使用当前角色头像作为背景
await tavo.chat.update({ background: { useAvatar: true } })

// 使用纯色背景（hex）
await tavo.chat.update({ background: { color: &#x27;#222222&#x27; } })

// 清除会话级覆盖，回落到主题背景
await tavo.chat.update({ background: null })
```

image 类型 string：图片背景。支持 http(s):// 网图，或相对当前文档目录的本地路径（推荐配合 tavo.image.generate + tavo.file.save 落盘后传入）。不要传绝对路径。

useAvatar 类型 boolean：为 true 时使用当前会话角色头像作为背景。

color 类型 string：&#x27;#RRGGBB&#x27; 或 &#x27;#AARRGGBB&#x27; 纯色背景。

opacity 类型 number（0-1）：图片不透明度，仅图片背景有意义。

传 background: null 清除覆盖；不传 background 键则不改动。

聊天对象字段

聊天对象（current 返回）包含以下常见字段：

```
{
  id: 1,                    // 聊天 ID
  name: &#x27;与爱丽丝的对话&#x27;,    // 聊天名称
  characters: [             // 聊天中的角色概要列表
    {
      id: 12,
      name: &#x27;Alice&#x27;,
      avatar: &#x27;alice.png&#x27;
    },
    {
      id: 7,
      name: &#x27;Lee&#x27;,
      avatar: &#x27;lee.png&#x27;
    },
  ],
  persona: {                // 当前使用的用户身份概要（可能为 null）
    id: 5,
    name: &#x27;默认用户身份&#x27;,
  },
  preset: {                 // 当前使用的预设概要
    id: 9,
    name: &#x27;默认预设&#x27;,
  },
  lorebooks: [{
    id: 17,
    name: &#x27;不夜城&#x27;,
  }],
  regexes: [{               // 当前启用的正则概要列表
    id: 3,
    name: &#x27;移除舞台提示&#x27;,
  }],
  background: {             // 会话级背景覆盖；无覆盖时为 null
    image: &#x27;files/chat/bg.png&#x27;,
    opacity: 0.85,
  },
  theme: {                  // 当前实际生效的主题概要
    id: 6,
    name: &#x27;夜色&#x27;,
  },
  responseMode: &#x27;scenario&#x27;, // natural | everyone | manual | scenario
  allowSelfResponses: true,
  overrideScenario: &#x27;由主持人根据当前线索决定下一位发言者。&#x27;,
}
```

🎨 聊天主题

通过 tavo.theme 管理完整的 ChatTheme 主题库：

```
const themes = await tavo.theme.all()
const theme = await tavo.theme.get(themes[0].id)
const matches = await tavo.theme.find(&#x27;夜色&#x27;, { match: &#x27;contains&#x27; })
const id = await tavo.theme.create({ name: &#x27;夜色副本&#x27;, background: { color: &#x27;#ff10131a&#x27; } })
await tavo.theme.update(id, { console: { blur: 18 } })
const exported = await tavo.theme.export(id)
await tavo.file.export(exported.path)
await tavo.theme.import(exported.path)
await tavo.chat.update({ theme: id })
await tavo.theme.delete(id)
```

create 接受完整或部分主题对象，update 接受相同结构的递归 patch。嵌套字段使用严格白名单：

userBubble、characterBubble：color、blur（0–100）、radius（0–50）、alignment（left 或 right）。气泡填充色使用 color，不支持 background、backgroundColor 或边框字段。

userBubbleFont、characterBubbleFont：textStyle、toneTextStyle、quoteTextStyle，以及 toneHighlight、toneDelimiters、toneSymbol、quoteHighlight、quoteDelimiters、quoteSymbol。文字样式使用 fontFamily、fontSize、fontWeight、fontStyle 和 color。

background：useAvatar、image、opacity、color。

thinking、statusBar：fontSize、fontWeight、color、backgroundColor。

console：color、blur、radius、sendColor、fontSize、fontWeight、fontColor、placeholderColor。

四种头像样式：avatar、radius、name。

颜色必须是 8 位 #AARRGGBB。例如：

```
await tavo.theme.create({
  name: &#x27;暗夜神秘&#x27;,
  background: { color: &#x27;#FF0B0E14&#x27;, useAvatar: false },
  userBubble: { color: &#x27;#E6232A38&#x27;, radius: 16, alignment: &#x27;right&#x27; },
  userBubbleFont: { textStyle: { color: &#x27;#FFF2F4F8&#x27; } },
  console: { color: &#x27;#6610141C&#x27;, fontColor: &#x27;#FFD8DCE6&#x27; },
})
```

完整方法为 all、get、find、create、update、import、export 和 delete。官方主题只读。同名导入会询问覆盖或另存；与官方主题冲突时只能另存。

theme.export 把 .thm 写入当前聊天隔离的 files/chat 存储并返回虚拟路径，不主动打开系统分享界面。用户需要导出到 Tavo 外部时，再由前台脚本调用单参数 tavo.file.export(path)。

.thm 压缩文件最大 64 MiB，最多 16 个常规条目，总解压大小最大 256 MiB。theme.json 没有独立大小上限。危险路径、目录、符号链接和重复条目名都会被拒绝。

当前聊天使用的主题被更新、覆盖导入或重新绑定时，WebView 会原位更新，不会刷新页面或中断正在运行的 TavoJS / tool call。

🧙 角色

可以通过此接口管理角色，所有角色接口均为 tavo.character.<method>(...)

获取所有角色概要

await tavo.character.all()

返回角色概要对象数组（每项仅包含 id、name、avatar 等概要信息）：

```
let chars = await tavo.character.all()
console.log(chars[0].id)     // 例如 12
console.log(chars[0].avatar) // 例如 "chara/alice.png"
console.log(chars[0].name)   // 例如 "Alice"
```

获取单个角色

await tavo.character.get(<characterId>)

按角色 ID 获取角色对象，不存在时返回 null。

```
let char = await tavo.character.get(12)
if (char) {
  console.log(char.name)
}
```

按名称查找角色

await tavo.character.find(<name>[, <options>])

按名称查找角色，返回角色对象数组。options.match 可选：&#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27;（默认 &#x27;exact&#x27;）

```
let chars = await tavo.character.find(&#x27;Alice&#x27;)
let chars2 = await tavo.character.find(&#x27;Ali&#x27;, { match: &#x27;prefix&#x27; })
console.log(chars.length)
```

新建角色

await tavo.character.create(<character>)

创建角色并返回新角色 ID。character.name 和 character.firstMes（或 CCv3 的 first_mes）为必填项。

CC / SillyTavern 兼容： 支持裸 CC 兼容 data、完整 CCv2/CCv3 wrapper 和 SillyTavern { character: ... } wrapper。推荐使用 CCv3 snake_case 字段。格式识别与字段转换统一在 Dart 层完成。

```
let id = await tavo.character.create({
  name: &#x27;Alice&#x27;,
  firstMes: &#x27;你好，我是 Alice。&#x27;,
  description: &#x27;一位温柔的向导&#x27;,
})
```

更新角色

await tavo.character.update(<character>)

更新角色并返回角色 ID。character.id、character.name 和 character.firstMes 为必填项。

```
await tavo.character.update({
  id: 12,
  name: &#x27;Alice&#x27;,
  firstMes: &#x27;你好，我是 Alice。&#x27;,
  personality: &#x27;耐心、细致&#x27;,
})
```

导入角色卡

await tavo.character.import(<card>)

导入完整的 CCv2/CCv3 或 SillyTavern 兼容角色卡。可以传入 { spec: "chara_card_v3", data: {...} }、裸 data 对象或 { character: ... } wrapper。若卡片包含 character_book，会同时创建世界书；包含 extensions.regex_scripts，会同时创建正则脚本。操作前会弹窗请求用户确认。

返回值：

```
{
  characterId: 12,     // 创建的角色 ID
  lorebookId: 5,       // 创建的世界书 ID（若无则为 null）
  regexId: 3,          // 创建的正则脚本 ID（若无则为 null）
}
```

```
const result = await tavo.character.import(card)
// result.characterId, result.lorebookId, result.regexId
```

删除角色

await tavo.character.delete(<characterId>)

按角色 ID 删除角色：

```
await tavo.character.delete(12)
await tavo.character.delete(char)  // char 需要时带 id 的角色对象
```

角色对象字段

角色对象（get / find 返回）包含以下常见字段：

```
{
  id: 12,                    // 角色的唯一 ID
  avatar: &#x27;xxx.png&#x27;,         // 角色头像图片 URL 或路径
  name: &#x27;Alice&#x27;,             // 角色名称（必填）
  description: &#x27;...&#x27;,        // 角色简介/描述
  firstMes: &#x27;...&#x27;,           // 角色打招呼内容（必填）
  personality: &#x27;...&#x27;,        // 角色性格描述
  scenario: &#x27;...&#x27;,           // 适用场景或使用场景描述
  mesExample: &#x27;...&#x27;,         // 消息示例，以 <START> 分割
  creatorNotes: &#x27;...&#x27;,       // 创建者注释或补充说明
  systemPrompt: &#x27;...&#x27;,       // 系统提示词
  postHistoryInstructions: &#x27;...&#x27;,  // 信息上下文历史后的额外提示或说明
  alternateGreetings: [&#x27;...&#x27;],     // 角色可用的备用打招呼
  tags: [&#x27;guide&#x27;],           // 角色标签，用于分类或检索
  creator: &#x27;Colin&#x27;,          // 创建者用户名或昵称
  characterVersion: &#x27;1.0&#x27;,  // 角色版本号
  nickname: &#x27;Ali&#x27;,           // 角色昵称���别名，如果填写了将替代 name 作为 {{char}} 的输出
  groupOnlyGreetings: [&#x27;...&#x27;],     // 仅限群聊使用的特定打招呼语
  creationDate: new Date(&#x27;2026-03-05T10:20:30.000Z&#x27;),      // 创建时间（Date 对象）
  modificationDate: new Date(&#x27;2026-03-05T11:30:00.000Z&#x27;),  // 最后修改时间（Date 对象）
}
```

说明：创建、更新、导入、删除角色时会弹出确认框，用户取消后操作不会生效。

🎭 用户身份

可以通过此接口管理用户身份，所有用户身份接口均为 tavo.persona.<method>(...)

获取所有用户身份概要

await tavo.persona.all()

返回用户身份概要对象数组（每项包含 id、name）：

```
let personas = await tavo.persona.all()
console.log(personas[0].id)    // 例如 5
console.log(personas[0].name)  // 例如 "默认用户身份"
```

获取单个用户身份

await tavo.persona.get(<personaId>)

按用户身份 ID 获取用户身份对象，不存在时返回 null：

```
let persona = await tavo.persona.get(5)
if (persona) {
  console.log(persona.name)
  console.log(persona.description)
}
```

按名称查找用户身份

await tavo.persona.find(<name>[, <options>])

按名称查找用户身份，返回用户身份对象数组。options.match 可选：&#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; （默认 &#x27;exact&#x27;）

```
let personas = await tavo.persona.find(&#x27;默认&#x27;)
let personas2 = await tavo.persona.find(&#x27;默&#x27;, { match: &#x27;prefix&#x27; })
console.log(personas.length)
```

新建用户身份

await tavo.persona.create(<persona>)

创建用户身份并返回新用户身份 ID。persona.name 和 persona.description 为必填项。

```
let id = await tavo.persona.create({
  name: &#x27;侦探用户身份&#x27;,
  description: &#x27;注重细节，擅长结构化推理。&#x27;,
  avatar: &#x27;chara/persona-detective.png&#x27;,
})
```

更新用户身份

await tavo.persona.update(<persona>)

更新用户身份。persona.id、persona.name 和 persona.description 为必填项。

```
await tavo.persona.update({
  id: 5,
  name: &#x27;默认用户身份&#x27;,
  description: &#x27;语气更简洁，优先给出可执行结论。&#x27;,
  avatar: &#x27;chara/persona-default.png&#x27;,
  active: true,
})
```

删除用户身份

await tavo.persona.delete(<personaId>)

按用户身份 ID 删除用户身份：

```
await tavo.persona.delete(5)
await tavo.persona.delete(persona)  // persona 需要是带 id 的用户身份对象
```

用户身份对象字段

用户身份对象（get 返回）包含以下常见字段：

```
{
  id: 5,  // 用户身份唯一 ID
  name: &#x27;默认用户身份&#x27;,  // 用户身份名称（必填）
  description: &#x27;...&#x27;,  // 用户身份描述（必填）
  avatar: &#x27;xxx.png&#x27;,  // 用户身份头像 URL 或路径（可选）
  active: true,  // 是否为默认用户身份
  sortIndex: 12,  // 排序索引
}
```

🎛️ 预设

可以通过此接口管理预设，所有预设接口均为 tavo.preset.<method>(...)

获取所有预设（摘要）

await tavo.preset.all()

返回预设摘要对象数组（每项包含 id、name）：

```
let presets = await tavo.preset.all()
console.log(presets[0].id)    // 例如 1
console.log(presets[0].name)  // 例如 "Default"
```

获取单个预设

await tavo.preset.get(<presetId>)

按预设 ID 获取完整预设对象，不存在时返回 null：

```
let preset = await tavo.preset.get(1)
if (preset) {
  console.log(preset.name)
  console.log(preset.entries.length)
  console.log(preset.basicPrompts.chatStart)
}
```

按名称查找预设

await tavo.preset.find(<name>[, <options>])

按名称查找预设，返回完整预设对象数组。options.match 可选：&#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; （默认 &#x27;exact&#x27;）

```
let presets = await tavo.preset.find(&#x27;Default&#x27;)
let presets2 = await tavo.preset.find(&#x27;Def&#x27;, { match: &#x27;prefix&#x27; })
console.log(presets.length)
```

导入预设

await tavo.preset.import(<preset>)

以 SillyTavern 预设格式导入预设，弹出确认对话框，用户确认后保存并返回新预设 ID，取消则返回 null。preset.name 为预设名称（可选，缺省为 &#x27;Preset&#x27;）。

```
const id = await tavo.preset.import({
  name: &#x27;My Preset&#x27;,
  prompts: [...],
  prompt_order: [{ character_id: 100001, order: [...] }],
})
```

新建预设

await tavo.preset.create(<preset>)

创建预设并返回新预设 ID。preset.name 为必填项，其余字段可选；preset.basicPrompts 与 preset.entries 中缺失的部分将自动填充默认值。

```
let id = await tavo.preset.create({
  name: &#x27;我的预设&#x27;,
  basicPrompts: {
    continueNudge: &#x27;[继续你的上一条消息，不要重复原有内容。]&#x27;,
  },
  entries: [
    {
      identifier: &#x27;abc123&#x27;,
      name: &#x27;🌸 文风控制&#x27;,
      content: &#x27;采用精致优雅的叙事风格，类似晋江、长佩等平台受欢迎的高质量女性向作品。&#x27;,
    },
  ],
})
```

更新预设

await tavo.preset.update(<preset>)

更新预设。preset.id 为必填项。传入的 entries 会直接覆盖原有的 entries，典型用法是先 get 取出，修改后再 update 写回。

```
const preset = await tavo.preset.get(33);
preset.entries.find(e => e.identifier == &#x27;main&#x27;).content = &#x27;请用中文回复 {{user}} 的所有问题。&#x27;;
await tavo.preset.update(preset)
```

删除预设

await tavo.preset.delete(<presetId>)

按预设 ID 删除预设：

```
await tavo.preset.delete(1)
await tavo.preset.delete(preset)  // preset 需要是带 id 的预设对象
```

预设对象字段

完整预设对象（get / find 返回）包含以下字段：

```
{
  id: 1,          // 预设唯一 ID
  name: &#x27;Default&#x27;, // 预设名称（必填）
  basicPrompts: { /* BasicPrompts，见下 */ },
  entries: [],    // PresetEntry[] 提词条目列表（见下）
}
```

基础提词字段（BasicPrompts）

basicPrompts 包含各类系统提词模板，所有字段均可选，缺省时使用内置默认值：

```
{
  persona: &#x27;{{persona}}&#x27;,        // 用户身份描述的格式模板
  description: &#x27;{{description}}&#x27;, // 角色描述的格式模板
  personality: &#x27;{{personality}}&#x27;, // 角色性格的格式模板（用 {{personality}} 标记插入位置）
  scenario: &#x27;{{scenario}}&#x27;,      // 场景的格式模板（用 {{scenario}} 标记插入位置）
  exampleMessageStart: &#x27;[Example Chat]&#x27;,  // 示例对话起始标记
  chatStart: &#x27;[Start a new Chat]&#x27;,        // 聊天历史起始标记
  groupChatStart: &#x27;[Start a new group chat. Group members: {{group}}]&#x27;,  // 群聊起始标记
  groupNudge: &#x27;[Write the next reply only as {{char}}.]&#x27;,  // 群聊中催促指定角色回复的提词
  continueNudge: &#x27;[Continue your last message without repeating its original content.]&#x27;,  // 续写按钮的提词
  impersonation: &#x27;[Write your next reply from the point of view of {{user}}...]&#x27;,  // 扮演用户时的提词
  lorebook: &#x27;{0}&#x27;,  // 世界书条目的包装模板（用 {0} 标记内容插入位置）
}
```

提词条目字段（PresetEntry）

entries 数组中每一项的结构：

```
{
  // ── 基本信息 ──────────────────────────────────
  identifier: &#x27;main&#x27;,   // 条目唯一标识（内置条目有固定 identifier，见下表）
  name: &#x27;Main Prompt&#x27;,  // 条目显示名称
  content: &#x27;...&#x27;,       // 提词正文（marker 类型无此字段）
  enabled: true,        // 是否启用此条目（在激活列表中是否生效）
  active: true,         // 是否加入激活列表（false 时条目仅存档，不参与提示词构建）

  // ── 类型 ──────────────────────────────────────
  type: &#x27;custom&#x27;,       // 条目类型：
                        //   &#x27;builtin&#x27; - 内置提词（固定 identifier，如 main / jailbreak）
                        //   &#x27;marker&#x27;  - 位置标记（无内容，仅标记其他内容的插入位置）
                        //   &#x27;custom&#x27;  - 自定义提词

  // ── 角色与注入（custom 类型可配置）──────────────
  role: &#x27;system&#x27;,       // 消息角色：&#x27;system&#x27; | &#x27;user&#x27; | &#x27;assistant&#x27;
  injectionPosition: &#x27;relative&#x27;,  // 注入位置：
                                  //   &#x27;relative&#x27; - 相对位置（跟随预设列表顺序）
                                  //   &#x27;absolute&#x27; - 绝对位置（插入到聊天历史的特定深度）
  injectionDepth: 4,   // 注入深度，仅 injectionPosition 为 &#x27;absolute&#x27; 时生效
                        // 0 = 最后一条消息之后，1 = 最后一条消息之前，以此类推
}
```

内置条目 identifier 列表

以下 identifier 对应系统内置的固定提词或位置标记，创建 / 更新时可直接引用：

identifier
名称
类型
说明

main
Main Prompt
builtin
主提词，对话的核心指令

worldInfoBefore
Lorebook Before
marker
世界书（角色描述上方）插入点

personaDescription
Persona Description
marker
用户身份描述插入点

charDescription
Char Description
marker
角色描述插入点

charPersonality
Char Personality
marker
角色性格插入点

scenario
Scenario
marker
场景描述插入点

enhanceDefinitions
Enhance Definitions
builtin
增强角色定义的补充提词

nsfw
Auxiliary Prompt
builtin
辅助提词（默认为空）

worldInfoAfter
Lorebook After
marker
世界书（角色描述下方）插入点

dialogueExamples
Chat Examples
marker
示例对话插入点

chatHistory
Chat History
marker
聊天历史插入点

jailbreak
Post-History Instructions
builtin
历史记录后的补充指令

📚 世界书

可以通过此接口管理世界书，所有世界书接口均为 tavo.lorebook.<method>(...)

获取所有世界书概要

await tavo.lorebook.all()

返回世界书概要对象数组（每项包含 id、name、entries）：

```
let lorebooks = await tavo.lorebook.all()
console.log(lorebooks[0].id)       // 例如 3
console.log(lorebooks[0].name)     // 例如 "城市设定"
console.log(lorebooks[0].entries)  // 例如 12（条目数量）
```

获取单个世界书

await tavo.lorebook.get(<lorebookId>)

按世界书 ID 获取对象，不存在时返回 null：

```
let lorebook = await tavo.lorebook.get(3)
if (lorebook) {
  console.log(lorebook.name)
  console.log(lorebook.entries.length)
}
```

按名称查找世界书

await tavo.lorebook.find(<name>[, <options>])

按名称查找世界书，返回世界书对象数组。options.match 可选：&#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; （默认 &#x27;exact&#x27;）

```
let lorebooks = await tavo.lorebook.find(&#x27;城市&#x27;)
let lorebooks2 = await tavo.lorebook.find(&#x27;城市&#x27;, { match: &#x27;suffix&#x27; })
console.log(lorebooks.length)
```

导入世界书

await tavo.lorebook.import(<lorebook>)

导入世界书。接受裸 CCv3 Lorebook / character_book、独立 { spec: "lorebook_v3", data: {...} }、SillyTavern World Info（entries 为以 UID 为 key 的对象）以及 Tavo 原生世界书对象。操作前会弹窗请求用户确认。返回新世界书 ID，取消则返回 null。

```
const id = await tavo.lorebook.import({
  name: &#x27;My Lorebook&#x27;,
  entries: [...]
})
```

新建世界书

await tavo.lorebook.create(<lorebook>)

创建世界书并返回新世界书 ID。lorebook.name 为必填项。推荐使用 CCv3 Lorebook 字段；也接受独立 lorebook_v3 wrapper、SillyTavern World Info 和 Tavo 原生字段。

```
let id = await tavo.lorebook.create({
  name: &#x27;城市设定&#x27;,
  entries: [],
})
```

更新世界书

await tavo.lorebook.update(<lorebook>)

更新世界书。lorebook.id 和 lorebook.name 为必填项。条目格式与 create 相同。

```
await tavo.lorebook.update({
  id: 3,
  name: &#x27;城市设定（重制）&#x27;,
  entries: [],
})
```

删除世界书

await tavo.lorebook.delete(<lorebookId>)

按世界书 ID 删除世界书：

```
await tavo.lorebook.delete(3)
await tavo.lorebook.delete(lorebook)  // lorebook 需要是带 id 的世界书对象
```

世界书对象字段

世界书对象（get / find 返回）包含以下字段：

```
{
  id: 3,           // 世界书唯一 ID
  name: &#x27;城市设定&#x27;, // 世界书名称（必填）
  entries: [],     // LorebookEntry[] 条目列表（见下）
}
```

条目对象字段（LorebookEntry）

entries 数组中每一项的结构：

格式兼容： 推荐使用 CCv3 字段 keys、secondary_keys、constant、position、selective、use_regex 和 extensions。也接受 SillyTavern 的 key、keysecondary、disable、order 等字段，以及下方列出的 Tavo 原生字段。所有入口共用 Dart 格式识别，不需要调用方自行转换。

```
{
  // ── 基本信息 ──────────────────────────────────
  identifier: &#x27;entry-uuid&#x27;,  // 条目唯一标识（字符串）
  name: &#x27;城市总览&#x27;,            // 条目名称（仅供显示和搜索）
  content: &#x27;这是一座临海城市，夜间常有浓雾。&#x27;,  // 注入到提示词的正文内容
  enabled: true,             // 是否启用此条目
  strategy: &#x27;constant&#x27;,      // 触发策略：&#x27;constant&#x27;（常驻）| &#x27;keyword&#x27;（关键词触发）

  // ── 关键词 ─────────────────────────────────────
  keywords: [&#x27;城市&#x27;, &#x27;港口&#x27;],         // 主关键词列表（strategy 为 &#x27;keyword&#x27; 时生效）
  secondaryKeywords: [&#x27;夜晚&#x27;, &#x27;雾&#x27;],  // 次级关键词列表
  secondaryKeywordStrategy: &#x27;none&#x27;,  // 次级关键词匹配策略：
                                     //   &#x27;none&#x27;   - 不启用次级关键词
                                     //   &#x27;andAny&#x27; - 主词命中且任意次级词命中（默认）
                                     //   &#x27;andAll&#x27; - 主词命中且全部次级词命中
                                     //   &#x27;notAny&#x27; - 主词命中且没有次级词命中
                                     //   &#x27;notAll&#x27; - 主词命中且不是全部次级词命中
  scanDepth: 2,              // 关键词扫描的消息深度（默认 2，最大 1000）
  caseSensitive: false,      // 关键词是否区分���小写
  matchWholeWord: true,      // 是否全词匹配

  // ── 注入位置 ───────────────────────────────────
  injectionPosition: &#x27;lorebookBefore&#x27;,  // 注入位置：
                                        //   &#x27;lorebookBefore&#x27;         - 角色描述上方（↑Char）
                                        //   &#x27;lorebookAfter&#x27;          - 角色描述下方（↓Char）
                                        //   &#x27;topOfExampleMessages&#x27;   - 示例对话之前
                                        //   &#x27;bottomOfExampleMessages&#x27;- 示例对话之后
                                        //   &#x27;atDepth&#x27;                - 聊天历史的绝对深度位置
  injectionDepth: 4,         // 注入深度，仅 injectionPosition 为 &#x27;atDepth&#x27; 时生效
  injectionRole: &#x27;system&#x27;,   // 注入角色：&#x27;system&#x27; | &#x27;user&#x27; | &#x27;assistant&#x27;

  // ── 概率与行为 ─────────────────────────────────
  probability: 100,  // 激活概率（0–100，默认 100）
  sticky: 0,         // 激活后持续保持的消息轮数（0 表示不持续）
  cooldown: 0,       // 激活一次后的冷却轮数（0 表示无冷却）
  delay: 0,          // 延迟激活的消息轮数（0 表示立即）
}
```

🎨 正则

可以通过此接口管理正则组（一组查找/替换规则），所有正则接口均为 tavo.regex.<method>(...)

获取所有正则（摘要）

await tavo.regex.all()

返回正则摘要对象数组（每项包含 id、name、entries，其中 entries 为规则条数，不是条目数组）：

```
let list = await tavo.regex.all()
console.log(list[0].id)       // 例如 2
console.log(list[0].name)     // 例如 "我的正则"
console.log(list[0].entries)  // 例如 5（条规则数量）
```

获取单个正则

await tavo.regex.get(<regexId>)

按 ID 获取完整正则对象，不存在时返回 null：

```
let r = await tavo.regex.get(2)
if (r) {
  console.log(r.name)
  console.log(r.entries.length)
}
```

按名称查找正则

await tavo.regex.find(<name>[, <options>])

按名称查找正则，返回完整正则对象数组。options.match 可选：&#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; （默认 &#x27;exact&#x27;）

```
let found = await tavo.regex.find(&#x27;我的&#x27;)
let found2 = await tavo.regex.find(&#x27;我的&#x27;, { match: &#x27;contains&#x27; })
console.log(found.length)
```

导入正则

await tavo.regex.import(<regex>)

以 SillyTavern 正则格式导入正则组，弹出确认对话框，用户确认后保存并返回新正则组 ID，取消则返回 null。regex.name 为组名（可选，缺省为 &#x27;Regex&#x27;）；regex.entries 为 SillyTavern 格式的规则数组。

```
const id = await tavo.regex.import({
  name: &#x27;高亮处理&#x27;,
  entries: [
    { scriptName: &#x27;高亮&#x27;, findRegex: &#x27;\\[高亮:(.+?)\\]&#x27;, replaceString: &#x27;<mark>$1</mark>&#x27;, placement: [2], disabled: false, markdownOnly: true, promptOnly: false, runOnEdit: false, substituteRegex: 0 }
  ]
})
```

新建正则

await tavo.regex.create(<regex>)

创建正则并返回新 ID。regex.name 为必填；regex.entries 为规则数组，可省略（视为空列表）。创建 / 更新前会弹出确认对话框。

```
let id = await tavo.regex.create({
  name: &#x27;Demo 正则&#x27;,
  entries: [
    {
      name: &#x27;状态栏&#x27;,
      findRegex: &#x27;/<status>(.*?)<\/status>/gim&#x27;,
      replaceString: &#x27;<pre>$1</pre>&#x27;,
      placements: [&#x27;char&#x27;],
      timing: &#x27;display&#x27;,
    },
  ],
})
```

更新正则

await tavo.regex.update(<regex>)

更新正则。regex.id 与 regex.name 均为必填（前端封装会校验）。典型用法：get → 修改 → update。

```
const r = await tavo.regex.get(2)
r.entries[0].enabled = false
await tavo.regex.update(r)
```

删除正则

await tavo.regex.delete(<regexId>)

按 ID 删除；也可传入带 id 的正则对象：

```
await tavo.regex.delete(2)
await tavo.regex.delete({ id: 2 })
```

正则对象字段

完整对象（get / find 返回）结构：

```
{
  id: 2,
  name: &#x27;我的正则&#x27;,
  entries: [ /* RegexEntry[]，见下 */ ],
}
```

规则条目字段（RegexEntry）

entries 中每一项：

```
{
  name: &#x27;规则显示名&#x27;,             // 必填（字符串），否则解析可能失败
  findRegex: &#x27;pattern&#x27;,          // 查找用正则（可支持 JavaScript 正则类似的 `/pattern/flags` 写法）
  replaceString: &#x27;&#x27;,             // 替换为的字符串
  trimStrings: [],               // 额外要裁剪的字符串列表
  placements: [&#x27;char&#x27;],          // 作用位置，可多选：
                                 //   &#x27;user&#x27;      - 用户输入
                                 //   &#x27;char&#x27;      - AI 输出
                                 //   &#x27;reasoning&#x27; - 推理内容
                                 //   &#x27;lorebook&#x27;  - 世界书注入内容
  timing: &#x27;display&#x27;,           // 执行时机：
                                 //   &#x27;display&#x27;         - 仅显示时（不写入持久消息，类似 ST markdownOnly）
                                 //   &#x27;send&#x27;            - 仅发送进模型前
                                 //   &#x27;sendAndDisplay&#x27;  - 显示与发送都执行
                                 //   &#x27;receive&#x27;         - 收到回复后持久化（仅输入/输出相关）
                                 //   &#x27;editAndReceive&#x27;  - 收到与编辑消息时都会持久化改写
  substitution: &#x27;none&#x27;,        // 宏替换方式：&#x27;none&#x27; | &#x27;raw&#x27; | &#x27;escaped&#x27;
  minDepth: null,              // 可选，消息深度下限（整数）
  maxDepth: null,              // 可选，消息深度上限（整数）
  enabled: true,               // 是否启用该条规则
}
```

省略字段时，端侧会为 findRegex、replaceString、trimStrings、placements、timing、substitution、enabled 等填入合理默认值（例如 placements: [&#x27;char&#x27;]、timing: &#x27;display&#x27;）。

🧠 长记忆

可以通过此接口读取或修改当前聊天的长期记忆（Long-term Memory），所有接口均为 tavo.memory.<method>(...)

获取当前记忆

await tavo.memory.current()

获取当前聊天记忆对象：

```
const memory = await tavo.memory.current()
console.log(memory.enabled)         // true / false
console.log(memory.memories.length) // 记忆条数
```

更新记忆

await tavo.memory.update(<memory>)

更新当前聊天记忆并返回记忆记录 ID。可更新字段：

enabled：是否启用记忆

memories：记忆文本数组（string[]）

```
const memory = await tavo.memory.current()

memory.enabled = true
memory.memories = [
  &#x27;用户喜欢简洁、结论先行的回答风格&#x27;,
  &#x27;用户倾向于让角色保持冷静和专业&#x27;,
]

const memoryId = await tavo.memory.update(memory)
console.log(memoryId)
```

追加记忆

自 v1.0.0 起

await tavo.memory.append(<memories>)

在不替换现有内容的情况下，将一条或多条非空字符串依次追加到当前聊天的长期记忆。
即使长期记忆当前已关闭，也可以保存新条目，但接口不会自动开启提示词注入。

```
const result = await tavo.memory.append([
  &#x27;用户喜欢简洁、结论先行的回答风格&#x27;,
  &#x27;用户计划下个月去京都&#x27;,
])

console.log(result.appendedCount) // 2
console.log(result.totalCount)    // 追加后的总条数
console.log(result.enabled)       // 当前记忆注入状态
```

记忆对象字段

current 返回对象结构：

```
{
  id: 12,  // 记忆记录 ID
  enabled: true,  // 是否启用长期记忆
  memories: [     // 记忆条目列表（字符串数组）
    &#x27;用户偏好简洁回复&#x27;,
    &#x27;避免重复解释已确认信息&#x27;
  ],
}
```

✨ 生成请求

可以通过此接口直接触发一次文本生成，所有生成接口均为 tavo.generate(...)。

发起生成

await tavo.generate(<prompt>, <options>)

prompt 类型 string：本次生成的用户输入内容

options 类型 object：生成选项（若无额外配置，传空对象 \{\}）

返回值类型为 string，即模型生成的文本内容。

```
const result = await tavo.generate(&#x27;请用一句话总结今天发生的事情&#x27;)
console.log(result)
```

options 字段

options 支持以下字段：

context 类型 boolean（默认 false）：

true：带当前对话上下文生成（沿用当前聊天状态）

false：与当前对话无关的AI生成请求（默认）

preset 类型 number | object（可选）：

直接传预设 ID，例如 12

传对象时仅识别 id，例如 { id: 12 }

settings 类型 object（可选）：覆盖本次请求的模型参数

示例：

```
const text = await tavo.generate(
  &#x27;根据最近对话，给我 3 条行动建议&#x27;,
  {
    context: true,
    preset: { id: 8 },
    settings: {
      temperature: 0.7,
      topP: 0.9,
      maxCompletionTokens: 300,
    },
  },
)

console.log(text)
```

注意事项

该接口为一次性请求，返回完整文本，不返回流式分片

生成请求会使用当前聊天绑定的模型 API；若当前聊天无可用 API，会抛出异常（请用 try/catch 捕获）

使用示例

将以下内容复制到气泡中，以观察效果：

```
<h3>生成请求 API 演示</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 80px; max-height: 300px; overflow-y: auto;"></pre>
<button id="btn-generate" onclick="generate()">生成角色卡</button>
<p id="status"></p>
<div id="actions" style="display:none; gap:8px;">
  <button onclick="downloadJson()">下载 JSON 文件</button>
  <button onclick="createCharacter()">直接创建角色卡</button>
</div>
<script>
let generatedCard = null;
const log = (...args) => {
  const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
  document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
};
function setUi(loading, status, showActions = false) {
  document.getElementById(&#x27;btn-generate&#x27;).disabled = loading;
  document.getElementById(&#x27;status&#x27;).textContent = status;
  document.getElementById(&#x27;actions&#x27;).style.display = showActions ? &#x27;flex&#x27; : &#x27;none&#x27;;
}
async function generate() {
  const p = prompt(&#x27;请输入想要生成的角色特点&#x27;);
  if (!p) return;
  setUi(true, &#x27;生成中...&#x27;);
  try {
    let text = await tavo.generate(`根据以下信息生成一张角色卡，输出符合 Character Card Spec V3 规范的JSON格式\n${p}`);
    log(text)
    text = text.trim();
    if (text.startsWith(&#x27;```&#x27;) && text.endsWith(&#x27;```&#x27;)) {
      text = text.replace(/^```[a-zA-Z]*\n?/, &#x27;&#x27;).replace(/```$/, &#x27;&#x27;);
    }
    generatedCard = JSON.parse(text);
    if (generatedCard.mes_example instanceof Array) generatedCard.mes_example = generatedCard.mes_example.join(&#x27;\n&#x27;)
    setUi(false, `角色卡 《${generatedCard.name}》 已生成`, true);
  } catch (e) {
    log(e);
    console.log(e);
    setUi(false, `角色卡生成失败`, false)
  }
}
async function downloadJson() {
  await tavo.file.export(`${generatedCard.name}.json`, JSON.stringify(generatedCard))
}
async function createCharacter() {
  await tavo.character.create(generatedCard);
}
</script>
```

🎨 生图

可以通过此接口直接触发一次生图，所有生图接口均为 tavo.image.<method>(...)。

发起生图

await tavo.image.generate(<prompt>, <options>)

prompt 类型 string：本次生图的提示词（必填，非空）

options 类型 object：生图选项（可选）

返回值类型为 string，默认是图片 dataUrl；传入 saveAs 时返回保存后的虚拟路径。两者都可直接用于：

<img src="..."> 渲染

```
const img = await tavo.image.generate(&#x27;a calico cat sleeping on a keyboard&#x27;)
const el = document.getElementById(&#x27;cat&#x27;)
el.src = img
el.onclick = () => tavo.utils.preview(el)
```

options 字段

options 支持以下字段，全部可选：

size 类型 string（例 "1024x1024"）：OpenAI 系平台使用

aspectRatio 类型 string（例 "16:9"、"1:1"）：支持宽高比参数的平台使用

negativePrompt 类型 string：负面提示词。NovelAI / SD 类生效，OpenAI / Gemini 忽略

referenceImages 类型 string[]：参考图数组，用于 img2img。每项可以是 dataUrl，也可以是 tavo.file.save 返回的相对路径（直接传路径更高效，省去读回 dataUrl）。Gemini / OpenAI / Partner（Volink）/ OpenRouter 支持多图；NovelAI 协议限制只取第一张

extraBody 类型 object：透传到平台 API 的额外字段（如 seed / guidance_scale / quality 等）

saveAs 类型 string（含扩展名，如 &#x27;hero.png&#x27;）：传了就直接落盘，返回虚拟路径而非 dataUrl。等价于 tavo.image.generate(...) + tavo.file.save(saveAs, dataUrl) 两步合一。文件名规则同 tavo.file.save（禁止 / \ : ..，违反会抛出 Error，同名覆盖）

scope 类型 string：&#x27;chat&#x27;（默认）| &#x27;global&#x27;，仅在传了 saveAs 时生效，与 tavo.file.save 的 scope 同义

```
const wide = await tavo.image.generate(&#x27;cyberpunk night street, neon&#x27;, {
  aspectRatio: &#x27;16:9&#x27;,
  negativePrompt: &#x27;low quality, blurry, watermark&#x27;,
  extraBody: { quality: &#x27;hd&#x27; },
})

// 一步生成 + 落盘
const path = await tavo.image.generate(&#x27;a calico cat&#x27;, {
  saveAs: &#x27;hero.png&#x27;,                  // 返回 &#x27;files/chat/hero.png&#x27;（虚拟路径）
})
imgEl.src = path
tavo.set(&#x27;hero&#x27;, path)                  // 路径塞变量，跨克隆/导入聊天免重写
```

注意事项

该接口为一次性请求，返回完整 dataUrl（或虚拟路径，当传 saveAs 时），不返回流式分片

使用当前聊天绑定的生图 API（与输入框生图入口相同）；若当前聊天无可用生图 API，会抛出异常

不会触发提示词扩写（与「生图设置」页的「自动扩写」开关无关；如需扩写，请先用 tavo.generate 转一道）

未传 saveAs 时不会持久化到本地存储，bytes 仅通过返回值送出；传了 saveAs 则直接落盘

不弹出任何用户确认对话框

使用示例

将以下内容复制到气泡中观察效果：

```
<h3>🎨 生图 API 演示</h3>
<div class="control">
  <button id="btn-generate" onclick="run()">生成图片</button>
  <p id="status"></p>
  <img id="out" onclick="tavo.utils.preview(this)" style="max-width: 100%; border-radius: 8px; margin-top: 8px; cursor: pointer;" />
</div>
<script>
async function run() {
  const p = prompt(&#x27;请输入生图提示词&#x27;, &#x27;a calico cat sleeping on a keyboard&#x27;)
  if (!p) return
  const btn = document.getElementById(&#x27;btn-generate&#x27;)
  const status = document.getElementById(&#x27;status&#x27;)
  const out = document.getElementById(&#x27;out&#x27;)
  btn.disabled = true
  status.textContent = &#x27;生成中...&#x27;
  out.src = &#x27;&#x27;
  try {
    const img = await tavo.image.generate(p, { aspectRatio: &#x27;1:1&#x27; })
    out.src = img
    status.textContent = &#x27;完成&#x27;
  } catch (e) {
    status.textContent = &#x27;失败：&#x27; + e.message
    tavo.utils.toast(&#x27;生图失败：&#x27; + e.message)
  } finally {
    btn.disabled = false
  }
}
</script>
```

🔊 TTS

自 v0.92.0 起

通过当前聊天已有的 TTS 绑定合成并播放文本。

```
await tavo.tts.play(&#x27;欢迎回来。&#x27;, {
  voice: { character: 123 }, // 也可以传 { id: 123 }
  queue: false,
  applyPlaybackRules: false,
})

await tavo.tts.stop()
```

voice 必须在 character 和 persona 中二选一，两者都接受 id 或包含
id 的对象。普通消息 TavoJS 可以省略 voice，使用宿主消息说话人的绑定；
插件 TavoJS 必须显式传入 voice，并通过现有聊天、消息或资料库 API 自行找到
说话人。

play() 在开始播放或进入队列时返回 true；文本为空、目标不存在或目标没有
可用绑定时返回 false。queue 与 applyPlaybackRules 默认都是 false。
stop() 会停止当前聊天的共享 TTS 并清空等待队列。目前不支持直接传入
voiceId 或 TTS endpoint id。

📁 文件

可以通过此接口把数据持久化到 app 本地存储，所有文件接口均为 tavo.file.<method>(...)。

适合把生成的图片、下载的资源、配置文件等存到磁盘，避免把大体积数据（如图片 dataUrl）直接塞进变量或消息内容里。

tavo.file.import 和 tavo.file.export 会打开系统文件选择或分享/保存界面。

作用域（scope）

与变量一致，文件也分作用域：

chat（默认）：聊天作用域，文件随当前聊天保存，聊天删除时一并清理

global：全局作用域，跨聊天持久保存，需脚本主动删除

列出已存文件

await tavo.file.list(<options>)

返回当前作用域的一页常规文件。options 只能包含：

scope：&#x27;chat&#x27;（默认）或 &#x27;global&#x27;。不传时列出当前聊天的文件，只有要跨聊天共享资源时才传 &#x27;global&#x27;

limit：每页条数，整数，默认 100，范围 1 到 200

cursor：上一页返回的 nextCursor，首项不传

```
{
  files: Array<{
    path: string       // files/chat/<name> 或 files/global/<name>
    name: string
    size: number       // 字节数
    mimeType: string
    modifiedAt: string // UTC ISO 8601，例如 2026-08-10T01:02:03.000Z
  }>
  nextCursor?: string
}
```

文件名按大小写敏感的字符串顺序排序。分页是实时读取而非快照：两页之间被删除的文件会消失，插入到 cursor 之前的文件不会出现在后续页。cursor 只能用于相同的 scope。

```
let cursor
do {
  const page = await tavo.file.list({ scope: &#x27;chat&#x27;, limit: 100, cursor })
  for (const file of page.files) {
    // 先按字节数和 MIME 类型筛选，再按需 load 大文件
    console.log(file.name, file.size, file.mimeType, file.modifiedAt)
  }
  cursor = page.nextCursor
} while (cursor)
```

导入外部文件

await tavo.file.import(<options>)

打开系统文件选择器，把用户选中的外部文件复制到 Tavo 文件存储，然后返回 ImportedFile[]。即使只允许选择一个文件，返回值也始终是数组；用户取消时返回 []。

options.multiple：是否允许多选，默认 false

options.scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;

options.extensions：可选的非空扩展名数组，例如 [&#x27;txt&#x27;, &#x27;md&#x27;]。不要带 .、通配符或路径分隔符

options.conflict：目标同名时的处理方式，默认 &#x27;rename&#x27;

&#x27;rename&#x27;：保留已有文件，并将新文件命名为 name (1).ext、name (2).ext 等

&#x27;overwrite&#x27;：覆盖已有文件；同一批中重名时保留最后一个

&#x27;error&#x27;：遇到同名文件时整批失败

每个结果包含：

```
{
  path: string       // Tavo 内部虚拟路径，例如 files/chat/source.txt
  name: string       // 冲突处理后的最终存储名
  originalName: string
  size: number       // 实际读取到的字节数
  mimeType: string
}
```

导入按所选批次保持原子性。Tavo 会先读取并暂存全部文件，任一文件校验、读取或写入失败时，不会留下部分导入结果，已覆盖的目标也会恢复。

```
const files = await tavo.file.import({
  multiple: true,
  extensions: [&#x27;txt&#x27;, &#x27;md&#x27;],
  conflict: &#x27;rename&#x27;
})

for (const file of files) {
  const text = await tavo.file.load(file.path)
  console.log(file.originalName, file.path, text)
}
```

保存文件

await tavo.file.save(<name>, <content>, <options>)

把内容写入存储，返回相对路径字符串，可直接用于 <img src>、referenceImages 等。

name 类型 string：文件名（含扩展名，如 &#x27;avatar.png&#x27;）。不能含 / \ : ..，否则抛错。同名覆盖。

content 类型 string：要保存的内容，见下方「content 与 encoding」

options 类型 object（可选）：

scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;

encoding：不传时自动识别；显式可传 &#x27;utf8&#x27; | &#x27;base64&#x27; | &#x27;dataUrl&#x27;

content 与 encoding

不传 encoding 时按内容自动识别：

以 data: 开头：当 dataUrl，解码二进制存

以 http:// / https:// 开头：下载远程内容存

其余：当 UTF-8 纯文本存

需要覆盖自动识别时显式传 encoding：

&#x27;utf8&#x27;：强制当纯文本（即使内容长得像 dataUrl）

&#x27;base64&#x27;：内容是裸 base64（无 data: 前缀），解码二进制存

&#x27;dataUrl&#x27;：内容是 dataUrl，剥前缀解码存

```
// 纯文本 / JSON（自动 utf8）
await tavo.file.save(&#x27;note.md&#x27;, &#x27;# 标题\n正文&#x27;)
await tavo.file.save(&#x27;cfg.json&#x27;, JSON.stringify({ theme: &#x27;dark&#x27; }), { scope: &#x27;global&#x27; })

// 生成的图片（generate 返回 dataUrl，自动识别）
const dataUrl = await tavo.image.generate(&#x27;a calico cat&#x27;)
const path = await tavo.file.save(&#x27;cat.png&#x27;, dataUrl)
document.getElementById(&#x27;out&#x27;).src = path  // 直接渲染

// 从 URL 下载
await tavo.file.save(&#x27;report.pdf&#x27;, &#x27;https://example.com/report.pdf&#x27;, { scope: &#x27;global&#x27; })

// 把 dataUrl 字符串当纯文本存（不解码）
await tavo.file.save(&#x27;log.txt&#x27;, dataUrl, { encoding: &#x27;utf8&#x27; })
```

读取文件

await tavo.file.load(<name>, <options>)

读出内容，文件不存在返回 null。

options.scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;

options.encoding：&#x27;utf8&#x27;（默认，返回文本）| &#x27;dataUrl&#x27;（返回 dataUrl，可 <img src>）| &#x27;base64&#x27;（返回裸 base64）

name 既可以是单个文件名，也可以直接传 save 或 import 返回的完整虚拟路径。传完整路径时会从路径确定 chat / global 作用域，不要再传冲突的 options.scope。

```
const text = await tavo.file.load(&#x27;note.md&#x27;)                             // 文本
const dataUrl = await tavo.file.load(&#x27;cat.png&#x27;, { encoding: &#x27;dataUrl&#x27; })  // dataUrl
```

提示：渲染图片通常不需要 load，直接用 tavo.file.url(name) 或 save 的返回值当 <img src> 即可。load 主要用于读回文本配置，或需要把图片字节做二次处理时。

删除文件

await tavo.file.delete(<name>, <options>)

删除文件，不存在则静默返回。options.scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;。

name 也可以直接传 files/chat/<name> 或 files/global/<name> 虚拟路径。

```
await tavo.file.delete(&#x27;cat.png&#x27;)
await tavo.file.delete(&#x27;logo.png&#x27;, { scope: &#x27;global&#x27; })
```

文件是否存在

await tavo.file.exists(<name>, <options>)

返回 boolean。options.scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;。

name 也可以直接传 files/chat/<name> 或 files/global/<name> 虚拟路径。

```
if (await tavo.file.exists(&#x27;avatar.png&#x27;)) {
  document.getElementById(&#x27;out&#x27;).src = tavo.file.url(&#x27;avatar.png&#x27;)
}
```

拼接渲染路径（同步）

tavo.file.url(<name>, <scope>)

同步返回文件的相对路径（不检测是否存在，永远返回字符串），用于在知道文件名时直接拼出 <img src>：

scope：&#x27;chat&#x27;（默认）| &#x27;global&#x27;

```
// 已知文件名，直接渲染（无需 await，无需记 save 的返回值）
imgEl.src = tavo.file.url(&#x27;avatar.png&#x27;)
imgEl.src = tavo.file.url(&#x27;logo.png&#x27;, &#x27;global&#x27;)
```

导出到外部文件

await tavo.file.export(<name>, <content>, <options>)

await tavo.file.export(<path>)

把内容交给系统分享/保存界面，不会先写入 Tavo 内部文件存储。系统流程完成后返回 undefined。

单参数形式会按原始字节导出已有的 files/chat/... 或 files/global/... 虚拟路径，适合配合 tavo.theme.export()。

name：导出的文件名，不能含 / \\ : ..

content：字符串内容

options.encoding：&#x27;utf8&#x27;（默认）| &#x27;base64&#x27; | &#x27;dataUrl&#x27;

编码必须显式匹配内容，不会像 tavo.file.save 一样自动猜测。普通文本直接省略 options 即可；二进制内容使用明确的 base64 或 dataUrl。

```
await tavo.file.export(&#x27;notes.txt&#x27;, &#x27;普通 UTF-8 文本&#x27;)
await tavo.file.export(&#x27;image.png&#x27;, imageBase64, { encoding: &#x27;base64&#x27; })
await tavo.file.export(&#x27;image.png&#x27;, imageDataUrl, { encoding: &#x27;dataUrl&#x27; })
```

选择、读取、翻译并导出

```
const [source] = await tavo.file.import({ extensions: [&#x27;txt&#x27;, &#x27;md&#x27;] })
if (source) {
  const text = await tavo.file.load(source.path)
  const translated = await tavo.generate(`请把以下内容翻译成中文：\n\n${text}`)
  await tavo.file.export(`translated-${source.name}`, translated)
}
```

注意事项

文件名不能含 / \ : ..（防路径穿越），违反抛 Error

同名覆盖，无冲突提示

chat 作用域文件随聊天删除一并清理；global 作用域需脚本主动 delete

克隆聊天时，原会话的 chat 作用域文件会一并拷贝到新会话；变量与消息里的路径无需重写（路径不绑 chatId，新会话渲染时自动解析）

URL 下载不限制白名单 / 大小 / 超时，脚本作者自行担保来源

使用示例

```
<h3>📁 文件存储演示</h3>
<button onclick="run()">生成并存盘</button>
<p id="status"></p>
<div id="out"></div>
<script>
async function run() {
  const status = document.getElementById(&#x27;status&#x27;)
  const out = document.getElementById(&#x27;out&#x27;)
  status.textContent = &#x27;生成中...&#x27;
  try {
    const dataUrl = await tavo.image.generate(&#x27;a calico cat on a windowsill&#x27;, { aspectRatio: &#x27;1:1&#x27; })
    // 存盘，拿短路径（不把大 dataUrl 塞进消息/变量）
    const path = await tavo.file.save(&#x27;demo-cat.png&#x27;, dataUrl)
    out.innerHTML = `<img src="${path}" style="max-width:240px;border-radius:8px" />`
    // 路径记到变量，下次直接取
    tavo.set(&#x27;lastCat&#x27;, path)
    status.textContent = &#x27;已存盘：&#x27; + path
  } catch (e) {
    status.textContent = &#x27;失败：&#x27; + e.message
  }
}
</script>
```

⌨️ 输入框

可以通过此接口读取或操作聊天输入框，所有输入框接口均为 tavo.input.<method>(...)

读取输入框

await tavo.input.get()

获取当前输入框中的文本内容：

```
let text = await tavo.input.get()  // 获取当前输入框内容
```

写入输入框

tavo.input.set(<text>)

覆盖写入输入框内容（会清除原有内容）：

```
tavo.input.set(&#x27;你好！&#x27;)  // 将输入框内容替换为"你好！"
```

追加到输入框

tavo.input.append(<text>)

在输入框现有内容末尾追加文本：

```
tavo.input.append(&#x27; 继续聊吧&#x27;)  // 在原有内容后追加文字
```

清空输入框

tavo.input.clear()

清空输入框内容：

```
tavo.input.clear()
```

发送消息

tavo.input.send()

通过正常聊天流程发送当前输入，并等待 Tavo 接受或拒绝；不会等待模型回复或生图完成：

```
tavo.input.set(&#x27;今天天气不错&#x27;)
const result = await tavo.input.send()
if (!result.ok) console.log(result.reason, result.text)
```

成功返回 { ok: true, text }。失败返回 { ok: false, reason, text }，其中 reason 为 cancelled、busy 或 rejected。插件取消时还可能包含 cancelledBy 和 message。text 是 input hooks 完成并 trim 后的最终输入。

🛠️ 工具

通用工具接口，所有工具接口均为 tavo.utils.<method>(...)

轻量提示

tavo.utils.toast(<text>)

显示一个轻量 toast 提示，数秒后自动消失

打开链接

tavo.utils.openUrl(<url>)

在外部浏览器中打开一个 URL：

```
tavo.utils.openUrl(&#x27;https://example.com&#x27;)
```

导出文件

tavo.utils.export(<name>, <data>)

旧版兼容接口。它仍保持原有的 Base64 优先自动判断行为，但已弃用；新代码请使用编码明确、默认 UTF-8 的 tavo.file.export。

data 可以是 Base64 编码内容或普通文本字符串：

```
tavo.utils.export(&#x27;叶离角色卡&#x27;, btoa(&#x27;这是一段文本或二进制，调用 btoa 转化为 base64&#x27;))  // 传 base64 数据（推荐）
tavo.utils.export(&#x27;record.txt&#x27;, &#x27;这是一段文本内容&#x27;)  // 普通文本
```

全屏图片预览

tavo.utils.preview(<src-or-img>)

传入 <img> 元素：自 v1.0.0 起

打开一个全屏的图片查看器（可缩放 / 拖动 / 保存到相册）。参数可以是 <img> 元素，也可以是以下形式的 src 字符串：

data:image/<mime>;base64,... 的 dataUrl（如 tavo.image.generate 的返回值）

http:// / https:// URL

app 内的文件相对路径（files/<...>）

src 必须为非空字符串，否则会抛 Error。打开 viewer 后立即返回，不等用户关闭。

```
// 预览生图结果
const img = await tavo.image.generate(&#x27;a calico cat sleeping on a keyboard&#x27;)
tavo.utils.preview(img)
```

如果想让气泡中的图片点击后进入全屏预览，请自行给图片绑定点击事件：

```
<img src="..." onclick="tavo.utils.preview(this)" style="cursor:pointer" />
```

传入 <img> 元素时，预览会从图片当前所在位置展开；只传 src 字符串时仍可预览，但使用普通淡入动画。

Tavo 不会自动拦截普通 HTML <img> 的点击。装饰图可以不绑定 onclick，可交互图再绑定预览事件。Markdown 图片仍会自动获得点击预览，这样脚本作者能明确控制哪些 HTML 图片可点。

询问用户

自 v1.0.0 起

await tavo.utils.ask({ question, options?, allowOther?, placeholder?, defaultValue? })

弹出原生询问界面并等待用户回答。参数必须是一个对象，question 是唯一必填字段。支持三种模式：

```
// 1. 仅文本回答
await tavo.utils.ask({ question: &#x27;我该怎么称呼你？&#x27; })

// 2. 推荐选项 + 自定义文本，allowOther 默认为 true
await tavo.utils.ask({
  question: &#x27;你喜欢哪种风格？&#x27;,
  options: [
    { value: &#x27;concise&#x27;, label: &#x27;简洁&#x27; },
    { value: &#x27;detailed&#x27;, label: &#x27;详细&#x27; },
  ],
})

// 3. 严格选项，不允许自定义文本
await tavo.utils.ask({
  question: &#x27;选择导出格式&#x27;,
  options: [&#x27;Markdown&#x27;, &#x27;纯文本&#x27;],
  allowOther: false,
})
```

options 可使用非空字符串，或 { value, label, description?, meta? } 对象。meta 是选项的简短补充信息。

placeholder 可自定义文本输入框提示，defaultValue 可预选匹配的选项，或在允许自定义回答时预填文本。

问题、选项值和标签不能为空，标准化后的选项值不能重复，未知字段会被拒绝。

allowOther 默认为 true。设置为 false 时必须提供选项，且 defaultValue 如果存在，必须匹配某个选项。

点击选项会立即返回。自定义文本会去除首尾空白，并且必须显式提交。

回答结果是 { "status": "answered", "answer": "concise", "source": "option" }，自定义文本的 source 为 "custom"。手动关闭界面会成功返回 { "status": "cancelled" }。

选择器

await tavo.utils.select(<options>, <title?>, <defaultValue?>)

弹出一个原生选择器，等待用户选择后返回所选的 value，取消则返回 null。此兼容接口保���不变，仍使用位置参数 options, title?, defaultValue?，返回类型仍为 String?。

options：选项数组，支持以下三种格式：

string[]：字符串数组，value 与显示文本相同

{ value: string, label: string }[]：对象数组，value 为返回值，label 为显示文本

{ value: string, label: string, description?: string, meta?: string }[]：完整对象，支持简短补充信息与描述

title：（可选）选择器标题

defaultValue：（可选）默认选中项的 value

```
// 1. 字符串数组
const fruit = await tavo.utils.select([&#x27;苹果&#x27;, &#x27;香蕉&#x27;, &#x27;橙子&#x27;], &#x27;选择水果&#x27;)

// 2. 对象数组（value + label）
const lang = await tavo.utils.select([
  { value: &#x27;zh&#x27;, label: &#x27;中文&#x27; },
  { value: &#x27;en&#x27;, label: &#x27;English&#x27; },
  { value: &#x27;ja&#x27;, label: &#x27;日本語&#x27; },
], &#x27;选择语言&#x27;, &#x27;zh&#x27;)

// 3. 完整对象（value + label + description + meta）
const role = await tavo.utils.select([
  { value: &#x27;warrior&#x27;, label: &#x27;战士&#x27;, description: &#x27;近战物理攻击&#x27;, meta: &#x27;推荐新手&#x27; },
  { value: &#x27;mage&#x27;,    label: &#x27;法师&#x27;, description: &#x27;远程魔法攻击&#x27;, meta: &#x27;高爆发&#x27; },
  { value: &#x27;healer&#x27;,  label: &#x27;治疗师&#x27;, description: &#x27;辅助回血&#x27;,   meta: &#x27;团队支援&#x27; },
], &#x27;选择职业&#x27;, &#x27;mage&#x27;)

if (role !== null) {
  tavo.utils.toast(`你选择了: ${role}`)
}
```

📱 App

可以通过此接口读取或操作一些应用属性，所有接口均为 tavo.app.<method>(...)

获得当前 app 版本

```
await tavo.app.version();  // 字符串：0.77.0
await tavo.app.versionNumber();  // 数字： 770
```

🏷️ 版本

可以获得当前 API 版本。Tavo 提供不同版本的 API 接口访问，例如 tavo.v1 即为 v1 版本的命名空间。

```
// 以下方式等效
tavo.get(&#x27;name&#x27;)
tavo.v1.get(&#x27;name&#x27;)
```

🔌 兼容性

提供与其他平台的兼容性支持。

触发斜杠命令（SillyTavern 兼容）

triggerSlash(<cmd>)

触发 SillyTavern 风格的斜杠命令，用于兼容从 SillyTavern 迁移过来的脚本：

```
triggerSlash(&#x27;/send Hello World | /trigger&#x27;)
```

⏳ 持续更新中

TavoJS API 现在还处于早期的 beta 阶段，我们仍在持续建设中，如果你有疑问或好的想法，欢迎到社区中进行反馈。

工具调用

让兼容的聊天模型使用 Tavo 的原生和 TavoJS 内置工具。

宏（Macros）

宏（Macros）可以动态注入一些内容在角色定义、预设、世界书、正则及其他所有生成提示词的地方。