URL: https://docs.tavoai.dev/en/guides/voice-connection/image-setting/
STATUS: 200

Guide
Voice & Image Generation

Copy Page

Image Generation Settings

Generate Images in Chat

Tap the "+" button on the left side of the chat input box, then choose "Generate Image" to enter image generation mode.

You can also type the image generation command directly in the chat input box.

Format:

```
/imagine prompt
```

Example:

```
/imagine a dark room
```

After the image is generated, tap the image in chat to open the preview page.

There are 3 buttons at the bottom of the preview page:

 Edit Again: return to the prompt editing state for the current image, then edit the prompt and continue generating.

 Generate Again: regenerate an image with the current prompt.

 Download: save the image locally. Before downloading, make sure Tavo has photo album permission.

Image Injection Prompt

"Image Generation Settings" controls the text written into chat history when an image is generated. The default template is:

```
[Generated an image: {{prompt}}]
```

{{prompt}} is replaced with the prompt used for the current image generation request.

This text is written with the generated image description or chat history, making it easier to reference later in the conversation.

If you are not sure what to write, keep the default. The "Restore Default" button in the upper-right corner restores the official template.

Suggestions

Keep {{prompt}} if you want the character to remember what the image was based on.

For shorter chat history, use a shorter template such as [Image: {{prompt}}].

After editing the template, test it with a simple prompt to confirm that the history display looks right.

Image Generation API Settings

Configure the image generation API under More - Voice & Image Generation to enable Tavo's image generation feature.

Local TTS Speech Service Configuration

Through appropriate voice configuration, each AI character can have a unique voice that matches their personality traits, thereby enhancing the overall conversational experience and immersion.