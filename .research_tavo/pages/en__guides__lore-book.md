URL: https://docs.tavoai.dev/en/guides/lore-book/
STATUS: 200

Guide

Copy Page

📖 Lore Book

1. What is a World Book?

World Book is a "background encyclopedia" and "story guide" written for the story world you co-create with AI characters. It does not appear directly in dialogues but works behind the scenes, ensuring the AI remembers all the world rules, key information, and plotlines you set, keeping the entire story consistent, logical, and immersive.

If "character presets" define "who the actors (characters) are," then the World Book defines "what the stage (world) is like" and "how the script (story) should be performed."

2. Core Problems Solved by the World Book

The World Book primarily helps you address three major challenges when co-creating stories with AI:

Problem 1: AI&#x27;s "Amnesia" — Maintaining [World Consistency]

Problem: You explicitly set "this world has no metal, all tools are made of crystal," but as the conversation progresses, the AI suddenly has a character pull out an "iron sword," instantly breaking the world&#x27;s logic.

World Book&#x27;s Role: Create an entry to inform the AI of the world&#x27;s fundamental rules.

◦ Entry Example:

Trigger Keywords: tools, weapons, manufacturing

Content: In this world, metal minerals do not exist. All tools, weapons, and machinery are carved from "drive crystals" with different attributes. The purity of the crystals determines the efficiency of the tools.

Effect: When conversations involve manufacturing or weapons, the AI will automatically reference this "world rule," ensuring no inconsistent "metal products" appear.

Problem 2: AI&#x27;s "Off-Topic Tendencies" — Focusing [Narrative Mainline]

Problem: You are in a tense "escape from pursuit," but the AI keeps steering the conversation toward street food and weather, dragging the plot and draining the tension.

World Book&#x27;s Role: Create an entry to remind the AI of the core task and atmosphere of the current scene, much like a director.

◦ Entry Example:

Trigger Keywords: pursuit, escape, guards

Content: The current core scenario is that {{char}} and {{user}} are being pursued by city guards, and the situation is critical. Dialogues and descriptions should focus on hiding traces, planning escape routes, and dealing with sudden crises, creating a tense and urgent pace.

Effect: When keywords like "pursuit" appear, the AI&#x27;s responses will closely follow the "escape" mainline, actively advancing the plot and maintaining the intended atmosphere.

Problem 3: Setting "Information Overload" — Achieving [Layered Unfolding]

Problem: You have a vast background like "the grudges among the three great knight orders of the empire," spanning thousands of words. If crammed entirely into character settings, the AI may struggle to digest it, and the character&#x27;s opening lines might turn into a history lesson.

World Book&#x27;s Role: Break down complex backgrounds into multiple "information fragments," allowing the AI to reveal them naturally at appropriate moments.

◦ Entry Breakdown Example:

Entry 1 (Keyword Blaze Knight Order): An elite force directly under the royal family, known for their red armor and strict discipline.

Entry 2 (Keyword Silent Oath): A non-aggression pact signed by the three great knight orders twenty years ago, though minor friction persists in secret.

Entry 3 (Keyword Northern Rebellion): The key event that caused the split between the Blaze and Silverwing Knight Orders, with the truth concealed by the royal family.

Effect: Early in the story, only the "Blaze Knight Order" introduction might be triggered. As the conversation deepens and the user asks, "Why is their relationship so strained?" the "Silent Oath" or "Northern Rebellion" entries are triggered. The AI can then gradually reveal deeper layers of history, allowing the story&#x27;s background to emerge naturally rather than being delivered in one overwhelming infusion.

3. Structure and Writing Guide for World Books

【Trigger Words】

Role: Define the "retrieval tags" for this card. When dialogue content matches these keywords, this encyclopedia card will be "recalled" and used by the AI.

Writing Tips:

◦ Cover core terms and their common synonyms or alternative names.

◦ Example: For a forbidden spell named "Whisper of the Void," set trigger words: void whisper, forbidden spell, ancient incantation.

【Entry Content】

Role: Write down the "objective facts" you need the AI to absolutely remember and use. This is the knowledge itself.

Writing Tips: Use declarative sentences to state indisputable facts, as if writing an objective textbook or archive.

Categorized Example Templates:

◦ Geography & Locations:

Trigger Words: Eternal Winter City, Northern Capital

Content: The northernmost capital on the continent, covered in snow year-round. Built within massive glacial fissures and powered by geothermal energy. The residents here are resilient and insular.

◦ Organizations & Factions:

Trigger Words: Alchemy Society, the Society

Content: A neutral academic organization with advanced knowledge of potions and compounds. Internally divided into three schools of research: "Life," "Transformation," and "Destruction," each fiercely competitive.

◦ Concepts & Laws:

Trigger Words: Mana Tide, tide period

Content: The concentration of mana in the world fluctuates cyclically, peaking once a month during "high tide." During high tide, spell power increases but becomes harder to control; during low tide, casting is more difficult but precision is higher.

◦ Key Items:

Trigger Words: Philosopher&#x27;s Stone, Sage&#x27;s Stone

Content: A legendary alchemical creation, a red diamond-shaped crystal. Its true power lies not in transmuting base metals but in "achieving limited perfect restoration." However, each use randomly strips the user of a "cognition" (e.g., an emotion or a memory).

◦ Character Relationships & Secrets:

Trigger Words: Leon&#x27;s Death, succession dispute

Content: The current king&#x27;s younger brother, Leon, died three years ago in a "hunting accident," though many suspect the queen was behind it to eliminate a succession obstacle. This incident is the root of the royal family&#x27;s internal division.

4. Tips and Techniques

Start with "Patching": There&#x27;s no need to build a perfect world from the start. During conversations, whenever the AI "forgets" or "misunderstands" something, immediately create a World Book entry for that point. Your World Book will naturally grow and refine as the story progresses.

Focus on "Unshakable Facts": Ask yourself, "What truths about my world are absolute laws that must never be altered by AI improvisation?" Write these laws into the World Book.

It&#x27;s a Living "Script": The World Book can be modified at any time. Early in the story, an entry might read, "Dragons are terrifying calamities." When the plot develops and the protagonist becomes a dragon rider, you can update the entry to, "Dragons are ancient and wise races that can form alliances with individuals of specific temperament." The World Book evolves with the story.

Presets

**Presets** refer to a set of **basic configurations and behavior patterns** established for characters, helping you quickly set up identity, personality, behavioral guidelines, etc., for characters, reducing repetitive setup work. Through presets, you can quickly create characters or scenarios that meet specific needs. These presets will determine how characters behave, their dialogue style, and how they interact with users.

Regex

In Tavo, regular expressions are used for automated text content processing. Specifically, they can help you: