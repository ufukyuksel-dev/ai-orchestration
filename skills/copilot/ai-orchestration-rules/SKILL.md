---
name: ai-orchestration-rules
description: Use when Copilot must load approved rules or the user explicitly wants a durable behavior instruction drafted, previewed, and promoted through ai_orch.
---

# Rules and instructions — Copilot terminal

Every operation is one direct `ai_orch` command. There is no native MCP or
alternative transport.

## Load

```bash
ai_orch rule instructions --scope effective
ai_orch rule instructions --scope module --module-path "<listed directory>"
```

Read the complete statements. The effective response's module list is an index,
not loaded policy. Load only listed module paths. Fail closed on incomplete
responses and never substitute a memory search.

## Author

```text
agree exact text and scope with the user
  -> ai_orch rule draft --file candidate.json [--global]
  -> ai_orch rule preview --draft-id <real UUID> [--global]
  -> show the complete server confirmation card
  -> receive explicit approval in the current conversation
  -> ai_orch rule promote --approval-file approval.json [--global]
  -> verify returned status and reload applicable instructions
```

The candidate is an instruction rule, for example:

```json
{
  "statement": "Run focused tests after changing persistence behavior.",
  "enforcement": "instruction",
  "appliesAll": true
}
```

Use `--global` only when the user explicitly chose global scope. Never promote
from a summary, recompute hashes, reuse approval after text/scope changes,
interpret silence as approval, or invent a human turn reference. Rules are not
captured through `memory learn`.
