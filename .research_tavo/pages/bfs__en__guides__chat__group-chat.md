URL: https://docs.tavoai.dev/en/guides/chat/group-chat/
STATUS: 200

Guide
Chat

Copy Page

Group Chat

Create and Manage Group Chats

1.1. Create Group Chat

In the left "Menu Bar," click the "+" button at the top.

In the pop-up menu, select "Create Group Chat."

In the character list, check the roles you want to add to the group chat. Confirm to start a new group chat session.

1.2. Manage Group Members

In the group chat interface, click the sidebar icon in the top-right corner to open the right-side function panel. In the "Group Members" area, you can manage as follows:

Add Members: Click the "Add" button and select new roles from the list to join the group chat.

Remove Members: Click the "×" button on the right side of any member entry to remove them from the group chat.

Mute/Unmute Members: Click the message icon on the right side of any member entry to toggle their mute/unmute status.

Set Reply Mode

Find the "Reply Mode" setting below the sidebar panel and choose different group chat interaction logic:

Natural Chat: Mentioned roles will reply first; if no one is mentioned, a random role will speak.

All Members Reply: All roles will reply to every message you send.

Designated Speaker: Roles will not reply automatically. You must explicitly specify a speaker by using @role_name.

Contextual Speaker: Tavo decides who should speak next based on the current conversation context.

Contextual Speaker Settings

When using Contextual Speaker, Tavo sends a speaker-selection prompt before each round of replies to decide which character or characters should speak next.

Contextual Speaker API: optionally assign a dedicated API for speaker selection. After it is set, this API is used instead of the chat API for deciding speakers.

Contextual Speaker Prompt: controls how Tavo chooses the next speaker. Keep {{group}} in the prompt so Tavo can insert the current group member list.

Result format: the prompt should ask the model to return only character names, separated by commas when multiple characters should speak.

Advanced Settings

In the chat interface, click the sidebar icon in the top-right corner to open the right-side function panel. Within the "Advanced Options" section of the panel, you can configure the following core settings:

Import & Export

Open the sidebar and click "+" - "Import Chat History", then select the chat History file exported from Chat Platform (with .jsonl file extension, you can search for "jsonl" extension in the folder to locate the file)