URL: https://docs.tavoai.dev/en/guides/supported-macros/
STATUS: 200

Guide

Copy Page

Macros

⚡ Continuously Updating

Below is a summary of macro parameters currently supported by Tavo, with ongoing updates.

Tavo is still under active development. If you have great ideas, welcome feedback from the community.

How to Use

Macros can dynamically inject content into character definitions, presets, World Books, regular expressions, and all other prompt generation areas.

For example, in the character Song Yi definition, we can write:

```
{{char}} is the CEO of the Song Group.    // Song Yi is the CEO of the Song Group
{{user}} is {{char}}'s secretary.        // Yuelin is Song Yi's secretary (assuming the user identity is Yuelin; this will change automatically when the user identity switches)
```

We also often guide response methods in presets:

```
Your primary task is to understand and respond to {{user}}'s emotions. When {{user}} expresses emotions, always prioritize giving a sincere and empathetic response rather than rushing to advance the plot.
```

Or describe historical and world background in World Books:

```
{{user}} was transported to another world by a time-space vortex in 1984, met {{char}}, and reunited on {{date}}.
```

Or use parameter changes in World Books and regular expressions:

```
// World Book: When "Healing Spell" is entered
{{user}} used the healing spell, restoring full health and reducing magic points by 10. {{setvar::hp::100}} {{addvar::mp::-10}}
// Regular Expression: Display status bar at the end $
{{user}} Current Status:
Health: {{getvar::hp}}
Magic Points: {{getvar::mp}}
```

General Syntax

The general syntax for macros is {{<macro_name>}}, e.g., {{char}}
If parameters are required, use double colons to separate them, e.g., {{getvar::hp}}

Simple conditionals

Macros can keep or remove a block of text based on whether another macro has a non-empty value:

```
{{#if persona}}
User persona: {{persona}}
{{/if}}
```

When {{persona}} expands to a non-empty value, the content inside the block is kept. When its value is empty or the macro is unavailable, the entire block is removed. You can use multiple if blocks and nest them.

Use EJS for complex conditions

Macro if only checks for a non-empty value. It does not support else, number or text comparisons, && / ||, or direct checks of {{getvar}} variables. The values 0 and false are non-empty, so they are also treated as true.

For branching, comparisons, loops, or variable logic, use EJS Templates:

```
<% if (getvar("hp", 0) > 0) { %>
The character can still act.
<% } else { %>
The character can no longer act.
<% } %>
```

Macro Reference

Characters

{{user}} User identity name

{{char}} Character name

{{group}} / {{charIfNotGroup}} All characters in a group chat, separated by commas

{{groupNotMuted}} Characters in a group chat who are not muted

Character Card

{{charDescription}} Character settings, compatible with legacy version {{description}}

{{charPersonality}} Character personality traits, compatible with legacy version {{personality}}

{{charScenario}} Scene description in the character card

{{scenario}} Scene description (prioritizes conversation-level scene description; if unavailable, uses character scene description)

{{persona}} User identity description

{{charPrompt}} Content of System Instructions - Main Prompt

{{charInstruction}} / {{charJailbreak}} Content of System Instructions - Post-History Instructions

{{mesExamples}} Conversation examples (rendered)

{{mesExamplesRaw}} Conversation examples (original text)

{{charVersion}} Additional Information - Character version

{{charCreatorNotes}} Additional Information - Notes, compatible with legacy version {{creatorNotes}}

Messages

{{lastMessage}} Last message

{{input}} User input message

{{lastUserMessage}} Last user message

{{lastCharMessage}} Last character message

Date and Time

{{time}} Current time

{{date}} Current date

{{weekday}} Current weekday

{{isotime}} ISO time (format: hour:minute)

{{isodate}} ISO date (format: year-month-day)

{{idleDuration}} Duration since the last message, compatible with legacy version {{idle_duration}}

{{time::UTC+9}} Time in UTC+9 (format: hour:minute), compatible with legacy version {{time_UTC±<timezone>}}

Random Numbers

{{random::1::3::5}} Randomly selects one number from 1, 3, 5, compatible with legacy version {{random::1,3,5}}

{{roll::3d6}} Roll dice, 3d6 = three six-sided dice

Formatting

{{newline}} Line break

{{newline::<number of lines>}} Add multiple line breaks

{{space}} Space

{{space::<number of spaces>}} Add multiple spaces

{{trim}} Remove leading/trailing spaces and line breaks

{{noop}} Empty

