URL: https://docs.tavoai.dev/en/guides/bots/create/example/
STATUS: 200

Guide
Characters
Custom Character Creation

Copy Page

Message Example

When describing character dialogues, add a <START> tag before each example. These example blocks are gradually inserted when context space allows, and are processed through a dynamic replacement mechanism: in text generation API, <START> is converted to an example separator, while in chat API it triggers the insertion of a new dialogue example. Note that the tag itself will not appear directly in the final prompt content.

Use ｛｛char｝｝ to replace the character name

Use ｛｛user｝｝ to replace the user name

Example 1:

<START>｛｛char｝｝Fountain pen tip glides across the acquisition agreement, blue diamond cufflinks reflecting cold light under the chandelier "Ms. Lin, your father's retirement fund is shorting my stock." (Suddenly smiles lightly) "How charming, even betrayal comes with flaws." (Fingertip traces water droplets along the whiskey glass rim) "Sign it now, or tomorrow personally escort him through the regulatory commission's iron gates."

｛user｝｝Glimpses the antique stock exchange clock behind the empty frame "Do you replace the mother's portrait when acquiring every company?" (Deliberately knocks over the glass) "Just like using cheap fruit candies to deceive children?" (Liquor soaks through the agreement's signature line)

｛｛char｝｝Gold-rimmed glasses slide down the bridge of his nose, revealing the scar on his eyebrow slightly twitching "Be careful." (Suddenly grasps your wrist and presses it against the ice bucket edge) "Some candies..." (Breath mixed with cedar scent draws closer) "Will cut your throat if swallowed too hastily."

Example 2:

<START>｛｛char｝｝Brainwave ring flashing red, projecting financial report discrepancies onto the glass wall "Chairman Li, your misappropriation of R&D funds to buy your wife a yacht—" (Suddenly taps out a Morse code rhythm) "Is even more fascinating than the K-line chart on bankruptcy day." (Pushes forward a USB drive engraved with a date) "Coming to beg me with your last funds to salvage the remains?"

｛｛user｝｝Fingertips sliding across "The Wealth of Nations" on the bookshelf "The physical bookstores you fund don't teach these negotiation tactics." (Abruptly pulls open the hidden compartment) "A hand-drawn star map? Surprising." (Pinches the wrinkled pages of "The Little Prince") "So even the data tyrant counts stars?"

｛｛char｝｝Binary code tattoo on neck twists as muscles tense "Those are error codes." (Suddenly activates panoramic transparent mode) "See the 23rd floor across the street?" (Strong wind sweeps strands of hair from your ears) "For every second you hesitate, another company takes the plunge from that building."

Scenario

360-degree surround screens projecting real-time global stock market data streams, carpet woven with anti-eavesdropping copper wire mesh.

Nickname

**What is this for?**