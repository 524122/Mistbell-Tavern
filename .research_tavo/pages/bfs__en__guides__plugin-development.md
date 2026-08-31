URL: https://docs.tavoai.dev/en/guides/plugin-development/
STATUS: 200

Guide

Copy Page

Plugin Development

Since v0.91.0

Tavo plugins are zip-format .tpg packages.

What Plugins Can Do

Plugins can add reusable tools and interaction features to Tavo:

Add plugin settings, such as switches, options, text fields, and sliders.

Add actions to the chat input + menu or the chat right sidebar.

React to chat/message changes and rewrite or cancel input-box sends.

Mount HTML fragments into the chat page, such as status bars, floating panels, or message decorations.

Use the TavoJS API to work with the input box, messages, variables, generation, and other Tavo capabilities.

Build a plugin when an extension should be reusable across characters, personas, or chats. Use character-card TavoJS instead when the behavior belongs to one specific card.

Requirement

Plugins require Advanced Rendering to be enabled for the chat. Plugin code is separate from JavaScript in character cards and messages; chat-content JavaScript settings do not disable enabled plugin code.

Package Structure

Create a folder, then zip the folder contents as a .tpg file. manifest.json must be at the package root. If the plugin declares input actions or sidebar actions, its entry file must also exist in the package.

```
my-plugin/
├── manifest.json
├── entry.js
├── locales/
│   ├── en.json
│   └── zh-CN.json
├── ui/
│   └── panel.html
└── cover.png
```

```
cd my-plugin
zip -r ../my-plugin.tpg manifest.json entry.js locales ui cover.png
```

Tavo rejects packages with missing manifests, unsafe paths, unsupported specVersion, or missing entry script files. If a package uses a specVersion newer than the installed Tavo supports, update Tavo instead of changing the manifest to an older version whose field semantics may differ. Plugin paths are package-relative virtual paths and must use forward slashes / on every platform, including Windows. Do not put OS path separators or raw path.join() output in manifest paths. Do not include absolute paths, Windows backslash paths, or files that rely on ../ traversal.

Choosing a Plugin Surface

Choose the surface from the user job first, then write manifest.json. A plugin may declare multiple surfaces. A common pattern is to store configuration with settings.schema, then expose one or more surfaces to run or display the feature.

Surface
Use when
Notes

contributes.inputActions
The user is composing in the input box and wants an explicit one-tap action: draft a reply, rewrite the current input, insert a template, append prompt text, or prepare before sending.
Native menu labels are single-line. Handlers currently receive no arguments. There is no current message context, so tavo.message.current() returns null.

contributes.sidebar
The feature is a chat-level utility that is useful but lower frequency: summarize the chat, export or save data, batch-edit messages, refresh plugin state, or run a one-off management flow.
This is a native right-sidebar action, not an embedded panel. The first version does not support icons, descriptions, dynamic state, or chat-type filters.

contributes.htmlFragments mounted under /chat
The feature needs persistent chat-page UI: a status bar, floating panel, global control strip, style injection, or page-level display based on the current chat or input.
Chat fragments appear on the chat page and do not have current message context. Keep them lightweight and avoid covering the host chat UI.

contributes.htmlFragments mounted under /messages
The feature belongs next to individual messages: message decorations, status labels, per-message buttons, a block on the last character message, or UI that depends on message-scoped variables.
A message fragment renders for every matching message unless filtered by role or position. It has tavo.message.current(), but DOM and script cost matter more here.

manifest.json

manifest.json describes the plugin identity, entry script, requested permissions, settings form, input actions, right-sidebar actions, and HTML fragments.

