# AI Orchestration: project memory and rules

Tools come from the `ai-orchestration` MCP server. If they are missing or a call cannot connect, continue without them and do not retry.

1. **Start.** First action: `session.bootstrap(rootPath=<cwd>, task=<the user's request in one line>)`.
   - Follow the returned `rules`. If `askUser` is set, ask the user once, then call again with `loadRules` (and `remember=true` for always/never). If you cannot ask (a subagent or a non-interactive run), continue without the rules and do not call it again.
   - `pathRules` apply when you edit those paths (a directory covers everything below it). If only `pathIndex` is returned, call `rules.instructions(projectKey, scope="module", modulePaths=[<path>])` before editing a listed path.
   - `memory` cards are what an earlier task in this repo learned. Use a card whose summary fits your task: start from its approach, files and command instead of exploring, then check them against the code. Ignore cards that do not fit.
   - Call it again only after context compaction (pass `knownEtag` if you still hold the rules) or in another repository.
2. **During the task.** `memory.search(query, projectKey)` only when a missing project decision or convention could change the work.
3. **Last step, once your final check has passed.** Call `memory.learn` once when this task taught stable, reusable knowledge about this repository (how something behaves, where to change it, how to verify it, a pitfall) that no card already says; also when the user asked you to remember something. Save knowledge, never a task log or a list of the files you changed. If your check passed only after more than one attempt (a JDK or environment setting, a flag, a formatter, a test that fails for reasons unrelated to your change), one candidate must be that `procedure`: the exact command that finally worked, with its settings, and what failed before it; it saves every later task those attempts. Up to three candidates, each with:
   - `kind`: `behavior`, `navigation`, `procedure`, `field_mapping`, `debug_finding`, `change_point` or `test_address`
   - `summary` (at most 160 characters): the situation it applies to, not this task's specifics
   - `content` (at most 700 characters; longer is cut off): the fact itself; for a procedure, first the exact command that verifies it and any pitfall that cost you turns, then the approach
   - `locators` (at least one, at most eight per call): the code it is about, as precisely as you know it. Method: `{kind:"symbol", ref:"pkg.Class#method", signature:"method(Type)", path:"<repo path>", role:"primary_change_point"}`; class: `{kind:"symbol", ref:"pkg.Class", path:"<repo path>"}`; file: `{kind:"file", ref:"<repo path>"}`; package: `{kind:"directory", ref:"<repo dir>"}`. Name the existing classes and methods the knowledge relies on, not only the files you added; never downgrade a known method to its file. Roles: `primary_change_point`, `supporting`, `validation`, `configuration`, `entry_point`.
   - optional `appliesWhen`, `limitations`, `reusableFor` (up to six short items each); `reference: {details}` only for long-form evidence that does not fit in `content`

Details, only when needed, in `skills/contracts/` next to this file: `startup-details.md`, `memory.md`, `references.md`, `last-job.md`, `saved-jobs.md`, `personal-memory.md`, `rule-authoring.md`, `memory-delete.md`.
GitHub Copilot does not use these tools: it uses the `ai_orch` terminal command with its `ask_user` gates, see `skills/copilot/session-instructions.md`.
