# Rule authoring: full contract

> On-demand contract. Load only when this workflow is needed. Moved verbatim from the former `skills/session-instructions.md`; where it conflicts with the lean session contract (`session.bootstrap`, offline rule, `memory.learn`), the session contract wins.

## Turn a correction into a rule

When a correction reveals a reusable preference, propose its exact text and scope. Do not persist guesses or turn every criticism into a global policy. The user chooses global/project/module reach and approves the full confirmation card before activation.

Use existing `rules.draft` with a typed candidate, then `rules.preview`. Explain the human-readable scope alongside the unchanged server confirmation card. Preserve every returned hash and workflowContractVersion. Only after an explicit human-authored approval of that card use `rules.promote` with the actual approval text/turn reference. Do not fabricate approval evidence or treat permission to implement this system as approval of a particular rule.

Candidate examples (replace statements/paths with the text the user actually wants):

- GLOBAL_STRICT: omit projectKey intentionally; `{"statement":"<exact text>","enforcement":"instruction","appliesAll":true}`.
- PROJECT: explicit resolved projectKey; same candidate shape.
- MODULE: explicit projectKey; `{"statement":"<exact text>","enforcement":"instruction","appliesAll":false,"targets":[{"kind":"path_glob","targetKey":"core/**"}]}`. List additional literal directory/** targets for a module spanning several paths; never infer their meaning from names alone.

Instructions cannot have detectors, checks or selector groups. Statement limit is 16384 UTF-8 bytes and optional rationale limit is 4096 bytes; oversized input is rejected, not truncated. The read result is at most 100 rules/index entries and 65536 serialized UTF-8 bytes. Existing module glob limits still apply. You can request only global rules (`scope="global_strict"`), only project-wide rules (`scope="project"`), or selected module rules (`scope="module"`).

Keep normal project memory/code workflows for task-specific knowledge after startup policies are loaded. This does not authorize unrelated compatibility work, security features, abstractions, or automatic fixes beyond the user's requested scope.

These instruction policies are separate from class/selector-based plan rules such as `**Controller` matching. They are fetched without compiling a plan or providing changed files, and INSTRUCTION records are excluded from plan compiler candidates.
