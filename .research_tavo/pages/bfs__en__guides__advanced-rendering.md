URL: https://docs.tavoai.dev/en/guides/advanced-rendering/
STATUS: 200

Guide

Copy Page

🖥️ Advanced Rendering (Web)

Advanced Rendering

Enabling Advanced Rendering (AR) allows the chat page to render standard HTML and CSS, supporting highly powerful and flexible page beautification.

How to Enable

Open the main interface.

Click the top-left corner to open the left sidebar.

Click More at the bottom.

Click Settings.

Click Advanced Rendering.

Toggle the Advanced Rendering switch to ON.

How to Use

=== A Simple Example: HTML Code
Edit any chat bubble on the chat page and paste the following content:

```
This is a line with <span style="color: red">red</span> text.
It also includes <strong>bold</strong> text.
Sometimes you can even see an image <img className="max-w-md w-full" src="/static/images/docs/upload-wikimedia-org-0c3ce2fd3bba.jpg" alt="" />
```

===

JavaScript Support

Long Memory Documentation

Imagine having multiple conversations with an AI, and everything you discuss can be remembered and referenced in future conversations. This is **Long Memory**. It allows the AI to remember what you've said without forgetting after each conversation ends. Your preferences, interests, and important details are all preserved and can be recalled at any time.

Tool Calling

Let compatible chat models use Tavo's built-in native and TavoJS tools.