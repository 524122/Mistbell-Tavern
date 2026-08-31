URL: https://stage1st.com/2b/forum.php?mod=viewthread&tid=2280067&extra=&ordertype=1
STATUS: 200

ROLE和预设提示词优化V2.0 - ＰＣ数码 - Stage1st - stage1/s1 游戏动漫论坛

切换到窄版

请 登录 后使用快捷导航
没有账号？ 立即注册

用户名
UID
Email

自动登录

找回密码

密码

登录

立即注册

快捷导航

归墟

购买邀请码

VTB

动漫

游戏

手游

八卦体育

模玩

影视

数码

s1义父捐助

开放式竞猜

欧美动漫

动漫鉴赏区

搜索

搜索

本版

帖子

用户

Stage1st &raquo; 论坛 &rsaquo; 主论坛 &rsaquo; ＰＣ数码 &rsaquo; ROLE和预设提示词优化V2.0

返回列表
发新帖

查看: 1482 | 回复: 1

[其他]
ROLE和预设提示词优化V2.0

[复制链接]

绝地潜兵

绝地潜兵
当前离线

卡片召唤师

精华
|
战斗力 鹅
|
回帖 0

注册时间 2024-3-22

电梯直达

楼主

发表于 2026-4-30 22:10
|
只看该作者

| 正序浏览
| 阅读模式

本帖最后由 绝地潜兵 于 2026-5-27 01:12 编辑

ROLE和预设提示词优化V2.0

本文只分析逻辑控制和提示词结构

目标：强化语义锚定强度，提高缓存命中率，高效激活MoE路由

1. 运用LLM的U型注意力机制

2. CoT嵌入: 注入think引导词，强制模型内化推理

3. Markdown / YAML-like:

伪代码结构：使用 #标题，KEY: value格式
正向引导：基于自然语言，避免出现大量禁止和if...else
符号慎用：除了系统变量之外的{}[]**少用
KEY: value一定要用英文半角标点和空格
高权重Token：可以避免设定被稀释，如CONSTRAINT等英文指令

软件环境：

1. Cherry Studio: 使用工具类提示词，扁平化集中注入。

2. Tavo-酒馆APP: 使用RPG提示词，JSON解析器自动拆分，按需注入不同槽

3. Deepseek V4 Flash/Pro，GLM-5/4.7

提示词最终都是一起注入到LLM，只是注入的顺序不同，利用机制微调内容生成的效果。

下面的是Tavo的默认预设顺序，一般固定，否则干扰叙事稳定

每个都有用，甚至有隐藏开关

1. Main Prompt

此处注意力极高 。

作用：

注入CoT引导、核心指令、叙事风格
利用高权重Token激活MoE路由中的叙事、角色、文学专家
不要太多内容,用英文描述效果更好。

示例：

<｜begin of sentence｜> 分析 {{user}} 意图 -> 调用 {{char}}性格特征 -> 生成符合世界观的回复 <｜end of sentence｜>

FIRST_PERSON_PERSPECTIVE_LOCK: No speaking for {{user}}

STYLE: Show don't tell.
复制代码

2. Lorebook Before

此处注意力中高 。

作用：

世界观前置
世界书注入位置是Char↑

示例：

World_Settings: 赛博朋克

Atmosphere: 压抑，霓虹闪烁，高科技低生活

Key_Factions:

Corp: 公司特工，冷漠高效

Gang: 街头混混，义体改造
复制代码

3. Persona Description

此处注意力中高

作用：

用户角色内容不宜多，静态数据区域，容易和AI角色产生干扰
详细背景设定，可移入世界书

示例：

User_Name: V

Occupation: 雇佣兵

Background: 试图在夜之城闯出名堂，最近接手了一个高风险任务
复制代码

4. Char Description

此处注意力中高

作用：

AI角色的外貌特征和背景故事，静态数据区域
详细背景设定，可移入世界书

示例：

Char: 强尼银手

Origin: 2023年爆炸事件幸存者，数字幽灵

Appearance:

Hair: 黑色短发，略显凌乱

Body: 赛博义体手臂，银色金属光泽

Attire: 破旧皮夹克，甚至有些地方磨损

History: 武侍乐队主唱，轰炸荒坂塔的传奇人物
复制代码

