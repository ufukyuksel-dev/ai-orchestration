# `ai_orch` command reference

`ai_orch` is the terminal interface to AI Orchestration; the native
`ai-orchestration` MCP tools reach the same server. Its tool table covers every
tool the server registers.

Run every command directly from the intended repository, or target another
verified repository with `--repo-root`. Project-scoped commands resolve the
canonical Git root and attach the resolved `projectKey` themselves.

There is no `ai_orch call <tool>`, no `--endpoint` and no `--project-key`.

## Commands

### Project and diagnosis

| Command | Purpose | Boundary |
|---|---|---|
| `project resolve` | Show the bound canonical root and projectKey | Does not scan |
| `scan run\|start [--file <f>]` | Build or refresh the code map | Use when it is missing or out of date; options: force, includePaths, providerOverride… |
| `scan status\|result\|diagnostics\|cancel --scan-run-id <uuid>` | Follow a scan | |
| `capabilities` | List commands and availability | Read-only |
| `doctor` | Launcher, binding, contract and service state | Read-only |

### Instructions and rules

| Command | Purpose | Boundary |
|---|---|---|
| `rule instructions --scope effective` | Startup load after the user accepts the `ask_user` choice | Fails closed on an incomplete response after opt-in |
| `rule instructions --scope global_strict\|project` | One scope only | Still goes through the verified project binding |
| `rule instructions --scope module --module-path <dir>` | Module policy text | Repeatable, max 32 repository-relative directories |
| `rule draft --file <f> [--global]` | Immutable typed draft | `--global` omits projectKey deliberately |
| `rule preview --draft-id <uuid> [--global]` | The exact confirmation card | Must match the draft's real scope |
| `rule promote --approval-file <f> \| <flags> [--global]` | Activate an exactly previewed draft | Needs the server's hashes and real human approval |

### Memory

| Command | Purpose | Boundary |
|---|---|---|
| `memory search --query … --top-k 3 [--excerpt-max-chars 128..1024] [--provider-override …]` | Compact relevant previews | Default top-k 3, max 5 |
| `memory get --memory-id <uuid>` | One full record | Project ownership is re-verified |
| `memory write --file <f>` | Write durable memory | `scope` project (default) or global; `memoryType: "rule"` goes through `rule draft` |
| `memory update --file <f>` | Repair an existing record (locators, text, tags) | Requires an audit reason |
| `memory pending --limit --offset` | Project pending cards | Not the admin list |
| `memory confirm --file <f>` | Apply the user's decision to a pending record | Real decision text |
| `memory archive --memory-id --reason` | Recoverable archive | Permanent delete (`memory.delete mode=hard_delete`) needs the user's confirmation |
| `memory relate --file <f>` | Explicit relation | Does not activate a pending memory |

### Code and references

| Command | Purpose | Boundary |
|---|---|---|
| `codebase baseline search --query … --top-k` | Baseline capsules | Uses the current code map |
| `codebase symbol get --ref … [--edge-limit]` | One symbol with file/line bounds | Coordinates may be stale |
| `codebase symbol neighbors --symbol-id … [--depth 1..3]` | Bounded neighborhood | Server parameter is `symbolId`; `--ref` is an alias |
| `codebase impact analyze --ref …` | Reverse dependencies | Keep `resolvedImpact` vs `possibleImpact` distinct |
| `codebase diagnose --file <f>` | Ranked suspects | Real error text; redaction applies |
| `reference list [--dir] [--cursor] [--limit]` | List the shared catalog | Local-user; empty dir = shared root |
| `reference read --path … [--offset-bytes] [--max-bytes]` | Bounded redacted text | Offsets address redacted output |
| `reference mkdir --dir …` | Create a shared directory | Shared-root relative |
| `reference write --file <f>` | Create/update shared text | Expected-hash contract; max 256 KiB |

There is **no** `codebase read` command. `codebase.read` is a server *scope*
name, not a tool. When you need source, read the file/line region that
`symbol get` returned.

### Jobs and personal facts (local-user)

| Command | Purpose | Boundary |
|---|---|---|
| `last-job save --file <f>` | Replace the single checkpoint | Cross-project singleton; max 256 KiB |
| `last-job get` | Read the checkpoint | For a resume |
| `job save --file <f>` | New job, or upsert a known UUID | An unknown valid UUID creates a new job |
| `job search --query … --top-k 3` | Find a job from a clue | Similarity ≠ certainty |
| `job get --job-id <uuid>` | One full handoff | `found: false` is a real gap |
| `personal-memory save --file <f>` | One personal fact | Returns the redacted text |
| `personal-memory search --query … --top-k` | Recall personal facts | |

Tools without a command here (`memory.approve`, `memory.review.*`,
`memory.delete mode=hard_delete`, `session.bootstrap`, `extras`) are reachable
through the native MCP tools; a hard delete still needs the user's confirmation.

## Request files

Mutations, long text and approval evidence use a command-specific `--file` JSON
request written with your normal file tool, inside the repository or the
`ai_orch` staging directory. No here-docs, pipes, `eval` or shell substitution.

Rejected before any network call: unknown fields, duplicate keys, trailing
tokens, non-objects, `NaN`/`Infinity`, invalid UTF-8, oversized files, paths
outside an allowed root, and the bridge-owned fields `projectKey`, `rootPath`,
`tool`, `endpoint`, `command`, `Authorization` (`scope` only where a command
documents it).

Never put a token or secret in an argument, a request file or a log.

## Output

New commands:

```json
{"schemaVersion":"2","success":true,"command":"…","context":{"scopeKind":"…"},"result":{},"warnings":[]}
```

`scopeKind` is `project`, `global-rule`, `local-user` or `diagnostic`. The three
original commands (`rule draft|preview|promote` in their project form) keep their original `{success, command, projectKey, result}`
envelope; their decoded warnings go to stderr.

Success JSON goes to stdout; failure JSON and a non-zero exit go to stderr.

Check in this order: process exit → JSON parse → JSON-RPC error → MCP isError →
the operation's typed domain failure → required receipt/status fields →
project/scope match. Do not blind-search the payload for the word `success`.

## Limits enforced before the network

| Field | Limit |
|---|---|
| memory `summary` / `content` | 160 / 700 **characters** |
| rule `statement` / `rationale` | 16,384 / 4,096 UTF-8 bytes |
| draft / plan JSON | 1 MiB |
| module paths | 32 |
| reference content | 256 KiB; path 1024 bytes / 32 segments |
| LAST_JOB content | 256 KiB |
| named job title / summary / content | 512 B / 8 KiB / 256 KiB |
| `agentConfidence` / `confidence` | finite, 0.0–1.0 |

Character limits are not byte limits: test them with Turkish characters.

## Legacy

The `ai-orch-memory` launcher keeps its original commands (`catalog`, `resolve`,
`search`, `get`, `write`, `pending`, `confirm`, `update`, `archive`) and its
original output shape for existing consumers. New Copilot
documentation teaches the canonical `ai_orch` form only.
