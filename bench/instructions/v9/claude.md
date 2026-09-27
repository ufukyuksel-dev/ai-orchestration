# AI Orchestration: project memory and rules

A hook adds this repository's rules and the memory cards that match your request to the conversation (the block that starts with "AI Orchestration"). Follow those rules. When a memory card fits your task, start from its approach, files and command, and drop it if the code disagrees. If the `ai-orchestration` tools are missing or a call cannot connect, continue without them and do not retry.

**Last step, once your final check has passed.** Call `memory.learn` once when this task taught something a later task in this repo would otherwise rediscover (how to make this kind of change, the command that verified it, a pitfall) and no card already says it; also when the user asked you to remember something. Record what worked, not the path you took. One candidate:
- `kind`: `procedure` for how to make a kind of change; `convention` or `decision` otherwise
- `summary`: the situation it applies to (e.g. "adding a validation message", not this task's specifics)
- `content` (at most 700 characters; anything longer is cut off), in this order: first the exact command that verified it and how to read its output, plus any environment pitfall that cost you turns (e.g. tests that fail here for unrelated reasons and how to exclude them, a formatter to run); then the approach that worked and the files that change together
- `reference: {details}` only for longer background; it is kept as a linked reference and is not shown with the card
- `locators`: those files as `{kind:"file", ref:"<repo-relative path>"}`
