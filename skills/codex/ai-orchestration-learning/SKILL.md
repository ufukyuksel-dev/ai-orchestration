---
name: ai-orchestration-learning
description: Use at the end of a task in Codex when the work produced verified, reusable project knowledge worth saving with the MCP memory.learn tool.
---

# Project learning — Codex

Call `memory.learn(rootPath, candidates)` **at most once**, at task end, only when the task established knowledge that a future session would otherwise have to rediscover. No durable finding means no call. If `ai-orchestration` is unavailable or a call failed with a connection error, do not retry; just finish the task.

## Worth saving
Verified, project-specific, reusable, non-obvious:
- a decision and why (`decision`), a team convention (`convention`), a multi-step procedure (`procedure`)
- behaviour that is surprising from the code alone (`behavior`), a known failure cause (`debug_finding`)
- where a change must be made (`change_point`), how to test something (`test_address`), a data/field mapping (`field_mapping`)

## Never save
Progress notes, changed-file lists, test-pass reports, facts obvious from one read of the code, guesses, secrets, rules (rules use `rules.draft`), or anything `memory.search` already returned.

## Candidate shape
`{kind, summary (≤160 chars), content (≤700 chars, one fact), locators, appliesWhen?, limitations?, reusableFor?}`, 1–3 per call, at most 8 distinct locators in total.

- Code knowledge (`behavior`, `navigation`, `field_mapping`, `debug_finding`, `change_point`, `test_address`) needs at least one symbol or file locator copied exactly from the source you read:
  - class: `{"kind":"symbol","ref":"com.example.PaymentService","path":"src/main/java/com/example/PaymentService.java"}`
  - method: `{"kind":"symbol","ref":"com.example.PaymentService#approve","signature":"approve(java.lang.String)","path":"src/main/java/com/example/PaymentService.java","role":"primary_change_point"}`
  - file: `{"kind":"file","ref":"src/main/resources/application.yml","role":"configuration"}`
  - A directory locator is navigation only; it cannot be the sole locator.
- `decision`, `convention` and `procedure` may use `"locators": []`.
- Never invent scanner or database IDs.

## Examples
Good:
1. `{"kind":"debug_finding","summary":"Refund approval fails when the ledger lock is taken twice","content":"PaymentService.approve acquires the ledger lock and RefundJob.run acquires it again; running both in one transaction deadlocks. Refunds must go through RefundJob only.","locators":[{"kind":"symbol","ref":"com.example.PaymentService#approve","path":"src/main/java/com/example/PaymentService.java"}]}`
2. `{"kind":"decision","summary":"Invoices keep two decimal places because of the tax export","content":"Invoice amounts are rounded HALF_EVEN to two decimals because the national tax export rejects more precision (finance decision FIN-12).","locators":[]}`
3. `{"kind":"procedure","summary":"Run integration tests offline","content":"Use ./mvnw -q -o verify -Pit; the online build cannot reach the artifact proxy from CI runners.","locators":[]}`

Bad (do not send):
1. "Fixed the failing test by changing >= to ==" — a progress note.
2. "PaymentService is a Spring service" — obvious from the code.
3. "The timeout is probably configurable" — a guess, not verified.

The response reports `created`, `reused` (an existing learned record matched), `duplicates` (the gate refused an already-covered fact; nothing saved) and `rejected`. Reused and duplicate are success, not errors — do not resend. Report only what the tool returned.
