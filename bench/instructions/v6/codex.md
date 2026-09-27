# AI Orchestration: project memory and rules

Tools come from the `ai-orchestration` MCP server. If they are missing or a call cannot connect, continue without them and do not retry.

1. **Start.** First action: `session.bootstrap(rootPath=<cwd>, task=<the user's request in one line>)`.
   - Follow the returned `rules`. If `askUser` is set, ask the user once, then call again with `loadRules` (and `remember=true` for always/never). If you cannot ask (a subagent or a non-interactive run), continue without the rules and do not call it again.
   - `pathRules` apply when you edit those paths (a directory covers everything below it). If only `pathIndex` is returned, call `rules.instructions(projectKey, scope="module", modulePaths=[<path>])` before editing a listed path.
   - `memory` cards are what an earlier task in this repo learned. Use a card whose summary fits your task: start from its approach, files and command instead of exploring, then check them against the code. Ignore cards that do not fit.
   - Call it again only after context compaction (pass `knownEtag` if you still hold the rules) or in another repository.
2. **During the task.** `memory.search(query, projectKey)` only when a missing project decision or convention could change the work.
3. **Last step, once your final check has passed.** Call `memory.learn` once when this task taught something a later task in this repo would otherwise rediscover (how to make this kind of change, the command that verified it, a pitfall) and no card from step 1 already says it; also when the user asked you to remember something. Record what worked, not the path you took. One candidate:
   - `kind`: `procedure` for how to make a kind of change; `convention` or `decision` otherwise
   - `summary`: the situation it applies to (e.g. "adding a validation message", not this task's specifics)
   - `content` (short): the approach that worked and the files that change together, then the exact command that verified it
   - `reference: {details}` for anything longer (pitfalls, background); it is kept as a linked reference, not in the card
   - `locators`: those files as `{kind:"file", ref:"<repo-relative path>"}`

Details, only when needed, in `skills/contracts/` next to this file: `startup-details.md`, `memory.md`, `references.md`, `last-job.md`, `saved-jobs.md`, `personal-memory.md`, `rule-authoring.md`, `memory-delete.md`.
GitHub Copilot does not use these tools: it uses the `ai_orch` terminal command with its `ask_user` gates, see `skills/copilot/session-instructions.md`.