```
{
  "specVersion": 2,
  "id": "com.example.quick-note",
  "name": { "$t": "plugin.name" },
  "version": "1.0.0",
  "entry": "entry.js",
  "author": "Example Author",
  "description": { "$t": "plugin.description" },
  "releaseNotes": { "$t": "releaseNotes.1_0_0" },
  "localization": {
    "defaultLocale": "en",
    "resources": {
      "en": "locales/en.json",
      "zh-CN": "locales/zh-CN.json"
    }
  },
  "cover": "cover.png",
  "permissions": ["input", "message", "tts"],
  "contributes": {
    "inputActions": [
      { "id": "insert-note", "label": { "$t": "actions.insertNote" } }
    ],
    "sidebar": [
      { "id": "append-note", "label": { "$t": "actions.appendNote" } }
    ],
    "htmlFragments": [
      { "id": "chat-note-panel", "src": "ui/panel.html", "mount": "/chat/body/end" }
    ],
    "settings": {
      "schema": [
        { "type": "info", "text": { "$t": "settings.info" }, "icon": "info" },
        { "key": "enabled", "type": "switch", "label": { "$t": "settings.enabled" }, "default": true },
        {
          "key": "mode",
          "type": "select",
          "label": { "$t": "settings.mode.label" },
          "default": "short",
          "options": [
            { "value": "short", "label": { "$t": "settings.mode.short" } },
            { "value": "detailed", "label": { "$t": "settings.mode.detailed" } }
          ]
        },
        { "key": "strength", "type": "slider", "label": { "$t": "settings.strength" }, "min": 0, "max": 1, "step": 0.1, "default": 0.5 },
        { "type": "break" },
        { "key": "template", "type": "textarea", "label": { "$t": "settings.template.label" }, "default": { "$t": "settings.template.default" } }
      ]
    }
  }
}
```

Root Fields

Manifest v2 and minAppVersion: Since v0.93.0

Field
Required
Notes

id
Yes
Lowercase plugin id. Use letters, digits, ., _, or -, for example com.author.my-plugin. Tavo normalizes it to lowercase.

name
Yes
Display name. In v2 it may be a literal string or { "$t": "key" }.

version
Yes
In v2 this must be valid SemVer, such as 1.0.0 or 1.0.0-beta.1; v1 still accepts any non-empty version string.

specVersion
Yes for new plugins
Use 2. Missing or explicit 1 remains supported for local-install compatibility but has no package localization API.

entry
Conditional
Required when contributes.inputActions or contributes.sidebar is declared. It points to the plugin entry script, usually entry.js. The path must be relative, stay inside the plugin package, and use / separators. Legacy scripts.actions manifests still work as a compatibility alias; if both are present, entry wins.

author
Yes in v2
Author name shown in plugin details. It must be 1–32 user-visible characters on one line and is not localized.

description
Yes in v2
Detailed description shown in plugin lists and details. It may be localized and supports Markdown.

releaseNotes
No
Release notes for the sibling version. In v2 it may be localized and supports Markdown. Each package describes only its current release, without repeating the version or embedding the complete history.

localization
Yes in v2
Requires defaultLocale; an optional resources map points locale tags to package-local JSON catalogs.

cover
No
Relative image path used as the plugin cover.

minAppVersion
No
Optional in v2; when present it must be valid SemVer and is checked before installation. v1 does not enforce a minimum app version.

permissions
No
String array describing the capabilities used by the plugin, such as input, message, generate, variable, file, network, or tts.

contributes
No
Declarative plugin contributions.

User-visible Text Limits

Manifest v2 applies the same limits to literal declarations and to the current
value after a $t reference is localized. Lengths count user-perceived Unicode
characters (grapheme clusters), not UTF-16 code units.

Location
v2 limit

manifest name
Required, 1–64 characters, single-line

manifest author
Required, 1–32 characters, single-line

manifest description
Required, 1–10,000 characters and at most 64 KiB UTF-8; multiline

manifest releaseNotes
Optional, 1–5,000 characters and at most 32 KiB UTF-8; multiline

input/sidebar action label
1–48 characters, single-line

settings field and structured select-option label
1–80 characters, single-line

settings info.text
1–500 characters, multiline plain text

