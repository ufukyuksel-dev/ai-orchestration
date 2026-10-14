# GitHub Copilot CLI + AI Orchestration: what it saves, and how

Measured on GitHub Copilot CLI 1.0.86, model `claude-sonnet-5`, on the bench's own task suites (hidden-test scoring,
`bench/run.py`). Two arms of every task run side by side on the same machine:

- **A — plain Copilot.** An empty `COPILOT_HOME`: no hooks, skills, instructions or agent; the default tool set.
- **B — Copilot + AI Orchestration as installed.** `install_user_integration.py` (or `ai_orch project add <repo>
  --copilot`), started with the documented `copilot --agent ai-orchestration`. Memory starts empty at the beginning
  of each chain and is built only by what the earlier tasks of that chain learned.

Tokens are the sum of `input + output` of every model call, taken from Copilot's own usage database
(`session-store.db`, one row per call). Learning is included, as are failed runs. "Savings" = 1 − B / A.

## Results

| suite (language) | tasks compared | success A | success B | tokens A | tokens B | **savings** |
|---|---|---|---|---|---|---|
| py-holdout P2 (Python, Flask) | 6 (2 chains × 3) | 6/6 | 6/6 | 1,959,220 | 1,181,711 | **39.7 %** |
| go-holdout G1+G2 (Go, gin) | 9 matched (stopped early) | 7/9 | 9/9 | 10,979,835 | 7,467,033 | **32.0 %** |
| final F1 (Java, Spring) | 3 (1 chain) | 3/3 | 3/3 | 7,090,278 | 3,966,872 | **44.1 %** |
| **matched completed tasks, all suites** | 18 | 16/18 | **18/18** | 20,029,333 | 12,615,616 | **37.0 %** |

- Copilot had never run these tasks before the product was frozen. No setting was tuned on them. They are new for the
  Copilot integration but were used earlier with Claude Code and Codex, so they are not untouched holdouts.
