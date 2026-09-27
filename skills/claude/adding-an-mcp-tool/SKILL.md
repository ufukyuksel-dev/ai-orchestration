---
name: adding-an-mcp-tool
description: Use when adding a new MCP tool or capability to the AI Orchestration server (a new tool under modules/mcp/server).
---

# Adding an MCP tool

Startup is already covered by the session contract (one `session.bootstrap` call); this skill needs no extra instruction read.

1. **Ambiguity stop:** if the requested tool name, scope, project boundary, or side effect is unclear after two focused passes, ask the user before encoding a permanent MCP contract. Give 2-3 likely interpretations and the evidence for each.
2. Create `XxxMcpTool` in `modules/mcp/server`: `@Component` + `@ConditionalOnProperty(prefix="ai-orchestration.mcp", name="enabled", havingValue="true", matchIfMissing=true)`. Methods are annotated `@McpTool(name="x.y", description=...)` with `@McpToolParam` per argument.
3. In each method: resolve context via `McpClientContextHolder.require()`, **scope-check** (`requireScope`/`context.hasScope`), and **audit** via `McpAuditLogger` for every outcome — success, `denied_scope`, error. Reject cross-project access.
4. Register the scope in **all** of: `McpApiKeyService` tiers (prod keys); `LocalTrustProperties.DEFAULT_LOCAL_SCOPES` (fallback); the `ai-orchestration.local-trust.local-scopes` list in **both** `application.yml` and `application-local-first.yml` (these YAML lists override the constant, so the scope won't apply in local-first without them); and a Flyway `Vn__<name>_scope.sql` granting it to existing keys.
5. Never bypass human-approval gates (hard-delete params, `memory.confirm`); redact before any external egress.
6. Tests: missing-scope rejected + audited; happy path; ideally an integration test asserting the tool registers.

Mirror an existing tool: `MemoryMcpTool`, `CodebaseMcpTool`, or `ScannerMcpTool`.
