---
name: changing-code-safely
description: Use before editing, refactoring, renaming, or deleting code. Reuses sufficient focused memory-search locators for bounded low-risk changes and escalates to module, baseline, impact and plan tools only when risk or ambiguity requires them.
---

# Changing code safely

Understand blast radius before editing — don't grep-and-hope, and don't repeat
discovery already supplied by a focused learning context. Every AI Orchestration
call is one direct `ai_orch` terminal command; there is no native MCP path for
Copilot and no fallback to one.

## Focused fast path

Use this path when all of the following are true:

- the session-start rule choice has been answered; when the user opted in, the
  effective instructions were loaded successfully;
- one focused `memory search` returned sufficient project-bound code locators;
- the complete change stays inside those returned source/test targets (plus a
  directly referenced helper read locally);
- the task is not a deletion, migration, security/auth/permission change,
  public contract, cross-module refactor, or ambiguous business behavior.

On this path, read the returned targets locally, edit, and run the focused test.
Do not repeat the work with module-rule probes, baseline search, symbol get,
neighbors or impact analysis. Memory is navigation, not proof, so
current source and tests remain mandatory. Do not open evidence or write memory
in consumer/change mode; the learning skill owns producer and refresh writes.

## Escalation path

Use the steps below only when the focused fast-path conditions are not met.

1. **Load the applicable module policy when rule loading was accepted.** The module list in an `effective`
   instruction response is an index, not loaded text:

   ```bash
   ai_orch rule instructions --scope module --module-path "src/main/java/com/mbworldwideapps/aiorchestration/modules/<module>"
   ```

   Parent-directory policies apply to descendants. Load only a directory that
   the effective response explicitly listed; do not probe an unlisted path. If
   a required listed policy load fails, stop the dependent work and report the
   gap.

2. **Locate:**

   ```bash
   ai_orch codebase baseline search --query "<behaviour or symbol>" --top-k 5
   ai_orch codebase symbol get --ref "<UUID, Class#method, FQN or path>"
   ```

   There is no `codebase read` command. When you need source, use the
   `filePath` and line bounds returned by `symbol get` and read that region
   locally — the coordinates describe the scanned baseline and may be stale.

3. **Context:**

   ```bash
   ai_orch codebase symbol neighbors --symbol-id "<ref>" --depth 1
   ```

   The server parameter is `symbolId`; `--ref` is accepted as an alias.

4. **Impact:**

   ```bash
   ai_orch codebase impact analyze --ref "<ref>"
   ```

   Read both buckets. `resolvedImpact` is precise (D2 resolved edges).
   `possibleImpact` is D1 name-only, lower confidence, and must be verified
   manually. Keep the distinction in what you report.

5. **Ambiguity stop:** if the target term maps to several symbols or
   conventions, or the intended behaviour is still unclear after two focused
   passes, ask the user before editing. Offer 2–3 likely interpretations with the
   evidence for each. Do not guess business meaning and do not persist an
   uncertain conclusion as memory.

6. **Edit** following project conventions: constructor injection, typed
   `@ConfigurationProperties`, scope check plus audit for every MCP tool,
   redaction before any egress, human-approval gates intact.

7. **Verify:** run the mirrored tests for every impacted area
   (`src/test/java/.../<package>`); add or adjust tests for the change.

If the baseline is stale or missing, do **not** run any `scanner.scan*`
operation and do not ask for a scan as a precondition. Say that the baseline
evidence is stale, then establish the blast radius with bounded local `rg`,
targeted file reads and the mirrored tests. Never change a shared symbol without
checking impact.
