URL: https://docs.tavoai.dev/en/guides/javascript-api/
STATUS: 200

Guide

Copy Page

🧩 TavoJS API

📖 Overview

The TavoJS API is a set of JavaScript interfaces for players and creators, enabling users to access powerful functionalities and high playability when JavaScript support is enabled.

Plugin lifecycle boundary: generation:prepare, generation:success,
generation:error, and generation:cancelled are not pure TavoJS or
tavo.events APIs. Only installed plugin entry scripts can register them
with tavo.plugin.on(...); HTML fragment scripts cannot. See
Plugin Development.

✨ Vibe Coding Friendly

For non-professional programming players, we strongly recommend copying this document to an AI and letting the AI generate highly playable code that integrates closely with Tavo!

Errors During Chat Transitions

Rejected TavoJS operations fail with a normal JavaScript Error and may expose
stable error.code and structured error.details. During a chat transition, a
call that is not yet bound to the current chat receives:

```
error.code === &#x27;conflict&#x27;
error.details.reason === &#x27;chat_runtime_not_ready&#x27;
error.details.retryable === true
```

This means the request did not execute and may be issued again after the new
runtime for the current chat is ready. Tavo does not retry it automatically. An
installed plugin entry script should rerun the relevant logic after the new
chat&#x27;s chat:opened event, not loop immediately from the stale runtime. Other
scripts should abandon the attempt and wait for their next lifecycle execution.

⚙️ Variables

Variables are used to store data. Native JavaScript variables only survive within the page and are lost after refresh, so we provide a set of variable APIs to help users store data long-term.

Get a Variable

tavo.get(<name>[, <scope>])

For example:

```
let age = tavo.get(&#x27;age&#x27;)  // Get "age" from chat variables
let bestScore = tavo.get(&#x27;bestScore&#x27;, &#x27;global&#x27;)  // Get global best score
let hp = tavo.get(&#x27;hp&#x27;, &#x27;message&#x27;)  // Get "hp" on the current message (floor)
```

Scope

Scope defines where variables are available. We support the following:

chat: Chat scope, the default scope. Variables are only accessible in the current chat. You should always prioritize this scope (exportable with the chat).

global: Global scope. Variables can be accessed and saved across conversations (so naming collisions require extra care).

message: Message scope. Variables live on a single message (floor) and are removed when that message is deleted (see Message Scope below).

⚠️ Variables in different scopes are fully isolated; there is no cross-scope overriding.

Set a Variable

tavo.set(<name>, <value>[, <scope>])

For example:

```
tavo.set(&#x27;age&#x27;, 16)  // Set chat variable age = 16
tavo.set(&#x27;Lily_lover&#x27;, &#x27;Colin&#x27;, &#x27;global&#x27;)  // Set a global variable: Lily&#x27;s lover is Colin
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 32, location: &#x27;Cave&#x27; })  // Set current chat status: HP 100, MP 32, location Cave
```

Update a Variable

tavo.update(<name>, <value>[, <scope>])

The biggest difference from tavo.set(...) is that it supports partial updates for object values, for example:

```
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 32 })  // status = { hp: 100, mp: 32 }
tavo.update(&#x27;status&#x27;, { hp: 70 })  // status = { hp: 70, mp: 32 }
tavo.update(&#x27;status&#x27;, { status: &#x27;poisoned&#x27; })  // status = { hp: 70, mp: 20, status: &#x27;poisoned&#x27; }
```

Delete a Variable

tavo.unset(<name>[, <scope>])

For example:

```
tavo.set(&#x27;age&#x27;, 16)  // age = 16
tavo.unset(&#x27;age&#x27;)  // age = null
```

Variable Paths

When operating on variables, path-style access is supported, for example:

```
tavo.set(&#x27;status&#x27;, { hp: 100, mp: 50 })
tavo.get(&#x27;status.hp&#x27;)  // 100
tavo.unset(&#x27;status.hp&#x27;)  // status = { mp: 50 }
```

Message Scope

Since v0.88.0

Besides chat / global, a variable can also live on a single message (floor) and is removed when that message is deleted. This is great for binding state to a specific reply — a character&#x27;s mood, HP, or turn counter on that floor.

Pass the string &#x27;message&#x27; to target the current host floor (the message running this code; when not executed inside a bubble, it falls back to the last floor). Or pass an object { scope: &#x27;message&#x27;, id: n } to write to a specific message by its id (a stable primary key that does not drift when messages are deleted).

```
tavo.set(&#x27;hp&#x27;, 100, &#x27;message&#x27;)                       // Write to the current floor (host semantics)
let hp = tavo.get(&#x27;hp&#x27;, &#x27;message&#x27;)                   // Read "hp" on the current floor
tavo.set(&#x27;hp&#x27;, 50, { scope: &#x27;message&#x27;, id: 2338 })   // Write to a specific message by id
tavo.unset(&#x27;hp&#x27;, &#x27;message&#x27;)                          // Delete "hp" on the current floor
```

Use Variables in Prompts

You can send variables to the model through prompts by using macros:

{{getvar::<name>}} gets a variable (scope: chat, current chat)
{{getglobalvar::<name>}} gets a variable (scope: global)

For example:

```
{{char}} has a new name: {{getvar::name}}
{{user}} current HP: {{getvar::status.hp}}
Global all-time best score: {{getglobalvar::highestScore}}
```

For more variable macros, see related page

Example

Copy the following into any bubble to see it in action.

```
<h3>Variable API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 80px; max-height: 300px; overflow-y: auto;"></pre>
<p style="margin: 4px 0; font-size: 12px; opacity: .6;">chat scope (saved with current chat)</p>
<div style="display: grid; grid-template-columns: auto auto auto;">
  <button onclick="varGet()">tavo.get (chat)</button>
  <button onclick="varSet()">tavo.set (chat)</button>
  <button onclick="varUnset()">tavo.unset (chat)</button>
</div>
<p style="margin: 8px 0 4px; font-size: 12px; opacity: .6;">global scope (saved across chats and still exists after switching conversations)</p>
<div style="display: grid; grid-template-columns: auto auto auto;">
  <button onclick="varGetGlobal()">tavo.get (global)</button>
  <button onclick="varSetGlobal()">tavo.set (global)</button>
  <button onclick="varUnsetGlobal()">tavo.unset (global)</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function varGet() {
    const val = await tavo.get(&#x27;status&#x27;);
    log(&#x27;chat / status =&#x27;, val ? val : &#x27;(not set yet)&#x27;);
  }
  async function varSet() {
    await tavo.set(&#x27;status&#x27;, { hp: 100, mp: 32, location: &#x27;Cave&#x27; });
    tavo.utils.toast(&#x27;Set chat / status&#x27;);
  }
  async function varUnset() {
    await tavo.unset(&#x27;status&#x27;);
    tavo.utils.toast(&#x27;Deleted chat / status&#x27;);
  }
  async function varGetGlobal() {
    const val = await tavo.get(&#x27;globalScore&#x27;, &#x27;global&#x27;);
    log(&#x27;global / globalScore =&#x27;, val ? val : &#x27;(not set yet)&#x27;);
  }
  async function varSetGlobal() {
    const s = await tavo.get(&#x27;globalScore&#x27;, &#x27;global&#x27;);
    const score = (s ? s : 0) + 1;
    await tavo.set(&#x27;globalScore&#x27;, score, &#x27;global&#x27;);
    tavo.utils.toast(`globalScore +1, current: ${score}`);
  }
  async function varUnsetGlobal() {
    await tavo.unset(&#x27;globalScore&#x27;, &#x27;global&#x27;);
    tavo.utils.toast(&#x27;Deleted global / globalScore&#x27;);
  }
</script>
```

💬 Messages

You can use this interface to read or modify messages. All message interfaces are tavo.message.<method>(...).

Find Messages

await tavo.message.find(<indexRange>[, <filter>])

Finds messages by floor range indexRange and filter filter, and returns an array:

indexRange type number | array: floor range

When number:

Gets the message at the specified floor

Floors start from 0: first message is 0, second is 1, and so on

Negative numbers count from the end: -1 is the last message, -2 is the second last, and so on

When array:

[start, end], for example [2, 4], gets messages 2, 3, and 4 (inclusive)

[start] means from start to the end

[0, end] means from floor 0 to end

[] | null | undefined means all floors

In all cases, this interface always returns an array. If a specified floor does not exist, it returns [].

filter type object: filter conditions

role type string: filter by role, optional values (default: all):

&#x27;system&#x27; system messages

&#x27;assistant&#x27; character messages

&#x27;user&#x27; user messages

hidden type boolean: filter by hidden flag, optional values (default: all):

true only hidden messages

false only non-hidden messages

characters type array: character ID array, only keeps messages sent by these characters

Message object format:

```
{
  id: 2338,  // Message ID
  characterId: 34,  // Character ID (assistant messages only)
  content: &#x27;Hello!&#x27;,  // Message content
  hidden: false,  // Whether this is a hidden message
  role: &#x27;assistant&#x27;  // Message role
}
```

For example:

```
await tavo.message.find(2)  // Get the 3rd message
await tavo.message.find([3, 100])  // Get messages 3-100; if only 50 exist, returns 3-50
await tavo.message.find(-1, { role: &#x27;user&#x27; })  // Last user message
await tavo.message.find([10], { hidden: false })  // Non-hidden messages with floor >= 10
```

Get a Single Message

await tavo.message.get(<messageId>)

Gets one message by message ID. Returns null if the ID is invalid or the message does not exist.

```
let msg = await tavo.message.get(2338)  // Get message with ID 2338
```

Get Current Message

await tavo.message.current()

Gets the message object for the message where this code is executed. Fields are the same as "Message object format" above and tavo.message.get.

Typical use cases: read role info on this message (tavo.character.get(currentMessage.characterId)), or modify this message (tavo.message.update).

```
const self = await tavo.message.current()
console.log(self)
```

Get Message Count

await tavo.message.count()

Gets the total number of messages in the current chat (including hidden messages). Commonly used to locate the last floor: first floor is 0, last floor is messageCount - 1.

```
let lastIndex = await tavo.message.count() - 1
console.log(lastIndex)
```

Append a Message

await tavo.message.append(<message>)

Appends a message to the end of the current chat. Returns the new message ID on success, or null on failure.

message type object, common fields:

content type string: message content (required)

role type string: &#x27;assistant&#x27; | &#x27;user&#x27; (defaults to &#x27;assistant&#x27;)

characterId type number: when role = &#x27;assistant&#x27;, you can specify the speaker character ID (optional in 1-on-1 chat, required in group chat)

hidden type boolean: whether hidden (default false)

Note:

If role = &#x27;assistant&#x27; and characterId is omitted, the character is auto-inferred from current session context.

