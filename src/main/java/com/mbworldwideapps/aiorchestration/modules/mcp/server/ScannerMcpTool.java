package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningService;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeDiagnosticRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspace;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolutionException;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolver;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanCodebaseRequest;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanCodebaseResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerAsyncService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanCancelResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanResultResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanStartResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanStatusResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScannerMcpTool {

    private final ScannerService scannerService;
    private final ScannerAsyncService scannerAsyncService;
    private final McpAuditLogger auditLogger;
    private final ProviderOverrideSanitizer providerOverrideSanitizer;
    private final ProjectWorkspaceResolver projectWorkspaceResolver;
    private final AgentLearningService agentLearningService;

    @Autowired
    public ScannerMcpTool(ScannerService scannerService, ScannerAsyncService scannerAsyncService,
            McpAuditLogger auditLogger, ProviderOverrideSanitizer providerOverrideSanitizer,
            ProjectWorkspaceResolver projectWorkspaceResolver, AgentLearningService agentLearningService) {
        this.scannerService = scannerService;
        this.scannerAsyncService = scannerAsyncService;
        this.auditLogger = auditLogger;
        this.providerOverrideSanitizer = providerOverrideSanitizer;
        this.projectWorkspaceResolver = projectWorkspaceResolver;
        this.agentLearningService = agentLearningService;
    }

    public ScannerMcpTool(ScannerService scannerService, McpAuditLogger auditLogger,
            ProviderOverrideSanitizer providerOverrideSanitizer,
            ProjectWorkspaceResolver projectWorkspaceResolver) {
        this(scannerService, null, auditLogger, providerOverrideSanitizer, projectWorkspaceResolver, null);
    }

    public ScannerMcpTool(ScannerService scannerService, ScannerAsyncService scannerAsyncService,
            McpAuditLogger auditLogger, ProviderOverrideSanitizer providerOverrideSanitizer,
            ProjectWorkspaceResolver projectWorkspaceResolver) {
        this(scannerService, scannerAsyncService, auditLogger, providerOverrideSanitizer,
                projectWorkspaceResolver, null);
    }

    @McpTool(name = "scanner.project.resolve", description = "Resolve the effective projectKey for a local repo root without starting a scan. Local-trust callers register roots containing a .git file/directory; an explicit projectKey updates its current checkout path. Bearer callers only resolve existing bindings. Report whether the persisted binding was verified and, when learning is enabled, return a principal-bound workspaceBindingId. Use the absolute local pwd before memory/codebase calls in multi-repo work.")
    public ScannerProjectResolveResponse resolveProject(
            @McpToolParam(description = "Absolute local repository root path. Do not pass '.' from external client workspaces.", required = false) String rootPath,
            @McpToolParam(description = "Optional explicit project key. Local-first normalizes it; bearer keys must match the caller project.", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        Instant started = Instant.now();
        String effectiveProjectKey = context.projectKey();
        try {
            effectiveProjectKey = McpProjectKeys.forScan(context, projectKey, rootPath);
            ProjectBindingResolution binding = resolveProjectBinding(rootPath, projectKey,
                    effectiveProjectKey, context);
            effectiveProjectKey = binding.projectKey();
            UUID workspaceBindingId = issueWorkspaceBinding(context, binding);
            ScannerProjectResolveResponse response = new ScannerProjectResolveResponse(
                    binding.rootPath(),
                    effectiveProjectKey,
                    context.projectKey(),
                    McpProjectKeys.isLocalTrust(context),
                    binding.verified(),
                    binding.repositoryFingerprint(),
                    workspaceBindingId);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("projectKey", effectiveProjectKey);
            metadata.put("bindingVerified", binding.verified());
            metadata.put("workspaceBindingIssued", workspaceBindingId != null);
            auditLogger.log(context, "scanner.project.resolve", McpAuditLogger.hashQuery(rootPath), 1,
                    latencyMs(started), "success", metadata, null);
            return response;
        } catch (McpAccessException e) {
            auditLogger.log(context, "scanner.project.resolve", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "denied_scope", Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.project.resolve", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "error", Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    private UUID issueWorkspaceBinding(McpClientContext context, ProjectBindingResolution binding) {
        if (agentLearningService == null || !agentLearningService.enabled() || !binding.verified()
                || binding.repositoryFingerprint() == null) {
            return null;
        }
        try {
            ProjectWorkspace workspace = projectWorkspaceResolver.resolve(
                    binding.projectKey(), binding.repositoryFingerprint());
            return agentLearningService.issueWorkspaceBinding(
                    AgentLearningMcpTool.principal(context), context.clientId(), workspace);
        } catch (RuntimeException learningUnavailable) {
            // Workspace binding is additive. A learning-only failure must not turn
            // the established scanner.project.resolve contract into a startup outage.
            return null;
        }
    }

    private ProjectBindingResolution resolveProjectBinding(String rootPath, String requestedProjectKey,
            String derivedProjectKey, McpClientContext context) {
        String reportedRoot = rootPath == null || rootPath.isBlank() ? "." : rootPath.trim();
        if (McpProjectKeys.isLocalTrust(context)
                && requestedProjectKey != null && !requestedProjectKey.isBlank()
                && Path.of(reportedRoot).isAbsolute()) {
            ProjectWorkspace workspace = projectWorkspaceResolver.registerRoot(derivedProjectKey, Path.of(reportedRoot));
            return new ProjectBindingResolution(workspace.repositoryRoot().toString(), workspace.projectKey(),
                    true, workspace.repositoryFingerprint());
        }
        try {
            ProjectWorkspace workspace = projectWorkspaceResolver.resolveByPath(Path.of(reportedRoot));
            boolean callerBoundToDerivedKey = !McpProjectKeys.isLocalTrust(context)
                    || (requestedProjectKey != null && !requestedProjectKey.isBlank());
            if (callerBoundToDerivedKey && !derivedProjectKey.equals(workspace.projectKey())) {
                ProjectWorkspace callerWorkspace = projectWorkspaceResolver.resolve(derivedProjectKey);
                if (!callerWorkspace.repositoryRoot().equals(workspace.repositoryRoot())) {
                    throw new ProjectWorkspaceResolutionException("project_binding_mismatch:" + derivedProjectKey);
                }
                workspace = callerWorkspace;
            }
            Path requestedRoot = Path.of(reportedRoot);
            if (McpProjectKeys.isLocalTrust(context) && requestedRoot.isAbsolute()
                    && java.nio.file.Files.exists(requestedRoot.resolve(".git"))
                    && !requestedRoot.toAbsolutePath().normalize().equals(workspace.repositoryRoot())
                    && !sameDirectory(requestedRoot, workspace.repositoryRoot())) {
                workspace = projectWorkspaceResolver.registerRoot(derivedProjectKey, requestedRoot);
            }
            return new ProjectBindingResolution(workspace.repositoryRoot().toString(), workspace.projectKey(),
                    true, workspace.repositoryFingerprint());
        } catch (ProjectWorkspaceResolutionException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("unknown_project_path:")) {
                if (McpProjectKeys.isLocalTrust(context) && Path.of(reportedRoot).isAbsolute()) {
                    ProjectWorkspace registered = projectWorkspaceResolver.registerRoot(derivedProjectKey, Path.of(reportedRoot));
                    return new ProjectBindingResolution(registered.repositoryRoot().toString(), registered.projectKey(),
                            true, registered.repositoryFingerprint());
                }
                return new ProjectBindingResolution(reportedRoot, derivedProjectKey, false, null);
            }
            throw exception;
        }
    }

    private static boolean sameDirectory(Path first, Path second) {
        try {
            return java.nio.file.Files.isSameFile(first, second);
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private record ProjectBindingResolution(
            String rootPath,
            String projectKey,
            boolean verified,
            String repositoryFingerprint) {
    }

    @McpTool(name = "scanner.scan", description = "Scan a local codebase into project memory and code baseline.")
    public ScanCodebaseResponse scan(
            @McpToolParam(description = "Root path to scan. Defaults to backend working directory.", required = false) String rootPath,
            @McpToolParam(description = "Optional project key. Local-first defaults to a key derived from rootPath.", required = false) String projectKey,
            @McpToolParam(description = "Force rescan unchanged files.", required = false) Boolean force,
            @McpToolParam(description = "Optional semantic provider override: local-qwen | claude | codex", required = false) String providerOverride,
            @McpToolParam(description = "Optional semantic model audit tag/pin.", required = false) String semanticModel,
            @McpToolParam(description = "Reindex existing Postgres code capsules into the vector baseline.", required = false) Boolean forceReindex,
            @McpToolParam(description = "Optional relative file or directory paths to include in a scoped scan.", required = false) List<String> includePaths,
            @McpToolParam(description = "Optional relative file or directory paths to exclude from a scoped scan.", required = false) List<String> excludePaths,
            @McpToolParam(description = "Optional per-run cap for semantic-enabled files. The configured scanner cap still applies.", required = false) Integer maxSemanticFiles,
            @McpToolParam(description = "Optional per-run cap for semantic class/method capsule targets.", required = false) Integer maxSemanticSymbols,
            @McpToolParam(description = "Set false for a structural-only scan (no semantic LLM capsules). Defaults to the configured semantic setting.", required = false) Boolean semanticEnabled) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        Instant started = Instant.now();
        String sanitizedOverride = null;
        String effectiveProjectKey = context.projectKey();
        try {
            sanitizedOverride = providerOverrideSanitizer.sanitize(providerOverride, context).orElse(null);
            effectiveProjectKey = McpProjectKeys.forScan(context, projectKey, rootPath);
            ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(
                    rootPath,
                    effectiveProjectKey,
                    force,
                    sanitizedOverride,
                    semanticModel,
                    forceReindex,
                    null,
                    includePaths,
                    excludePaths,
                    maxSemanticFiles,
                    maxSemanticSymbols,
                    // maxSemanticFlows: exposed on scanner.scan.start (async) + CLI only; sync scanner.scan uses the configured cap.
                    null,
                    semanticEnabled));
            auditLogger.log(context, "scanner.scan", McpAuditLogger.hashQuery(rootPath), response.candidatesCreated(),
                    latencyMs(started), "success",
                    metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    null);
            return response;
        } catch (McpAccessException e) {
            auditLogger.log(context, "scanner.scan", McpAuditLogger.hashQuery(rootPath), 0, latencyMs(started),
                    "denied_scope", metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan", McpAuditLogger.hashQuery(rootPath), 0, latencyMs(started),
                    "error", metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    public ScanCodebaseResponse scan(String rootPath, Boolean force, String providerOverride, String semanticModel,
            Boolean forceReindex) {
        return scan(rootPath, null, force, providerOverride, semanticModel, forceReindex, null, null, null, null, null);
    }

    public ScanCodebaseResponse scan(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex) {
        return scan(rootPath, projectKey, force, providerOverride, semanticModel, forceReindex,
                null, null, null, null, null);
    }

    @McpTool(name = "scanner.scan.start", description = "Queue a long-running scanner scan and return immediately with scanRunId. Poll scanner.scan.status/result.")
    public ScannerScanStartResponse start(
            @McpToolParam(description = "Root path to scan. Defaults to backend working directory.", required = false) String rootPath,
            @McpToolParam(description = "Optional project key. Local-first defaults to a key derived from rootPath.", required = false) String projectKey,
            @McpToolParam(description = "Force rescan unchanged files.", required = false) Boolean force,
            @McpToolParam(description = "Optional semantic provider override: local-qwen | claude | codex", required = false) String providerOverride,
            @McpToolParam(description = "Optional semantic model audit tag/pin.", required = false) String semanticModel,
            @McpToolParam(description = "Reindex existing Postgres code capsules into the vector baseline.", required = false) Boolean forceReindex,
            @McpToolParam(description = "Optional relative file or directory paths to include in a scoped scan.", required = false) List<String> includePaths,
            @McpToolParam(description = "Optional relative file or directory paths to exclude from a scoped scan.", required = false) List<String> excludePaths,
            @McpToolParam(description = "Optional per-run cap for semantic-enabled files. The configured scanner cap still applies.", required = false) Integer maxSemanticFiles,
            @McpToolParam(description = "Optional per-run cap for semantic class/method capsule targets.", required = false) Integer maxSemanticSymbols,
            @McpToolParam(description = "Optional per-run cap for semantic flow capsule targets (endpoint/domain flows). The configured scanner cap still applies.", required = false) Integer maxSemanticFlows,
            @McpToolParam(description = "Set false for a structural-only scan (no semantic LLM capsules). Defaults to the configured semantic setting.", required = false) Boolean semanticEnabled) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        requireAsyncService();
        Instant started = Instant.now();
        String sanitizedOverride = null;
        String effectiveProjectKey = context.projectKey();
        try {
            sanitizedOverride = providerOverrideSanitizer.sanitize(providerOverride, context).orElse(null);
            effectiveProjectKey = McpProjectKeys.forScan(context, projectKey, rootPath);
            ScannerScanStartResponse response = scannerAsyncService.start(new ScanCodebaseRequest(
                    rootPath, effectiveProjectKey, force, sanitizedOverride, semanticModel, forceReindex, null,
                    includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, maxSemanticFlows,
                    semanticEnabled));
            auditLogger.log(context, "scanner.scan.start", McpAuditLogger.hashQuery(rootPath), 1,
                    latencyMs(started), "success",
                    metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    null);
            return response;
        } catch (McpAccessException e) {
            auditLogger.log(context, "scanner.scan.start", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "denied_scope",
                    metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan.start", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "error",
                    metadata(force, forceReindex, sanitizedOverride, semanticModel, effectiveProjectKey,
                            includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, semanticEnabled),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    public ScannerScanStartResponse start(String rootPath, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex) {
        return start(rootPath, null, force, providerOverride, semanticModel, forceReindex, null, null, null, null, null, null);
    }

    public ScannerScanStartResponse start(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex) {
        return start(rootPath, projectKey, force, providerOverride, semanticModel, forceReindex,
                null, null, null, null, null, null);
    }

    @McpTool(name = "scanner.scan.status", description = "Return queued/running/completed/failed status for an async scanner run.")
    public ScannerScanStatusResponse status(
            @McpToolParam(description = "scanRunId returned by scanner.scan.start") String scanRunId,
            @McpToolParam(description = "Optional project key. Local-first may omit it and resolve by scanRunId.", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        requireAsyncService();
        Instant started = Instant.now();
        try {
            UUID id = UUID.fromString(scanRunId);
            ScannerScanStatusResponse response = status(context, projectKey, id)
                    .orElseThrow(() -> new IllegalArgumentException("scanRunId not found for this project: " + id));
            auditLogger.log(context, "scanner.scan.status", id.toString(), 1, latencyMs(started),
                    "success", Map.of("status", response.status(), "projectKey", response.projectKey()), null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan.status", McpAuditLogger.hashQuery(scanRunId), 0,
                    latencyMs(started), "error", Map.of(), e.getClass().getSimpleName());
            throw e;
        }
    }

    public ScannerScanStatusResponse status(String scanRunId) {
        return status(scanRunId, null);
    }

    @McpTool(name = "scanner.scan.result", description = "Return final scanner result for an async run. While queued/running, done=false and result=null.")
    public ScannerScanResultResponse result(
            @McpToolParam(description = "scanRunId returned by scanner.scan.start") String scanRunId,
            @McpToolParam(description = "Optional project key. Local-first may omit it and resolve by scanRunId.", required = false) String projectKey,
            @McpToolParam(description = "Maximum diagnostics to include, max 100", required = false) Integer diagnosticLimit) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        requireAsyncService();
        Instant started = Instant.now();
        try {
            UUID id = UUID.fromString(scanRunId);
            ScannerScanResultResponse response = result(context, projectKey, id, clampDiagnosticLimit(diagnosticLimit))
                    .orElseThrow(() -> new IllegalArgumentException("scanRunId not found for this project: " + id));
            auditLogger.log(context, "scanner.scan.result", id.toString(), response.diagnostics().size(),
                    latencyMs(started), "success", Map.of("status", response.status(), "done", response.done()),
                    null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan.result", McpAuditLogger.hashQuery(scanRunId), 0,
                    latencyMs(started), "error", Map.of(), e.getClass().getSimpleName());
            throw e;
        }
    }

    public ScannerScanResultResponse result(String scanRunId, Integer diagnosticLimit) {
        return result(scanRunId, null, diagnosticLimit);
    }

    @McpTool(name = "scanner.scan.diagnostics", description = "Return diagnostics for an async scanner run.")
    public List<CodeDiagnosticRecord> diagnostics(
            @McpToolParam(description = "scanRunId returned by scanner.scan.start") String scanRunId,
            @McpToolParam(description = "Optional project key. Local-first may omit it and resolve by scanRunId.", required = false) String projectKey,
            @McpToolParam(description = "Maximum diagnostics to include, max 100", required = false) Integer limit) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        requireAsyncService();
        Instant started = Instant.now();
        try {
            UUID id = UUID.fromString(scanRunId);
            ScannerScanStatusResponse status = status(context, projectKey, id)
                    .orElseThrow(() -> new IllegalArgumentException("scanRunId not found for this project: " + id));
            List<CodeDiagnosticRecord> diagnostics = scannerAsyncService.diagnostics(status.projectKey(), id,
                    clampDiagnosticLimit(limit));
            auditLogger.log(context, "scanner.scan.diagnostics", id.toString(), diagnostics.size(),
                    latencyMs(started), "success", Map.of(), null);
            return diagnostics;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan.diagnostics", McpAuditLogger.hashQuery(scanRunId), 0,
                    latencyMs(started), "error", Map.of(), e.getClass().getSimpleName());
            throw e;
        }
    }

    public List<CodeDiagnosticRecord> diagnostics(String scanRunId, Integer limit) {
        return diagnostics(scanRunId, null, limit);
    }

    @McpTool(name = "scanner.scan.cancel", description = "Cancel a queued/running async scanner run for this project.")
    public ScannerScanCancelResponse cancel(
            @McpToolParam(description = "scanRunId returned by scanner.scan.start") String scanRunId,
            @McpToolParam(description = "Optional project key. Local-first may omit it and resolve by scanRunId.", required = false) String projectKey,
            @McpToolParam(description = "Optional cancellation reason", required = false) String reason) {
        McpClientContext context = McpClientContextHolder.require();
        requireScope(context, "scanner.scan");
        requireAsyncService();
        Instant started = Instant.now();
        try {
            UUID id = UUID.fromString(scanRunId);
            ScannerScanCancelResponse response = cancel(context, projectKey, id, reason)
                    .orElseThrow(() -> new IllegalArgumentException("scanRunId not found for this project: " + id));
            auditLogger.log(context, "scanner.scan.cancel", id.toString(), response.cancelled() ? 1 : 0,
                    latencyMs(started), "success", Map.of("status", response.status()), null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "scanner.scan.cancel", McpAuditLogger.hashQuery(scanRunId), 0,
                    latencyMs(started), "error", Map.of(), e.getClass().getSimpleName());
            throw e;
        }
    }

    public ScannerScanCancelResponse cancel(String scanRunId, String reason) {
        return cancel(scanRunId, null, reason);
    }

    private static void requireScope(McpClientContext context, String scope) {
        if (!context.hasScope(scope)) {
            throw new McpAccessException("Missing MCP scope: " + scope);
        }
    }

    private static long latencyMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private void requireAsyncService() {
        if (scannerAsyncService == null) {
            throw new IllegalStateException("scanner async service is not configured");
        }
    }

    private static int clampDiagnosticLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 25;
        }
        return Math.min(limit, 100);
    }

    private java.util.Optional<ScannerScanStatusResponse> status(McpClientContext context, String projectKey,
            UUID scanRunId) {
        if (projectKey != null && !projectKey.isBlank()) {
            return scannerAsyncService.status(McpProjectKeys.effective(context, projectKey), scanRunId);
        }
        if (McpProjectKeys.isLocalTrust(context)) {
            return scannerAsyncService.status(scanRunId);
        }
        return scannerAsyncService.status(context.projectKey(), scanRunId);
    }

    private java.util.Optional<ScannerScanResultResponse> result(McpClientContext context, String projectKey,
            UUID scanRunId, int diagnosticLimit) {
        if (projectKey != null && !projectKey.isBlank()) {
            return scannerAsyncService.result(McpProjectKeys.effective(context, projectKey), scanRunId, diagnosticLimit);
        }
        if (McpProjectKeys.isLocalTrust(context)) {
            return scannerAsyncService.result(scanRunId, diagnosticLimit);
        }
        return scannerAsyncService.result(context.projectKey(), scanRunId, diagnosticLimit);
    }

    private java.util.Optional<ScannerScanCancelResponse> cancel(McpClientContext context, String projectKey,
            UUID scanRunId, String reason) {
        if (projectKey != null && !projectKey.isBlank()) {
            return scannerAsyncService.cancel(McpProjectKeys.effective(context, projectKey), scanRunId, reason);
        }
        if (McpProjectKeys.isLocalTrust(context)) {
            return scannerAsyncService.cancel(scanRunId, reason);
        }
        return scannerAsyncService.cancel(context.projectKey(), scanRunId, reason);
    }

    private static Map<String, Object> metadata(Boolean force, Boolean forceReindex, String providerOverride,
            String semanticModel, String projectKey, List<String> includePaths, List<String> excludePaths,
            Integer maxSemanticFiles, Integer maxSemanticSymbols, Boolean semanticEnabled) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("force", Boolean.TRUE.equals(force));
        metadata.put("forceReindex", Boolean.TRUE.equals(forceReindex));
        metadata.put("projectKey", projectKey);
        if (providerOverride != null) {
            metadata.put("providerOverride", providerOverride);
        }
        if (semanticModel != null && !semanticModel.isBlank()) {
            metadata.put("semanticModel", semanticModel.trim());
        }
        if (includePaths != null && !includePaths.isEmpty()) {
            metadata.put("includePaths", includePaths);
        }
        if (excludePaths != null && !excludePaths.isEmpty()) {
            metadata.put("excludePaths", excludePaths);
        }
        if (maxSemanticFiles != null) {
            metadata.put("maxSemanticFiles", maxSemanticFiles);
        }
        if (maxSemanticSymbols != null) {
            metadata.put("maxSemanticSymbols", maxSemanticSymbols);
        }
        if (semanticEnabled != null) {
            metadata.put("semanticEnabled", semanticEnabled);
        }
        return metadata;
    }
}
