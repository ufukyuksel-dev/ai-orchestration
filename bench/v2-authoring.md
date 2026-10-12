# Authoring a v2 benchmark suite

Binding design: `output/plan-harness-benchmark-v2.md` (§4 taxonomy, §4.1 matrix, §5 memory chains, §6.3 scoring).
Runner: `bench/run.py`; task checker: `bench/verify_task.py`. Read both before writing anything.

## Files

| What | Public repo suite | Private repo suite (wordfall, wordfall-server, taplearn) |
|---|---|---|
| suite file | `bench/tasks/<suite>.json` | `$P/tasks/<suite>.json` with `"private": true` |
| hidden checks | `bench/hidden/<task>/…` | `$P/hidden/<task>/…` |
| reference solution | `bench/reference/<task>.patch` (code/tests), `<task>.answer.md` (investigation) | `$P/reference/…` |
| answer key | `bench/answers/<task>.json` | `$P/answers/<task>.json` |
| mutants | `bench/mutants/<task>/<n>.patch` | `$P/mutants/<task>/…` |
| baseline patches | `bench/baselines/<task>.patch` | `$P/baselines/<task>.patch` |
| notes (K, pitfalls, why each check) | `bench/<suite>-notes.md` | `$P/<suite>-notes.md` |

`$P` = `~/.config/super-computer-ai/private/ai-orch-bench`. **Nothing from a private repository (code, paths, names,
task texts) may be written inside this repository.** No secrets, keys, account ids or personal paths anywhere.

## Suite file

Same shape as `bench/tasks/py-holdout.json` plus the v2 fields:

```json
{"suite": "<name>", "private": true, "repo": "<dir under bench/.work/repos>", "sha": "<pinned>",
 "basePatch": "setup/<x>.patch", "worktreeFiles": {"local.properties": "setup/<x>"},
 "env": {"JAVA_HOME": "{java_home:21}"}, "note": "...",
 "qualityCommand": "...", "hiddenTarget": "...", "hiddenTestCommand": "...", "ownTestCommand": "...",
 "families": [{"id": "W1", "title": "...", "chain": true, "tasks": [
   {"id": "wst01", "type": "B", "kind": "code", "role": "discovery", "prompt": "...", "hidden": ["X.kt"],
    "hiddenTarget": "<module test dir>", "hiddenTestCommand": "...", "ownTestCommand": "...",
    "baseline": {"patch": "baselines/wst01.patch"}, "rescan": false, "allowedPaths": ["..."]}]}]}
```

- `type` ∈ F B R X T C I P (plan §4). `kind`: `code` (F B R X C P), `tests` (T), `investigation` (I).
- Per-task `hiddenTarget` / `hiddenTestCommand` / `ownTestCommand` / `qualityCommand` override the suite's
  (multi-module repos check the touched modules). `{hidden}` and `{tests}` are substituted as in `run.py`.
- Every position starts from a parentless snapshot of `sha` + `basePatch` + `baseline.patch`, so an injected bug or
  a stale-probe rename is invisible in git history.
- Independent tasks: one task per family, `"chain"` absent. Memory chains: `"chain": true`, 3–4 tasks, roles
  `discovery` (position 1), `dependent`, `stale` (baseline patch renames/moves what K refers to),
  `control` (unrelated subsystem). The runner rescans the code map whenever a position's baseline differs from the
  last scanned one (`rescan` is not needed); analysis finds stale probes by `role`.

## Task rules

- The prompt states WHAT is required and how it can be observed, never HOW or WHERE. No file names in B prompts:
  describe the symptom as a user or caller sees it. No hints of the pitfalls the notes list.
- **B (bug from symptom)**: inject a realistic bug through `baseline.patch` at a place different from where the symptom
  shows (e.g. a mapper, a shared util, a constant). The hidden test reproduces the symptom (fails on baseline) and
  passes after the reference fix (usually the reverse of the injection, or a better fix). Existing tests of the
  module must still pass after the fix; it is fine if one existing test already fails on the baseline.
- **F (feature + convention)**: add a convention probe (test or static check) for a convention visible only in the
  code (error type, naming, DI wiring, result wrapper, logging) as a separate file in the task's `"probe"` list
  (optional `"probeTests"` filter, same command as the hidden tests). Probes are **report-only** (`probeOk`, plan
  §6.2), never part of success; `verify_task.py` requires the reference to pass them.
- **R (refactor)**: ≥ 5 files, no behavior change; hidden API-shape test (e.g. reflection or compile-only usage of
  the new structure) + the existing tests.
- **X (cross-module)**: ≥ 2 JVM modules changed; hidden tests in each affected module (one command may run both).
- **T (tests)**: `allowedPaths` = test source globs; `testCommand` runs the module tests; `mutants` = 3–5 patches of
  production code that each break the behavior in a way a good test catches; the reference patch adds the tests and
  kills all of them. No equivalent mutants.
- **C (build/config)**, **P (perf)**: see plan §4. P: assert a query count / call count / no blocking call
  deterministically, never wall-clock time.
- **I (investigation)**: the prompt asks a question and names the answer keys, ending with: *"Do not change any files.
  End your reply with a block that starts with a line `ANSWER:` followed by one `key: value` line per key: …"*.
  The answer key lists the accepted aliases and `contradict` regexes for the tempting wrong answers. Questions must
  need real code reading (follow a call chain, find where a value is decided, which module owns X, what happens on Y),
  answerable from the code alone, one correct answer per key. Avoid values that contain `, ` or ` or ` unless the
  key is `multi`.
- Keep each hidden check under 3 minutes on a warm machine (`--offline` for Gradle). Measure it (`checkSeconds`).

## Verification (required before you report)

`python3 bench/verify_task.py --suite <suite>` must print OK for every task: code/tests tasks fail on the baseline
and pass with the reference patch; investigation reference answers pass and an empty answer fails. For T tasks also
confirm each mutant alone breaks the reference tests (the scorer does this). Fix tasks until all are OK; drop a task
only if it cannot be made objective, and say so.

Do not run any agent (claude/codex) on the scored tasks; do not modify `bench/run.py`, `bench/verify_task.py`
or `bench/analyze.py` (report a needed change instead).
