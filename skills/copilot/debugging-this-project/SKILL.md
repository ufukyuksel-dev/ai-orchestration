---
name: debugging-this-project
description: Use when debugging a failure, test error, exception, or unexpected behavior in the AI Orchestration project, before reading files broadly. Localizes through ai_orch terminal diagnose/symbol/impact commands with the real error text, and never starts a scan.
---

# Debugging this project

Localize with the existing code baseline before reading the repo broadly. Every
AI Orchestration call is one direct `ai_orch` terminal command.

1. **Bind the project.** `ai_orch` resolves the canonical Git root itself and
   attaches the resolved `projectKey`; never pass a project key yourself. From a
   central workspace, target another verified repository with `--repo-root`.

   ```bash
   ai_orch project resolve
   ```

2. **Diagnose with the real failure text**, not a paraphrase:

   ```bash
   ai_orch codebase diagnose --file diagnose.json
   ```

   ```json
   {
     "symptom": "…",
     "errorText": "…",
     "stackTrace": "…",
     "changedFiles": ["src/main/java/…"]
   }
   ```

   The server redacts before egress; still, do not paste credentials or raw
   private data into the request.

3. **Inspect the top suspects:**

   ```bash
   ai_orch codebase symbol get --ref "<ref>"
   ai_orch codebase symbol neighbors --symbol-id "<ref>" --depth 1
   ```

   Then read only the returned file/line region locally.

4. **Past decisions, only when they would change the work:**

   ```bash
   ai_orch memory search --query "<component or exact error phrase>" --top-k 3
   ```

   Treat scanner diagnostics as existing evidence unless the user explicitly
   requests a fresh scan.

5. **Ambiguity stop:** if the symptom maps to several plausible meanings, or the
   top suspects are weak or conflicting after two focused passes, ask the user one
   concise clarification question with 2–3 likely interpretations. Do not keep
   searching recursively and do not invent business meaning from weak matches.

6. **Stale baseline:** do not start a scan merely because the baseline is stale.
   When requested, use the approved project-bound `ai_orch scan` commands; never
   fall back to native MCP, raw HTTP or the database.

7. **Fix, then run the mirrored tests** under `src/test/java/.../<package>`.

Common areas → where to look:

- MCP auth/scope errors → `modules/mcp/server` (`McpAuthenticationFilter`,
  `McpApiKeyService`, `LocalTrustProperties`).
- DB / Flyway / SCRAM / migration → `src/main/resources/db/migration/Vn__*.sql`
  plus the `Jdbc*Repository`; profile/datasource in `application*.yml`.
- Provider routing / timeouts → `modules/llmgateway` (`DefaultLLMGateway`,
  `SimpleRoutingPolicy`, `*CliProvider`).
- Startup refuses to boot → `config` (`LocalTrustStartupGuard`, `*Properties`).

Do not dump the whole repo into context; let diagnose and neighbors localize
first.
