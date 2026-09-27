package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ScannerAsyncService {

    private static final Logger log = LoggerFactory.getLogger(ScannerAsyncService.class);
    private static final int MAX_CONCURRENT_RUNS = 2;
    private static final int MAX_QUEUED_RUNS = 20;

    private final ScannerService scannerService;
    private final CodeBaselineRepository repository;
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, ActiveProjectRun> activeRunsByProject = new ConcurrentHashMap<>();

    public ScannerAsyncService(ScannerService scannerService, CodeBaselineRepository repository) {
        this.scannerService = scannerService;
        this.repository = repository;
        this.executor = new ThreadPoolExecutor(MAX_CONCURRENT_RUNS, MAX_CONCURRENT_RUNS, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUED_RUNS), runnable -> {
            Thread thread = new Thread(runnable, "scanner-async");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void failInterruptedRunsOnStartup() {
        int failed = repository.failIncompleteRuns("interrupted_by_restart");
        if (failed > 0) {
            log.warn("Marked stale async scanner runs as failed on startup: count={}", failed);
        }
    }

    public synchronized ScannerScanStartResponse start(ScanCodebaseRequest request) {
        String projectKey = blankToNull(request.projectKey());
        if (projectKey == null) {
            throw new IllegalArgumentException("projectKey is required for async scanner runs");
        }
        String rootPath = blankToNull(request.rootPath()) == null ? "." : request.rootPath().trim();
        ActiveProjectRun existing = activeRunsByProject.get(projectKey);
        if (existing != null) {
            return existingStartResponse(existing, request, rootPath, projectKey);
        }
        UUID scanRunId = UUID.randomUUID();
        ActiveProjectRun activeRun = new ActiveProjectRun(scanRunId);
        activeRunsByProject.put(projectKey, activeRun);
        String provider = blankToNull(request.providerOverride()) == null ? "default" : request.providerOverride().trim();
        String semanticModel = blankToNull(request.semanticModel());
        boolean force = Boolean.TRUE.equals(request.force());
        boolean forceReindex = request.forceReindexEnabled();
        McpClientContext context = McpClientContextHolder.get();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("async", true);
        metadata.put("force", force);
        metadata.put("forceReindex", forceReindex);
        putScopeMetadata(metadata, request);
        try {
            repository.queueRun(scanRunId, projectKey, rootPath, provider, semanticModel, metadata);
        } catch (RuntimeException e) {
            activeRunsByProject.remove(projectKey, activeRun);
            throw e;
        }
        FutureTask<Void> future = new FutureTask<>(() -> {
            if (!activeRun.markStarted()) {
                return null;
            }
            if (context != null) {
                McpClientContextHolder.set(context);
            }
            try {
                run(scanRunId, request);
                return null;
            } finally {
                McpClientContextHolder.clear();
                activeRunsByProject.remove(projectKey, activeRun);
            }
        });
        futures.put(scanRunId, future);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException e) {
            futures.remove(scanRunId, future);
            activeRunsByProject.remove(projectKey, activeRun);
            repository.completeRun(scanRunId, "failed", 0, 0, 0, 0, 0,
                    Map.of("async", true, "error", "scanner_async_queue_full"));
            throw new IllegalStateException("scanner async queue is full", e);
        }
        if (future.isDone()) {
            futures.remove(scanRunId, future);
        }
        return new ScannerScanStartResponse(scanRunId, "queued", rootPath, projectKey, force,
                request.providerOverride(), semanticModel, forceReindex,
                "Scan queued. Poll scanner.scan.status with this scanRunId.");
    }

    private ScannerScanStartResponse existingStartResponse(ActiveProjectRun existing, ScanCodebaseRequest request,
            String requestedRootPath, String projectKey) {
        CodeScanRunRecord run = repository.findRun(projectKey, existing.scanRunId()).orElse(null);
        String status = run == null ? "queued" : run.status();
        String rootPath = run == null ? requestedRootPath : run.rootPath();
        String provider = run == null ? request.providerOverride() : defaultToNull(run.provider());
        String semanticModel = run == null ? request.semanticModel() : run.semanticModel();
        boolean force = run == null ? Boolean.TRUE.equals(request.force())
                : booleanMetadata(run.metadata(), "force");
        boolean forceReindex = run == null ? request.forceReindexEnabled()
                : booleanMetadata(run.metadata(), "forceReindex");
        return new ScannerScanStartResponse(existing.scanRunId(), status, rootPath, projectKey, force,
                provider, semanticModel, forceReindex,
                "A scan for this project is already queued or running; returning its existing scanRunId.");
    }

    public Optional<ScannerScanStatusResponse> status(String projectKey, UUID scanRunId) {
        return repository.findRun(projectKey, scanRunId).map(this::toStatus);
    }

    public Optional<ScannerScanStatusResponse> status(UUID scanRunId) {
        return repository.findRun(scanRunId).map(this::toStatus);
    }

    public Optional<ScannerScanResultResponse> result(String projectKey, UUID scanRunId, int diagnosticLimit) {
        return repository.findRun(projectKey, scanRunId)
                .map(run -> toResult(run, repository.diagnosticsForRun(projectKey, scanRunId, diagnosticLimit)));
    }

    public Optional<ScannerScanResultResponse> result(UUID scanRunId, int diagnosticLimit) {
        return repository.findRun(scanRunId)
                .map(run -> toResult(run, repository.diagnosticsForRun(run.projectKey(), scanRunId, diagnosticLimit)));
    }

    public List<CodeDiagnosticRecord> diagnostics(String projectKey, UUID scanRunId, int limit) {
        return repository.diagnosticsForRun(projectKey, scanRunId, limit);
    }

    public Optional<ScannerScanCancelResponse> cancel(String projectKey, UUID scanRunId, String reason) {
        Optional<CodeScanRunRecord> existing = repository.findRun(projectKey, scanRunId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CodeScanRunRecord run = existing.get();
        if (done(run.status())) {
            return Optional.of(new ScannerScanCancelResponse(scanRunId, run.status(), false,
                    "Scan is already finished."));
        }
        Future<?> future = futures.remove(scanRunId);
        ActiveProjectRun activeRun = activeRunsByProject.get(projectKey);
        boolean cancelledBeforeStart = activeRun != null
                && activeRun.scanRunId().equals(scanRunId)
                && activeRun.cancelBeforeStart();
        if (future != null) {
            future.cancel(true);
        }
        if (cancelledBeforeStart) {
            activeRunsByProject.remove(projectKey, activeRun);
        }
        boolean cancelled = repository.cancelRun(projectKey, scanRunId, reason);
        String status = repository.findRun(projectKey, scanRunId).map(CodeScanRunRecord::status)
                .orElse(run.status());
        return Optional.of(new ScannerScanCancelResponse(scanRunId, status, cancelled,
                cancelled ? "Scan cancellation requested." : "Scan could not be cancelled."));
    }

    public Optional<ScannerScanCancelResponse> cancel(UUID scanRunId, String reason) {
        return repository.findRun(scanRunId)
                .flatMap(run -> cancel(run.projectKey(), scanRunId, reason));
    }

    private void run(UUID scanRunId, ScanCodebaseRequest request) {
        try {
            scannerService.scan(new ScanCodebaseRequest(request.rootPath(), request.projectKey(), request.force(),
                    request.providerOverride(), request.semanticModel(), request.forceReindex(), scanRunId,
                    request.includePaths(), request.excludePaths(), request.maxSemanticFiles(),
                    request.maxSemanticSymbols(), request.maxSemanticFlows(), request.semanticEnabled()));
        } catch (Throwable e) {
            log.warn("Async scanner run failed: scanRunId={} rootPath={}", scanRunId, request.rootPath(), e);
            repository.findRun(request.projectKey(), scanRunId)
                    .filter(run -> !done(run.status()))
                    .ifPresent(run -> repository.completeRun(scanRunId, "failed", run.filesDiscovered(),
                            run.filesScanned(), run.filesSkipped(), run.filesRejected(), run.candidatesCreated(),
                            Map.of("async", true, "error", e.getClass().getSimpleName(), "message",
                                    e.getMessage() == null ? "" : e.getMessage())));
        } finally {
            futures.remove(scanRunId);
        }
    }

    private ScannerScanStatusResponse toStatus(CodeScanRunRecord run) {
        boolean done = done(run.status());
        return new ScannerScanStatusResponse(run.id(), run.status(), run.rootPath(), run.projectKey(),
                run.provider(), run.semanticModel(), run.dataEgress(), run.startedAt(), run.completedAt(),
                run.filesDiscovered(), run.filesScanned(), run.filesSkipped(), run.filesRejected(),
                run.candidatesCreated(), done, "completed".equals(run.status()), run.metadata());
    }

    private ScannerScanResultResponse toResult(CodeScanRunRecord run, List<CodeDiagnosticRecord> diagnostics) {
        ScanCodebaseResponse result = null;
        if (done(run.status())) {
            result = new ScanCodebaseResponse(run.rootPath(), run.projectKey(), run.filesDiscovered(),
                    run.filesScanned(), run.filesSkipped(), run.filesRejected(), run.candidatesCreated(),
                    unchangedSkipRate(run), durationMs(run), memoryIds(run.metadata()), run.id());
        }
        return new ScannerScanResultResponse(run.id(), run.status(), done(run.status()), result, diagnostics,
                run.metadata());
    }

    private static boolean done(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static double unchangedSkipRate(CodeScanRunRecord run) {
        return run.filesDiscovered() == 0 ? 0.0 : (double) run.filesSkipped() / (double) run.filesDiscovered();
    }

    private static long durationMs(CodeScanRunRecord run) {
        if (run.completedAt() == null || run.startedAt() == null) {
            return 0L;
        }
        return Duration.between(run.startedAt(), run.completedAt()).toMillis();
    }

    private static List<UUID> memoryIds(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("memoryIds");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof String)
                .map(item -> parseUuid((String) item))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String defaultToNull(String provider) {
        return "default".equals(provider) ? null : blankToNull(provider);
    }

    private static boolean booleanMetadata(Map<String, Object> metadata, String key) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(key));
    }

    private static void putScopeMetadata(Map<String, Object> metadata, ScanCodebaseRequest request) {
        if (request.includePaths() != null && !request.includePaths().isEmpty()) {
            metadata.put("includePaths", request.includePaths());
        }
        if (request.excludePaths() != null && !request.excludePaths().isEmpty()) {
            metadata.put("excludePaths", request.excludePaths());
        }
        if (request.maxSemanticFiles() != null) {
            metadata.put("maxSemanticFiles", request.maxSemanticFiles());
        }
        if (request.maxSemanticSymbols() != null) {
            metadata.put("maxSemanticSymbols", request.maxSemanticSymbols());
        }
        if (request.maxSemanticFlows() != null) {
            metadata.put("maxSemanticFlows", request.maxSemanticFlows());
        }
        if (request.semanticEnabled() != null) {
            metadata.put("semanticEnabled", request.semanticEnabled());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class ActiveProjectRun {

        private final UUID scanRunId;
        private boolean started;
        private boolean cancelledBeforeStart;

        private ActiveProjectRun(UUID scanRunId) {
            this.scanRunId = scanRunId;
        }

        private UUID scanRunId() {
            return scanRunId;
        }

        private synchronized boolean markStarted() {
            if (cancelledBeforeStart) {
                return false;
            }
            started = true;
            return true;
        }

        private synchronized boolean cancelBeforeStart() {
            if (started) {
                return false;
            }
            cancelledBeforeStart = true;
            return true;
        }
    }
}
