URL: https://docs.tavoai.dev/en/guides/ejs-template/
STATUS: 200

Guide

Copy Page

🧩 EJS Templates

Since v0.87.0

📖 Overview

EJS templates let you embed <% %> syntax in every prompt-building field — character cards (description, personality, scenario, greeting, etc.), presets, lorebooks, and regex — to write conditionals, loops, and variable logic that Macros can't do.

Relationship to Macros: both apply to the same fields. Macros ({{char}}, {{setvar}}, …) are great for simple injection; EJS handles logic, variable math, and loops.

Render order: EJS renders first, then the result is handed to the {{}} macro engine. So EJS output can still contain macros, which the macro engine processes afterward.

Based on EJS: Tavo ships its own EJS engine supporting the most common subset (tag syntax + variables/logic), but not include / partial / custom delimiters and other advanced features.

⚙️ Enable it first

EJS is on by default. If it isn't working, check Settings → Chat Settings → Compatibility → Enable EJS templates (the first item in that group).

🚀 Quick start

Paste into any prompt field (the character greeting is the most visible one):

```
<% if (getvar("vip", "no") === "no") { %>Welcome, guest.<% } else { %>Welcome back, valued member!<% } %>
```

When vip is unset it renders as: Welcome, guest.

🏷️ Tag syntax

Syntax
Purpose
Example → Output

<%- expr %>
Output (raw, unescaped)
<%- "1<2" %> → 1<2

<%= expr %>
Output (HTML-escaped)
<%= "1<2" %> → 1<2

<% stmt %>
Run logic, no output
<% var n = 1 %> → (none)

<%# comment %>
Comment, not executed
a<%# note %>b → ab

print(x)
Output inside a logic block
<% print("hi") %> → hi

<%% %%>
Output literal tags
<%% raw %%> → <% raw %>

-%>
Trim the newline right after the closing tag
merges two lines

<#escape-ejs>…</#escape-ejs>
Tags inside are treated as literal text
<#escape-ejs><% x %></#escape-ejs> → <% x %>

Loop example:

```
<% for (var i = 1; i <= 3; i++) { %><%= i %><% } %>
```

Output: 123

🔣 Variables

Tavo provides a set of variable functions bridged to the built-in two-layer variable store.

Function
Description

getvar(key)
Read a variable; empty string if missing

getvar(key, default)
Return default when missing

getvar(key, {scope, defaults})
Read from a specific layer / default

setvar(key, value, {scope})
Write a variable (stored as-is, no type parsing)

incvar(key, n=1, {scope})
Increment (default +1)

decvar(key, n=1, {scope})
Decrement (default -1)

delvar(key, {scope})
Delete a variable

```
<% setvar("hp", 100) %>HP: <%- getvar("hp") %>          → HP: 100
<% setvar("code", "007") %>ID: <%- getvar("code") %>    → ID: 007 (leading zeros kept)
<% incvar("n") %><% incvar("n") %>n=<%- getvar("n") %>  → n=2
<%- getvar("missing", "fallback") %>                     → fallback
```

Keys support dot paths, so you can read/write nested values directly (no need to fetch the object first):

```
<% setvar("o.a.b", 42) %><%- getvar("o.a.b") %>   → 42
```

Scope:

chat (default): per-conversation variable, persisted with the conversation (local is a compatible alias).

global: global variable, persisted immediately on write.

Without scope, getvar checks chat first, then global.

For getvar, cache behaves the same as no scope; message / initial behave the same as chat.

For setvar, anything other than global is treated as chat.

```
<% setvar("g", 1, {scope: "global"}) %><%- getvar("g", {scope: "global"}) %>
```

lodash-style _: _.get(obj, path, default), _.has(obj, path), _.set(obj, path, value), _.unset(obj, path), _.cloneDeep(obj). Paths accept both a.b[0].c and a.b.0.c.

```
<% setvar("o", {a: {b: 42}}) %><%- _.get(getvar("o"), "a.b") %>   → 42
```

🧩 Built-in constants

These constants are injected as globals; use them directly.

Constant
Meaning

charName
Current character name

userName
Current persona name

lastUserMessage
Raw text of the latest non-hidden user message

lastCharMessage
Raw text of the latest non-hidden character message

characterId
Current character ID

```
Hi, I'm <%- charName %>.   → Hi, I'm <character name>.
```

Common uses:

characterId: branch by character in group chats, or namespace per-character variables, e.g. getvar("affinity_" + characterId).

lastUserMessage: conditionally inject based on what the user just said (detect a keyword, append a hint), or feed it to logic / regex.

lastCharMessage: continuity, state updates, or avoiding repetition based on the character's last reply.

📍 Where it works

EJS is rendered uniformly during prompt assembly, covering:

Character cards: description / personality / scenario / greeting (incl. alternate greetings) / example dialogue / system prompt, etc.

Lorebooks: entry content and scan keywords

Presets: every prompt section

Regex: match / replace / trim strings

Others: translation, image-caption injection, group chat, etc.

⚠️ Notes

Whole-template fallback

If any single EJS tag in a field errors (syntax error, calling a non-existent method, etc.), the entire field falls back to its original text, unrendered — so nothing crashes and no content is lost. So when debugging, if you see "tags didn't take effect, it's all raw text," it usually means some EJS in that field is broken. For the same reason, don't mix a deliberately-broken / demo tag with working tags in the same field.

Order vs. macros: EJS first, then {{}} macros; EJS output may still contain macros. E.g. <%- "{{char}}" %> is first output by EJS as {{char}}, then replaced by the macro engine with the character name.

Escaped output: <%= %> produces HTML entities (e.g. <); if Advanced Rendering is on in chat, entities may be re-rendered. Check the Context Log to see the raw text.

Differences from upstream EJS: only the common subset is supported — no include / partial / custom delimiters, etc.

🔗 Related

Macros

TavoJS API

Regex

Official EJS docs

Macros

**Macros** can dynamically inject content into character definitions, presets, World Books, regular expressions, and all other prompt generation areas.

MCP Server

Connect an AI agent to Tavo through the built-in MCP Server.