Every value must contain non-whitespace text and must not contain unsafe control
characters. Single-line fields reject line breaks. If a catalog value violates
the relevant limit, Tavo ignores it and continues through the $t fallback
chain.

Use description for a complete explanation of the plugin's purpose, workflow,
capabilities, and caveats. Prefer at least 200 characters while staying within
the hard limits. GitHub Flavored Markdown is supported and recommended for
headings, lists, emphasis, and links; raw HTML is not executed. The plugin list
derives a localized, whitespace-collapsed plain-text preview of up to 120
characters from description; there is no separate summary field.

Use releaseNotes for the changes in the sibling version. Do not repeat the
version inside the field or copy the complete release history into every
package. The field may use GitHub Flavored Markdown. Omitting it does not block
validation, installation, or runtime behavior.

Version and App Compatibility

For specVersion: 2, version and the optional minAppVersion use SemVer. A
version must include major.minor.patch, such as 1.2.0; v1.2.0, 1.2, and
versions with leading zeroes are invalid. Prereleases follow SemVer precedence,
so 1.0.0-rc.1 < 1.0.0.

Missing specVersion and explicit specVersion: 1 manifests keep their legacy
behavior: version and minAppVersion may remain arbitrary non-empty strings,
and no minimum app version is enforced.

Localization

Since v0.93.0

Manifest v2 uses one package-local catalog for both Tavo's native plugin UI and
plugin HTML/JavaScript. localization.defaultLocale is required. The optional
resources map uses hyphenated locale tags such as en, en-US, zh-Hans, or
zh-CN; underscore tags such as zh_CN are invalid. Catalog paths must obey the
same package-relative path rules as entry and HTML fragments.

Strongly recommend supporting at least en, plus at least one additional
language commonly used by the plugin's intended users. Supporting more
languages is welcome. Choose additional locales from the actual audience
instead of assuming one fixed second language.

```
{
  "localization": {
    "defaultLocale": "en",
    "resources": {
      "en": "locales/en.json",
      "zh-CN": "locales/zh-CN.json"
    }
  }
}
```

Each catalog is a flat UTF-8 JSON object with non-empty string keys and string
values:

```
{
  "plugin.name": "Quick Note",
  "plugin.description": "Adds a quick note panel to chat.",
  "releaseNotes.1_0_0": "## Initial release\n\n- Add the quick note panel.",
  "actions.insertNote": "Insert Note",
  "actions.appendNote": "Append Note",
  "settings.info": "Configure the note panel.",
  "settings.enabled": "Enabled",
  "settings.mode.label": "Mode",
  "settings.mode.short": "Short",
  "settings.mode.detailed": "Detailed",
  "settings.strength": "Strength",
  "settings.template.label": "Template",
  "settings.template.default": "Remember:",
  "runtime.panel.title": "Quick Note",
  "runtime.greeting": "Hello, {name}!",
  "runtime.input.rememberPrefix": "Remember: ",
  "runtime.input.sidebarNote": "Note from the sidebar plugin",
  "runtime.input.draft": "Draft:\n{input}"
}
```

Use a plain string for text that should always display exactly as written. Only
an object of the exact shape { "$t": "key" } requests localization. Strings
beginning with $ are still literal. Keys may be semantic names such as
plugin.name or source text such as Quick Note; semantic keys are recommended,
not required. Tavo does not automatically translate content or require every
catalog to contain the same keys. If no catalog resolves a $t key, Tavo shows
the key itself. Normal installation and runtime do not block the plugin for
that reason; the tavo_plugin_audit flow below compares catalog keys and
returns non-blocking findings.

Localization is supported for:

manifest name, description, and releaseNotes;

input-action and sidebar-action label;

settings field label and info element text;

structured select-option label;

default on text and textarea settings.

For each key, Tavo tries the requested locale and compatible locales, then
English, then the plugin's defaultLocale, and finally the key itself. When the
App follows the system language, plugins use the raw device locale even if Tavo's
own interface does not support that language.

Localized Plugin HTML and JavaScript

