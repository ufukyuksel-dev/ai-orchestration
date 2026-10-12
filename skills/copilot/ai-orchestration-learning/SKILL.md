---
name: ai-orchestration-learning
description: Only when the user explicitly asks to save project knowledge with a command. Normally knowledge is saved by the hidden ai-orch-learn comment at the end of the final answer, with no tool call and no need to open this skill.
---

# Project learning — Copilot

The usual way needs no tool call. When a task taught something reusable about this repository, end the
final answer with one HTML comment, as each prompt's "AI Orchestration learning" line describes:
`<!-- ai-orch-learn {"learningCandidates":[...]} -->` (the request shape below). Copilot's terminal does
not display it; the installed stop hook reads it from the session transcript, checks it exactly as
`ai_orch memory learn` does and submits it once per request. It is visible only where raw output is
printed (`copilot -p`, `renderMarkdown: false`). Knowledge about this machine or sandbox (paths outside
the repository) is not saved. Only when you fixed a failing check yourself and wrote no comment does the
stop hook ask once for the comment alone: reply with it, or with `none`.
When the native `ai-orchestration` MCP tools are loaded, `memory.learn(rootPath, candidates)` reaches the same
server as the command below.

To save at another moment, or when the user asks, use the one terminal command:

```bash
ai_orch memory learn --file memory-learn.json --consume
```

Copilot has no native MCP learning path: never write through `.github/hooks`, raw HTTP or a
model-visible persistence tool. If the task produced
no durable reusable finding, write nothing.

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

Do not downgrade a known symbol to a file locator. Submit the exact human-readable symbol plus its
repository-relative source path. If the backend has not indexed that symbol yet, it accepts the
declared locator and queues its own structural scan and later binding.

Every candidate needs at least one symbol or file locator. Across the whole batch use at most eight
distinct locators. Never invent scanner/database IDs, line ranges, persistence IDs, project keys,
workspace bindings, runtime names or endpoint fields. The `ai_orch` bridge owns those values.

Use only these locator roles: `primary_change_point`, `supporting`, `validation`, `configuration`,
and `entry_point`. Keep `summary` at most 160 characters and `content` at most 700 characters; a
longer text is shortened by the bridge and reported in `warnings`, so there is no need to resubmit.
Write the request file with your file-creation tool, not a shell heredoc. The same locator may
serve several candidates.
`appliesWhen`, `limitations`, and `reusableFor` may each contain at most six unique, nonblank items;
each item is at most 160 characters.

Add `reference:{"details":"..."}` only for genuinely necessary long-form evidence. Do not invent or
submit `correctionRef`; the ordinary Copilot memory-search flow does not create a context-local `kN`
binding.

Persist only stable behavior, navigation, procedure, field mapping, debug findings, change points or
test addresses. Do not save task logs, changed-file summaries, test-pass reports, trivial facts,
secrets, hidden reasoning or rules.

Read the receipt `items`: for an item with `outcome` `rejected` or with `unresolvedLocators`, fix that
locator (exact `pkg.Class#method`, `signature` and `path`; check it with
`ai_orch codebase symbol get --ref`) and send that one candidate once more; otherwise make no further
learning call. A stale card means the code it points to is gone: repair its locators with
`ai_orch memory update` or archive it, in the same task.
