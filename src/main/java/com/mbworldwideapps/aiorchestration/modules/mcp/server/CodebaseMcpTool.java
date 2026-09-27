package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CodebaseMcpTool {

    private final CodebaseService codebaseService;
    private final McpAuditLogger auditLogger;
    private final AiOrchestrationProperties properties;

    public CodebaseMcpTool(CodebaseService codebaseService, McpAuditLogger auditLogger,
            AiOrchestrationProperties properties) {
        this.codebaseService = codebaseService;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    @McpTool(name = "codebase.baseline.search", description = "Search the scanned code by meaning; returns symbols with file and line range.")
    public CodebaseService.BaselineSearchResponse search(
            @McpToolParam(description = "Search query") String query,
            @McpToolParam(description = "Optional project key; defaults to caller project", required = false) String projectKey,
            @McpToolParam(description = "Number of results", required = false) Integer topK) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, "codebase.read", "codebase.baseline.search", query, started);
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = projectKey(context, projectKey);
            CodebaseService.BaselineSearchResponse response = codebaseService.search(query, effectiveProjectKey,
                    clampTopK(topK));
            auditLogger.log(context, "codebase.baseline.search", McpAuditLogger.hashQuery(query),
                    response.hits().size(), latencyMs(started), "success", Map.of("projectKey", effectiveProjectKey),
                    null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "codebase.baseline.search", McpAuditLogger.hashQuery(query), 0,
                    latencyMs(started), decision(e), Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(name = "codebase.symbol.get", description = "Look up a class/method/endpoint/file by name or FQN: location, summary and direct callers/callees.")
    public CodebaseService.SymbolGetResponse symbolGet(
            @McpToolParam(description = "Prefer an exact returned Scanner UUID or a narrow symbol name. Method FQNs use Class#method, not Class.method. A file path returns its symbols and can be large; use it only when file-wide context is needed.") String ref,
            @McpToolParam(description = "Optional project key; defaults to caller project", required = false) String projectKey,
            @McpToolParam(description = "Maximum outgoing edges per symbol; defaults to 10, max 100", required = false) Integer edgeLimit) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, "codebase.read", "codebase.symbol.get", ref, started);
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = projectKey(context, projectKey);
            CodebaseService.SymbolGetResponse response = withAttached(
                    codebaseService.symbolGet(ref, effectiveProjectKey, edgeLimit), effectiveProjectKey);
            auditLogger.log(context, "codebase.symbol.get", McpAuditLogger.hashQuery(ref), response.symbols().size(),
                    latencyMs(started), "success", Map.of("projectKey", effectiveProjectKey), null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "codebase.symbol.get", McpAuditLogger.hashQuery(ref), 0, latencyMs(started),
                    decision(e), Map.of("projectKey", effectiveProjectKey), e.getClass().getSimpleName());
            throw e;
        }
    }

    private com.mbworldwideapps.aiorchestration.modules.workspace.CodeTreeService codeTree;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCodeTree(com.mbworldwideapps.aiorchestration.modules.workspace.CodeTreeService codeTree) {
        this.codeTree = codeTree;
    }

    /** Rules and memories the user attached (panel graph) to these symbols or their files travel with the lookup. */
    private CodebaseService.SymbolGetResponse withAttached(CodebaseService.SymbolGetResponse response,
            String projectKey) {
        if (codeTree == null || response.symbols().isEmpty() || projectKey == null) return response;
        try {
            var attached = codeTree.attachedToSymbols(projectKey, response.symbols().stream()
                    .map(d -> d.symbol().symbolId()).filter(java.util.Objects::nonNull).limit(20).toList());
            return response.withAttached(
                    attached.rules().stream().map(r -> new CodebaseService.Attached(r.id(), r.summary(), r.target()))
                            .toList(),
                    attached.memories().stream().map(m -> new CodebaseService.Attached(m.id(), m.summary(), m.target()))
                            .toList());
        } catch (RuntimeException e) {
            return response; // attachments are a bonus; the lookup itself must not fail on them
        }
    }

    public CodebaseService.SymbolGetResponse symbolGet(String ref, String projectKey) {
        return symbolGet(ref, projectKey, null);
    }

    @McpTool(name = "codebase.symbol.neighbors", description = "codebase.symbol.neighbors: Return a bounded graph neighborhood for a symbol UUID, FQN, name, or path.")
    public CodebaseService.SymbolNeighborsResponse neighbors(
            @McpToolParam(description = "Symbol UUID, FQN, name, or file path") String symbolId,
            @McpToolParam(description = "Traversal depth, max 3", required = false) Integer depth,
            @McpToolParam(description = "Optional edge type filter", required = false) List<String> edgeTypes,
            @McpToolParam(description = "Optional project key; defaults to caller project", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, "codebase.read", "codebase.symbol.neighbors", symbolId, started);
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = projectKey(context, projectKey);
            CodebaseService.SymbolNeighborsResponse response = codebaseService.neighbors(symbolId, depth, edgeTypes,
                    effectiveProjectKey);
            auditLogger.log(context, "codebase.symbol.neighbors", McpAuditLogger.hashQuery(symbolId),
                    response.nodes().size(), latencyMs(started), "success", Map.of("projectKey", effectiveProjectKey),
                    null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "codebase.symbol.neighbors", McpAuditLogger.hashQuery(symbolId), 0,
                    latencyMs(started), decision(e), Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(name = "codebase.impact.analyze", description = "Analyze reverse dependencies for a symbol, FQN, name, or path. D1 name-only CALLS are returned as possibleImpact, not resolvedImpact.")
    public CodebaseService.ImpactAnalyzeResponse impact(
            @McpToolParam(description = "Symbol UUID, FQN, name, or file path") String ref,
            @McpToolParam(description = "Optional project key; defaults to caller project", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, "codebase.read", "codebase.impact.analyze", ref, started);
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = projectKey(context, projectKey);
            CodebaseService.ImpactAnalyzeResponse response = codebaseService.impact(ref, effectiveProjectKey);
            auditLogger.log(context, "codebase.impact.analyze", McpAuditLogger.hashQuery(ref),
                    response.resolvedImpact().size() + response.possibleImpact().size(), latencyMs(started), "success",
                    Map.of("projectKey", effectiveProjectKey), null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "codebase.impact.analyze", McpAuditLogger.hashQuery(ref), 0,
                    latencyMs(started), decision(e), Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(name = "codebase.diagnose", description = "Diagnose a symptom using baseline search, graph evidence, and scanner diagnostics.")
    public CodebaseService.DiagnoseResponse diagnose(
            @McpToolParam(description = "Symptom or question") String symptom,
            @McpToolParam(description = "Optional error text", required = false) String errorText,
            @McpToolParam(description = "Optional stack trace", required = false) String stackTrace,
            @McpToolParam(description = "Optional changed files", required = false) List<String> changedFiles,
            @McpToolParam(description = "Optional project key; defaults to caller project", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, "codebase.read", "codebase.diagnose", symptom, started);
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = projectKey(context, projectKey);
            CodebaseService.DiagnoseResponse response = codebaseService.diagnose(symptom, errorText, stackTrace,
                    changedFiles, effectiveProjectKey);
            auditLogger.log(context, "codebase.diagnose", McpAuditLogger.hashQuery(symptom),
                    response.suspects().size(), latencyMs(started), "success",
                    Map.of("projectKey", effectiveProjectKey), null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "codebase.diagnose", McpAuditLogger.hashQuery(symptom), 0, latencyMs(started),
                    decision(e), Map.of("projectKey", effectiveProjectKey), e.getClass().getSimpleName());
            throw e;
        }
    }

    private void requireScope(McpClientContext context, String scope, String toolName, String query, Instant started) {
        if (!context.hasScope(scope)) {
            auditLogger.log(context, toolName, McpAuditLogger.hashQuery(query), 0, latencyMs(started),
                    "denied_scope", Map.of(), McpAccessException.class.getSimpleName());
            throw new McpAccessException("Missing MCP scope: " + scope);
        }
    }

    private String projectKey(McpClientContext context, String projectKey) {
        String effective = McpProjectKeys.effective(context, projectKey);
        if (!McpProjectKeys.canAccess(context, effective)) {
            throw new McpAccessException("Cannot read codebase baseline for a different project");
        }
        return effective;
    }

    private int clampTopK(Integer requested) {
        if (requested == null || requested <= 0) {
            return properties.mcp().defaultTopK();
        }
        return Math.min(requested, properties.mcp().maxTopK());
    }

    private static long latencyMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private static String decision(RuntimeException exception) {
        return exception instanceof McpAccessException ? "denied_project" : "error";
    }
}