tavo.plugin.i18n: Since v0.93.0

Every localized v2 plugin scope exposes the synchronous tavo.plugin.i18n API
in entry, action handlers, /chat fragments, and /messages fragments:

```
const i18n = tavo.plugin.i18n;

function render() {
  document.querySelector('#title').textContent =
    i18n.t('runtime.panel.title');
  const greeting = i18n.t('runtime.greeting', { name: 'Colin' });
  document.querySelector('#greeting').textContent = greeting;
}

render();
const unsubscribe = i18n.onChange((event) => {
  console.log(event.requestedLocale, event.locale);
  render();
});
```

requestedLocale, locale, and defaultLocale are live getters.

supportedLocales is a copy-safe array.

t(key, params?) synchronously returns a string. It returns a missing key
unchanged and supports simple {name} placeholders for strings, finite
numbers, and booleans. For example, the catalog value Hello, {name}! with
{ name: 'Colin' } returns Hello, Colin!.

onChange(handler) returns an unsubscribe function. New getters and t()
values are available before the handler runs.

Changing language does not rerun the plugin's entry script. Tavo also does not
translate or mutate existing plugin DOM automatically; rerender it from the
onChange handler. This namespace is plugin-only and is not available to plain
character-card or message TavoJS.

Treat localization as part of plugin completion, not only manifest setup. Put
all user-visible manifest, settings, HTML, and JavaScript text in the catalogs
and resolve it with { "$t": "key" } or tavo.plugin.i18n.t() as appropriate. This
includes HTML text nodes, buttons, placeholders, title and aria-label
attributes, loading and empty states, errors, confirmations, and toast text.
Brand names, protocol tokens, identifiers, and other intentionally invariant
values may remain literal. Every localized HTML fragment must render once with
the current locale and subscribe to tavo.plugin.i18n.onChange() so changing
language rerenders the complete user-visible fragment.

Treat localization as three surfaces that must be checked independently:

Host-native UI: manifest, action labels, and settings use $t.

Plugin runtime UI: HTML, toasts, errors, and dynamic copy use
tavo.plugin.i18n.t(), and HTML rerenders completely from onChange().

Generated or inserted visible content: explicitly follow the current
conversation language, the App/plugin locale, or a stable user setting.
Hidden prompts may stay in the author's working language, but they must
explicitly constrain the language of user-visible output.

Do not replace Tavo's locale with navigator.language, private localStorage,
or a parallel plugin language toggle. Those sources drift from the App language.

Entry Script

Since v0.92.0

entry is the plugin's main script. It registers handlers for declared input/sidebar actions and plugin hooks. Legacy scripts.actions still works as a compatibility alias.

Action handlers use the TavoJS API to interact with Tavo. The example below only shows plugin event registration; see the full API docs for input, message, variable, and generation interfaces.

```
tavo.plugin.onInputAction('insert-note', async () => {
  await tavo.input.append(tavo.plugin.i18n.t('runtime.input.rememberPrefix'));
});

tavo.plugin.onSidebarAction('append-note', async () => {
  const note = tavo.plugin.i18n.t('runtime.input.sidebarNote');
  await tavo.input.append(`\n\n${note}`);
});
```

Plugins that only provide settings, HTML fragments, or other declarative contributions can omit entry unless they also need to run JavaScript.

Using tavo

Use tavo directly in entry, /chat HTML fragments, and /messages HTML fragments, for example await tavo.input.get(). Tavo automatically associates it with the current plugin. Do not use window.tavo or globalThis.tavo; they are not part of the plugin API.

/messages HTML fragments can read the current message with tavo.message.current(). The method returns null in entry, input actions, sidebar actions, and /chat HTML fragments.

```
// Recommended across plugin surfaces.
tavo.plugin.onInputAction('guide', async () => {
  const input = await tavo.input.get();
  await tavo.input.set(tavo.plugin.i18n.t('runtime.input.draft', { input }));
});

// Avoid window/globalThis in plugin code.
// const input = await window.tavo.input.get();
// const cfg = globalThis.tavo.plugin.config.get('basePrompt');
```