If no valid character can be inferred, or the character does not belong to current chat, creation fails and returns null.

For example:

```
let newId = await tavo.message.append({
  role: &#x27;assistant&#x27;,
  characterId: 34,
  content: &#x27;This is an appended message&#x27;,
  hidden: false,
})
```

In 1-on-1 chats, creating a visible message can be simplified:

```
let newId = await tavo.message.append({
  content: &#x27;This is an appended message. role defaults to assistant; in 1-on-1 chats, character is auto-inferred; hidden defaults to false&#x27;,
})
```

Update a Message

await tavo.message.update(<message>, <opts?>)

Updates an existing message by message ID. Returns the message ID on success, or null on failure.

message type object, common fields:

id type number: message ID to update (required)

content type string: updated message content (required)

reasoning type string: reasoning content (optional, empty string clears it)

hidden type boolean: whether hidden (optional, defaults to false)

opts type object, optional fields:

reuseContext type boolean: whether to keep the current bubble&#x27;s script execution environment. Defaults to false.

```
const lastMessage = (await tavo.message.find(-1))[0]  // Get the last message
lastMessage.content = &#x27;Updated content&#x27;
lastMessage.reasoning = &#x27;Optional reasoning content&#x27;
lastMessage.hidden = true  // Mark as hidden
await tavo.message.update(lastMessage)  // Update the last message
```

reuseContext Notes

If your script calls tavo.message.update to update the same bubble that is running the script, and you want the script to keep running after the update, pass reuseContext: true.

```
// Called from a script, updating the same bubble that is running it
await tavo.message.update(self, { reuseContext: true })
console.log(&#x27;The script continues after the update&#x27;)
```

Delete a Message

await tavo.message.delete(<messageId>)

Deletes a message by message ID. Returns the deleted message ID on success, or null on failure.

```
const count = await tavo.message.count();  // Get total message count
const midIndex = Math.floor(count / 2);
const midMessage = (await tavo.message.find(midIndex))[0]  // Get a middle message
await tavo.message.delete(midMessage.id)  // Delete that middle message
```

🗨️ Chat

You can use this interface to get information about the current chat. All chat interfaces are tavo.chat.<method>(...).

Get Current Chat

await tavo.chat.current()

Gets the currently active chat information. Returns null if there is no active chat.

For example:

```
let chat = await tavo.chat.current()
console.log(chat.name)        // Print current chat name
console.log(chat.characters[0]?.name)  // Print first character name
console.log(chat.persona?.name)        // Print current user persona name (if any)
```

Async await/async

In TavoJS API, almost all interfaces except variable operations require asynchronous calls.
For async calls, prepend await, for example: let chat = await tavo.chat.current(). If you forget and write let chat = tavo.chat.current(), it will fail (check logs in the JavaScript console in the sidebar).
Also, await can only be used in an async function (or module top level), for example:

```
async function demo() {
  let chat = await tavo.chat.current();
}
```

Likewise, if you forget to add async before function but still use await inside, it will throw an error.

In short: except for variable operations, all TavoJS API calls must use await, and the calling function must be declared with async.

Update Current Chat

await tavo.chat.update(<chat>)

Updates the current chat.

Updatable fields:

name: chat title

characters: array of character IDs or { id } objects (directly replaces the current character list)

persona: persona ID or { id }

preset: preset ID or { id }

lorebooks: array of lorebook IDs or { id } objects ([] clears the list)

regexes: array of regex IDs or { id } objects ([] clears the list)

background: chat background (session-level override; does not affect the theme; see below)

theme: theme ID, { id }, or null to return to the default theme

responseMode: response mode, preferably natural, everyone, manual, or scenario; indexes 0–3 are also accepted

allowSelfResponses: whether a character message may trigger another character response

overrideScenario: chat-specific scenario override; pass an empty string to clear it

```
await tavo.chat.update({
  name: &#x27;New Chat Title&#x27;,
  characters: [12, 34],
  persona: 5,
  preset: 9,
  lorebooks: [17],
  regexes: [3],
  responseMode: &#x27;scenario&#x27;,
  allowSelfResponses: true,
  overrideScenario: &#x27;Let the host choose the next speaker from current clues.&#x27;,
})
```

Note: This interface only updates the current chat, and does not support updating other sessions by chat ID.

Set Chat Background background

The session-level background override is independent from the chat theme. It does not modify or create a theme. The three background sources are mutually exclusive, with priority useAvatar > image > color:

```
// Use an image background (URL, or a relative path returned by tavo.file.save, such as &#x27;files/chat/bg.png&#x27;)
await tavo.chat.update({ background: { image: &#x27;files/chat/bg.png&#x27;, opacity: 0.85 } })

// Use the current character avatar as the background
await tavo.chat.update({ background: { useAvatar: true } })

// Use a solid color background (hex)
await tavo.chat.update({ background: { color: &#x27;#222222&#x27; } })

// Clear the session-level override and fall back to the theme background
await tavo.chat.update({ background: null })
```

image type string: image background. Supports http(s):// images or local paths relative to the current document directory. Recommended with tavo.image.generate + tavo.file.save after saving to disk. Do not pass absolute paths.

useAvatar type boolean: when true, uses the current session character avatar as the background.

color type string: &#x27;#RRGGBB&#x27; or &#x27;#AARRGGBB&#x27; solid color background.

opacity type number (0-1): image opacity, meaningful only for image backgrounds.

Pass background: null to clear the override. Omit the background key to leave it unchanged.

Chat Object Fields

The chat object returned by current commonly includes:

```
{
  id: 1,                    // Chat ID
  name: &#x27;Conversation with Alice&#x27;,    // Chat name
  characters: [             // Character summary list in this chat
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
  persona: {                // Current user persona summary (can be null)
    id: 5,
    name: &#x27;Default User Persona&#x27;,
  },
  preset: {                 // Current preset summary
    id: 9,
    name: &#x27;Default Preset&#x27;,
  },
  lorebooks: [{
    id: 17,
    name: &#x27;Sleepless City&#x27;,
  }],
  regexes: [{               // Enabled regex summary list
    id: 3,
    name: &#x27;Remove Stage Directions&#x27;,
  }],
  background: {             // Session-level background override; null when unset
    image: &#x27;files/chat/bg.png&#x27;,
    opacity: 0.85,
  },
  theme: {                  // Currently effective theme summary
    id: 6,
    name: &#x27;Nightfall&#x27;,
  },
  responseMode: &#x27;scenario&#x27;, // natural | everyone | manual | scenario
  allowSelfResponses: true,
  overrideScenario: &#x27;Let the host choose the next speaker from current clues.&#x27;,
}
```

Example

Copy the following into any bubble to see it in action.

```
<h3>Chat API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 80px; max-height: 300px; overflow-y: auto;"></pre>
<div style="display: grid; grid-template-columns: auto auto;">
  <button onclick="chatCurrent()">tavo.chat.current</button>
  <button onclick="chatRename()">Rename current chat</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function chatCurrent() {
    const chat = await tavo.chat.current();
    log(chat);
  }
  async function chatRename() {
    const chat = await tavo.chat.current();
    if (!chat) return tavo.utils.toast(&#x27;No active chat right now&#x27;);
    const newName = prompt(&#x27;Enter the new chat name&#x27;, chat.name);
    if (!newName || newName === chat.name) return;
    chat.name = newName;
    await tavo.chat.update(chat);
    tavo.utils.toast(`Renamed to: ${newName}`);
  }
</script>
```

🎨 Chat Themes

Use tavo.theme to manage the full ChatTheme library:

```
const themes = await tavo.theme.all()
const theme = await tavo.theme.get(themes[0].id)
const matches = await tavo.theme.find(&#x27;night&#x27;, { match: &#x27;contains&#x27; })
const id = await tavo.theme.create({ name: &#x27;Night Copy&#x27;, background: { color: &#x27;#ff10131a&#x27; } })
await tavo.theme.update(id, { console: { blur: 18 } })
const exported = await tavo.theme.export(id)
await tavo.file.export(exported.path)
await tavo.theme.import(exported.path)
await tavo.chat.update({ theme: id })
await tavo.theme.delete(id)
```

create accepts a complete or partial theme object, and update accepts a recursive patch with the same shape. Nested objects use strict field allowlists:

userBubble and characterBubble: color, blur (0–100), radius (0–50), and alignment (left or right). Bubble fill uses color; background, backgroundColor, and border fields are not supported.

userBubbleFont and characterBubbleFont: textStyle, toneTextStyle, quoteTextStyle, plus toneHighlight, toneDelimiters, toneSymbol, quoteHighlight, quoteDelimiters, and quoteSymbol. Text styles use fontFamily, fontSize, fontWeight, fontStyle, and color.

background: useAvatar, image, opacity, and color.

thinking and statusBar: fontSize, fontWeight, color, and backgroundColor.

console: color, blur, radius, sendColor, fontSize, fontWeight, fontColor, and placeholderColor.

All four avatar styles: avatar, radius, and name.

Colors must use the eight-digit #AARRGGBB form. For example:

```
await tavo.theme.create({
  name: &#x27;Dark Mystery&#x27;,
  background: { color: &#x27;#FF0B0E14&#x27;, useAvatar: false },
  userBubble: { color: &#x27;#E6232A38&#x27;, radius: 16, alignment: &#x27;right&#x27; },
  userBubbleFont: { textStyle: { color: &#x27;#FFF2F4F8&#x27; } },
  console: { color: &#x27;#6610141C&#x27;, fontColor: &#x27;#FFD8DCE6&#x27; },
})
```

The complete method set is all, get, find, create, update, import, export, and delete. Official themes are read-only. Same-name imports ask whether to overwrite or save as new; an official-theme conflict can only be saved as new.

theme.export writes a .thm archive to the current chat&#x27;s isolated files/chat storage and returns its virtual path. It does not open a share dialog. Call the one-argument tavo.file.export(path) from a foreground script when the user wants to export it outside Tavo.

A .thm archive may be at most 64 MiB compressed, contain at most 16 regular entries, and expand to at most 256 MiB in total. theme.json has no separate size limit. Unsafe paths, directories, symbolic links, and duplicate entry names are rejected.

Updating, overwriting, or rebinding the theme used by the current chat updates the WebView in place without reloading the page or interrupting a running TavoJS/tool call.

🧙 Characters

You can use this interface to manage characters. All character interfaces are tavo.character.<method>(...).

Get All Character Summaries

await tavo.character.all()

Returns an array of character summary objects (each item only contains summary fields such as id, name, and avatar):

```
let chars = await tavo.character.all()
console.log(chars[0].id)     // e.g. 12
console.log(chars[0].avatar) // e.g. "chara/alice.png"
console.log(chars[0].name)   // e.g. "Alice"
```

