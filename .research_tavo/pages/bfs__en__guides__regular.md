URL: https://docs.tavoai.dev/en/guides/regular/
STATUS: 200

Guide

Copy Page

Regex

Purpose of Regex Configuration

In Tavo, regular expressions are used for automated text content processing. Specifically, they can help you:

Identify specific text patterns, such as removing unwanted prefixes or extracting specific information.

Modify or replace text content to make character dialogue more natural or compliant with settings.

Clean redundant characters or formatting to make conversations more concise and standardized.

The main application of regex is in text processing and automatic replacement. For example, you can automatically remove redundant information from user and character conversations, or standardize formatting.

Regex Assistant

When creating or editing a regex rule, tap the code icon on the right side of "Regular Expression" to open the "Regex Assistant".

Choose a template from the "Regex Assistant" popup, and Tavo will automatically fill in the corresponding regular expression. The current built-in templates are: Chain of Thought, Quote, Narration, Markdown Code Block, and Tag.

After choosing a template, continue with the configuration fields below, such as replacement content, scope, and execution timing. If you want more templates added, leave feedback in the Tavo Discord.

Detailed Explanation of Regex Configuration Items

1. Name

Purpose: Give this rule a name for easy identification and management.

Example:

Name: Clean System Prompts

This is just for your convenience in identifying it. For instance, when you have multiple rules, the name helps you quickly find the rule you need.

2. Regular Expression (Find Regex)

Purpose: The regular expression is the text pattern you want to match. It's used to search for specific patterns in text and tells the system what content you want to match.

Example:

Regular Expression: ^\[System Prompt\]: (.+)$

This regular expression means: match all content that starts with [System Prompt]: and capture all text after [System Prompt]:. For example, "Task Started" in [System Prompt]: Task Started will be matched and extracted.

^: indicates matching the beginning of a line.

\[System Prompt\]: matches the literal characters [System Prompt] (square brackets [ and ] have special meaning in regular expressions and need to be escaped with backslashes \).

(.+)$: matches and captures the content after the colon.

3. Replace With

Purpose: This is the text you want to use to replace the matched content. You can use $1, $2, etc., to reference captured groups from the regular expression.

Example:

Replace With: $1

This means replacing the first captured group from the regular expression (i.e., the content after [System Prompt]:) with new content. So, [System Prompt]: Task Started becomes Task Started.

You can also use other placeholders, such as:

{{match}}: represents the complete matched content (the entire [System Prompt]: Task Started).

$1: represents the first captured group (i.e., "Task Started").

4. Trim Out

Purpose: Before executing the replacement, remove certain parts from the matched content, usually used to eliminate unwanted parts.

Example:

Trim Out: Player says:

This function will remove all matched instances of Player says:, making the content you process cleaner.

5. Scope

Purpose: Defines which types of messages this rule applies to.

Specifically includes:

User Messages: applies to messages sent by users.

Character Messages: applies to messages sent by characters.

Chain of Thought: processes content in the chain of thought during AI's thinking process.

World Book: applies to processing character backgrounds, world settings, and other content.

Example:

If you want to remove redundant prefixes from character messages, select "Character Messages" as the scope.

Scope: Character Messages

6. Execution Timing

Purpose: Controls when the regex rule takes effect, specifically including:

On Display: executes regex when messages are prepared for display to users.

On Send: executes regex when messages are sent to the system.

On Send and Display: executes regex both when sending and displaying.

On Receive: executes regex when receiving messages.

On Receive and Rewrite: executes regex when receiving messages and modifying content.

Example:

If you want to remove unnecessary symbols or redundant parts before messages are displayed, you can select "On Display":

Execution Timing: On Display

This way, the system will perform text processing before presenting messages to users.

7. Replace Parameters

Purpose: Controls whether to perform replacement and how to handle replacement content.

Specifically includes:

No Replace: performs no replacement, keeping content as is.

Plain Replace: preserves original formatting when replacing.

Escape Replace: automatically handles special characters when replacing content (e.g., properly escaping backslashes and other escape characters).

Example:

If you select "Plain Replace", the replaced content will maintain the original formatting, avoiding encoding errors and other issues:

Replace Parameters: Plain Replace

Comprehensive Example:

Suppose you have the following rule:

[System Prompt]: in user messages needs to be removed, keeping only the content that follows.

Using regular expression: ^\[System Prompt\]: (.+)$

Replace with: $1

Scope: User Messages

Execution Timing: On Display

Replace Parameters: Plain Replace

After configuration, a user message:

[System Prompt]: Task Started

Will be displayed as:

Task Started

Summary

Regular expressions help you identify and modify specific patterns in text.

Replace with determines how to modify matched text.

Scope and Execution Timing help you decide in which situations and when to apply these modifications.

Replace Parameters control specific behaviors during replacement.

Through regular expressions, you can greatly improve the management and automated processing of conversation content, making dialogues more natural and meeting expectations.

Lore Book

**World Book** is a **"background encyclopedia" and "story guide"** written for the story world you co-create with AI characters. It does not appear directly in dialogues but works behind the scenes, ensuring the AI remembers all the world rules, key information, and plotlines you set, keeping the entire story consistent, logical, and immersive.

Long Memory Documentation

Imagine having multiple conversations with an AI, and everything you discuss can be remembered and referenced in future conversations. This is **Long Memory**. It allows the AI to remember what you've said without forgetting after each conversation ends. Your preferences, interests, and important details are all preserved and can be recalled at any time.