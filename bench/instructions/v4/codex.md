# AI Orchestration: project memory and rules

Tools come from the `ai-orchestration` MCP server. If they are missing or a call cannot connect, continue without them and do not retry.

1. **Start.** First action: `session.bootstrap(rootPath=<cwd>, task=<the user's request in one line>)`.
   - Follow the returned `rules`. If `askUser` is set, ask the user once, then call again with `loadRules` (and `remember=true` for always/never).
   - `memory` cards are what an earlier task in this repo learned: start from their files, commands and pitfalls instead of exploring, then check them against the code.
   - Call it again only after context compaction (pass `knownEtag` if you still hold the rules) or in another repository.
2. **During the task.** `memory.search(query, projectKey)` only when a missing project decision or convention could change the work.
3. **Last step, before your final answer.** Call `memory.learn` once when the user asked you to remember something, or when this task taught you reusable project knowledge (a non-obvious procedure, the build/test command, a pitfall), so the next similar task in this repo starts where you ended. Skip mechanical changes and anything a card from step 1 already said. One candidate:
   - `kind`: `procedure` for how to make a kind of change; `convention` or `decision` otherwise
   - `summary`: the situation it applies to (e.g. "adding a validation message", not this task's specifics)
   - `content`: which files change together, the exact command that verified it, and the pitfall you hit or avoided
   - `locators`: those files as `{kind:"file", ref:"<repo-relative path>"}`

Jobs, personal memory, references and rule authoring: see `skills/contracts/` in the AI Orchestration repository.