- Plain Copilot failed two Go tasks; the product arm failed none. Counting tokens per successful task instead
  (failed runs' tokens included), the saving is **44.0 %**.
- **The validation is partial.** The protocol planned P2, G1, G2, F1 and F2 with 2 chains each (30 task runs per
  arm) plus an unrelated-task control (MBP1/MBP2). Done:
  - P2 in full;
  - Go until it was stopped on request: 9 tasks completed by both arms;
  - F1 as one chain;
  - F2 and the unrelated-task control were **not run**.
- **Only matched tasks count.** Every table compares only tasks that both arms completed. In Go the product arm has
  one more recorded run (g2t1, rep 2: 1,311,967 tokens) that plain Copilot did not complete before the stop, so it is
  left out of every number here.
- **Small sample.** Single tasks vary a lot between runs: plain Copilot's p1t1 took 300k in one run and 510k in
  another. No confidence interval was computed.

### Per task

**Python P2** (each row is one rep: the chain p2t1 → p2t2 → p2t3)

| rep | task | A tokens | B tokens | B / A |
|---|---|---|---|---|
| 1 | p2t1 (first task, no memory yet) | 240,159 | 262,529 | 1.09 |
| 1 | p2t2 | 440,984 | 253,935 | 0.58 |
| 1 | p2t3 | 400,374 | 111,350 | 0.28 |
| 2 | p2t1 (first task, no memory yet) | 251,488 | 184,493 | 0.73 |
| 2 | p2t2 | 307,110 | 162,623 | 0.53 |
| 2 | p2t3 | 319,105 | 206,781 | 0.65 |

First tasks: 0.909 of plain; later tasks of the chain: **0.501** of plain.

**Go G1/G2** (completed by both arms)

| rep | task | A tokens (ok) | B tokens (ok) |
|---|---|---|---|
| 1 | g1t1 | 1,071,714 ✅ | 714,533 ✅ |
| 1 | g1t2 | 1,215,021 ✅ | 875,384 ✅ |
| 1 | g1t3 | 918,017 ✅ | 890,790 ✅ |
| 1 | g2t1 | 1,410,754 ❌ | 1,189,534 ✅ |
| 1 | g2t2 | 1,791,581 ✅ | 947,315 ✅ |
| 1 | g2t3 | 1,457,856 ✅ | 649,322 ✅ |
| 2 | g1t1 | 844,172 ❌ | 938,886 ✅ |
| 2 | g1t2 | 1,086,239 ✅ | 609,711 ✅ |
| 2 | g1t3 | 1,184,481 ✅ | 651,558 ✅ |

**Java F1** (one chain)

| task | A tokens (calls) | B tokens (calls) | what memory had |
|---|---|---|---|
| f1t1 — delete a visit | 1,520,001 (32) | 1,517,091 (36) | nothing yet |
| f1t2 | 4,395,817 (70) | 666,971 (20) | the 3 cards f1t1 saved |
| f1t3 | 1,174,460 (33) | 1,782,810 (49) | 4 cards |

Two things this chain shows:
- **f1t2 is where memory pays.** The card f1t1 saved ("`Pet.visits` lacked `orphanRemoval`; needed
  `orphanRemoval=true` to actually delete visit rows") reached f1t2 as a pointer line. The product arm finished in 20
  calls; plain Copilot needed 70.
- **f1t3 cost more in the product arm** even though a card with current code arrived. Not every reuse is a gain.

### Development history (py-holdout P1; tuned on, so never part of the claim)

| version | B / A | what changed |
|---|---|---|
| before this work | 1.41–1.63 (product **more** expensive) | separate learning round after every task, no lean agent |
| dev 1 | 0.725 | learning inside the answer, lean agent, current code with cards |
| dev 2 | 0.824 | + test command up front: removed the failure that used to trigger learning, so one chain learned nothing |
| dev 3 | 0.647 | + one-call fallback for a change with no knowledge to go on, symbol and code-map fixes |

### A private Kotlin Multiplatform game (pilot, no build or test in the tasks)
A real client project (636 Kotlin files, ~40 Gradle modules). Tasks are a bug fix, a code change and a question about
the code; every prompt says not to build, compile or run tests, and grading (hidden tests / answer key) runs only after
the agent finished. The agent ran no build or test in any run. Three arms: A plain Copilot, Bnomem the product with
memory cleared before every task, B the product with memory. **One repetition per arm**: a pilot, not a claim.

| chain | follow-up tasks (2–3): A | Bnomem | **B** | B / Bnomem |
|---|---|---|---|---|
| text input (bug fix → change → question) | 458,412 | 279,423 | **174,170** | 0.62 |
| coin rewards (dev chain, before the fix below) | 249,764 | 365,118 | **235,758** | 0.65 |

- In the follow-up change and question of the first chain, B made **no search** before its edit or answer: the card
  learned in the bug fix named the one shared helper and arrived with the file outline (`209-210 function …`), and
  the agent read exactly those lines. Plain Copilot searched 14 and 17 times.
- Unrelated control tasks got no card; B and Bnomem cost the same there (105,104 vs 105,736).
- **Bug found and fixed:** Copilot writes Kotlin member locators with their parameter list
  (`Repo#save(Lang,Int)`), the code map keeps the parameters in the signature. Those cards did not bind, so the prompt
  showed the first 40 lines of the class instead of the member. The member now binds (same parameters, or the only
  member of that name in the file; overloads are never guessed).

## How the savings come about

Copilot sends the **whole conversation again with every model call**: the system prompt, every tool's schema, the
instructions, every file read so far and every earlier step. A task's cost is therefore roughly
**number of calls × size of each call**. Every change below reduces one of the two.

### 1. A smaller call: the compact custom agent
- Plain Copilot offers 19 tools. Their schemas alone are ≈ 11.5k of the ≈ 18.6k tokens of a first call. Measured
  first-call input (`Reply OK`, no tools used): default 18,556; with `bash view edit create grep glob` 8,002;
  background shells +0.6k, `skill` +0.6k, `web_fetch` +0.3k; the subagent tools alone +4.9k.
- Copilot has no setting that changes the default tool set, but it supports custom agents. The installer writes
  `agents/ai-orchestration.agent.md` with a compact tool list (shell and background shells, view/edit/create,
  grep/glob, skill, web_fetch, ask_user; no subagents, no session SQL). It is started with
  `copilot --agent ai-orchestration`. First call: ≈ 10.7k instead of ≈ 18.6k, and every later call is ≈ 7–9k
  smaller too.
- The agent body also asks to batch independent reads in one turn. In the dev runs, reads were then requested
  together (3–4 views in one call). Batching edits to different files did not happen, whatever the wording.
- `--agent` is a choice: plain `copilot` keeps its full tool set, and the hooks and memory still work there.

### 2. Learning without an extra round
- Before: when a task ended, Copilot was asked to write `memory-learn.json` and run `ai_orch memory learn`. That is
  3–5 more model calls, **95–132k tokens per task, about a quarter of the product arm's cost.**
- Now every prompt carries one line. If the task taught something new about the repository, the agent ends its
  final answer with a hidden HTML comment:
  ```
  <!-- ai-orch-learn {"learningCandidates":[{"kind":"debug_finding","summary":"…","content":"…",
       "locators":[{"kind":"file","ref":"src/…/VisitController.java","role":"primary_change_point"}]}]} -->
  ```
  Copilot's terminal does not display HTML comments (checked in tmux); the transcript keeps them.
- The `agentStop` hook (`copilot_memory_gate.py stop`) does the rest:
  1. It reads the final answer from the session transcript. Copilot writes that file *after* the hook starts, so the
     hook waits for the transcript record of this very stop (`hook.start` of `agentStop` with the payload's
     `timestamp`), then takes the last answer before it. If it cannot confirm the stop, it saves nothing.
  2. It checks the candidates exactly like `ai_orch memory learn` does. It drops one that names a path on this
     machine outside the repository; such notes are about the sandbox, not the code.
  3. It submits them once through the same write gate, with a per-request id. A replay returns the same receipt, and
     a failed request is retried at a later stop rather than counted as saved.
- **No extra model call.** If the agent wrote no comment, the hook asks once for that comment alone (≈ 20k), and
  only in two cases: the task changed code with no card, step memory or search result to go on, or the agent fixed a
  failing check of its own.
- Example (Java f1t1, product arm, saved with no extra call):
  - "VisitController's @ModelAttribute injects a blank new Visit before every handler … never add new endpoints …"
    (debug_finding);
  - "Pet.visits OneToMany lacked orphanRemoval; needed orphanRemoval=true to actually delete visit rows on save"
    (field_mapping);
  - "H2 seed data … pet 7/8 visits with known ids 1-4 under owners 6 and 8" (navigation).

### 3. Fewer wrong steps: how to check, before the first check
- In every Python dev task, plain and product arms alike first ran `pytest`, which the project does not use, then
  switched to `unittest`: one wasted call each.
- The prompt hook now adds the test command read from the build files (`ai_orch_profile`, also used by Claude
  Code's hooks), labelled as advice, not a result. Example: "`python -m unittest`"; for Maven and Gradle the
  one-module command, with Docker-bound tests left out.
- It is given once per session, only for a task that asks for a change ("Can you fix …?" counts, "How does … work?"
  does not), and not while build files are being changed in the working tree.
- Result in dev: test runs 15 → 9, and no task tried `pytest` again.

### 4. Less searching: what the last task learned, with today's code
- The prompt brings the cards that match the task. For each selected card the server adds what it points at in the
  **current** code:
  - a symbol: its numbered lines, at most 40; a cut excerpt says which lines it shows and where the symbol ends;
  - a file: its outline (top-level classes and functions with line ranges).
- Example (P1, follow-up task):
  ```
  Current outline, app/models.py:
    63-85 class PaginatedAPIMixin
    98-279 class User
    287-300 class Post
  ```
  The agent read `models.py` lines 287–320 directly instead of grep → view → grep.
- Nothing stale is shown: the SHA-256 of the file on disk must equal the hash the code map scanned, or no code is
  shown.
- A learned name such as `models.Post#to_dict` resolves to the scanner's `app.models.Post#to_dict` only when exactly
  one symbol of that file matches. Nothing is guessed across files.
- In dev, reads before the first edit went from 4–7 (plain) to 2–3 when the card arrived.

### 5. No repeats
- When a memory search found nothing, memory is not searched again in that request, through any route (the
  `ai_orch` command, a step lookup, or Claude Code's MCP tools).
- The same query is never asked twice.
- Knowledge that was delivered (prompt card, step card, or the agent's own search) is named in the stop reminder as
  "already known", so it is not saved again.

### 6. Bugs these measurements found and fixed
- **The final answer was read too early.** Copilot ends a turn after every model call and starts the next tool's
  hook, so "turn end followed by a hook start" did not mean the final turn was written. One chain saved nothing that
  way. The hook now matches its own stop record.
- **The learning skill's one-line description**, which Copilot sends with every call, said knowledge "should be
  saved with one final typed ai_orch terminal command". One agent followed it and ran the old 4-call round. The
  description now says to use the skill only when the user asks.
- **Stale code map in the bench.** A learned symbol the server cannot resolve yet triggers a rescan of its file. That
  rescan recorded the previous task's edits, which the bench then reset. The bench now rescans before every task;
  freshness itself was not loosened.
- **Top-level Python functions** (`module#name`) were dropped from outlines. They are now filtered by kind (methods
  and properties out) instead of by `#`.

### What did not work, and was not shipped
- **Asking for edits to different files in one turn.** It reached reads, never edits.
- **Delivering weaker matches (0.55–0.65) as full cards.** In an offline replay it recovered 3 of 4 missed follow-ups
  but also gave API cards to unrelated questions in the same repository (0 → 3 of 6) and to 10 more unrelated queries.
  The 0.65 card threshold stays.

## Install and reproduce
- **User level:** `python3 skills/copilot/ai-orchestration-memory/scripts/install_user_integration.py`. It writes
  the hooks, skills, startup instructions, the `ai_orch` launcher and the `ai-orchestration` agent into
  `~/.copilot`.
- **Per repository (also IntelliJ):** in the repository's terminal, `ai_orch project add --copilot` (the folder
  defaults to the current one). It indexes the code and writes into `.github` what Copilot uses: the instructions,
  the skill links, `hooks/ai-orchestration.json` and the `ai-orchestration` server in `mcp.json` (other servers in
  that file are kept). Copilot CLI, and the Copilot CLI sessions of IntelliJ's Copilot chat, load the hooks and the
  MCP server once the folder is trusted. It also writes the compact agent to `.github/agents`, its tool list in
  both Copilot CLI's names (`bash`, `view`, `edit`, …) and the JetBrains agent's names (`run_in_terminal`,
  `read_file`, `insert_edit_into_file`, …): each client ignores the other's names, so neither loses its terminal or
  edit tools (an earlier CLI-only list left the IDE without its terminal). In IntelliJ chat choose **Copilot CLI**
  as the agent provider (`/migrate:workspace` moves an old session) and pick the `ai-orchestration` agent. The
  older local harness (plugin 1.18) rejects `agentStop` (`[HookParser] Invalid event type`), so the learning is
  never saved, and refuses the `~/.copilot/hooks` file as an untrusted workspace folder. Checked live with an empty user home, everything from `.github` only: the agent's first
  call was 12.4k tokens (default agent 26.0k), it worked with `bash`/`view`/`edit`, the prompt hook added its
  context, and the stop hook saved the task's learning; in another run a card saved by the stop hook was delivered
  by the next task's prompt hook.
- **Start with:** `copilot --agent ai-orchestration`.
- **Bench:**
  ```
  bench/fetch.sh && bench/server.sh start
  python3 bench/run.py --suite py-holdout --agents copilot --arms A --families P2 --reps 2 --out p2-A
  python3 bench/run.py --suite py-holdout --agents copilot --arms B --families P2 --reps 2 --out p2-B
  ```
  Per-call tokens are in `bench/.work/results/<run>/sessions/*.db` (`assistant_usage_events`).
