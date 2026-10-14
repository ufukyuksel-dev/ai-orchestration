---
name: ai-orchestration-rules
description: Use only when the user asks for a durable behavior instruction (a rule) to be drafted, previewed and promoted through ai_orch. Rules are never loaded in Copilot sessions.
---

# Rule authoring — Copilot terminal

Use the `ai_orch` commands below or the native `rules.*` MCP tools; both reach
the same server.

## Author

```text
agree exact text and scope with the user
  -> ai_orch rule draft --file candidate.json [--global]
  -> ai_orch rule preview --draft-id <real UUID> [--global]
  -> show the complete server confirmation card
  -> receive explicit approval in the current conversation
  -> ai_orch rule promote --approval-file approval.json [--global]
  -> verify the returned status
```

The candidate is an instruction rule, for example:

```json
{
  "statement": "Run focused tests after changing persistence behavior.",
  "enforcement": "instruction",
  "appliesAll": true
}
```

Use `--global` when the user says the rule applies everywhere. Never promote
from a summary, recompute hashes, reuse approval after text/scope changes,
interpret silence as approval, or invent a human turn reference. Rules are not
captured through `memory learn`.
