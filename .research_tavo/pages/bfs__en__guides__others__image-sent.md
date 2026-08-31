URL: https://docs.tavoai.dev/en/guides/others/image-sent/
STATUS: 200

Guide
App & Data

Copy Page

Image Sending

After enabling this feature, you can send images to characters. The AI model will recognize and describe the image content to facilitate conversation.

Operation Path

Open the left sidebar.

Click the "More" option, find and select "Voice" in the menu.

After entering the settings interface, select the "Chat Settings" tab.

In the chat settings, find and enter the "Image Sending" submenu.

Core Configuration Steps

Enable the Feature: Set the "Image Sending" function switch to the "On" state.

Configure Image Description API (Key Step):

◦ You need to select a model API that supports multimodal recognition.

◦ API Requirement: The model behind this API must have the ability to understand image content (i.e., a "multimodal model"), such as GPT-4V, Claude-3, Gemini, etc.

Configure Prompts (Recommended Default):

◦ Image Description Generation Prompt: Used to guide the model on how to describe the images you send.

◦ Image Description Injection Prompt: Used to guide the system on how to integrate the image description into the conversation context with the character.

◦ Friendly Reminder: If you are unfamiliar with writing prompts, it is recommended to directly use the system's default prompts, which usually yield good results.

After completing the above configurations, you can send images to characters in the chat interface.

Backup

You can use this feature to back up your core data (including API keys) and restore data from backup files at any time to ensure the security of your data configuration.

Custom Shortcuts

With this feature, you can create and manage a set of personalized quick input phrases to rapidly enter frequently used content during chats, thereby improving conversation efficiency.