---
name: ai-orchestration-learning
description: Use when Copilot has found durable project knowledge that should be saved with one final typed ai_orch terminal command.
---

# Project learning — Copilot terminal flow

Copilot uses only direct `ai_orch` terminal commands for AI Orchestration. It has no native MCP or
lifecycle-hook learning path. Never rely on `.github/hooks`, a final-response sidecar, raw HTTP or a
model-visible persistence tool.

If the task produced no durable reusable finding, do not write anything. Otherwise create one bounded
request containing zero to three candidates and make one final learning command:

```bash
ai_orch memory learn --file memory-learn.json --consume
```

Create `memory-learn.json` inside the current repository and pass that repository-local path. Do not
place the request in Copilot's session workspace or another temporary directory.
After an accepted `--consume` request, do not run a separate delete or cleanup command: the bridge
has already removed the request file. If submission fails, the file is intentionally retained for
inspection or retry.

The request must satisfy `contracts/agent-learning/learning-candidate.schema.json`:

```json
{"learningCandidates":[{
  "kind":"behavior",
  "summary":"Short durable statement",
  "content":"Reusable explanation",
  "locators":[
    {"kind":"symbol","ref":"com.example.PaymentService#approve","signature":"approve(java.lang.String)","path":"src/main/java/com/example/PaymentService.java","role":"primary_change_point"},
    {"kind":"file","ref":"src/main/resources/application.yml","role":"configuration"}
  ],
  "appliesWhen":["condition"],
  "limitations":["boundary"],
  "reusableFor":["future task"]
}]}
```

Use exact human-readable locators:

- class/interface: `package.Class` with its repository-relative `path`
- method/function: `package.Class#method`, optional exact `signature`, and its `path`
- file: repository-relative path in `ref`; never add `path` or `signature`
- module/package directory: repository-relative directory in `ref`; navigation only; never add
  `path` or `signature`

Do not scan, poll scanner state, or downgrade a known symbol to a file locator. Submit the exact
human-readable symbol plus its repository-relative source path. If the backend has not indexed that
symbol yet, it accepts the declared locator and queues its own structural scan and later binding.

Every candidate needs at least one symbol or file locator. Across the whole batch use at most eight
distinct locators. Never invent scanner/database IDs, line ranges, persistence IDs, project keys,
workspace bindings, runtime names or endpoint fields. The `ai_orch` bridge owns those values.

Use only these locator roles: `primary_change_point`, `supporting`, `validation`, `configuration`,
and `entry_point`. Keep `summary` at most 160 characters and `content` at most 700 characters.
`appliesWhen`, `limitations`, and `reusableFor` may each contain at most six unique, nonblank items;
each item is at most 160 characters.

Add `reference:{"details":"..."}` only for genuinely necessary long-form evidence. Do not invent or
submit `correctionRef`; the ordinary Copilot memory-search flow does not create a context-local `kN`
binding.

Persist only stable behavior, navigation, procedure, field mapping, debug findings, change points or
test addresses. Do not save task logs, changed-file summaries, test-pass reports, trivial facts,
secrets, hidden reasoning or rules. After an `ACCEPTED` receipt, make no further learning call.
