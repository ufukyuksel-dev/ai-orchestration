# Rule authoring: full contract

> On-demand contract. Load only when this workflow is needed. Moved verbatim from the former `skills/session-instructions.md`; where it conflicts with the lean session contract (`session.bootstrap`, offline rule, `memory.learn`), the session contract wins.

## Turn a correction into a rule

When the user asks for a rule, save it without asking again: the request itself is the approval. Use the user's wording as the statement. Reach: global only when the user says it applies everywhere or to all projects, a module when they name a directory, otherwise this project. When a correction merely suggests a reusable preference and the user did not ask for a rule, offer it once instead of saving it.

If `rules.draft`, `rules.preview` and `rules.promote` are not in your tool list, call them through `extras(op="rules.draft", args={...})` with the same arguments. Use existing `rules.draft` with a typed candidate, then `rules.preview`. Preserve every returned hash and workflowContractVersion and pass them to `rules.promote` right away, with the user's request as `humanRawText`, a reference to that turn as `humanTurnRef`, `aiInterpretedAsApproval=true` and your confidence. Afterwards tell the user the saved statement and its reach in one line. Never invent a request the user did not make, and do not treat permission to implement this system as a request for a particular rule.

Candidate examples (replace statements/paths with the text the user actually wants):

- GLOBAL_STRICT: omit projectKey intentionally; `{"statement":"<exact text>","enforcement":"instruction","appliesAll":true}`.
- PROJECT: explicit resolved projectKey; same candidate shape.
- MODULE: explicit projectKey; `{"statement":"<exact text>","enforcement":"instruction","appliesAll":false,"targets":[{"kind":"path_glob","targetKey":"core/**"}]}`. List additional literal directory/** targets for a module spanning several paths; never infer their meaning from names alone.

Instructions cannot have detectors, checks or selector groups. Statement limit is 16384 UTF-8 bytes and optional rationale limit is 4096 bytes; oversized input is rejected, not truncated. The read result is at most 100 rules/index entries and 65536 serialized UTF-8 bytes. Existing module glob limits still apply. You can request only global rules (`scope="global_strict"`), only project-wide rules (`scope="project"`), or selected module rules (`scope="module"`).

Keep normal project memory/code workflows for task-specific knowledge after startup policies are loaded. This does not authorize unrelated compatibility work, security features, abstractions, or automatic fixes beyond the user's requested scope.

These instruction policies are separate from class/selector-based plan rules such as `**Controller` matching. They are fetched without compiling a plan or providing changed files, and INSTRUCTION records are excluded from plan compiler candidates.
