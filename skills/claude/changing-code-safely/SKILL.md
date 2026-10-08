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
5. **Edit** following project conventions: constructor injection, typed `@ConfigurationProperties`, scope-check + audit for MCP tools, redaction before egress, hard-delete confirmation intact.
6. **Verify:** run the mirrored tests for every impacted area (`src/test/java/.../<package>`); add or adjust tests for the change.

When the code map is missing or out of date, refresh it with `scanner.scan.start`, or verify callers and affected paths with bounded local searches, targeted source reads and relevant tests. Never change a shared symbol without checking impact.
