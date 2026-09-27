# How AI Orchestration works

AI Orchestration is a small local server. Your coding agents (Claude Code, Codex, GitHub Copilot) talk to it; you
talk to it through a panel in the browser. The server makes no outside calls; the rules and cards it hands to an agent
become part of that agent's context and go to the agent's model provider like any file the agent reads.

```text
 Claude Code ─┐   MCP (HTTP, 127.0.0.1)            ┌─ Postgres   rules, memories, code map
 Codex ───────┼──►  AI Orchestration server  ──────┼─ Qdrant     vectors for memory and code search
 Copilot ─────┘   (ai_orch terminal command)       └─ Ollama     bge-m3 embeddings (no chat model needed)
                          ▲
                   panel http://127.0.0.1:18080/
```

## Four things you work with

| | What it is | Where it comes from |
|---|---|---|
| **Memory** | Short cards about *this* repository: which files change together, the exact command that verifies a change, the pitfall. | Written by the agent at the end of a task that taught something reusable, or by you in the panel. |
| **Rules** | Approved instructions: global, per project, or attached to one directory, file or method. | Written by you in the panel; every rule goes through a preview and a typed approval. |
| **Jobs** | A handoff for the next session (`last job`) and named saved jobs. | Saved by the agent when you ask. |
| **Panel** | Memories, rules, pending proposals, references, jobs and the layered code graph. | `http://127.0.0.1:18080/` |

## One session, step by step

1. **Start.** The session receives, in one answer:
   - the approved global and project rules;
   - the rules attached to paths (inline when they are short, otherwise a list of paths to fetch before editing them);
   - up to three memory cards that match the task by meaning (bge-m3, works across languages).

   In Claude Code a prompt hook puts this into the context together with your request, so the agent spends no turn
   on it. Codex calls `session.bootstrap(rootPath, task)` once; Copilot runs `ai_orch` in the terminal.
2. **Work.** The agent starts from the cards' files and commands instead of exploring. It searches memory only when a
   missing project decision could change the work.
3. **Learn.** Before its final answer, if the task taught something reusable (a non-obvious procedure, the test
   command, a pitfall) or you asked it to remember something, the agent makes one `memory.learn` call with up to
   three cards, each pointing at the code it is about (a method, a class, a file or a package). The server does the
   rest: it resolves those pointers against the code map, drops exact duplicates and keeps near duplicates with
   different content. In Claude Code a stop hook asks for this step once when the agent changed files and is about
   to finish without it.
4. **Next time.** A new session on the same repository gets that card at step 1.

The first session also asks you once whether rules should load automatically (always / never); the answer is stored
and never asked again.

## The code map

"Add project" in the panel indexes a repository structurally (files, packages, classes, methods, calls). No language
model is used and the server sends no code anywhere. The map powers the layered graph in the panel and lets memory cards and
rules point at real code. When code changes and a card's files no longer match, the card is marked stale.

## Why it is cheap for the agent

Every tool schema and instruction line is paid for in every turn of an agent session. AI Orchestration keeps the
agent-facing surface small: a short instruction block, one startup answer with everything the session needs (in
Claude Code it arrives with the request and costs no turn), three tools in Claude Code (`memory.learn`,
`rules.instructions` and `extras`, which reaches saved jobs, personal memory and references when you ask for them),
compact cards (≤ 700 characters each) and no extra calls for small rule sets.
