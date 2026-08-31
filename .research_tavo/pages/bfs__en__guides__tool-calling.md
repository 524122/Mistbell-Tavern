URL: https://docs.tavoai.dev/en/guides/tool-calling/
STATUS: 200

Guide

Copy Page

Tool Calling

Since v1.0.0

Tavo can expose its built-in TavoJS and native app catalog, including capabilities such as file storage, tavo_web_fetch, and tavo_web_search, to compatible chat models. Native coordination, provider-protocol handling, discovery, and audit records run in Dart, while the TavoJS business tools execute in the active chat WebView. Native Dart tools require a compatible provider codec, the global Tool use setting, any complete default connection they depend on, and native transport. Advanced Rendering is required only for WebView-owned TavoJS, character-script, message-script, and plugin surfaces. A reply can keep running when you switch to another chat.

Supported protocol families include OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, and Gemini generateContent-compatible endpoints. Tool use is off by default.

Enable Tools

Open Settings.

Open Tools.

Enable Tool use.

Keep Load tools dynamically enabled unless your model has trouble using tavo_tool_search.

When dynamic loading is enabled, the first model request contains tavo_tool_search followed by the always-visible tavo_ask_user and tavo_web_fetch business tools. A complete Web Search API also adds tavo_web_search. Turning dynamic loading off sends the complete built-in catalog from the first request. Tavo does not silently retry without tools if a provider rejects the request.

Each model round that includes tools receives one short, general instruction to use a visible tool when it can directly fulfill the request. Tool-specific guidance lives in the descriptions for tavo_tool_search, extension_tool_search, and tavo_ask_user. Rounds without tools receive no tool-use instruction.

Discovering Tools

tavo_tool_search searches for hidden Tavo built-in tools, while
extension_tool_search searches tools provided by installed plugins or
connected MCP services. Matching tools become visible and callable in the next
round.

Argument
Required
Type
Description

query
Yes
string
English description of the required capability, up to 256 Unicode scalar values.

limit
No
integer
Return from 1 through 8 matches. Defaults to 8.

```
{
  "tool": "tavo_tool_search",
  "arguments": {
    "query": "list files in the current chat",
    "limit": 5
  }
}
```

Available Capabilities

The built-in catalog covers:

variables, messages, and the current chat

characters, personas, chat themes, presets, lorebooks, and regexes

memory, image generation, and TTS playback

input-box editing and user questions

chat-scoped and global file storage with tavo_file_save, tavo_file_load, tavo_file_delete, tavo_file_exists, and tavo_file_list

current-source search snippets with tavo_web_search when configured

reading HTTP(S) pages as Markdown with tavo_web_fetch

See the TavoJS API for complete public TavoJS signatures and examples. This page focuses on the stricter model-facing schemas and runtime behavior rather than repeating every TavoJS operation.

Calls in one model response run in provider order. Write operations use Tavo's confirmation settings where their individual contracts require it. Tool limits and timeouts are available under Settings > Tools > Execution settings.

