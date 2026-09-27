---
name: debugging-this-project
description: Use when debugging a failure, test error, exception, or unexpected behavior in the AI Orchestration project, before reading files broadly.
---

# Debugging this project

Startup is already covered by the session contract (one `session.bootstrap` call); this skill needs no extra instruction read.

Localize with the code baseline before reading the repo broadly.

1. In local-first multi-repo work, before ANY `memory.search` or `codebase.*` call from an external workspace, run local `pwd`, then call `scanner.project.resolve(rootPath="<absolute pwd>")`. You MUST pass the returned `projectKey`; omitting it does not error, it silently returns `AI_ORCHESTRATION` (the MCP server repo) results. Do not use `rootPath="."` for external workspaces.
2. `codebase.diagnose(symptom, errorText, stackTrace, projectKey=...)` → ranked suspects with evidence + confidence.
3. Inspect top suspects: `codebase.symbol.get(ref, projectKey=...)`, then `codebase.symbol.neighbors(ref, depth=1, projectKey=...)` for callers/callees/injections.
4. `memory.search(area, projectKey=...)` for known decisions/issues on that area; check recent scanner `code_diagnostics`.
5. If the symptom maps to multiple plausible meanings or the top suspects are weak/conflicting after two focused passes, ask the user one concise clarification question with 2-3 likely interpretations. Do not keep recursively searching or guess business meaning.
6. Do not automatically start a scan at session/task startup or because a baseline is stale/missing. Run `scanner.scan` or `scanner.scan.start` only when the user explicitly requests a scan in the current conversation. Otherwise use bounded local searches, targeted source reads, and relevant tests; state any stale-evidence limitation without stopping to ask for a scan.
7. Fix, then run the mirrored tests under `src/test/java/.../<package>`.

Common areas → where to look:
- MCP auth/scope errors → `modules/mcp/server` (`McpAuthenticationFilter`, `McpApiKeyService`, `LocalTrustProperties`).
- DB / Flyway / SCRAM / migration → `src/main/resources/db/migration/Vn__*.sql` + the `Jdbc*Repository`; profile/datasource in `application*.yml`.
- Provider routing / timeouts → `modules/llmgateway` (`DefaultLLMGateway`, `SimpleRoutingPolicy`, `*CliProvider`).
- Startup refuses to boot → `config` (`LocalTrustStartupGuard`, `*Properties`).

Do not dump the whole repo into context; let diagnose + neighbors localize first.
