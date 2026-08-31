URL: https://docs.tavoai.dev/en/guides/preset/
STATUS: 200

Guide

Copy Page

🗒️ Presets

1. What are Presets?

Presets refer to a set of basic configurations and behavior patterns established for characters, helping you quickly set up identity, personality, behavioral guidelines, etc., for characters, reducing repetitive setup work. Through presets, you can quickly create characters or scenarios that meet specific needs. These presets will determine how characters behave, their dialogue style, and how they interact with users.

2. Basic Prompts in Presets

The basic prompts in presets are mainly used to determine the identity, personality, behavior patterns, and other core elements of characters, forming the basic framework of the character. Below is an explanation and examples of each basic prompt:

User Identity

Purpose: Defines your relationship with the character and interaction style.

Example:

{{user}} is {{char}}'s good friend, having a long-standing deep emotional bond with her, and will show intimacy and care during interactions.

Character Setting

Purpose: Establishes a complete identity background for the character, including their profession, goals, history, etc.

Example:

{{char}} is a smart and curious explorer who always pursues unknown territories. She has a goal: to find the lost treasure.

Personality Traits

Purpose: Describes the character's personality characteristics, helping determine how they react, express emotions, etc.

Example:

{{char}} has a calm and rational personality, is methodical in doing things, but shows firm determination and leadership when facing difficulties.

Scenario Setting

Purpose: Provides the current environment or background setting where the character is located, determining their behavior and reactions in this context.

Example:

{{char}} is currently standing in an ancient library, surrounded by dusty books, with a somewhat dark and mysterious atmosphere.

New Example Chat

Purpose: Provides dialogue examples with the character to help AI understand how to interact with {{user}}.

Example:

{{char}}: Do you think today's task will be completed smoothly? I'm a bit uncertain.
{{user}}: I'm also a bit worried, but we can work together.
{{char}}: Yes, I believe we can overcome all difficulties!

New Chat

Purpose: Sets up the basic dialogue pattern for new conversation scenarios.

Example:

{{char}}: Today seems like a good day! Do you have any plans?
{{user}}: I might go for a walk to relax.
{{char}}: A walk sounds nice! Can we go together?

Group Chat Progression

Purpose: Indicates how to advance the plot or interaction in multi-person conversations.

Example:

{{char}} mentions in the group chat: "Is everyone ready to face the challenge? This mission won't be easy, but we can overcome it together!"

Continue Progression

Purpose: Used to drive plot development in conversations, ensuring the story or interaction doesn't stagnate.

Example:

{{char}} continues: "Since everyone is ready, let's begin. Our goal is to uncover the library's secrets."

AI Assistance

Purpose: Provides the AI with style and methods for answering {{user}}'s questions.

Example:

If {{user}} asks about {{char}}'s background, the AI will respond: "I am an explorer, naturally curious about the unknown, and I've been searching for lost treasures."

3. Other Prompt Descriptions

Beyond presets, there are a series of detailed prompts that help you adjust character behavior in specific situations or optimize dialogue output.

Main Prompt

Purpose: This is the overall guidance for the character, usually positioned at the beginning of the dialogue, used to set the character's behavioral tone and conversation direction.

Example:

Your task is to deeply roleplay the {{char}} character and embark on adventures with {{user}}. You need to always maintain {{char}}'s personality consistency and actively advance plot development, creating a tense and exciting atmosphere.

Word Info (Before)

Purpose: Provides additional background information or vocabulary requirements before the dialogue, helping AI understand how to use specific vocabulary or expressions.

Example:

In the dialogue, {{char}} must use ancient magical terminology and cannot use modern vocabulary, such as "witchcraft" instead of "magic."

Persona Description

Purpose: Describes the character's personality and behavioral characteristics, helping AI understand the character's inner motivations.

Example:

{{char}} is a brave and determined warrior who is full of justice and always stands on the side of the weak. She has a strong sense of responsibility but can also be somewhat stubborn.

Char Description

Purpose: Describes the character's appearance, clothing, and other physical characteristics.

Example:

{{char}} is tall with fair skin, slightly messy black short hair, wearing a set of battle armor, with determined and sharp eyes.

Char Personality

Purpose: Provides in-depth description of the character's personality traits and how they treat others.

Example:

{{char}}'s personality is straightforward and decisive, occasionally appearing somewhat impatient, with high standards for herself.

Scenario

Purpose: Sets the current situation where the character is located, helping AI understand the environmental background and character's performance.

Example:

{{char}} is exploring an abandoned ancient castle, surrounded by broken stone pillars and dark corridors, with a mysterious and tense atmosphere.

Enhance Definitions

Purpose: Reinforces specific definitions of character behavior, allowing AI to more precisely portray the character in specific scenarios.

Example:

When {{char}} faces danger, she should display calm and decisive reactions, rather than panic or impulsiveness.

Auxiliary Prompt

Purpose: Auxiliary prompts that provide suggestions for dialogue style or behavioral details, helping AI better adapt to conversation scenarios.

Example:

When conversing with {{user}}, {{char}} should maintain a friendly and slightly playful tone, occasionally using humorous language to lighten the atmosphere.

World Info (After)

Purpose: Helps AI understand changes in the world or character after dialogue progression, driving continued plot development.

Example:

After the events unfold, {{char}}'s mindset begins to change. She becomes more doubtful of her previous decisions, and the atmosphere becomes more oppressive.

Chat Examples

Purpose: Provides a set of dialogue examples for interacting with the character, helping AI understand how to communicate effectively with {{user}}.

Example:

{{char}}: Today's mission is complete, we successfully found the treasure!
{{user}}: I can't believe we actually did it!
{{char}}: Yes, teamwork made it all possible!

Chat History

Purpose: Instructs AI to reference past dialogue content to maintain conversation coherence and consistency.

Example:

Based on previous conversations, {{char}} knows that {{user}} has recently been dealing with a major decision and might need more support and encouragement.

Post-History Instructions

Purpose: Provides format requirements for output content, controlling the final dialogue length and layout style.

Example:

Output content should be concise yet deep, with an ideal length controlled between 500-800 words, ensuring appropriate spacing between paragraphs and avoiding excessive density.

Themes

You can customize the overall visual style and interface elements of the chat through the sidebar.

Lore Book

**World Book** is a **"background encyclopedia" and "story guide"** written for the story world you co-create with AI characters. It does not appear directly in dialogues but works behind the scenes, ensuring the AI remembers all the world rules, key information, and plotlines you set, keeping the entire story consistent, logical, and immersive.