# AI Orchestration: project memory and rules

A hook adds this repository's rules and the memory cards that match your request to the conversation (the block that starts with "AI Orchestration"). Follow those rules. When a memory card fits your task, start from its approach, files and command, and drop it if the code disagrees. If a card gives the command that verifies a change, use it as your check: run it once, trust its exit code, and do not re-run it or add broader builds just to confirm. If the `ai-orchestration` tools are missing or a call cannot connect, continue without them and do not retry.

**Last step, once your final check has passed.** Call `memory.learn` once when this task taught stable, reusable knowledge about this repository (how something behaves, where to change it, how to verify it, a pitfall) that no card already says; also when the user asked you to remember something. Save knowledge, never a task log or a list of the files you changed. Up to three candidates, each with:
- `kind`: `behavior`, `navigation`, `procedure`, `field_mapping`, `debug_finding`, `change_point` or `test_address`
- `summary` (at most 160 characters): the situation it applies to, not this task's specifics
- `content` (at most 700 characters; longer is cut off): the fact itself; for a procedure, first the exact command that verifies it and any pitfall that cost you turns, then the approach
- `locators` (at least one, at most eight per call): the code it is about, as precisely as you know it. Method: `{kind:"symbol", ref:"pkg.Class#method", signature:"method(Type)", path:"<repo path>", role:"primary_change_point"}`; class: `{kind:"symbol", ref:"pkg.Class", path:"<repo path>"}`; file: `{kind:"file", ref:"<repo path>"}`; package: `{kind:"directory", ref:"<repo dir>"}`. Name the existing classes and methods the knowledge relies on, not only the files you added; never downgrade a known method to its file. Roles: `primary_change_point`, `supporting`, `validation`, `configuration`, `entry_point`.
- optional `appliesWhen`, `limitations`, `reusableFor` (up to six short items each); `reference: {details}` only for long-form evidence that does not fit in `content`

Saved jobs, personal memory and references: only when the user asks for them, through the `extras` tool; details in `skills/contracts/` next to this file.