Get a Single Character

await tavo.character.get(<characterId>)

Gets a character object by character ID. Returns null if not found.

```
let char = await tavo.character.get(12)
if (char) {
  console.log(char.name)
}
```

Find Characters by Name

await tavo.character.find(<name>[, <options>])

Finds characters by name and returns an array of character objects. options.match supports: &#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; (default: &#x27;exact&#x27;).

```
let chars = await tavo.character.find(&#x27;Alice&#x27;)
let chars2 = await tavo.character.find(&#x27;Ali&#x27;, { match: &#x27;prefix&#x27; })
console.log(chars.length)
```

Create a Character

await tavo.character.create(<character>)

Creates a character and returns the new character ID. character.name and character.firstMes (or CCv3 first_mes) are required.

CC / SillyTavern compatibility: Bare CC-compatible data, complete CCv2/CCv3 wrappers, and SillyTavern { character: ... } wrappers are accepted. Prefer CCv3 snake-case fields. Format detection and field normalization share the Dart implementation.

```
let id = await tavo.character.create({
  name: &#x27;Alice&#x27;,
  firstMes: &#x27;Hi, I am Alice.&#x27;,
  description: &#x27;A gentle guide&#x27;,
})
```

Update a Character

await tavo.character.update(<character>)

Updates a character and returns the character ID. character.id, character.name, and character.firstMes are required.

```
await tavo.character.update({
  id: 12,
  name: &#x27;Alice&#x27;,
  firstMes: &#x27;Hi, I am Alice.&#x27;,
  personality: &#x27;Patient, detail-oriented&#x27;,
})
```

Import a Character Card

await tavo.character.import(<card>)

Imports a complete CCv2/CCv3 or SillyTavern-compatible character card. Pass a { spec: "chara_card_v3", data: {...} } card, a bare data object, or a { character: ... } wrapper. If the card includes character_book, a lorebook is created simultaneously; if it includes extensions.regex_scripts, a regex script is created simultaneously. A confirmation dialog is shown before the operation.

Return value:

```
{
  characterId: 12,     // ID of the created character
  lorebookId: 5,       // ID of the created lorebook (null if none)
  regexId: 3,          // ID of the created regex script (null if none)
}
```

```
const result = await tavo.character.import(card)
// result.characterId, result.lorebookId, result.regexId
```

Delete a Character

await tavo.character.delete(<characterId>)

Deletes a character by character ID:

```
await tavo.character.delete(12)
await tavo.character.delete(char)  // char must be a character object that includes id
```

Character Object Fields

Character objects (returned by get / find) commonly include:

```
{
  id: 12,                    // Unique character ID
  avatar: &#x27;xxx.png&#x27;,         // Character avatar image URL or path
  name: &#x27;Alice&#x27;,             // Character name (required)
  description: &#x27;...&#x27;,        // Character intro/description
  firstMes: &#x27;...&#x27;,           // Character greeting (required)
  personality: &#x27;...&#x27;,        // Character personality description
  scenario: &#x27;...&#x27;,           // Applicable scenario/use-case description
  mesExample: &#x27;...&#x27;,         // Message examples, separated by <START>
  creatorNotes: &#x27;...&#x27;,       // Creator notes or additional remarks
  systemPrompt: &#x27;...&#x27;,       // System prompt
  postHistoryInstructions: &#x27;...&#x27;,  // Extra guidance after message history
  alternateGreetings: [&#x27;...&#x27;],     // Alternate greetings for this character
  tags: [&#x27;guide&#x27;],           // Character tags for classification/search
  creator: &#x27;Colin&#x27;,          // Creator username or nickname
  characterVersion: &#x27;1.0&#x27;,  // Character version
  nickname: &#x27;Ali&#x27;,           // Character nickname/alias; if set, it replaces name in {{char}} output
  groupOnlyGreetings: [&#x27;...&#x27;],     // Greetings only used in group chat
  creationDate: new Date(&#x27;2026-03-05T10:20:30.000Z&#x27;),      // Creation time (Date object)
  modificationDate: new Date(&#x27;2026-03-05T11:30:00.000Z&#x27;),  // Last modified time (Date object)
}
```

Note: Creating, updating, importing, and deleting characters will show a confirmation dialog. If the user cancels, the operation will not take effect.

Example

Copy the following into any bubble to see it in action.

```
<h3>Character API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 100px; max-height: 400px; overflow-y: auto;"></pre>
<div style="display: grid; grid-template-columns: auto auto auto auto auto auto;">
  <button onclick="charAll()">tavo.character.all</button>
  <button onclick="charGet()">tavo.character.get</button>
  <button onclick="charFind()">tavo.character.find</button>
  <button onclick="charCreate()">tavo.character.create</button>
  <button onclick="charUpdate()">tavo.character.update</button>
  <button onclick="charDelete()">tavo.character.delete</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function charAll() {
    const chars = await tavo.character.all();
    log(chars);
  }
  async function charGet() {
    const id = prompt(&#x27;Enter a character ID (you can get it from tavo.character.all())&#x27;);
    if (!id) return;
    const char = await tavo.character.get(Number(id));
    log(char);
  }
  async function charFind() {
    const name = prompt(&#x27;Enter the character name to search&#x27;);
    if (!name) return;
    const chars = await tavo.character.find(name);
    log(chars);
  }
  async function charCreate() {
    const id = await tavo.character.create({
      name: &#x27;Demo Character&#x27;,
      first_mes: &#x27;Hi, I am a demo character!&#x27;,
      description: &#x27;This is a test character created via tavo.character.create.&#x27;,
    });
    if (!id) return;
    const char = await tavo.character.get(id);
    log(char);
  }
  async function charUpdate() {
    const chars = await tavo.character.find(&#x27;Demo Character&#x27;);
    if (!chars.length) return tavo.utils.toast(&#x27;Click tavo.character.create first to create a character&#x27;);
    const char = chars[0];
    const newDesc = prompt(&#x27;Edit character description:&#x27;, char.description);
    if (!newDesc) return;
    char.description = newDesc;
    await tavo.character.update(char);
    log(await tavo.character.get(char.id));
  }
  async function charDelete() {
    const chars = await tavo.character.find(&#x27;Demo Character&#x27;);
    if (!chars.length) return tavo.utils.toast(&#x27;Click tavo.character.create first to create a character&#x27;);
    const id = await tavo.character.delete(chars[0].id);
    if (!id) return;
    tavo.utils.toast(&#x27;Deleted Demo Character&#x27;);
    log(&#x27;Deleted&#x27;);
  }
</script>
```

🎭 Personas

You can use this interface to manage user personas. All persona interfaces are tavo.persona.<method>(...).

Get All Persona Summaries

await tavo.persona.all()

Returns an array of persona summary objects (each item includes id and name):

```
let personas = await tavo.persona.all()
console.log(personas[0].id)    // e.g. 5
console.log(personas[0].name)  // e.g. "Default User Persona"
```

Get a Single Persona

await tavo.persona.get(<personaId>)

Gets a persona object by persona ID. Returns null if not found:

```
let persona = await tavo.persona.get(5)
if (persona) {
  console.log(persona.name)
  console.log(persona.description)
}
```

Find Personas by Name

await tavo.persona.find(<name>[, <options>])

Finds personas by name and returns an array of persona objects. options.match supports: &#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; (default: &#x27;exact&#x27;).

```
let personas = await tavo.persona.find(&#x27;Default&#x27;)
let personas2 = await tavo.persona.find(&#x27;Def&#x27;, { match: &#x27;prefix&#x27; })
console.log(personas.length)
```

Create a Persona

await tavo.persona.create(<persona>)

Creates a persona and returns the new persona ID. persona.name and persona.description are required.

```
let id = await tavo.persona.create({
  name: &#x27;Detective Persona&#x27;,
  description: &#x27;Detail-oriented and strong at structured reasoning.&#x27;,
  avatar: &#x27;chara/persona-detective.png&#x27;,
})
```

Update a Persona

await tavo.persona.update(<persona>)

Updates a persona. persona.id, persona.name, and persona.description are required.

```
await tavo.persona.update({
  id: 5,
  name: &#x27;Default User Persona&#x27;,
  description: &#x27;Use a more concise tone and prioritize actionable conclusions.&#x27;,
  avatar: &#x27;chara/persona-default.png&#x27;,
  active: true,
})
```

Delete a Persona

await tavo.persona.delete(<personaId>)

Deletes a persona by persona ID:

```
await tavo.persona.delete(5)
await tavo.persona.delete(persona)  // persona must be a persona object that includes id
```

Persona Object Fields

Persona objects (returned by get) commonly include:

```
{
  id: 5,  // Unique persona ID
  name: &#x27;Default User Persona&#x27;,  // Persona name (required)
  description: &#x27;...&#x27;,  // Persona description (required)
  avatar: &#x27;xxx.png&#x27;,  // Persona avatar URL or path (optional)
  active: true,  // Whether this is the default persona
  sortIndex: 12,  // Sorting index
}
```

Example

Copy the following into any bubble to see it in action.

```
<h3>Persona API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 100px; max-height: 400px; overflow-y: auto;"></pre>
<div style="display: grid; grid-template-columns: auto auto auto auto auto auto;">
  <button onclick="personaAll()">tavo.persona.all</button>
  <button onclick="personaGet()">tavo.persona.get</button>
  <button onclick="personaFind()">tavo.persona.find</button>
  <button onclick="personaCreate()">tavo.persona.create</button>
  <button onclick="personaUpdate()">tavo.persona.update</button>
  <button onclick="personaDelete()">tavo.persona.delete</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function personaAll() {
    const personas = await tavo.persona.all();
    log(personas);
  }
  async function personaGet() {
    const id = prompt(&#x27;Enter a persona ID (you can get it from tavo.persona.all())&#x27;);
    if (!id) return;
    const persona = await tavo.persona.get(Number(id));
    log(persona);
  }
  async function personaFind() {
    const name = prompt(&#x27;Enter the persona name to search&#x27;);
    if (!name) return;
    const personas = await tavo.persona.find(name);
    log(personas);
  }
  async function personaCreate() {
    const id = await tavo.persona.create({
      name: &#x27;Demo Persona&#x27;,
      description: &#x27;This is a test persona created via tavo.persona.create. It is concise and gives direct conclusions.&#x27;,
    });
    const persona = await tavo.persona.get(id);
    log(persona);
  }
  async function personaUpdate() {
    const personas = await tavo.persona.find(&#x27;Demo Persona&#x27;);
    if (!personas.length) return tavo.utils.toast(&#x27;Click tavo.persona.create first to create a persona&#x27;);
    const persona = personas[0];
    const newDesc = prompt(&#x27;Edit persona description:&#x27;, persona.description);
    if (!newDesc) return;
    persona.description = newDesc;
    await tavo.persona.update(persona);
    log(await tavo.persona.get(persona.id));
  }
  async function personaDelete() {
    const personas = await tavo.persona.find(&#x27;Demo Persona&#x27;);
    if (!personas.length) return tavo.utils.toast(&#x27;Click tavo.persona.create first to create a persona&#x27;);
    const id = await tavo.persona.delete(personas[0].id);
    if (!id) return;
    tavo.utils.toast(&#x27;Deleted Demo Persona&#x27;);
    log(&#x27;Deleted&#x27;);
  }
</script>
```

