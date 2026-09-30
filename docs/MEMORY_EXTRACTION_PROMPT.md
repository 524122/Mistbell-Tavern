# 记忆提取提示词优化方案

## 当前提示词的优点
- 结构化输出（triplet + metadata）
- 详细的字段说明和约束
- 示例丰富，覆盖多种场景
- 有 importance 标尺指导

## 优化版本（推荐）

```
你是对话记忆提取器。分析下方对话，提取值得长期保存的事实。

**输出纯 JSON（无 Markdown、无解释）：**
{
  "triplets": [
    {
      "subject": "实体名（user/角色/地点/物品/组织）",
      "relation": "关系类型",
      "object": "取值",
      "memoryType": "类型",
      "importance": 0.0-1.0,
      "tags": ["2-6个关键词"],
      "aliases": ["0-4个别名/同义说法"],
      "rawText": "10-80字第三人称陈述"
    }
  ]
}

**relation 可选值：**
name, likes, dislikes, prefers, wants, boundary, afraid_of, promised, located_at, member_of, role_is, has_item, title_is, told_user, confirmed, other

**memoryType 可选值：**
fact, event, emotion, core, preference, identity, relationship, goal, note, character_info, item, location

**提取规则：**
1. **范围**：身份、稳定偏好、长期边界、关系、目标、承诺、已确认设定、有持续影响的事件
2. **忽略**：临时情绪、空泛承诺、纯状态栏（HP/坐标/姿势）、礼貌用语、复述
3. **格式**：
   - rawText 用第三人称陈述，主语 user 保留英文
   - 一条记忆一个原子事实，不要合并
   - 方括号内的设定信息要提取（地名/身份/境界/职位）
4. **特殊处理**：
   - 角色"告诉"user 某事 → relation 用 `told_user`
   - user 自述或已确认 → 视为 user 属性
   - 事件有时间线索（明天/上周）→ 写进 rawText
   - 与已有记忆冲突 → rawText 显式写"已改为"
5. **数量**：整段最多 10 条，优先 importance ≥ 0.6

**importance 标尺：**
- 0.85-1.0：重大创伤、生死、誓言、核心身份
- 0.7-0.85：身份、长期边界、明确目标、关键承诺
- 0.5-0.7：稳定偏好、重要关系、项目状态、持续影响的事件
- 0.35-0.5：一般背景、一次性事件、世界设定细节

**示例：**
"我叫墨轩" → {"subject":"user","relation":"name","object":"墨轩","memoryType":"identity","importance":0.9,"tags":["名字","身份"],"aliases":["墨轩","名字"],"rawText":"user 的名字是墨轩"}

"我不喜欢被叫主人" → {"subject":"user","relation":"boundary","object":"不喜欢被叫主人","memoryType":"preference","importance":0.8,"tags":["称呼","边界"],"aliases":["主人","称呼偏好"],"rawText":"user 不喜欢被叫主人"}

"[凌月璃♀人族-玉臀宗长老-元婴]" → {"subject":"凌月璃","relation":"member_of","object":"玉臀宗","memoryType":"character_info","importance":0.75,"tags":["玉臀宗","长老","元婴"],"aliases":["凌月璃","玉臀宗长老"],"rawText":"凌月璃是玉臀宗长老，元婴期修为"}

"艾琳说：我欠你一次人情" → {"subject":"艾琳","relation":"told_user","object":"欠 user 一次人情","memoryType":"relationship","importance":0.7,"tags":["人情","承诺"],"aliases":["欠人情"],"rawText":"艾琳说欠 user 一次人情"}

无可提取内容返回 `{"triplets": []}`

**对话片段：**
%s
```

## 极简版本（token 消耗更低）

```
提取对话中的长期记忆，输出 JSON（无解释）：
{"triplets":[{"subject":"实体","relation":"关系","object":"值","memoryType":"类型","importance":0-1,"tags":[],"aliases":[],"rawText":"第三人称陈述"}]}

**提取：**
- 身份/名字/偏好/边界/目标/承诺/关系/已确认设定/持续影响的事件
- 方括号内设定信息（地名/身份/境界/职位）

**忽略：**
- 临时情绪/状态栏/礼貌用语/复述

**规则：**
- rawText 用第三人称，主语 user 保留英文
- 一条一个事实，最多 10 条
- 角色告诉 user → relation 用 `told_user`
- importance：0.9=核心身份/誓言，0.7=边界/目标，0.5=偏好/关系，0.35=一般事实

**示例：**
"我叫墨轩" → {"subject":"user","relation":"name","object":"墨轩","memoryType":"identity","importance":0.9,"tags":["名字"],"aliases":["墨轩"],"rawText":"user 的名字是墨轩"}

"[凌月璃-玉臀宗长老-元婴]" → {"subject":"凌月璃","relation":"member_of","object":"玉臀宗","memoryType":"character_info","importance":0.75,"tags":["玉臀宗","长老"],"aliases":["凌月璃"],"rawText":"凌月璃是玉臀宗长老，元婴期修为"}

无内容返回 `{"triplets":[]}`

**对话：**
%s
```

## 针对特定场景的优化

### 1. 修仙/玄幻场景强化版
在标准版基础上增加：
```
**修仙设定优先级：**
- 境界/修为/功法/法宝 → importance +0.1
- 宗门/势力/身份 → importance +0.15
- 天赋/体质/血脉 → importance +0.2
- 因果/誓言/机缘 → importance +0.2

**特殊标注：**
境界变化写明"从 X 突破到 Y"，法宝/功法写明品阶
```

### 2. 现代都市场景版
```
**职场/生活优先级：**
- 职业/职位/公司 → character_info, importance 0.7
- 住址/常去地点 → location, importance 0.5
- 家人/朋友关系 → relationship, importance 0.6-0.8
- 健康/过敏/禁忌 → preference, importance 0.85
```

### 3. 多语言混合版
```
**语言处理：**
- 英文对话 → rawText 用英文第三人称
- 中英混合 → 保持原语言，主体用对话主要语言
- 专有名词保持原文（人名/地名/术语）
```

## 实施建议

### 方案 A：直接替换默认提示词
修改 `MemoryExtractionService.kt:223` 的 `getDefaultPrompt()` 为优化版本

### 方案 B：添加到设置页让用户选择
1. 预设 3-4 个模板（通用/极简/修仙/现代）
2. 用户在设置页选择或自定义
3. 存储在 `settings.memory_extraction_prompt`

### 方案 C：智能切换
根据角色卡关键词自动选择：
- 检测到"宗门/修为/境界" → 修仙版
- 检测到"公司/职位/住址" → 现代版
- 其他 → 通用版

## 关键改进点

1. **结构更清晰**：用粗体分隔区块，模型更容易解析
2. **约束前置**：relation/memoryType 可选值提前列出
3. **示例精简**：保留最典型的 4 个，去掉冗余
4. **强调 JSON**：多处提醒"无解释/无 Markdown"，减少截断风险
5. **数量控制**：明确"最多 10 条"，避免输出过长被截断
6. **极简版本**：token 消耗降低约 40%，适合高频调用

## 测试验证

部署后用以下对话测试：

```
User: 我叫林晨，是个程序员，对海鲜过敏
Assistant: 很高兴认识你林晨！我会记住你的职业和过敏信息的。

User: 对了，我特别讨厌被人打断说话
Assistant: 明白了，我会注意不打断你。这是很重要的边界。
```

预期输出：
- user 名字 林晨 (importance 0.9)
- user 职业 程序员 (importance 0.7)
- user 过敏 海鲜 (importance 0.85)
- user 边界 不喜欢被打断 (importance 0.8)