Reading Plugin Settings

contributes.settings.schema declares the settings form and its defaults. Plugin code reads the current plugin's effective configuration values from tavo.plugin.config. Both methods are synchronous and read-only, so do not use await:

```
const enabled = tavo.plugin.config.get('enabled');
const config = tavo.plugin.config.all();
```

get(key) returns the saved user value, falling back to that field's schema default. It returns null when the key has neither a saved value nor a default.

all() returns a shallow copy of all effective values, including schema defaults and user overrides. Mutating the returned object does not save or change plugin settings.

tavo.plugin.config is available in entry, input/sidebar action handlers, and /chat or /messages HTML fragments. It only reads settings for the current plugin.

This API does not return the raw contributes.settings.schema definition and does not provide write methods. The plugin owns its manifest schema, and the user changes values through Tavo's plugin settings page.

Input Actions

contributes.inputActions declares items for the chat input + menu.

```
{
  "contributes": {
    "inputActions": [
      { "id": "insert-note", "label": { "$t": "actions.insertNote" } }
    ]
  }
}
```

Field
Required
Notes

id
Yes
Stable action id.

label
Yes
Text shown in the menu.

icon
No
Optional icon name for future-compatible manifests.

Register input action handlers in the file referenced by entry. Prefer onInputAction; it registers the lower-level event inputActions:<action-id>:

```
tavo.plugin.onInputAction('insert-note', async () => {
  await tavo.input.append(tavo.plugin.i18n.t('runtime.input.rememberPrefix'));
});
```

You can also use the lower-level event form: tavo.plugin.on('inputActions:<id>', handler). New plugins should prefer onInputAction(id, handler); it validates id and registers the lower-level inputActions:<id> event.

Sidebar Actions

contributes.sidebar declares actions for the chat right sidebar.

```
{
  "contributes": {
    "sidebar": [
      { "id": "append-note", "label": { "$t": "actions.appendNote" } }
    ]
  }
}
```

Field
Required
Notes

id
Yes
Stable action id.

label
Yes
Text shown in the right sidebar row.

Register sidebar action handlers in the file referenced by entry. Prefer onSidebarAction; it registers the lower-level event sidebar:<action-id>:

```
tavo.plugin.onSidebarAction('append-note', async () => {
  const note = tavo.plugin.i18n.t('runtime.input.sidebarNote');
  await tavo.input.append(`\n\n${note}`);
});
```

You can also use the lower-level event form: tavo.plugin.on('sidebar:<id>', handler). New plugins should prefer onSidebarAction(id, handler); it validates id and registers the lower-level sidebar:<id> event.

Native input and sidebar dispatch do not care whether the handler was registered through a helper or plugin.on; they only look up the final event name. When using plugin.on, <id> must exactly match contributes.inputActions[].id or contributes.sidebar[].id, including case, hyphens, and other characters.

If a plugin declares multiple sidebar actions, Tavo shows them in one section titled Plugin · Plugin Name.

Plugin Hooks

Entry scripts register hooks with tavo.plugin.on(type, handler).

Chat and Message Notifications

Since v0.92.0

These hooks notify plugins about chat and message changes. They cannot modify or block chat or generation flow. An error in one handler does not affect the chat or other plugins.

Event
When it fires

chat:opened
The current chat opens.

chat:closed
The user leaves the current chat or switches to another chat.

chat:updated
Current chat metadata changes, such as its title, characters, persona, preset, lorebooks, memory, or background.

chat:changed
Compatibility alias of chat:updated; the handler still receives event.type as chat:updated.

message:added
A message is added and saved to the current chat; it does not fire repeatedly during streaming.

message:updated
The content or metadata of a saved message in the current chat changes.

message:deleted
A message is removed from the current chat.

message:changed
Umbrella event emitted after message:added, message:updated, or message:deleted.