{{//<comment>}} Comment, renders as empty

Chat Variables

Chat variables are only available within the current chat

{{setvar::<variable_name>::<value>}} Set a variable, accepts three types: list (JSON format), number, or text

{{addvar::<variable_name>::<value>}} Add a value to a variable: appends to the end of a list, uses addition for numbers, or concatenates text to the end

{{incvar::<variable_name>}} Variable +1

{{decvar::<variable_name>}} Variable -1

{{getvar::<variable_name>}} Get the value of a variable

Global Variables

{{setglobalvar::<variable_name>::<value>}} Set a variable, accepts three types: list (JSON format), number, or text

{{addglobalvar::<variable_name>::<value>}} Add a value to a variable: appends to the end of a list, uses addition for numbers, or concatenates text to the end

{{incglobalvar::<variable_name>}} Variable +1

{{decglobalvar::<variable_name>}} Variable -1

{{getglobalvar::<variable_name>}} Get the value of a variable

Legacy Parameters

<USER> User identity name

<CHAR> / <BOT> Character name

<GROUP> / <CHARIFNOTGROUP> All characters in a group chat, separated by commas

Using Variable Macros

Variable macros are like giving the AI a small notebook it carries with it. You write data into it, and it can always look it up—no confusion, no forgetting.

```
❌ Without macros
You say “My HP is 97,” but after several turns, the AI says “Your HP should be 100, right?” — because it forgot what you said earlier.

✅ With macros
You send `{{setvar::hp::97}}`, and the AI writes “97” into its notebook.
No matter how many turns pass, it can always check and tell you “Your HP is 97.”
```

What Each Variable Macro Does

(1) setvar【Write】

{{setvar::variable_name::value}}

Stores a value into the AI’s notebook.
It can store numbers, text, or even a list.
If the variable already has a value, it will be overwritten.

```
Example: {{setvar::hp::100}} {{setvar::name::Xiao Ming}} My name is Xiao Ming, HP 100, start!
```

```
Best use cases:
🎮 Set initial HP at the start of a game
📝 Write down today’s tasks
😊 Record your current mood
🔄 Reset a value
```

(2) addvar【Add】

{{addvar::variable_name::value}} adds something to the existing value.

If it’s a number → performs addition (originally 50, +20 becomes 70)
If it’s a list → appends a new element
If it’s text → concatenates to the end

```
Example: Defeated a goblin! {{addvar::gold::15}} {{addvar::bag::"Goblin Ear"}}
```

```
Best use cases:
🍎 Pick up items and add to inventory
💰 Earn money, increase points
📜 Add a new task to a list
```

(3) incvar / decvar 【+1】/【-1】

{{incvar::variable_name}} {{decvar::variable_name}}

incvar automatically +1, decvar automatically -1
A shortcut version of addvar, no need to specify numbers
Want -3? Just write {{decvar::hp}} three times

```
Example: {{decvar::hp}} {{decvar::hp}} {{decvar::hp}} Hit by a dragon claw three times! {{incvar::water}} Drank a glass of water~
```

```
Best use cases:
❤️ Lose HP when taking damage
🔄 Turn +1
💧 Track water intake
⏳ Countdown -1
```

(4) getvar【Read】

{{getvar::variable_name}}

Retrieves the stored value and inserts it into your message
The AI does not see “getvar::hp”, but the actual value stored in hp

```
Example: My status: Name {{getvar::name}}, HP {{getvar::hp}}, Inventory {{getvar::bag}}
```

```
Best use cases:
📊 Check current status
✅ Verify values are correct
🤖 Let AI make decisions based on values
```

Use Cases

【Bottom-left of input box】→ Click  → Click 【Macro Helper 】, find the corresponding variable macro and click to auto-fill into the input box.
You only need to change the variable name and value to what you want.

(1) Preset Prompt

Let the AI automatically execute certain macros in every reply (e.g., “HP must decrease whenever damage occurs”)

```
🗺️ Example
[Your reply must follow these rules:
1. Whenever a character takes damage in the narration, add at the beginning:
  {{decvar::hp}} (repeat once per 1 damage)
2. At the end of each reply, append a status bar:
  [HP: {{getvar::hp}} | Turn: {{getvar::round}}]
3. After each reply, increase turn count by 1:
  {{incvar::round}}]
```

(2) Lorebook Entry

You can set trigger keywords — when these words appear in the conversation, this content will be injected into the context. Combined with variable macros, it enables automatic initialization when entering locations or triggering events.

```
🗺️ Example: Automatically initialize combat variables when entering "Dungeon"
Trigger keywords: dungeon, entrance
[You have entered the dungeon. Initialize combat state:
{{setvar::dungeon_floor::1}}
{{setvar::enemies_left::5}}
{{setvar::trap_count::0}}
Remember: each time an enemy is defeated, execute {{decvar::enemies_left}}]
```

```
📖 Example: Load new settings when entering Chapter 2
Trigger keywords: chapter 2, frost city outskirts
[{{setglobalvar::chapter::2}}
{{setglobalvar::location::Wasteland Outside Frost City}}
{{addglobalvar::characters::["Su Wu"]}}
A new character, Su Wu, appears. She is a silent guide from the snowy lands…]
```

(3) Regex Script

Input shorthand expansion — use short commands instead of long macros

```
Input: -hp5 → automatically becomes {{decvar::hp}}{{decvar::hp}}{{decvar::hp}}{{decvar::hp}}{{decvar::hp}}
Input: +gold20 → automatically becomes {{addvar::gold::20}}

Regex match -hp(\d+) → replace with corresponding number of decvar macros for faster input
```

Need more complex logic?

Macros are suitable for simple injection and non-empty conditional checks. For else, comparisons, loops, or variable logic, use EJS Templates.

TavoJS API

The TavoJS API is a set of JavaScript interfaces for players and creators, enabling users to access powerful functionalities and high playability when JavaScript support is enabled.

EJS Templates

EJS templates let you write logic, read/write variables, and loop inside any prompt field — character cards, presets, lorebooks, regex — using <% %>. An advanced complement to Macros.