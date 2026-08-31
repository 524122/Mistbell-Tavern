URL: https://docs.tavoai.dev/en/guides/voice-connection/voice-setting/
STATUS: 200

Guide
Voice & Image Generation

Copy Page

Voice Settings

Configure the rules and playback behavior for voice output here.

Operation Path

Open the left sidebar.

Click the "More" option, then find and select "Voice" from the menu.

After entering the voice settings interface, select the "Voice Settings" tab.

Switch Options Explanation

You can enable or disable the following features here:

Auto-Play Voice

◦ Function: When enabled, voice playback will automatically start when receiving character replies.

◦ Example: When a character says "Good morning!", the system will read it aloud without requiring a click.

Play *Tones*

◦ Function: When enabled, system narration or descriptive content will also be played as voice.

◦ Example: When narration like "It starts raining outside" appears in the story, it will also be read aloud.

Play "Quotes" Only

◦ Function: When enabled, voice playback will be limited to text marked with the > symbol.

◦ Example: For a message like:

This is the quoted part

This is regular text

Enabled: Only "This is the quoted part" will be read aloud.

Disabled: The entire message will be read aloud.

Play 'Code Blocks'

◦ Function: When enabled, controls whether to read code or specially formatted text enclosed in triple backticks (```) in messages.

◦ Example Message:

```
Today's weather: Sunny, Temperature: 25°C
```

Enabled: The system will read "Today's weather: Sunny, Temperature: 25°C" verbatim.
Disabled: The gray code block will be completely skipped, with no sound produced, and playback will continue with the following content.

Play <tags>

◦ Function: When enabled, content wrapped in specific tags within messages will also be read aloud.

◦ Example: For a message like:
<action>Nods head</action>
Enabled: "Nods head" will be read aloud.
Disabled: The tag content will be skipped and not read aloud.

Allow Background Playback

◦ Function: When enabled, voice playback can continue even when switching apps or locking the screen.

◦ Example: When enabled, even if you switch to WeChat or lock the screen, the current character's voice conversation will not be interrupted.

Regex Playback Rules

Voice playback now supports custom regular-expression rules. Use them when the built-in switches are not enough to control exactly which parts of a message should be spoken.

Operation Path

Open Voice Settings.

Find the playback rules area.

Create a new playback rule.

Each rule includes:

Name: a label that helps you identify the playback rule.

Regular Expression: the pattern used to match message content.

Rule: choose whether matched content should be included in playback or excluded from playback.

Examples

Exclude bracketed notes such as [system note].

Exclude stage directions such as *smiles*.

Include only dialogue wrapped in a specific format.

When multiple rules are used, keep each rule narrow and test it with a short message first.

Character Voice Binding

You can bind exclusive voices to characters to achieve a personalized voice conversation experience.

Image Generation API Settings

Configure the image generation API under More - Voice & Image Generation to enable Tavo's image generation feature.