The specific message event fires first, followed by message:changed. If the user already has a chat open when the plugin is enabled, the plugin also receives one chat:opened event.

Every event object contains type, pluginId, and the ISO timestamp string at. Chat events also contain chatId and chat; message events also contain chatId, change, and message.

```
tavo.plugin.on('chat:opened', async (event) => {
  console.log('opened', event.chat?.name || event.chatId);
});

tavo.plugin.on('message:changed', async (event) => {
  console.log(event.type, event.change, event.message?.id, event.at);
});
```

Generation Lifecycle Hooks

Since v0.92.0

Register these hooks from an installed plugin's entry script with tavo.plugin.on(...). HTML fragments and TavoJS in character cards or messages cannot register or receive them. Declare "permissions": ["generate"] in the manifest.

```
tavo.plugin.on('generation:prepare', async (event) => {
  event.text = '[Model-only context]\n' + event.text;
});

tavo.plugin.on('generation:success', async (event) => {
  event.text = event.text.trim();
});

tavo.plugin.on('generation:error', async (event) => {
  console.error(event.error.code, event.error.message);
});

tavo.plugin.on('generation:cancelled', async (event) => {
  console.log('stopped', event.partial);
});
```

Every event has read-only generationId, chatId, source, at, type, and pluginId. These hooks currently cover reply, groupReply, continuation, othersContinuation, and regeneration. They do not cover image, speech, summarization, independent, or pure TavoJS/JSAPI generation.

generation:prepare runs after the user message is saved and visible but before the model request starts. A handler may be synchronous or return a Promise; Tavo waits for that Promise before starting the model request. Its mutable event.text is the last user message sent to the model for this request. Changes affect only this model request, never the message saved in the chat, and may be empty.

generation:success runs after generation and extension processing but before
the character message saves. Its mutable final response body must be non-empty;
an empty rewrite is discarded.

generation:error notifies the plugin when generation fails. event.error provides code and message.

generation:cancelled is a non-blocking terminal notification with boolean
partial. A partial: true response still saves and then emits the existing
message:added; a partial: false cancellation saves no message.

generation:prepare handlers run in registration order and the whole pipeline shares one 55-second budget, rather than giving every handler 55 seconds. If a handler throws, times out, or writes invalid text, Tavo ignores that handler's changes and continues generation; handlers that have not started are skipped when the budget expires. Stopping generation ends the host wait immediately and late results are ignored, but Tavo cannot forcibly cancel network I/O already started by plugin code; use an AbortController when the plugin must cancel its own request. generation:success keeps its five-second limit per handler. These hooks cannot call event.cancel() to cancel generation. Each generation fires exactly one of generation:success, generation:error, or generation:cancelled.

Choosing a Send-Time Hook

Need
Hook

Fast validation, cancelling a send, or changing the user text saved in chat
input:beforeSend

Embedding, vector recall, external APIs, or model-only context injection
generation:prepare

Fast cleanup of the final model response
generation:success

Do not implement memory recall as input:beforeSend → event.cancel() → await an external API → tavo.input.send(). That interception/resend pattern keeps the message out of the chat until the work finishes and risks recursive sends or duplicate side effects. Migrate the slow work to generation:prepare:

```
tavo.plugin.on('generation:prepare', async (event) => {
  const original = event.text;
  const recalled = await recallMemory(original); // plugin-owned implementation
  if (!recalled) return;
  event.text = ['<memory>', recalled, '</memory>', original].join('\n');
});
```

The sequence is: input:beforeSend → persist and show the user message → generation:prepare → build the model request → start generation. Prepare can rewrite only the transient latest-user text. It cannot add a separate system/context message or change the user message already shown in the chat.

Input Send Hooks

Since v0.92.0

input:beforeSend intercepts sends from the send button / Enter, tavo.input.send(), and MCP tavo_input_send. input:afterSend notifies the plugin after Tavo accepts the input. Declare "permissions": ["input"] in the manifest.

