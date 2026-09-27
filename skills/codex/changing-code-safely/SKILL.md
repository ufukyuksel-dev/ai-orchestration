---
name: changing-code-safely
description: Use before editing, refactoring, renaming, or deleting any class, method, endpoint, or symbol in the AI Orchestration codebase.
---

# Changing code safely

Startup is already covered by the session contract (one `session.bootstrap` call); this skill needs no extra instruction read.

Understand blast radius before editing — don't grep-and-hope.

1. **Locate:** `codebase.baseline.search(query)` or `codebase.symbol.get(ref)` (ref = UUID, FQN, name, or path).
2. **Context:** `codebase.symbol.neighbors(symbolId, depth=1)` for callers, callees, injections, and endpoint edges.
3. **Impact:** `codebase.impact.analyze(ref)` — read both buckets. `resolvedImpact` is precise (D2 resolved edges); `possibleImpact` is D1 name-only (lower confidence) — verify those manually before trusting them.
4. **Ambiguity stop:** if the target term maps to multiple symbols/conventions or the intended behavior is unclear after two focused passes, ask the user before editing. Give 2-3 likely interpretations and the evidence for each.
5. **Edit** following project conventions: constructor injection, typed `@ConfigurationProperties`, scope-check + audit for MCP tools, redaction before egress, human-approval gates intact.
6. **Verify:** run the mirrored tests for every impacted area (`src/test/java/.../<package>`); add or adjust tests for the change.

Do not automatically start a scan at session/task startup or because a baseline is stale/missing. Run `scanner.scan` or `scanner.scan.start` only when the user explicitly requests a scan in the current conversation. Otherwise use bounded local searches, targeted source reads, and relevant tests; state any stale-evidence limitation without stopping to ask for a scan. Never change a shared symbol without checking impact; if baseline evidence is stale, verify callers and affected paths locally.
