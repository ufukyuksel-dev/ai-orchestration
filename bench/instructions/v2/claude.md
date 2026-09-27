# AI Orchestration: project memory and rules

Tools come from the `ai-orchestration` MCP server. If they are missing or a call cannot connect, continue without them and do not retry.

1. **Start.** First action: `session.bootstrap(rootPath=<cwd>, task=<the user's request in one line>)`.
   - Follow the returned `rules`. If `askUser` is set, ask the user once, then call again with `loadRules` (and `remember=true` for always/never).
   - `memory` cards are what an earlier task in this repo already learned: use their files, commands and pitfalls instead of re-exploring, then check them against the code.
   - Call it again only after context compaction (pass `knownEtag` if you still hold the rules) or in another repository.
2. **During the task.** `memory.search(query, projectKey)` only when a missing project decision or convention could change the work.
3. **End.** If you learned something a later task here would otherwise rediscover (files that change together, the exact build/test command, a non-obvious convention or failure), call `memory.learn` once with 1–3 candidates:
   `kind` `procedure` or `navigation`; `summary` = when it applies; `content` = what to change, where, how to verify (exact command), the pitfall; `locators` = the key files as `{kind:"file", ref:"<repo-relative path>"}`.
   Skip it for one-off facts.

Jobs, personal memory, references and rule authoring: see `skills/contracts/` in the AI Orchestration repository.