```
tavo.plugin.on('input:beforeSend', async (event) => {
  event.text = event.text.trim();
  if (!event.text.includes(':')) event.cancel('Add a speaker name');
});

tavo.plugin.on('input:afterSend', async (event) => {
  console.log('accepted input:', event.text);
});
```

Before-send runs before macros and slash-command parsing. type, pluginId, chatId, source, and at are read-only; text is the only mutable field and must remain a string. source is ui, tavojs, or mcp. Handler return values are ignored; call event.cancel(reason?) to cancel explicitly.

Handlers run in plugin and registration order, with a five-second limit for each handler. If a handler throws, times out, or changes text to a non-string value, Tavo ignores that handler's changes and continues sending. Explicit cancellation stops later handlers and keeps text changes and attachments completed by earlier handlers. input:afterSend does not wait for model or image generation.

HTML Fragments

contributes.htmlFragments declares local HTML files that appear on the chat page or next to messages.

Scripts in HTML fragments belong to the installed plugin and are not affected by chat-content JavaScript settings. Those settings only control scripts in character cards, model output, and other message bubble content.

```
{
  "contributes": {
    "htmlFragments": [
      { "id": "chat-panel", "src": "ui/panel.html", "mount": "/chat/body/end" },
      { "id": "message-tail", "src": "ui/tail.html", "mount": "/messages/end?role=character&position=last" }
    ]
  }
}
```

Field
Required
Notes

id
Yes
Stable fragment id.

src
Yes
Relative file path inside the plugin package. It cannot be absolute, contain \, use a URL scheme, or escape with ../.

mount
Yes
Mount target. Supported surfaces are /chat/... and /messages....

Floating Controls in Chat Fragments

If a /chat HTML fragment uses position: fixed, position: absolute, or a
similar overlay technique to place a floating button over the chat page, the
button must be draggable so controls from multiple plugins do not overlap
each other or cover Tavo's chat UI.

Support mouse and touch dragging, and pen input when practical.

Distinguish a click from a drag so repositioning does not trigger the
button's primary action.

Prefer remembering the last position under stable plugin and fragment ids.
Clamp a restored position to the visible viewport after window, orientation,
or safe-area changes.

Initial and restored positions must not cover critical host controls such as
input, send, or back controls. Verify common phone, tablet, and desktop sizes.

Provide a discoverable way to reset the saved position when it becomes
invalid or the user can no longer drag the control.

Supported chat mounts:

/chat

/chat/head/start

/chat/head/end

/chat/body/start

/chat/body/end

Supported message mounts:

/messages/start

/messages/end

/messages/start?role=user

/messages/end?role=character

/messages/end?position=last

/messages/end?role=character&position=last

For message mounts, role may be user or character; position may be first or last.

Settings Schema

contributes.settings.schema is a flat array rendered in order on the plugin settings page.

This declares the settings form schema. Plugin code reads effective values, after schema defaults and saved user values are resolved, through tavo.plugin.config.get(key) or tavo.plugin.config.all(). The plugin API does not expose the raw schema.

Type
Required fields
Optional fields
Notes

switch
key, label
default
Boolean setting.

select
key, label, options
default
options may contain legacy strings or structured { value, label } objects.

slider
key, label, min, max
step, default
max must be greater than min; step must be greater than zero.

text
key, label
default
Single-line text input.

textarea
key, label
default
Multi-line text input.

info
text
icon
icon may be info or warning.

divider
none
none
Full-width divider.

break
none
none
Starts a new settings group without rendering a visible control.

Field elements use key as the stored configuration key. Avoid changing keys after release unless you are intentionally resetting existing user configuration.

A legacy string select option uses the same literal text for its stored value
and display label. A structured option keeps a stable, non-localized value
while its label may be a literal or $t object. Language changes update the
label without changing saved values.

For text and textarea, the default may also be a $t object. The localized
default follows language changes only while the user has no saved override. A
saved value is never overwritten by a language change; resetting the field
removes the override and resumes the current localized default.