Chat themes expose all, get, find, create, update, import, export, and delete tools. Import/export uses current-chat files/chat/*.thm paths. Same-name imports ask whether to overwrite or save as new; official themes are read-only. Updating or rebinding the current theme pushes a live WebView configuration update without reloading the page.

Theme create/update schemas enumerate every valid nested bubble, font, background, reasoning/status label, console, and avatar field. Bubble fill uses color, bubble text color lives under userBubbleFont.textStyle.color or characterBubbleFont.textStyle.color, and console background/text use color and fontColor. Models do not need to read an existing theme to guess field names.

tavo_file_import and tavo_file_export remain foreground-only TavoJS/plugin interactions and are not available to model tool calls or headless runtimes. tavo.file.url remains a trusted TavoJS helper and has no named model tool. tavo_utils_toast, tavo_utils_open_url, tavo_utils_export, tavo_utils_preview, tavo_utils_select, tavo_app_version, and tavo_app_version_number remain available to existing trusted TavoJS callers where applicable, but are absent from model tool calling. tavo_javascript_eval is unsupported. Future Programmatic Tool Calling is separate infrastructure and does not reuse that removed runtime.

Formal replies and their native tool loops are app-scoped rather than owned by the current chat page, so they can continue when you switch chats. A model request that needs confirmation or another platform interaction waits for the matching conversation UI; it is never shown over another chat, and time spent waiting is excluded from the named-tool timeout. This lifecycle separation does not survive app termination, keep a TavoJS promise alive after its WebView is destroyed, provide mobile background execution, or make WebView plugins headless.

Character writes accept bare CC-compatible data, complete CCv2/CCv3 wrappers,
and SillyTavern character wrappers. Lorebook writes accept CCv3 data,
standalone lorebook_v3, SillyTavern World Info, and Tavo-native entries.
Preset and regex imports accept SillyTavern export shapes, while create/update
tools use Tavo-native fields. Nested validation failures identify paths such as
preset.entries[0].enabled or regex.entries[0].placements[0]; missing preset
and regex targets return resource_not_found.
Memory updates accept the object returned by memory.current, or a partial
object containing enabled and/or memories. memory.append adds one or more
non-empty strings without replacing existing entries and never enables memory
injection implicitly. Memory tools remain available when long-term memory is
disabled because that switch controls prompt injection rather than memory
management. Public TavoJS and plugins retain
their existing file API return and soft-failure behavior.
Image generation exposes concrete options for size, aspect ratio, negative
prompts, reference images, provider-specific request fields, and artifact
storage. Invalid nested values identify paths such as
options.referenceImages[0]. A missing image endpoint returns
resource_not_found, provider rejection returns permission_denied, and
other generation or artifact-write failures return internal_error without
exposing provider response bodies or physical paths. Successful images are
displayed immediately after their tool calls without a separate artifact prompt.
Model TTS calls require
an explicit character or persona voice. Missing speakers and voice bindings
return resource_not_found, while playback startup failures return
internal_error. Public message scripts retain their caller-message voice
fallback.
Variable names are non-empty paths, and variable scopes distinguish chat,
global, latest-message, and explicit message targets. Resource get and
delete tools accept a positive id or an { "id": ... } object. find
requires a non-empty name and supports exact, contains, prefix, and
suffix; unknown selector and option fields are rejected. Missing get/delete
and message-write targets return resource_not_found. Input tools edit the
draft with strings and never send it; unexpected draft-access failures return a
sanitized internal_error. Public TavoJS and plugins retain their existing
compatibility behavior.
Successful model calls return { "ok": true, "result": ... }; failures return
{ "ok": false, "error": { "code": "...", "message": "...", "details": {...} } },
with details omitted when unavailable. Validation details can include path,
expected, and actualType. Character and lorebook import values and keys are
user-visible content and may appear in diagnostics. This model envelope does not
change the compatible soft sentinels used by some public/plugin TavoJS calls.

Working with Stored Files

The five non-UI file tools run natively and do not require Advanced Rendering. Chat scope is the default and is bound to the conversation that started the current reply, even if you switch chats while it runs. Global scope is shared across chats and should be used only for an explicit cross-chat need. Save and load support utf8, base64, and dataUrl. Save overwrites a same-name file. Save and delete execute directly without a confirmation dialog; deleting a missing file returns resource_not_found.

Tool
Required arguments
Optional arguments

tavo_file_save
name, content
options.scope, options.encoding

tavo_file_load
name
options.scope, options.encoding

tavo_file_delete
name
options.scope

tavo_file_exists
name
options.scope

tavo_file_list
None
options.scope, options.limit, options.cursor

name is one file name without path separators, colons, or parent-directory segments. options.scope defaults to chat and may be set to global for an explicit cross-chat need. For save and load, options.encoding defaults to utf8 and also accepts base64 or dataUrl.

```
[
  {
    "tool": "tavo_file_save",
    "arguments": {
      "name": "notes.txt",
      "content": "Saved from a model tool",
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

tavo_file_list accepts optional options: scope, limit from 1 through 200 (default 100), and the previous page's opaque cursor. It returns files plus an optional nextCursor. Each file has path, name, byte size, mimeType, and UTC modifiedAt. Names use case-sensitive ordering. Pagination reads live storage rather than a snapshot, and a cursor can be reused only with its original scope. Agents should inspect size and MIME metadata before loading large or binary files.

The normal 64 KiB per-result and 256 KiB per-reply aggregate limits apply. Oversized loaded text remains successful as a UTF-8-safe prefix and is marked truncated in Tavo's result state. Oversized structured output such as a file list becomes { "truncated": true, "preview": "..." }. If another result cannot fit the aggregate budget, the call fails with result_too_large.

Searching the Web

tavo_web_search searches current sources and returns concise snippets. To enable it, open Settings > Tools > Web Search API, add a Tavily API, and enter your own Tavily API key. Tavo does not provide a shared Tavily key. Queries and filters go directly from your device to Tavily without a Tavo proxy. You can choose basic, advanced, fast, or ultra-fast search depth, 1 through 3 snippets per source, and a default result count from 1 through 20. Snippets per source is not sent for ultra-fast searches. Saving validates fields locally and provides no Test Connection or other network request.

You can save multiple Web Search APIs. Whenever the list is non-empty, exactly one API is the default, and only that default can execute. If the default is incomplete, the Web Search tool is hidden with no fallback to another saved API. Tavily uses its fixed official API origin.

Web Search targets native Android, iOS, and macOS. Release-build validation is tracked separately and is not asserted here. Linux and Windows use the portable native transport but remain outside the current validation matrix. Flutter Web is excluded and has no browser request path.

The model must provide a query. It can optionally choose 1 through 20 results, a general/news/finance topic, a day/week/month/year time range, and include or exclude domain lists. Results contain normalized titles, URLs, snippets, optional scores or publication times, and warnings. They do not expose the Tavily request payload, API key, provider request identifier, or connection identity, and they have no cursor or artifacts.

Argument
Required
Type
Description

query
Yes
string
Search query from 1 through 1,000 characters.

max_results
No
integer
Result count from 1 through 20; the connection default is used when omitted.

topic
No
string
general, news, or finance.

time_range
No
string
day, week, month, or year.

include_domains
No
string array
Up to 20 domains to include.

exclude_domains
No
string array
Up to 20 domains to exclude.

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

Web Search returns source snippets only. It does not automatically open or download any result. The model can call the separate tavo_web_fetch tool to read a selected page in full.

Reading a URL

tavo_web_fetch reads one HTTP(S) resource with GET and returns readable Markdown. It supports HTML, Markdown, plain text, JSON, and XML. Web Fetch reads one supplied URL and does not search for sources, while the separate Web Search tool returns source snippets. Web Fetch does not execute page JavaScript, render through WebView, read PDFs or other binary media, use a proxy, use a client certificate, or fall back to a Tavo server. Pages that appear to need JavaScript return whatever static content can be extracted, possibly empty, with a warning rather than a separate failure prompt.

A fresh call accepts a URL and optional arbitrary string headers, including Authorization, Cookie, and API-key headers. Same-origin redirects retain those headers. Cross-origin redirects remove every caller-provided header before continuing and return a warning. Public hosts, localhost, loopback, private or link-local services, internal hostnames, and custom ports are allowed. On Android and iOS, localhost means that mobile device, not your desktop computer.

Argument
Required
Type
Description

url
For a fresh fetch
string
HTTP(S) URL to fetch from the beginning.

headers
No
object
String-valued headers used only for a fresh fetch.

cursor
For continuation
string
Opaque cursor returned by the preceding page; takes precedence over url and headers.

Fresh fetch:

```
{
  "tool": "tavo_web_fetch",
  "arguments": {
    "url": "https://example.com/article",
    "headers": { "Accept-Language": "en" }
  }
}
```

Continuation:

```
{
  "tool": "tavo_web_fetch",
  "arguments": { "cursor": "<next_cursor>" }
}
```

Long documents return approximately 24 KiB at a time with an opaque continuation cursor. A cursor takes precedence if the model also repeats the URL or headers. Continuation reads the same cached snapshot and does not download the page again. Invalid or expired cursors fail without falling back to the URL. Cursors and the bounded 24 MiB cache exist only for the current reply.

Default limits are 20 MiB transferred while compressed, 40 MiB after decompression, 25 MiB of HTML input, 10 MiB of extracted Markdown per document, and five redirects. Existing tool timeouts and result limits still apply.

Fetched Markdown is sent to the model provider configured for the active chat. Tavo's bounded local audit keeps the complete submitted URL, including query and fragment, and every supplied header name and value, including credentials. The result audit stores metadata such as final URL, HTTP status, MIME type, content byte count, duration, and warning codes; it does not store fetched content, the page title, continuation cursor, or request headers.

Asking You a Question

The tavo_ask_user tool description tells the model to call it instead of guessing or replying with only a plain-text question when missing information, ambiguity, confirmation, or your preference could materially affect the result. It recommends concise options when practical, keeps custom input available by default, and avoids asking about trivial details that can be safely inferred.

The always-visible tavo_ask_user tool can pause a reply for one of three interactions:

a text-only question when only question is provided

suggested options plus a custom text field, because allowOther defaults to true

strict options when allowOther: false

Options can be non-empty strings or objects with value, label, and optional description and meta. meta supplies short supplementary information for an option. Questions, option values, and labels must be non-empty, normalized option values must be unique, and unknown fields are rejected. Strict mode requires options, and its defaultValue, if provided, must match an option. placeholder customizes the text-field hint.

Argument
Required
Type
Description

question
Yes
string
Non-empty question shown to the user.

options
No
array
Non-empty strings or { value, label, description?, meta? } objects.

allowOther
No
boolean
Whether custom text is allowed. Defaults to true.

placeholder
No
string
Hint shown in the custom-answer field.

defaultValue
No
string
Initial option value or custom text; in strict mode it must match an option.

```
{
  "tool": "tavo_ask_user",
  "arguments": {
    "question": "Which scope should contain the file?",
    "options": [
      { "value": "chat", "label": "Current chat" },
      { "value": "global", "label": "All chats" }
    ],
    "allowOther": false,
    "defaultValue": "chat"
  }
}
```

Choosing an option returns immediately. Custom text is trimmed and must be submitted explicitly. An answer returns {"status":"answered","answer":"concise","source":"option"} or the same shape with source: "custom". Closing the question returns {"status":"cancelled"} successfully. Once an Ask, confirmation, or picker modal opens, time spent waiting for you does not count toward the tool timeout; it keeps waiting until you act, stop generation, or its runtime becomes invalid.

The older tavo.utils.select(options, title?, defaultValue?) TavoJS API remains available to trusted direct callers, but is not exposed to model tool calls because tavo_ask_user covers both open-ended and strict option-only interactions.

Switching Chats and Confirmations

If a tool needs confirmation, the originating reply pauses and releases its execution slot. You can switch chats while it waits. Tavo shows a badge on the originating chat, where you can approve, reject, or cancel the request.

This continuation is currently in-memory. If the app process exits after a reply enters the tool protocol or waits for confirmation, Tavo cancels that reply on cold start instead of replaying model rounds or side effects. Mobile background execution is not implemented in this release.

Advanced Rendering and Plugins

Native Dart tools, including tavo_web_fetch, tavo_web_search, and tavo_ask_user, do not require Advanced Rendering. They remain gated by a compatible provider codec, the global Tool use setting, any complete default connection they depend on, and native transport. The TavoJS business catalog requires Advanced Rendering and an active chat WebView. Turning Advanced Rendering off keeps the Tool use setting but removes WebView-owned definitions until the WebView is active again. Character-card scripts, message scripts, plugin UI, and plugin lifecycle hooks are also WebView-owned.

Plugins cannot contribute model tools yet. A future plugin tool system will use an explicit WebView executor and readiness contract instead of sharing the built-in Dart runtime implicitly. External MCP servers are also not connected to in-chat tool calling yet.

Privacy and Logs

Conversation messages keep compact business-tool summaries. Detailed arguments, results, timing, and outcomes are stored in auxiliary logs and can be cleared through Settings > Storage > Logs without deleting the compact message summaries. Web-fetch records use the narrower local-only projection described above. Web Search logs can retain the bounded query and normalized snippets, but not the connection secret or provider-only payload.

Preview remains limited to trusted direct TavoJS callers and is not exposed to model tool calls.

Advanced Rendering (Web)

Enabling **Advanced Rendering** (AR) allows the chat page to render standard HTML and CSS, supporting highly powerful and flexible page beautification.

TavoJS API

The TavoJS API is a set of JavaScript interfaces for players and creators, enabling users to access powerful functionalities and high playability when JavaScript support is enabled.