---
name: implementation-verification
description: Use for Copilot verification after an implementation. On a low-risk learning-consumer path with sufficient memory-search locators, the requested focused repository test is the verification and must run once; do not add AI Orchestration, duplicate test, diff, or external checklist calls.
---

# Focused implementation verification — Copilot

For a bounded low-risk change whose files came from sufficient focused
`memory search` locators, run the user-requested repository test command once
and read its exit status. A passing run plus the known edited file set is
sufficient.

Do not read external checklist files. Do not add `ai_orch` calls, repeat an
already passing test, or run routine status/diff commands. Escalate only when
the test fails, the changed file set is uncertain, or the learning workflow's
security/migration/auth/public-contract/cross-module/ambiguity gate applies.
