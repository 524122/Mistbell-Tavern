URL: https://docs.tavoai.dev/en/guides/plugins/
STATUS: 200

Guide

Copy Page

Using Plugins

Since v0.91.0

Plugins are reusable Tavo extensions packaged as .tpg files.

What Plugins May Add

A settings page for the plugin.

Actions in the chat input + menu.

Actions in the chat right sidebar.

Chat UI fragments, such as status bars, floating panels, or message decorations.

Feature labels that explain what the plugin changes.

Install only trusted plugins

Plugin packages may include scripts and UI fragments. Only install files from creators you trust, and do not install forwarded or unknown .tpg files if you cannot verify their contents.

Install A Plugin

Open Settings.

Open Plugins.

Tap the import button in the top-right corner.

Select a .tpg plugin package.

Read the install warning and continue only if you trust the file.

After installation, the plugin appears in the plugin list.

Manage A Plugin

From Settings -> Plugins, you can:

Enable or disable a plugin.

Open the plugin settings page.

Search installed plugins by name, id, author, description, or version.

Uninstall a plugin.

If a plugin exposes settings, open the plugin and adjust its fields there. Use the reset action on that page to restore fields that have default values.

Plugin Language

Localized plugins follow Tavo's selected App language. If Tavo is set to follow
the system, plugins use the device's current language even when Tavo's own
interface does not offer that language.

A plugin may not include every language or every translated line. Tavo first
tries your language and compatible variants, then English, then the plugin's
declared default text. If the creator used an unresolved translation key, that
key may be shown as the final fallback.

Changing App or system language automatically updates native plugin text such
as the plugin name, action labels, and settings. Plugin-authored HTML/JavaScript
UI updates only when the plugin listens to tavo.plugin.i18n.onChange and
rerenders its own content. Tavo does not automatically rewrite arbitrary plugin
DOM. Neither kind of language change requires reinstalling the plugin.

Advanced Rendering

Plugins that add chat UI fragments require Advanced Rendering to affect the chat page.

Chat UI fragments are installed plugin runtime code. They are not disabled by the chat-content JavaScript execution mode, which only controls scripts embedded in character cards, model output, and other message bubble content.

If a plugin says it provides Chat UI but nothing appears in chat, check:

Settings -> Advanced Rendering is enabled.

The plugin itself is enabled.

The plugin package contains the UI files referenced by its manifest.

Troubleshooting

If a plugin cannot be installed, Tavo will reject it before writing it as an installed plugin. Common causes include:

The package does not contain manifest.json at the root.

manifest.json is empty or not valid JSON.

The package uses unsafe file paths such as absolute paths or ../.

The manifest declares input or sidebar actions, but its entry file is missing from the package.

The manifest uses unsupported fields or invalid contribution shapes.

The plugin uses a newer specVersion than this Tavo release supports. Update Tavo to the latest version and try installing it again.

Build Your Own Plugin

Plugin authors should read Plugin Development. It explains package structure, manifest.json, HTML fragments, settings schema, and AI-agent-assisted or conversational-AI-assisted workflows.

Current Limits

Plugins are still early. Test new plugins in a backup chat first, and keep backup or export habits before using plugins that change important roleplay flows.

MCP Server

Connect an AI agent to Tavo through the built-in MCP Server.

Plugin Development

Create Tavo .tpg plugin packages, write manifest.json, and use AI agents to build plugins.