Build With AI Agents

AI agents such as Codex and Claude Code can help you work with project files and tools directly. After connecting to Tavo's MCP Server, they can read the live resource docs and build plugins from your request.

In Tavo, enable the MCP Server from Settings -> MCP Server.

Connect your AI agent using the setup in MCP Server.

Ask the AI agent to read the MCP Server's plugin resource docs, such as tavo://docs/plugins.

Describe the plugin you want, and require it to run
tavo_plugin_validate_manifest, tavo_plugin_audit, and
tavo_plugin_package in that order.

Review the audit report, then enable the plugin in Tavo and switch between
supported languages in a backup chat.

If you want to publish it, ask the AI agent to package a .tpg file.

You can start with a prompt like this:

```
Read the Tavo MCP Server plugin resource docs first, such as tavo://docs/plugins.
Build a Tavo plugin named Quick Note.
It should have manifest specVersion 2, an entry.js script at the package root,
localization.defaultLocale set to en, support for at least en, and one additional
language commonly used by the intended users, with more languages when useful.
All user-visible manifest, settings, HTML, and JavaScript text must use the
catalogs. HTML must render initially and rerender completely after language changes.
Define the language policy for generated or inserted user-visible content. Do not
read navigator.language or keep a separate plugin locale. After writing the source,
run tavo_plugin_validate_manifest, tavo_plugin_audit, and tavo_plugin_package in
order and review every localizationAudit finding. Do not claim localization is
complete while findings remain unreviewed.
The plugin also needs an input action named insert-note, a settings switch named
enabled, and an HTML fragment mounted at /chat/body/end.
Install, enable, and test it in Tavo when finished. If I want to publish it,
package it as a .tpg file.
```

Start from this generic, copyable bilingual template:
manifest.json,
entry.js,
HTML fragment,
English catalog, and
Simplified Chinese catalog.

The agent's tool flow should be:

```
tavo_plugin_validate_manifest -> tavo_plugin_audit -> tavo_plugin_package -> tavo_plugin_install
```

Build With Conversational AI

Conversational AI such as ChatGPT, Claude, Gemini, and DeepSeek works mainly through a chat window. It can help you design plugin ideas, draft manifest.json, write entry.js, and create HTML fragments before you copy the files locally and test the package.

Every docs page has a Copy Page button that copies the current page as Markdown. Paste that Markdown into the conversational AI as context, together with your plugin goal, target Tavo version, desired actions, and settings.

Click Copy Page on this page.

Send the copied Markdown and your plugin requirements to the conversational AI.

Ask it to output a file tree, manifest.json, entry.js, and any required HTML fragments.

Copy the generated files into a local plugin folder, package them as .tpg (zip format), and test the plugin in a backup chat.

If installation fails or the plugin behaves unexpectedly, paste Tavo's error and the current file contents back into the conversation for another revision.

Validation Checklist

Before sharing a plugin:

Install it in a clean Tavo profile or backup chat.

Confirm the package root contains manifest.json, plus the entry file when the plugin declares input or sidebar actions.

Confirm all HTML fragment src files exist.

Run tavo_plugin_validate_manifest, tavo_plugin_audit, and
tavo_plugin_package in order, then handle or review every
localizationAudit finding.

Confirm all user-visible manifest, settings, HTML, and JavaScript text is in
the catalogs, with no unintended hard-coded copy.

Confirm every localized HTML fragment performs an initial render and
rerenders through tavo.plugin.i18n.onChange().

Switch between the plugin's supported App languages and confirm every native
and HTML surface updates without raw keys, stale previous-language text, or
unintended fallback-language text.

Confirm generated or inserted user-visible content has an explicit language
policy and test it in the intended conversation language.

Confirm every settings field has a stable key.

Confirm the permission list is no broader than necessary.

Export or keep the source folder alongside the generated .tpg.

Using Plugins

Install and manage Tavo plugin packages safely.

Other

**1. Storage Space Management** - Storage Space