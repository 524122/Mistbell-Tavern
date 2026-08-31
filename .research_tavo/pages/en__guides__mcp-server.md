URL: https://docs.tavoai.dev/en/guides/mcp-server/
STATUS: 200

Guide

Copy Page

MCP Server

Since v0.91.0

Tavo includes a built-in MCP Server that lets AI agents such as Codex or Claude Code connect and create directly. The creation flow no longer needs to repeat create-import-verify; agents can make changes and validate them quickly inside Tavo.

The MCP Server exposes runtime docs for the current Tavo version, so connected agents can read the docs and call available tools, including but not limited to reading or modifying Tavo characters, lorebooks, regexes, presets, personas, chat themes, chats, messages, and long-term memory, and inspecting the current runtime state.

Chat themes use tavo_theme_search, tavo_theme_get, tavo_theme_create, tavo_theme_update, tavo_theme_import, tavo_theme_export, and tavo_theme_delete. tools/list and tavo://schemas/chat-theme expose the complete nested field contract used by runtime validation, and unknown fields are rejected. Read tavo://themes/{id} for a live theme resource. Import/export requires an explicit chatId and uses chat-scoped .thm files. Import conflicts default to an error; retry with conflict: "overwrite" or conflict: "saveAs". Official themes are read-only. Archives are limited to 64 MiB compressed, 16 regular entries, and 256 MiB total uncompressed content.

Long-term memory uses tavo_memory_get, tavo_memory_update, and
tavo_memory_append, each with an explicit chatId. These tools remain
available while memory injection is disabled. Append preserves existing entries
and does not enable injection implicitly.

Character write tools accept bare CC-compatible data, complete CCv2/CCv3 wrappers, and SillyTavern character wrappers. Lorebook tools accept CCv3 data, standalone lorebook_v3, SillyTavern World Info, and Tavo-native entries. The tavo://schemas/* resources are JSON Schema Draft 2020-12 documents; the live tools/list response remains canonical for exact tool-call arguments.

Preset and regex imports accept SillyTavern export shapes, while create/update tools use Tavo-native fields. Nested validation failures identify paths such as preset.entries[0].enabled or regex.entries[0].placements[0]; missing preset and regex targets return the matching resource URI.

Invalid tool input returns JSON-RPC -32602 (Invalid params).
error.data.reason explains the rejection and safe error.data.details can
identify path, expected, and actualType. Character and lorebook import
values and keys are user-visible content and may appear in diagnostics. Internal
failures return -32603 (Internal error) instead and do not include validation
details; clients should not treat them as correctable argument errors.
Message tools validate ranges, filters, id/index selectors, and writable fields.
Invalid input identifies paths such as filter.hidden or message.content;
missing selected messages return -32004, the chat&#x27;s
tavo://chats/{id}/messages URI, and safe selector details.
Chat and persona create/update tools also expose concrete schemas. Invalid
values identify paths such as chat.characterIds[0], chat.pinned,
chat.mutedCharacterIds[0], persona.description, or persona.id. Missing
resources return -32004 with the matching chat, persona, character, preset,
lorebook, or regex resource URI.

File Tools

MCP exposes tavo_file_save, tavo_file_load, tavo_file_delete,
tavo_file_exists, and tavo_file_list. Every call requires an explicit
chatId, including calls that use global scope. Chat scope is the default;
use global scope only for an explicit cross-chat need.

Tool
Required arguments
Optional arguments

tavo_file_save
chatId, name, content
options.scope, options.encoding

tavo_file_load
chatId, name
options.scope, options.encoding

tavo_file_delete
chatId, name
options.scope

tavo_file_exists
chatId, name
options.scope

tavo_file_list
chatId
options.scope, options.limit, options.cursor

name is one file name without path separators, colons, or parent-directory
segments. Save and load support utf8, base64, and dataUrl.
tavo_file_list accepts a limit from 1 through 200, defaults to 100, and
continues with the opaque nextCursor returned by the preceding page.

```
[
  {
    "tool": "tavo_file_save",
    "arguments": {
      "chatId": 42,
      "name": "notes.txt",
      "content": "Saved through MCP",
      "options": { "scope": "chat", "encoding": "utf8" }
    }
  },
  {
    "tool": "tavo_file_list",
    "arguments": { "chatId": 42, "options": { "limit": 10 } }
  },
  {
    "tool": "tavo_file_load",
    "arguments": { "chatId": 42, "name": "notes.txt" }
  },
  {
    "tool": "tavo_file_exists",
    "arguments": { "chatId": 42, "name": "notes.txt" }
  },
  {
    "tool": "tavo_file_delete",
    "arguments": { "chatId": 42, "name": "notes.txt" }
  }
]
```

This lets an agent directly help create character cards, organize lorebooks, tune regexes, write plugins, or turn the current chat context into reusable material.

Enable The Server

Open Settings.

Open MCP Server.

Choose an access range.

Enable the server.

Copy the URL and bearer token into your MCP client.

The server is disabled by default. Tavo generates a bearer token when enabling the server if no custom token is set.

Keep your bearer token private

Anyone who can reach the MCP endpoint and has the bearer token can control the exposed Tavo tools. Do not paste the token into public chats, screenshots, docs, logs, or issue reports.

Agent Client

You do not need to hand-write setup commands. The easiest path is to copy Tavo&#x27;s full connection config and send it to your agent.

At the bottom of the MCP Server page, click Copy full connection config.

In your agent chat, send:

```
Tavo MCP Server: 0.91.0
Preferred access: direct HTTP JSON-RPC
Server URL: http://192.168.0.1:7347/mcp
Authorization: Bearer 123456
```

The agent replies that the connection succeeded.

Then ask the agent:

```
Help me write a character card for an imperial princess who has wandered through deep space for years.
```

The agent replies that the character card was created. You can then review and continue editing it in Tavo.

Can&#x27;t connect?

Check these first:

Make sure MCP Server is running. Try turning it off and on again.

Make sure both devices are on a network that can reach each other. VPNs, guest networks, and firewalls may block the connection.

If you do not have Wi-Fi, use a phone hotspot or USB tethering and keep LAN mode enabled.

If Personal Hotspot is enabled, connect the other device to that hotspot. If you are not using the hotspot, turn it off and try again.

An IP address shown from cellular data may be carrier-private and unreachable from other devices.

Use Public network only when the agent must connect from outside your private network. You usually also need port forwarding, a tunnel, or a reverse proxy.

Common errors:

401: Token is missing or wrong.

403: The selected access range does not allow the current device.

404/405: The URL or request method is wrong. Use a POST request.

Connection timeout: Usually caused by network, IP, port, or firewall issues.

EJS Templates

EJS templates let you write logic, read/write variables, and loop inside any prompt field — character cards, presets, lorebooks, regex — using <% %>. An advanced complement to Macros.

Using Plugins

Install and manage Tavo plugin packages safely.