5. Char Personality

此处注意力中

作用：

角色的核心资产区
这个位置很容易受到高权重token干扰，尽量用 KEY: value 格式提高权重
不同模型表现有差异

示例：

Core: 叛逆，激进

Attitude: 愤世嫉俗，对大公司充满仇恨

Speech_Style: 粗鲁直接，喜欢用摇滚乐比喻

Mental_State: 愤怒，但也渴望改变现状
复制代码

6. Scenario

此处注意力中

作用：

填写时间、地点、环境。

示例：

Location: 来生酒吧

Time: 深夜，暴雨

Context: {{char}} 与 {{user}} 正在策划一次潜入行动，气氛紧张
复制代码

7. Enhance Definitions {false}

此处注意力中低

作用：

覆盖原有性格。
通常保持默认关闭，维持角色性格稳定
很重要的开关，但位置权重不行，受上限文干扰大

8. Auxiliary Prompt_nsfw {false}

此处注意力中低

作用：

特殊风格辅助指令，默认关闭，在预设中手动开启和填充内容
很重要的开关，但位置权重不行，需要注入大量 高权重Token 强行激活MoE

9. Lorebook After

此处注意力中低

作用：

作为世界书事件信息补充，注入位置Char↓

示例：

Status_Check:

Current_State: 争吵中

Focus: 强尼正在质疑 {{user}} 的计划过于保守
复制代码

10. Chat Examples

此处注意力中

作用：

提供少样本学习，激活LLM的文学专家MoE
下面是AI瞎编的，具体的可以参考小说片段，但自己手搓更爽

示例：

{{user}}: 我们不能直接冲进去，那是自鲨

{{char}}: 自鲨？哈！这就是你和那些公司狗的区别。要么烧成灰，要么成为传奇，没有中间地带。
复制代码

11. Chat History

此处注意力中 → 高

具体要看LLM和上下文长度，设置50条上下文就容易幻觉

作用：

存储和注入上下文

注意：

世界书注入@Depth0_4就在这里，0是最后一句话，4是倒数第四句
权重都不如下面的PHI高

12. Post-History Instructions_jailbreak {false}

此处注意力极高

作用：

默认是关闭的，最后的指令注入点，强制约束输出格式
容易漂移的LLM（如DS4），必须打开检查清单；GLM5/4.7可以关闭

示例：

【角色沉浸要求】在你的思考过程（<think/>标签内）中，请遵守以下规则：

1. 请以角色第一人称进行内心独白，用括号包裹内心活动，例如"（心想：……）"或"(内心OS：……)"

2. 用第一人称描写角色的内心感受，例如"我心想""我觉得""我暗自"等

3. 思考内容应沉浸在角色中，通过内心独白分析剧情和规划回复
复制代码

收藏 12

回复

使用道具
举报

绝地潜兵

绝地潜兵
当前离线

卡片召唤师

精华
|
战斗力 鹅
|
回帖 0

注册时间 2024-3-22

2 #

楼主 |
发表于 2026-5-27 00:55
|
只看该作者

更新了V2.0：

从Cherry转移到了酒馆类APP
注意力分析也主要以酒馆类预设框架为主
头尾增加了CoT注入内容
修正1.0部分描述，其实是提高了缓存命中，而不是给AI看的游戏状态面板
高权重token其实是用少量样本激活MoE专家路由
（文学大师和审核大师都可以视为不同的MoE专家，之前发现的DSV4F变成死灵大师其实是激活了审核大师，写出来的东西吓屎人 ）

回复

使用道具
举报

返回列表
发新帖

高级模式

B
Color
Image
Link
Quote
Code
Smilies

您需要登录后才可以回帖 登录 | 立即注册

本版积分规则
发表回复

Archiver | 手机版 | 小黑屋 | 上海互联网违法和不良信息举报中心 | 网上有害信息举报专区 | 962110 反电信诈骗 | 举报电话 021-62035905 | Stage1st
( 沪ICP备13020230号-1 | 沪公网安备 31010702007642号 )

GMT+8, 2026-8-31 23:53
, Processed in 0.060392 second(s), 9 queries
, Gzip On, Redis On.

Powered by Discuz! X3.5

&copy; 2001-2026 Discuz! Team .

快速回复
返回顶部

返回列表