🎛️ Presets

You can use this interface to manage presets. All preset interfaces are tavo.preset.<method>(...).

Get All Preset Summaries

await tavo.preset.all()

Returns an array of preset summary objects (each item includes id and name):

```
let presets = await tavo.preset.all()
console.log(presets[0].id)    // e.g. 1
console.log(presets[0].name)  // e.g. "Default"
```

Get a Single Preset

await tavo.preset.get(<presetId>)

Gets a preset object by preset ID. Returns null if not found:

```
let preset = await tavo.preset.get(1)
if (preset) {
  console.log(preset.name)
  console.log(preset.entries.length)
  console.log(preset.basicPrompts.chatStart)
}
```

Find Presets by Name

await tavo.preset.find(<name>[, <options>])

Finds presets by name and returns an array of full preset objects. options.match supports: &#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; (default: &#x27;exact&#x27;).

```
let presets = await tavo.preset.find(&#x27;Default&#x27;)
let presets2 = await tavo.preset.find(&#x27;Def&#x27;, { match: &#x27;prefix&#x27; })
console.log(presets.length)
```

Import a Preset

await tavo.preset.import(<preset>)

Imports a preset in SillyTavern format. Shows a confirmation dialog; returns the new preset ID on confirm, or null on cancel. preset.name is optional (defaults to &#x27;Preset&#x27;).

```
const id = await tavo.preset.import({
  name: &#x27;My Preset&#x27;,
  prompts: [...],
  prompt_order: [{ character_id: 100001, order: [...] }],
})
```

Create a Preset

await tavo.preset.create(<preset>)

Creates a preset and returns the new preset ID. preset.name is required. Other fields are optional; missing parts in preset.basicPrompts and preset.entries will be filled with built-in defaults.

```
let id = await tavo.preset.create({
  name: &#x27;My Preset&#x27;,
  basicPrompts: {
    continueNudge: &#x27;[Continue your last message without repeating the original content.]&#x27;,
  },
  entries: [
    {
      identifier: &#x27;abc123&#x27;,
      name: &#x27;🌸 Style Control&#x27;,
      content: &#x27;Adopt a refined and elegant narrative style, similar to popular high-quality female-oriented works on platforms like Jinjiang and Changpei.&#x27;,
    },
  ],
})
```

Update a Preset

await tavo.preset.update(<preset>)

Updates a preset. preset.id is required. The incoming entries will overwrite the existing entries. A typical flow is get → modify → update.

```
const preset = await tavo.preset.get(33);
preset.entries.find(e => e.identifier == &#x27;main&#x27;).content = &#x27;Please reply in Chinese to all of {{user}}\&#x27;s questions.&#x27;;
await tavo.preset.update(preset)
```

Delete a Preset

await tavo.preset.delete(<presetId>)

Deletes a preset by preset ID:

```
await tavo.preset.delete(1)
await tavo.preset.delete(preset)  // preset must be a preset object that includes id
```

Preset Object Fields

Preset objects (returned by get / find) include:

```
{
  id: 1,          // Preset unique ID
  name: &#x27;Default&#x27;, // Preset name (required)
  basicPrompts: { /* BasicPrompts, see below */ },
  entries: [],    // PresetEntry[] prompt entries list (see below)
}
```

Basic Prompt Fields (BasicPrompts)

basicPrompts contains various system prompt templates. All fields are optional; missing fields use built-in defaults:

```
{
  persona: &#x27;{{persona}}&#x27;,        // Format template for the user persona description
  description: &#x27;{{description}}&#x27;, // Format template for the character description
  personality: &#x27;{{personality}}&#x27;, // Format template for the character personality (insert location via {{personality}})
  scenario: &#x27;{{scenario}}&#x27;,      // Format template for the scenario (insert location via {{scenario}})
  exampleMessageStart: &#x27;[Example Chat]&#x27;,  // Example chat start marker
  chatStart: &#x27;[Start a new Chat]&#x27;,        // Chat history start marker
  groupChatStart: &#x27;[Start a new group chat. Group members: {{group}}]&#x27;,  // Group chat start marker
  groupNudge: &#x27;[Write the next reply only as {{char}}.]&#x27;,  // Nudge a specific role in group chat
  continueNudge: &#x27;[Continue your last message without repeating its original content.]&#x27;,  // Nudge the "continue" action
  impersonation: &#x27;[Write your next reply from the point of view of {{user}}...]&#x27;,  // Impersonation prompt for acting as user
  lorebook: &#x27;{0}&#x27;,  // Wrap template for lorebook entries (insert via {0})
}
```

Prompt Entry Fields (PresetEntry)

Each item in entries has this structure:

```
{
  // -- Basic info ------------------------------------
  identifier: &#x27;main&#x27;,   // Unique entry identifier (built-in entries have fixed identifiers; see the table below)
  name: &#x27;Main Prompt&#x27;,  // Entry display name
  content: &#x27;...&#x27;,       // Prompt body text (no `content` for marker type)
  enabled: true,        // Whether this entry is enabled (takes effect in the active list)
  active: true,         // Whether it is included in the active list (inactive entries are archived and do not participate in prompt building)

  // -- Type -----------------------------------------
  type: &#x27;custom&#x27;,       // Entry type:
                        //   &#x27;builtin&#x27; - Built-in prompts (fixed identifier, e.g. main / jailbreak)
                        //   &#x27;marker&#x27;  - Position marker (no content; marks where other content gets inserted)
                        //   &#x27;custom&#x27;  - Custom prompt

  // -- Role and injection (configurable for custom)-
  role: &#x27;system&#x27;,       // Message role: &#x27;system&#x27; | &#x27;user&#x27; | &#x27;assistant&#x27;
  injectionPosition: &#x27;relative&#x27;,  // Injection position:
                                  //   &#x27;relative&#x27; - relative position (follows preset entry order)
                                  //   &#x27;absolute&#x27; - absolute position (insert at a specific depth in chat history)
  injectionDepth: 4,   // Injection depth (only effective when injectionPosition is &#x27;absolute&#x27;)
                        // 0 = after the last message, 1 = before the last message, and so on
}
```

Built-in Entry Identifier Table

The following identifier values correspond to built-in fixed prompts or position markers. You can reference them directly when creating/updating:

identifier
Name
Type
Description

main
Main Prompt
builtin
The main prompt; the core instruction for the conversation

worldInfoBefore
Lorebook Before
marker
Lorebook (insert above character description)

personaDescription
Persona Description
marker
User persona description insert point

charDescription
Char Description
marker
Character description insert point

charPersonality
Char Personality
marker
Character personality insert point

scenario
Scenario
marker
Scenario description insert point

enhanceDefinitions
Enhance Definitions
builtin
Extra prompt to enhance character definitions

nsfw
Auxiliary Prompt
builtin
Auxiliary prompt (defaults to empty)

worldInfoAfter
Lorebook After
marker
Lorebook (insert below character description)

dialogueExamples
Chat Examples
marker
Example dialog insert point

chatHistory
Chat History
marker
Chat history insert point

jailbreak
Post-History Instructions
builtin
Extra instructions after chat history

📚 Lorebooks

You can use this interface to manage lorebooks. All lorebook interfaces are tavo.lorebook.<method>(...).

Get All Lorebook Summaries

await tavo.lorebook.all()

Returns an array of lorebook summary objects (each item includes id, name, and entries):

```
let lorebooks = await tavo.lorebook.all()
console.log(lorebooks[0].id)       // e.g. 3
console.log(lorebooks[0].name)     // e.g. "City Setting"
console.log(lorebooks[0].entries)  // e.g. 12 (entry count)
```

Get a Single Lorebook

await tavo.lorebook.get(<lorebookId>)

Gets a lorebook object by ID. Returns null if not found:

```
let lorebook = await tavo.lorebook.get(3)
if (lorebook) {
  console.log(lorebook.name)
  console.log(lorebook.entries.length)
}
```

Find Lorebooks by Name

await tavo.lorebook.find(<name>[, <options>])

Finds lorebooks by name and returns an array of lorebook objects. options.match supports: &#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; (default: &#x27;exact&#x27;).

```
let lorebooks = await tavo.lorebook.find(&#x27;City&#x27;)
let lorebooks2 = await tavo.lorebook.find(&#x27;City&#x27;, { match: &#x27;suffix&#x27; })
console.log(lorebooks.length)
```

Import a Lorebook

await tavo.lorebook.import(<lorebook>)

Imports a bare CCv3 Lorebook / character_book, standalone { spec: "lorebook_v3", data: {...} } JSON, SillyTavern World Info whose entries is keyed by UID, or a Tavo-native lorebook object. A confirmation dialog is shown before the operation. Returns the new lorebook ID, or null if the user cancels.

```
const id = await tavo.lorebook.import({
  name: &#x27;My Lorebook&#x27;,
  entries: [...]
})
```

Create a Lorebook

await tavo.lorebook.create(<lorebook>)

Creates a lorebook and returns the new lorebook ID. lorebook.name is required. Prefer CCv3 Lorebook fields; standalone lorebook_v3, SillyTavern World Info, and Tavo-native fields are also accepted.

```
let id = await tavo.lorebook.create({
  name: &#x27;City Setting&#x27;,
  entries: [],
})
```

Update a Lorebook

await tavo.lorebook.update(<lorebook>)

Updates a lorebook. lorebook.id and lorebook.name are required. Entry formats are the same as for create.

```
await tavo.lorebook.update({
  id: 3,
  name: &#x27;City Setting (Remastered)&#x27;,
  entries: [],
})
```

Delete a Lorebook

await tavo.lorebook.delete(<lorebookId>)

Deletes a lorebook by ID:

```
await tavo.lorebook.delete(3)
await tavo.lorebook.delete(lorebook)  // lorebook must be a lorebook object that includes id
```

Lorebook Object Fields

Lorebook objects (returned by get / find) include:

```
{
  id: 3,           // Unique lorebook ID
  name: &#x27;City Setting&#x27;, // Lorebook name (required)
  entries: [],     // LorebookEntry[] entry list (see below)
}
```

Entry Object Fields (LorebookEntry)

Each item in the entries array has this structure:

Format compatibility: Prefer CCv3 fields such as keys, secondary_keys, constant, position, selective, use_regex, and extensions. SillyTavern fields including key, keysecondary, disable, and order, plus the Tavo-native fields below, are also accepted. Every entry point uses the shared Dart format detector, so callers do not need to convert the payload first.

```
{
  // -- Basic info ------------------------------------
  identifier: &#x27;entry-uuid&#x27;,  // Unique entry identifier (string)
  name: &#x27;City Overview&#x27;,     // Entry name (for display/search only)
  content: &#x27;This is a coastal city that often has thick fog at night.&#x27;,  // Main body injected into prompts
  enabled: true,             // Whether this entry is enabled
  strategy: &#x27;constant&#x27;,      // Trigger strategy: &#x27;constant&#x27; (always on) | &#x27;keyword&#x27; (keyword-triggered)

  // -- Keywords --------------------------------------
  keywords: [&#x27;city&#x27;, &#x27;port&#x27;],         // Primary keyword list (effective when strategy is &#x27;keyword&#x27;)
  secondaryKeywords: [&#x27;night&#x27;, &#x27;fog&#x27;],  // Secondary keyword list
  secondaryKeywordStrategy: &#x27;none&#x27;,  // Secondary keyword matching strategy:
                                     //   &#x27;none&#x27;   - Disable secondary keywords
                                     //   &#x27;andAny&#x27; - Primary matched and any secondary matched (default)
                                     //   &#x27;andAll&#x27; - Primary matched and all secondary matched
                                     //   &#x27;notAny&#x27; - Primary matched and none of secondary matched
                                     //   &#x27;notAll&#x27; - Primary matched and not all secondary matched
  scanDepth: 2,              // Message depth for keyword scanning (default 2, max 1000)
  caseSensitive: false,      // Whether keyword matching is case-sensitive
  matchWholeWord: true,      // Whether to match whole words only

  // -- Injection position ----------------------------
  injectionPosition: &#x27;lorebookBefore&#x27;,  // Injection position:
                                        //   &#x27;lorebookBefore&#x27;         - Above character description (↑Char)
                                        //   &#x27;lorebookAfter&#x27;          - Below character description (↓Char)
                                        //   &#x27;topOfExampleMessages&#x27;   - Before example dialog
                                        //   &#x27;bottomOfExampleMessages&#x27;- After example dialog
                                        //   &#x27;atDepth&#x27;                - Absolute depth in chat history
  injectionDepth: 4,         // Injection depth, only effective when injectionPosition is &#x27;atDepth&#x27;
  injectionRole: &#x27;system&#x27;,   // Injection role: &#x27;system&#x27; | &#x27;user&#x27; | &#x27;assistant&#x27;

  // -- Probability and behavior ----------------------
  probability: 100,  // Activation probability (0-100, default 100)
  sticky: 0,         // Message turns to keep active after activation (0 means no persistence)
  cooldown: 0,       // Cooldown turns after one activation (0 means no cooldown)
  delay: 0,          // Delayed activation turns (0 means immediate)
}
```

Example

Copy the following into any bubble to see it in action.

```
<h3>Lorebook API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 100px; max-height: 400px; overflow-y: auto;"></pre>
<div style="display: grid; grid-template-columns: auto auto auto;">
  <button onclick="lorebookAll()">tavo.lorebook.all</button>
  <button onclick="lorebookGet()">tavo.lorebook.get</button>
  <button onclick="lorebookFind()">tavo.lorebook.find</button>
  <button onclick="lorebookCreate()">tavo.lorebook.create</button>
  <button onclick="lorebookUpdate()">tavo.lorebook.update</button>
  <button onclick="lorebookDelete()">tavo.lorebook.delete</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function lorebookAll() {
    const lorebooks = await tavo.lorebook.all();
    log(lorebooks);
  }
  async function lorebookGet() {
    const id = prompt(&#x27;Enter a lorebook ID (you can get it from tavo.lorebook.all())&#x27;)
    if (!id) return;
    const lorebook = await tavo.lorebook.get(id);
    log(lorebook);
  }
  async function lorebookFind() {
    const name = prompt(&#x27;Enter the lorebook name to search&#x27;)
    if (!name) return;
    const lorebooks = await tavo.lorebook.find(name);
    log(lorebooks);
  }
  async function lorebookCreate() {
    const lorebook = { name: &#x27;Demo Lorebook&#x27;, entries: [] };
    lorebook.entries.push({
      identifier: &#x27;abc123&#x27;,
      name: &#x27;About this city&#x27;,
      content: &#x27;This is a coastal city that often has thick fog at night.&#x27;,
      enabled: true,
      strategy: &#x27;constant&#x27;,
    });
    const id = await tavo.lorebook.create(lorebook);
    const lb = await tavo.lorebook.get(id);
    log(lb)
    const c = confirm(&#x27;Set it as the lorebook for the current chat?&#x27;);
    if (!c) return;
    const chat = await tavo.chat.current();
    chat.lorebooks.push(lb);
    tavo.chat.update(chat);
  }
  async function lorebookUpdate() {
    const lorebooks = await tavo.lorebook.find(&#x27;Demo Lorebook&#x27;);
    if (!lorebooks.length) return tavo.utils.toast(&#x27;Click tavo.lorebook.create first to create a lorebook&#x27;);
    const lorebook = lorebooks[0];
    const newContent = prompt(`Edit the content of the first entry in lorebook ${lorebook.name}:`, lorebook.entries[0].content);
    lorebook.entries[0].content = newContent;
    await tavo.lorebook.update(lorebook);
    const lb = await tavo.lorebook.get(lorebook.id);
    log(lb)
  }
  async function lorebookDelete() {
    const lorebooks = await tavo.lorebook.find(&#x27;Demo Lorebook&#x27;);
    if (!lorebooks.length) return tavo.utils.toast(&#x27;Click tavo.lorebook.create first to create a lorebook&#x27;);
    const lorebook = lorebooks[0];
    const id = await tavo.lorebook.delete(lorebook);
    log(id)
  }
</script>
```

🎨 Regex

You can use this interface to manage regex groups (a set of find/replace rules). All regex interfaces are tavo.regex.<method>(...).

Get All Regex (Summaries)

await tavo.regex.all()

Returns an array of regex summary objects (each item includes id, name, and entries; entries is the number of rules, not the rule entries array):

```
let list = await tavo.regex.all()
console.log(list[0].id)       // e.g. 2
console.log(list[0].name)     // e.g. "My Regex"
console.log(list[0].entries)  // e.g. 5 (number of rules)
```

Get a Single Regex

await tavo.regex.get(<regexId>)

Gets a regex object by ID. Returns null if not found:

```
let r = await tavo.regex.get(2)
if (r) {
  console.log(r.name)
  console.log(r.entries.length)
}
```

Find Regex by Name

await tavo.regex.find(<name>[, <options>])

Finds regex groups by name and returns an array of full regex objects. options.match supports: &#x27;exact&#x27; | &#x27;prefix&#x27; | &#x27;suffix&#x27; | &#x27;contains&#x27; (default: &#x27;exact&#x27;).

```
let found = await tavo.regex.find(&#x27;My&#x27;)
let found2 = await tavo.regex.find(&#x27;My&#x27;, { match: &#x27;contains&#x27; })
console.log(found.length)
```

Import a Regex Group

await tavo.regex.import(<regex>)

Imports a regex group in SillyTavern format. Shows a confirmation dialog; returns the new regex group ID on confirm, or null on cancel. regex.name is optional (defaults to &#x27;Regex&#x27;); regex.entries is an array of SillyTavern-format regex entries.

```
const id = await tavo.regex.import({
  name: &#x27;Highlight&#x27;,
  entries: [
    { scriptName: &#x27;Highlight&#x27;, findRegex: &#x27;\\[highlight:(.+?)\\]&#x27;, replaceString: &#x27;<mark>$1</mark>&#x27;, placement: [2], disabled: false, markdownOnly: true, promptOnly: false, runOnEdit: false, substituteRegex: 0 }
  ]
})
```

Create a Regex Group

await tavo.regex.create(<regex>)

Creates a regex group and returns the new ID. regex.name is required; regex.entries can be omitted (treated as an empty list). A confirmation dialog will be shown before creating/updating.

```
let id = await tavo.regex.create({
  name: &#x27;Demo Regex&#x27;,
  entries: [
    {
      name: &#x27;Status Bar&#x27;,
      findRegex: &#x27;/<status>(.*?)<\/status>/gim&#x27;,
      replaceString: &#x27;<pre>$1</pre>&#x27;,
      placements: [&#x27;char&#x27;],
      timing: &#x27;display&#x27;,
    },
  ],
})
```

Update a Regex Group

await tavo.regex.update(<regex>)

Updates a regex group. regex.id and regex.name are required (validated by the front-end wrapper). Typical flow: get → modify → update.

```
const r = await tavo.regex.get(2)
r.entries[0].enabled = false
await tavo.regex.update(r)
```

Delete a Regex Group

await tavo.regex.delete(<regexId>)

Deletes a regex group by ID. You can also pass a regex object that includes id:

```
await tavo.regex.delete(2)
await tavo.regex.delete({ id: 2 })
```

Regex Object Fields

Regex objects (returned by get / find) have this structure:

```
{
  id: 2,
  name: &#x27;My Regex&#x27;,
  entries: [ /* RegexEntry[]; see below */ ],
}
```

Rule Entry Fields (RegexEntry)

Each item in entries:

```
{
  name: &#x27;Rule display name&#x27;,             // Required (string); otherwise parsing may fail
  findRegex: &#x27;pattern&#x27;,                 // Find regex (supports JavaScript-like `/pattern/flags`)
  replaceString: &#x27;&#x27;,                   // Replacement string
  trimStrings: [],                     // Additional strings to trim
  placements: [&#x27;char&#x27;],               // Target placements (can be multiple):
                                 //   &#x27;user&#x27;      - user input
                                 //   &#x27;char&#x27;      - AI output
                                 //   &#x27;reasoning&#x27; - reasoning content
                                 //   &#x27;lorebook&#x27;  - lorebook injected content
  timing: &#x27;display&#x27;,                  // Execution timing:
                                 //   &#x27;display&#x27;         - only display (do not write into persistent messages; similar to ST markdownOnly)
                                 //   &#x27;send&#x27;            - only send into the model (before generation)
                                 //   &#x27;sendAndDisplay&#x27;  - both display and send
                                 //   &#x27;receive&#x27;         - persist after receiving the reply (relevant for input/output)
                                 //   &#x27;editAndReceive&#x27;  - persist and rewrite when receiving edited messages
  substitution: &#x27;none&#x27;,              // Macro substitution mode: &#x27;none&#x27; | &#x27;raw&#x27; | &#x27;escaped&#x27;
  minDepth: null,                    // Optional: message depth lower bound (integer)
  maxDepth: null,                    // Optional: message depth upper bound (integer)
  enabled: true,                     // Whether this rule is enabled
}
```

If you omit fields, the side-end will fill reasonable defaults for findRegex, replaceString, trimStrings, placements, timing, substitution, enabled, etc. (e.g. placements: [&#x27;char&#x27;], timing: &#x27;display&#x27;).

🧠 Long-Term Memory

You can use this interface to read or modify long-term memory for the current chat. All interfaces are tavo.memory.<method>(...).

Get Current Memory

await tavo.memory.current()

Gets the current chat memory object:

```
const memory = await tavo.memory.current()
console.log(memory.enabled)         // true / false
console.log(memory.memories.length) // Number of memory items
```

Update Memory

await tavo.memory.update(<memory>)

Updates current chat memory and returns the memory record id. Updatable fields:

enabled: whether memory is enabled

memories: memory text array (string[])

```
const memory = await tavo.memory.current()

memory.enabled = true
memory.memories = [
  &#x27;The user prefers concise answers with conclusions first&#x27;,
  &#x27;The user tends to keep the character calm and professional&#x27;,
]

const memoryId = await tavo.memory.update(memory)
console.log(memoryId)
```

Append Memory

Since v1.0.0

await tavo.memory.append(<memories>)

Appends one or more non-empty strings in order without replacing existing
memory. Appending while long-term memory is disabled stores the entries but
does not enable prompt injection.

```
const result = await tavo.memory.append([
  &#x27;The user prefers concise answers with conclusions first&#x27;,
  &#x27;The user plans to visit Kyoto next month&#x27;,
])

console.log(result.appendedCount) // 2
console.log(result.totalCount)    // Total after appending
console.log(result.enabled)       // Current injection state
```

Memory Object Fields

current returns this object shape:

```
{
  id: 12,  // Memory record ID
  enabled: true,  // Whether long-term memory is enabled
  memories: [     // Memory item list (string array)
    &#x27;User prefers concise replies&#x27;,
    &#x27;Avoid repeating already-confirmed information&#x27;
  ],
}
```

Example

Copy the following into a bubble to see it in action:

```
<h3>Long-Term Memory API Demo</h3>
<button onclick="addOneMemory()">Add one memory</button>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 80px; max-height: 300px; overflow-y: auto;"></pre>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };

  async function addOneMemory() {
    const memory = await tavo.memory.current();
    if (!memory.enabled) {
      memory.enabled = true;
      tavo.utils.toast(&#x27;Long-term memory enabled automatically&#x27;)
    }
    memory.memories.push(`Watched a grand fireworks show with Guoguo ${new Date().toISOString()}`);
    const id = await tavo.memory.update(memory);
    tavo.utils.toast(`Added 1 memory item, now total ${memory.memories.length}`);
    log(memory);
  }
</script>
```

✨ Generation Requests

You can use this interface to trigger a one-off text generation directly. All generation requests use tavo.generate(...).

Start Generation

await tavo.generate(<prompt>, <options>)

prompt type string: user input for this generation

options type object: generation options (pass \{\} if no extra config is needed)

The return type is string, i.e. text generated by the model.

```
const result = await tavo.generate(&#x27;Summarize what happened today in one sentence&#x27;)
console.log(result)
```

options Fields

options supports the following fields:

context type boolean (default false):

true: generate with current conversation context (reuses current chat state)

false: AI generation request unrelated to current conversation (default)

preset type number | object (optional):

Pass a preset ID directly, e.g. 12

If passing an object, only id is recognized, e.g. { id: 12 }

settings type object (optional): override model parameters for this request

Example:

```
const text = await tavo.generate(
  &#x27;Based on our recent conversation, give me 3 action suggestions&#x27;,
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

Notes

This interface is a one-off request that returns full text, not streaming chunks

Generation requests use the model API bound to the current chat; if no API is available, an exception is thrown. Handle it with try/catch.

Example

Copy the following into a bubble to see it in action:

```
<h3>Generation Request API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 80px; max-height: 300px; overflow-y: auto;"></pre>
<button id="btn-generate" onclick="generate()">Generate Character Card</button>
<p id="status"></p>
<div id="actions" style="display:none; gap:8px;">
  <button onclick="downloadJson()">Download JSON File</button>
  <button onclick="createCharacter()">Create Character Card Directly</button>
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
  const p = prompt(&#x27;Enter the character traits you want&#x27;);
  if (!p) return;
  setUi(true, &#x27;Generating...&#x27;);
  try {
    let text = await tavo.generate(`Generate a character card from the following info. Output JSON that follows Character Card Spec V3.\n${p}`);
    log(text)
    text = text.trim();
    if (text.startsWith(&#x27;```&#x27;) && text.endsWith(&#x27;```&#x27;)) {
      text = text.replace(/^```[a-zA-Z]*\n?/, &#x27;&#x27;).replace(/```$/, &#x27;&#x27;);
    }
    generatedCard = JSON.parse(text);
    if (generatedCard.mes_example instanceof Array) generatedCard.mes_example = generatedCard.mes_example.join(&#x27;\n&#x27;)
    setUi(false, `Character card "${generatedCard.name}" generated`, true);
  } catch (e) {
    log(e);
    console.log(e);
    setUi(false, `Character card generation failed`, false)
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

🎨 Image Generation

Use this interface to trigger one image generation request. All image interfaces use tavo.image.<method>(...).

Generate an Image

await tavo.image.generate(<prompt>, <options>)

prompt type string: prompt for this image generation request (required, non-empty)

options type object: image generation options (optional)

Returns a string. By default it is an image dataUrl; when saveAs is passed, it returns the saved virtual path. Both can be used directly for:

rendering with <img src="...">

```
const img = await tavo.image.generate(&#x27;a calico cat sleeping on a keyboard&#x27;)
const el = document.getElementById(&#x27;cat&#x27;)
el.src = img
el.onclick = () => tavo.utils.preview(el)
```

options Fields

All fields are optional:

size type string (for example "1024x1024"): used by OpenAI-style platforms

aspectRatio type string (for example "16:9" or "1:1"): used by platforms that support aspect ratio parameters

negativePrompt type string: negative prompt. Effective for NovelAI / SD-style platforms; ignored by OpenAI / Gemini

referenceImages type string[]: reference images for img2img. Each item can be a dataUrl or a relative path returned by tavo.file.save. Passing paths directly is more efficient because it avoids loading dataUrls back into JS. Gemini / OpenAI / Partner (Volink) / OpenRouter support multiple images; the NovelAI protocol only uses the first image

extraBody type object: extra platform-native fields passed through to the API, such as seed, guidance_scale, or quality

saveAs type string (with extension, such as &#x27;hero.png&#x27;): saves directly to disk and returns a virtual path instead of a dataUrl. This is equivalent to tavo.image.generate(...) + tavo.file.save(saveAs, dataUrl) in one step. File name rules are the same as tavo.file.save (/ \ : .. are forbidden; violations throw Error; same-name files are overwritten)

scope type string: &#x27;chat&#x27; (default) | &#x27;global&#x27;. Only takes effect when saveAs is passed, and has the same meaning as tavo.file.save scope

```
const wide = await tavo.image.generate(&#x27;cyberpunk night street, neon&#x27;, {
  aspectRatio: &#x27;16:9&#x27;,
  negativePrompt: &#x27;low quality, blurry, watermark&#x27;,
  extraBody: { quality: &#x27;hd&#x27; },
})

// Generate and save in one step
const path = await tavo.image.generate(&#x27;a calico cat&#x27;, {
  saveAs: &#x27;hero.png&#x27;,                  // returns &#x27;files/chat/hero.png&#x27; (virtual path)
})
imgEl.src = path
tavo.set(&#x27;hero&#x27;, path)                  // store the path in a variable; no rewrite needed across chat clone/import
```

Notes

This interface is a one-off request that returns a full dataUrl (or a virtual path when saveAs is passed), not streaming chunks

It uses the image generation API bound to the current chat, the same as the input-box image generation entry; if no image generation API is available, it throws an exception

It does not trigger prompt expansion and is independent of the "Auto Expand" toggle in image settings. Use tavo.generate first if you need prompt expansion

Without saveAs, it does not persist bytes to local storage; image bytes are only returned through the API result. With saveAs, the image is saved directly to disk

It does not show any user confirmation dialog

Example

Copy the following into a bubble to see it in action:

```
<h3>🎨 Image Generation API Demo</h3>
<div class="control">
  <button id="btn-generate" onclick="run()">Generate Image</button>
  <p id="status"></p>
  <img id="out" onclick="tavo.utils.preview(this)" style="max-width: 100%; border-radius: 8px; margin-top: 8px; cursor: pointer;" />
</div>
<script>
async function run() {
  const p = prompt(&#x27;Enter an image prompt&#x27;, &#x27;a calico cat sleeping on a keyboard&#x27;)
  if (!p) return
  const btn = document.getElementById(&#x27;btn-generate&#x27;)
  const status = document.getElementById(&#x27;status&#x27;)
  const out = document.getElementById(&#x27;out&#x27;)
  btn.disabled = true
  status.textContent = &#x27;Generating...&#x27;
  out.src = &#x27;&#x27;
  try {
    const img = await tavo.image.generate(p, { aspectRatio: &#x27;1:1&#x27; })
    out.src = img
    status.textContent = &#x27;Done&#x27;
  } catch (e) {
    status.textContent = &#x27;Failed: &#x27; + e.message
    tavo.utils.toast(&#x27;Image generation failed: &#x27; + e.message)
  } finally {
    btn.disabled = false
  }
}
</script>
```

🔊 TTS

Since v0.92.0

Use the current chat&#x27;s configured TTS bindings to synthesize and play text.

```
await tavo.tts.play(&#x27;Welcome back.&#x27;, {
  voice: { character: 123 }, // or { id: 123 }
  queue: false,
  applyPlaybackRules: false,
})

await tavo.tts.stop()
```

voice may select exactly one character or persona. Both accept an id or
an object containing id. Ordinary message TavoJS may omit voice to use the
host message speaker. Plugin TavoJS must always pass voice and resolve the
speaker through the existing chat/message/library APIs.

play() returns true when playback starts or queues, and false for blank
text, a missing target, or a target without a usable binding. queue and
applyPlaybackRules both default to false. stop() stops the current chat&#x27;s
shared TTS and clears its waiting queue. Direct voiceId and endpoint-id
playback are not supported.

📁 Files

Use this interface to persist data into the app&#x27;s local storage. All file interfaces use tavo.file.<method>(...).

It is useful for saving generated images, downloaded resources, configuration files, and other data to disk instead of putting large payloads such as image dataUrls directly into variables or message content.

tavo.file.import and tavo.file.export open the system file picker or share/save UI.

Scope

Like variables, files have scopes:

chat (default): chat scope. Files are saved with the current chat and are cleaned up when the chat is deleted.

global: global scope. Files persist across chats and must be deleted by the script when no longer needed.

List Stored Files

await tavo.file.list(<options>)

Returns one page of regular files in a scope. options accepts only:

scope: &#x27;chat&#x27; (default) or &#x27;global&#x27;. Omitting it lists files for the current chat. Pass &#x27;global&#x27; only for resources intentionally shared across chats.

limit: integer page size, default 100, from 1 through 200

cursor: the previous page&#x27;s nextCursor, omitted for the first page

```
{
  files: Array<{
    path: string       // files/chat/<name> or files/global/<name>
    name: string
    size: number       // bytes
    mimeType: string
    modifiedAt: string // UTC ISO 8601, e.g. 2026-08-10T01:02:03.000Z
  }>
  nextCursor?: string
}
```

Names use case-sensitive string ordering. Pagination is live, not a snapshot: files deleted between pages disappear, and files inserted before the cursor will not appear on later pages. Reuse a cursor only with the same scope.

```
let cursor
do {
  const page = await tavo.file.list({ scope: &#x27;chat&#x27;, limit: 100, cursor })
  for (const file of page.files) {
    // Filter by byte size and MIME type before loading large content.
    console.log(file.name, file.size, file.mimeType, file.modifiedAt)
  }
  cursor = page.nextCursor
} while (cursor)
```

Import External Files

await tavo.file.import(<options>)

Opens the system file picker, copies the selected external files into Tavo storage, and returns an ImportedFile[]. The result is always an array even when only one file can be selected. Cancelling returns [].

options.multiple: allow multiple selection, default false

options.scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;

options.extensions: optional non-empty extension array such as [&#x27;txt&#x27;, &#x27;md&#x27;]. Do not include leading dots, wildcards, or path separators

options.conflict: behavior when a destination already exists, default &#x27;rename&#x27;

&#x27;rename&#x27;: keep the existing file and name the import name (1).ext, name (2).ext, and so on

&#x27;overwrite&#x27;: replace the existing file. For duplicates in one batch, the last selection wins

&#x27;error&#x27;: fail the entire batch on a conflict

Each result contains:

```
{
  path: string       // Tavo virtual path, such as files/chat/source.txt
  name: string       // final stored name after conflict handling
  originalName: string
  size: number       // actual byte count read
  mimeType: string
}
```

Imports are atomic per selected batch. Tavo reads and stages every selection before committing it. If validation, reading, or writing any file fails, no partial import is left behind and overwritten destinations are restored.

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

Save a File

await tavo.file.save(<name>, <content>, <options>)

Writes content to storage and returns a relative path string. The returned path can be used directly in <img src>, referenceImages, and similar places.

name type string: file name with extension, such as &#x27;avatar.png&#x27;. It must not contain / \ : ..; otherwise an error is thrown. Same-name files are overwritten.

content type string: content to save. See "content and encoding" below.

options type object (optional):

scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;

encoding: auto-detected when omitted; explicitly supports &#x27;utf8&#x27; | &#x27;base64&#x27; | &#x27;dataUrl&#x27;

content and encoding

When encoding is omitted, content is detected automatically:

Starts with data:: treated as dataUrl and decoded into binary

Starts with http:// / https://: downloads and stores the remote content

Everything else: stored as UTF-8 plain text

Pass encoding explicitly to override auto-detection:

&#x27;utf8&#x27;: force plain text, even if the content looks like a dataUrl

&#x27;base64&#x27;: content is raw base64 without a data: prefix, decoded into binary

&#x27;dataUrl&#x27;: content is a dataUrl, with the prefix stripped before decoding

```
// Plain text / JSON (auto utf8)
await tavo.file.save(&#x27;note.md&#x27;, &#x27;# Title\nBody&#x27;)
await tavo.file.save(&#x27;cfg.json&#x27;, JSON.stringify({ theme: &#x27;dark&#x27; }), { scope: &#x27;global&#x27; })

// Generated image (generate returns a dataUrl, auto-detected)
const dataUrl = await tavo.image.generate(&#x27;a calico cat&#x27;)
const path = await tavo.file.save(&#x27;cat.png&#x27;, dataUrl)
document.getElementById(&#x27;out&#x27;).src = path  // render directly

// Download from URL
await tavo.file.save(&#x27;report.pdf&#x27;, &#x27;https://example.com/report.pdf&#x27;, { scope: &#x27;global&#x27; })

// Save the dataUrl string as plain text without decoding it
await tavo.file.save(&#x27;log.txt&#x27;, dataUrl, { encoding: &#x27;utf8&#x27; })
```

Load a File

await tavo.file.load(<name>, <options>)

Reads file content. Returns null if the file does not exist.

options.scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;

options.encoding: &#x27;utf8&#x27; (default, returns text) | &#x27;dataUrl&#x27; (returns dataUrl, usable in <img src>) | &#x27;base64&#x27; (returns raw base64)

name may be a single file name or the complete virtual path returned by save or import. A complete path determines its own chat or global scope, so do not also pass a conflicting options.scope.

```
const text = await tavo.file.load(&#x27;note.md&#x27;)                             // text
const dataUrl = await tavo.file.load(&#x27;cat.png&#x27;, { encoding: &#x27;dataUrl&#x27; })  // dataUrl
```

Tip: rendering images usually does not require load. Use tavo.file.url(name) or the path returned by save directly as <img src>. load is mainly for reading text configuration or when you need to process image bytes again.

Delete a File

await tavo.file.delete(<name>, <options>)

Deletes a file. Missing files are ignored. options.scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;.

name may also be a files/chat/<name> or files/global/<name> virtual path.

```
await tavo.file.delete(&#x27;cat.png&#x27;)
await tavo.file.delete(&#x27;logo.png&#x27;, { scope: &#x27;global&#x27; })
```

Check Whether a File Exists

await tavo.file.exists(<name>, <options>)

Returns a boolean. options.scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;.

name may also be a files/chat/<name> or files/global/<name> virtual path.

```
if (await tavo.file.exists(&#x27;avatar.png&#x27;)) {
  document.getElementById(&#x27;out&#x27;).src = tavo.file.url(&#x27;avatar.png&#x27;)
}
```

Build a Render Path (Synchronous)

tavo.file.url(<name>, <scope>)

Synchronously returns the file&#x27;s relative path. It does not check whether the file exists and always returns a string. Use it when you know the file name and want to build an <img src> directly:

scope: &#x27;chat&#x27; (default) | &#x27;global&#x27;

```
// Known file name, render directly (no await and no need to remember the save return value)
imgEl.src = tavo.file.url(&#x27;avatar.png&#x27;)
imgEl.src = tavo.file.url(&#x27;logo.png&#x27;, &#x27;global&#x27;)
```

Export an External File

await tavo.file.export(<name>, <content>, <options>)

await tavo.file.export(<path>)

Passes content to the system share/save UI without first writing it into Tavo storage. It resolves to undefined after the system flow completes.

The one-argument overload exports the exact bytes of an existing files/chat/... or files/global/... virtual path. It is the intended companion to tavo.theme.export().

name: export file name. It must not contain / \\ : ..

content: string content

options.encoding: &#x27;utf8&#x27; (default) | &#x27;base64&#x27; | &#x27;dataUrl&#x27;

Encoding is explicit and is not auto-detected like tavo.file.save. Omit options for ordinary text, and choose base64 or dataUrl explicitly for binary content.

```
await tavo.file.export(&#x27;notes.txt&#x27;, &#x27;Plain UTF-8 text&#x27;)
await tavo.file.export(&#x27;image.png&#x27;, imageBase64, { encoding: &#x27;base64&#x27; })
await tavo.file.export(&#x27;image.png&#x27;, imageDataUrl, { encoding: &#x27;dataUrl&#x27; })
```

Select, Read, Translate, and Export

```
const [source] = await tavo.file.import({ extensions: [&#x27;txt&#x27;, &#x27;md&#x27;] })
if (source) {
  const text = await tavo.file.load(source.path)
  const translated = await tavo.generate(`Translate this content into English:\n\n${text}`)
  await tavo.file.export(`translated-${source.name}`, translated)
}
```

Notes

File names must not contain / \ : .. to prevent path traversal. Violations throw Error.

Same-name files are overwritten without a conflict prompt.

chat scope files are cleaned up when the chat is deleted; global scope files must be deleted by the script.

When cloning a chat, the original session&#x27;s chat scope files are copied into the new session. Paths stored in variables or messages do not need to be rewritten because paths are not tied to a chatId and are resolved against the currently viewed chat.

URL downloads do not enforce an allowlist, size limit, or timeout. Script authors are responsible for the source.

Example

```
<h3>📁 File Storage Demo</h3>
<button onclick="run()">Generate and Save</button>
<p id="status"></p>
<div id="out"></div>
<script>
async function run() {
  const status = document.getElementById(&#x27;status&#x27;)
  const out = document.getElementById(&#x27;out&#x27;)
  status.textContent = &#x27;Generating...&#x27;
  try {
    const dataUrl = await tavo.image.generate(&#x27;a calico cat on a windowsill&#x27;, { aspectRatio: &#x27;1:1&#x27; })
    // Save to disk and keep the short path instead of putting a large dataUrl into messages or variables.
    const path = await tavo.file.save(&#x27;demo-cat.png&#x27;, dataUrl)
    out.innerHTML = `<img src="${path}" style="max-width:240px;border-radius:8px" />`
    // Store the path in a variable for later use.
    tavo.set(&#x27;lastCat&#x27;, path)
    status.textContent = &#x27;Saved: &#x27; + path
  } catch (e) {
    status.textContent = &#x27;Failed: &#x27; + e.message
  }
}
</script>
```

⌨️ Input Box

You can use this interface to read or manipulate the chat input box. All input interfaces are tavo.input.<method>(...).

Read Input Box

await tavo.input.get()

Gets the current text content in the input box:

```
let text = await tavo.input.get()  // Get current input box content
```

Write to Input Box

tavo.input.set(<text>)

Overwrites the input box content (clears existing content):

```
tavo.input.set(&#x27;Hello!&#x27;)  // Replace input box content with "Hello!"
```

Append to Input Box

tavo.input.append(<text>)

Appends text to the end of current input box content:

```
tavo.input.append(&#x27; Let us keep chatting&#x27;)  // Append text to existing content
```

Clear Input Box

tavo.input.clear()

Clears input box content:

```
tavo.input.clear()
```

Send Message

tavo.input.send()

Sends the current input through the normal chat flow and waits until Tavo accepts or rejects it. It does not wait for model or image generation:

```
tavo.input.set(&#x27;Nice weather today&#x27;)
const result = await tavo.input.send()
if (!result.ok) console.log(result.reason, result.text)
```

Success returns { ok: true, text }. Failure returns { ok: false, reason, text }, where reason is cancelled, busy, or rejected. Plugin cancellation may also include cancelledBy and message. text is the final input after input hooks and trimming.

Example

Copy the following into any bubble to see it in action.

```
<h3>Input Box API Demo</h3>
<pre id="log" style="background: #0006; font-size: 12px; padding: 1em 1.5em; min-height: 60px; max-height: 200px; overflow-y: auto;"></pre>
<div style="display: grid; grid-template-columns: auto auto auto auto auto;">
  <button onclick="inputGet()">tavo.input.get</button>
  <button onclick="inputSet()">tavo.input.set</button>
  <button onclick="inputAppend()">tavo.input.append</button>
  <button onclick="inputClear()">tavo.input.clear</button>
  <button onclick="inputSend()">tavo.input.send</button>
</div>
<script>
  const log = (...args) => {
    const text = args.map(v => typeof v === &#x27;string&#x27; ? v : JSON.stringify(v, null, 2)).join(&#x27; &#x27;);
    document.getElementById(&#x27;log&#x27;).textContent = text + &#x27;\n\n&#x27;;
  };
  async function inputGet() {
    const text = await tavo.input.get();
    log(&#x27;Current input content:&#x27;, JSON.stringify(text));
  }
  function inputSet() {
    const text = prompt(&#x27;Enter content to write into the input box&#x27;, &#x27;Hello!&#x27;);
    if (text === null) return;
    tavo.input.set(text);
    tavo.utils.toast(&#x27;Written to input box&#x27;);
  }
  function inputAppend() {
    const text = prompt(&#x27;Enter content to append to the end of the input box&#x27;, &#x27;(appended content)&#x27;);
    if (text === null) return;
    tavo.input.append(text);
    tavo.utils.toast(&#x27;Appended to input box&#x27;);
  }
  function inputClear() {
    tavo.input.clear();
    tavo.utils.toast(&#x27;Input box cleared&#x27;);
  }
  async function inputSend() {
    tavo.input.set(&#x27;This message is automatically sent by tavo.input.send()&#x27;);
    log(await tavo.input.send());
  }
</script>
```

🛠️ Utilities

General utility interfaces. All utility interfaces are tavo.utils.<method>(...).

Toast

tavo.utils.toast(<text>)

Shows a lightweight toast notification that disappears automatically after a few seconds.

Open URL

tavo.utils.openUrl(<url>)

Opens a URL in an external browser:

```
tavo.utils.openUrl(&#x27;https://example.com&#x27;)
```

Export File

tavo.utils.export(<name>, <data>)

Deprecated compatibility API. It preserves the previous Base64-first auto-detection behavior. New code should use tavo.file.export, which has explicit encoding and defaults to UTF-8.

data can be Base64-encoded content or plain text:

```
tavo.utils.export(&#x27;Yeli Character Card&#x27;, btoa(&#x27;This is text or binary data converted to base64 using btoa&#x27;))  // Pass base64 data (recommended)
tavo.utils.export(&#x27;record.txt&#x27;, &#x27;This is plain text content&#x27;)  // Plain text
```

Fullscreen Image Preview

tavo.utils.preview(<src-or-img>)

Passing an <img> element: Since v1.0.0

Opens a fullscreen image viewer with zoom, pan, and save-to-album support. The argument can be an <img> element or a src string in one of these forms:

a data:image/<mime>;base64,... dataUrl, such as the return value of tavo.image.generate

an http:// or https:// URL

an in-app relative file path, such as files/<...>

src must be a non-empty string, otherwise an Error is thrown. The function returns immediately after opening the viewer; it does not wait for the user to close it.

```
// Preview an image generation result
const img = await tavo.image.generate(&#x27;a calico cat sleeping on a keyboard&#x27;)
tavo.utils.preview(img)
```

To make an image inside a bubble open the fullscreen preview when clicked, bind the click handler yourself:

```
<img src="..." onclick="tavo.utils.preview(this)" style="cursor:pointer" />
```

When you pass the <img> element, the preview expands from the image&#x27;s current position. Passing only a src string remains supported and uses a simple fade transition.

Tavo does not automatically intercept clicks on ordinary HTML <img> elements. Leave decorative images without onclick, and bind preview only for interactive images. Markdown images still receive click-to-preview automatically, so the script author controls which HTML images are clickable.

Ask the User

Since v1.0.0

await tavo.utils.ask({ question, options?, allowOther?, placeholder?, defaultValue? })

Shows a native question UI and waits for the user&#x27;s answer. The argument must be one object, and question is the only required field. It supports three modes:

```
// 1. Text only
await tavo.utils.ask({ question: &#x27;What should I call you?&#x27; })

// 2. Suggested options plus custom text; allowOther defaults to true
await tavo.utils.ask({
  question: &#x27;Which style do you prefer?&#x27;,
  options: [
    { value: &#x27;concise&#x27;, label: &#x27;Concise&#x27; },
    { value: &#x27;detailed&#x27;, label: &#x27;Detailed&#x27; },
  ],
})

// 3. Strict options without custom text
await tavo.utils.ask({
  question: &#x27;Choose the export format&#x27;,
  options: [&#x27;Markdown&#x27;, &#x27;Plain text&#x27;],
  allowOther: false,
})
```

options accepts non-empty strings or { value, label, description?, meta? } objects. meta provides short supplementary information for an option.

placeholder customizes the text-field hint. defaultValue preselects a matching option or prefills custom text when custom answers are allowed.

Questions, option values, and labels must be non-empty. Normalized option values must be unique, and unknown fields are rejected.

allowOther defaults to true. When it is false, options are required and any defaultValue must match an option.

Choosing an option returns immediately. Custom text is trimmed and must be submitted explicitly.

An answer returns { "status": "answered", "answer": "concise", "source": "option" }; custom text uses source: "custom". Closing the UI returns { "status": "cancelled" } successfully.

Select Picker

await tavo.utils.select(<options>, <title?>, <defaultValue?>)

Shows a native select picker and returns the selected value when the user makes a choice, or null if cancelled. This compatibility API is unchanged: it still uses positional options, title?, defaultValue? parameters and returns String?.

options: array of options — supports three formats:

string[]: plain string array; value and display text are the same

{ value: string, label: string }[]: object array; value is returned, label is displayed

{ value: string, label: string, description?: string, meta?: string }[]: full object with optional supplementary metadata and description

title: (optional) picker title shown at the top

defaultValue: (optional) value of the pre-selected option

```
// 1. String array
const fruit = await tavo.utils.select([&#x27;Apple&#x27;, &#x27;Banana&#x27;, &#x27;Orange&#x27;], &#x27;Choose a fruit&#x27;)

// 2. Object array (value + label)
const lang = await tavo.utils.select([
  { value: &#x27;zh&#x27;, label: &#x27;中文&#x27; },
  { value: &#x27;en&#x27;, label: &#x27;English&#x27; },
  { value: &#x27;ja&#x27;, label: &#x27;日本語&#x27; },
], &#x27;Select language&#x27;, &#x27;en&#x27;)

// 3. Full object (value + label + description + meta)
const role = await tavo.utils.select([
  { value: &#x27;warrior&#x27;, label: &#x27;Warrior&#x27;, description: &#x27;Melee physical damage&#x27;, meta: &#x27;Beginner friendly&#x27; },
  { value: &#x27;mage&#x27;,    label: &#x27;Mage&#x27;,    description: &#x27;Ranged magic damage&#x27;,   meta: &#x27;High burst&#x27; },
  { value: &#x27;healer&#x27;,  label: &#x27;Healer&#x27;,  description: &#x27;Support & healing&#x27;,     meta: &#x27;Team support&#x27; },
], &#x27;Choose a class&#x27;, &#x27;mage&#x27;)

if (role !== null) {
  tavo.utils.toast(`You selected: ${role}`)
}
```

Example

Copy the following into any bubble to see it in action.

```
<h3>Utilities API Demo</h3>
<div style="display: grid; grid-template-columns: auto auto auto; gap: 8px;">
  <button onclick="utilsToast()">tavo.utils.toast</button>
  <button onclick="utilsOpenUrl()">tavo.utils.openUrl</button>
  <button onclick="utilsExport()">tavo.utils.export</button>
</div>
<script>
  function utilsToast() {
    const text = prompt(&#x27;Enter toast text to display&#x27;, &#x27;Hello, this is a Toast notification!&#x27;);
    if (!text) return;
    tavo.utils.toast(text);
  }
  function utilsOpenUrl() {
    const url = prompt(&#x27;Enter a URL to open&#x27;, &#x27;https://example.com&#x27;);
    if (!url) return;
    tavo.utils.openUrl(url);
  }
  function utilsExport() {
    const filename = prompt(&#x27;Enter export filename&#x27;, &#x27;demo.txt&#x27;);
    if (!filename) return;
    const content = `This is a test file exported by tavo.utils.export.\nExport time: ${new Date().toLocaleString()}`;
    tavo.utils.export(filename, content);
  }
</script>
```

📱 App

You can use this interface to read some app properties. All interfaces are tavo.app.<method>(...).

Get Current App Version

```
await tavo.app.version();  // string: 0.77.0
await tavo.app.versionNumber();  // number: 770
```

🏷️ Version

You can access the current API version namespace. Tavo provides versioned API namespaces, for example tavo.v1 for the v1 API.

```
// These are equivalent
tavo.get(&#x27;name&#x27;)
tavo.v1.get(&#x27;name&#x27;)
```

🔌 Compatibility

Compatibility helpers for scripts migrated from other platforms.

Trigger Slash Commands (SillyTavern Compatible)

triggerSlash(<cmd>)

Triggers a SillyTavern-style slash command, useful for compatibility with scripts migrated from SillyTavern:

```
triggerSlash(&#x27;/send Hello World | /trigger&#x27;)
```

⏳ Continuously Updated

The TavoJS API is still in an early beta stage and is actively evolving. If you have questions or great ideas, feel free to share feedback in the community.

Tool Calling

Let compatible chat models use Tavo&#x27;s built-in native and TavoJS tools.

Macros

**Macros** can dynamically inject content into character definitions, presets, World Books, regular expressions, and all other prompt generation areas.