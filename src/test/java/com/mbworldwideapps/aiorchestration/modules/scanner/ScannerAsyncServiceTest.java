package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder;
import org.junit.jupiter.api.Test;

class ScannerAsyncServiceTest {

    @Test
    void startRunsScannerInBackgroundAndReturnsResult() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        doAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            repository.startRun(request.scanRunId(), request.projectKey(), request.rootPath(), "codex",
                    "gpt-5", "scanner-semantic-v1", true, Map.of("semanticEnabled", true));
            repository.completeRun(request.scanRunId(), "completed", 7, 5, 2, 0, 0,
                    Map.of("memoryIds", List.of(UUID.randomUUID().toString())));
            return new ScanCodebaseResponse(request.rootPath(), request.projectKey(), 7, 5, 2, 0, 0,
                    2.0 / 7.0, 10L, List.of());
        }).when(scannerService).scan(any());
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);

        ScannerScanStartResponse start = service.start(new ScanCodebaseRequest("/repo", "PROJECT_A",
                false, "codex", "gpt-5", false));
        waitUntil(() -> service.status("PROJECT_A", start.scanRunId())
                .map(ScannerScanStatusResponse::done).orElse(false));
        ScannerScanResultResponse result = service.result("PROJECT_A", start.scanRunId(), 25).orElseThrow();

        assertThat(start.status()).isEqualTo("queued");
        assertThat(result.done()).isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.result().filesDiscovered()).isEqualTo(7);
        service.shutdown();
    }

    @Test
    void repeatedStartForSameProjectReturnsExistingRunWithoutStartingSecondWorker() throws Exception {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            invocationCount.incrementAndGet();
            repository.startRun(request.scanRunId(), request.projectKey(), request.rootPath(), "codex",
                    "gpt-5", "scanner-semantic-v1", true, Map.of("force", false));
            workerEntered.countDown();
            if (!releaseWorker.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("worker was not released");
            }
            repository.completeRun(request.scanRunId(), "completed", 1, 1, 0, 0, 0, Map.of());
            return new ScanCodebaseResponse(request.rootPath(), request.projectKey(), 1, 1, 0, 0, 0,
                    0.0, 10L, List.of());
        }).when(scannerService).scan(any());
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);
        try {
            ScanCodebaseRequest request = new ScanCodebaseRequest("/repo", "PROJECT_A",
                    false, "codex", "gpt-5", false);
            ScannerScanStartResponse first = service.start(request);
            assertThat(workerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            ScannerScanStartResponse repeated = service.start(request);

            assertThat(repeated.scanRunId()).isEqualTo(first.scanRunId());
            assertThat(repeated.message()).contains("existing scanRunId");
            assertThat(runs).hasSize(1);
            assertThat(invocationCount).hasValue(1);
            releaseWorker.countDown();
            waitUntil(() -> service.status("PROJECT_A", first.scanRunId())
                    .map(ScannerScanStatusResponse::done).orElse(false));
        } finally {
            releaseWorker.countDown();
            service.shutdown();
        }
    }

    @Test
    void throwableFailureMarksRunFailed() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        doAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            repository.startRun(request.scanRunId(), request.projectKey(), request.rootPath(), "codex",
                    "", "scanner-semantic-v1", false, Map.of());
            throw new AssertionError("boom");
        }).when(scannerService).scan(any());
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);

        ScannerScanStartResponse start = service.start(new ScanCodebaseRequest("/repo", "PROJECT_A",
                false, "codex", null, false));
        waitUntil(() -> service.status("PROJECT_A", start.scanRunId())
                .map(status -> "failed".equals(status.status())).orElse(false));
        ScannerScanStatusResponse status = service.status("PROJECT_A", start.scanRunId()).orElseThrow();

        assertThat(status.status()).isEqualTo("failed");
        assertThat(status.metadata()).containsEntry("error", "AssertionError");
        service.shutdown();
    }

    @Test
    void startPropagatesMcpContextIntoWorkerThread() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        AtomicReference<McpClientContext> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            observed.set(McpClientContextHolder.get());
            repository.startRun(request.scanRunId(), request.projectKey(), request.rootPath(), "codex",
                    "gpt-5", "scanner-semantic-v1", true, Map.of("semanticEnabled", true));
            repository.completeRun(request.scanRunId(), "completed", 1, 1, 0, 0, 0, Map.of());
            return new ScanCodebaseResponse(request.rootPath(), request.projectKey(), 1, 1, 0, 0, 0,
                    0.0, 10L, List.of());
        }).when(scannerService).scan(any());
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);

        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "local",
                List.of("scanner.scan", "scanner.insights.write-memory")));
        ScannerScanStartResponse start;
        try {
            start = service.start(new ScanCodebaseRequest("/repo", "PROJECT_A",
                    false, "codex", "gpt-5", false));
        } finally {
            McpClientContextHolder.clear();
        }
        waitUntil(() -> service.status("PROJECT_A", start.scanRunId())
                .map(ScannerScanStatusResponse::done).orElse(false));

        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().hasScope("scanner.insights.write-memory")).isTrue();
        assertThat(McpClientContextHolder.get()).isNull();
        service.shutdown();
    }

    @Test
    void startupReaperFailsIncompleteRuns() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerAsyncService service = new ScannerAsyncService(mock(ScannerService.class), repository);
        UUID runId = UUID.randomUUID();
        repository.queueRun(runId, "PROJECT_A", "/repo", "codex", "", Map.of());

        service.failInterruptedRunsOnStartup();

        assertThat(repository.findRun("PROJECT_A", runId).orElseThrow().status()).isEqualTo("failed");
        service.shutdown();
    }

    @Test
    void cancelMarksRunCancelled() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerAsyncService service = new ScannerAsyncService(mock(ScannerService.class), repository);
        UUID runId = UUID.randomUUID();
        repository.queueRun(runId, "PROJECT_A", "/repo", "codex", "", Map.of());

        ScannerScanCancelResponse response = service.cancel("PROJECT_A", runId, "user requested").orElseThrow();

        assertThat(response.cancelled()).isTrue();
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(repository.findRun("PROJECT_A", runId).orElseThrow().status()).isEqualTo("cancelled");
        service.shutdown();
    }

    @Test
    void semanticEnabledFalseSurvivesAsyncRunReconstruction() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        AtomicReference<ScanCodebaseRequest> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            observed.set(request);
            repository.startRun(request.scanRunId(), request.projectKey(), request.rootPath(), "local-qwen",
                    "qwen3:8b", "scanner-semantic-v1", false, Map.of());
            repository.completeRun(request.scanRunId(), "completed", 1, 1, 0, 0, 0, Map.of());
            return new ScanCodebaseResponse(request.rootPath(), request.projectKey(), 1, 1, 0, 0, 0,
                    0.0, 10L, List.of());
        }).when(scannerService).scan(any());
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);

        ScannerScanStartResponse start = service.start(new ScanCodebaseRequest("/repo", "PROJECT_A", false,
                "local-qwen", "qwen3:8b", false, null, null, null, null, null, null, false));
        waitUntil(() -> service.status("PROJECT_A", start.scanRunId())
                .map(ScannerScanStatusResponse::done).orElse(false));

        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().semanticEnabled()).isEqualTo(false);
        service.shutdown();
    }

    @Test
    void semanticEnabledFalseSurfacesInQueuedMetadata() {
        Map<UUID, CodeScanRunRecord> runs = new ConcurrentHashMap<>();
        CodeBaselineRepository repository = repository(runs);
        ScannerService scannerService = mock(ScannerService.class);
        ScannerAsyncService service = new ScannerAsyncService(scannerService, repository);

        ScannerScanStartResponse start = service.start(new ScanCodebaseRequest("/repo", "PROJECT_A", false,
                "local-qwen", "qwen3:8b", false, null, null, null, null, null, null, false));

        assertThat(service.status("PROJECT_A", start.scanRunId()).orElseThrow().metadata())
                .containsEntry("semanticEnabled", false);
        service.shutdown();
    }

    private static CodeBaselineRepository repository(Map<UUID, CodeScanRunRecord> runs) {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        when(repository.findRun(anyString(), any())).thenAnswer(invocation -> {
            String projectKey = invocation.getArgument(0);
            UUID scanRunId = invocation.getArgument(1);
            return Optional.ofNullable(runs.get(scanRunId))
                    .filter(run -> projectKey.equals(run.projectKey()));
        });
        when(repository.diagnosticsForRun(anyString(), any(), anyInt())).thenReturn(List.of());
        when(repository.failIncompleteRuns(anyString())).thenAnswer(invocation -> {
            String reason = invocation.getArgument(0);
            AtomicInteger updated = new AtomicInteger();
            runs.values().stream()
                    .filter(run -> run.status().equals("queued") || run.status().equals("running"))
                    .toList()
                    .forEach(run -> {
                        runs.put(run.id(), copy(run, "failed", Map.of("reason", reason)));
                        updated.incrementAndGet();
                    });
            return updated.get();
        });
        when(repository.cancelRun(anyString(), any(), anyString())).thenAnswer(invocation -> {
            String projectKey = invocation.getArgument(0);
            UUID scanRunId = invocation.getArgument(1);
            String reason = invocation.getArgument(2);
            CodeScanRunRecord run = runs.get(scanRunId);
            if (run == null || !projectKey.equals(run.projectKey())
                    || !(run.status().equals("queued") || run.status().equals("running"))) {
                return false;
            }
            runs.put(scanRunId, copy(run, "cancelled", Map.of("reason", reason)));
            return true;
        });
        doAnswer(invocation -> {
            UUID scanRunId = invocation.getArgument(0);
            String projectKey = invocation.getArgument(1);
            String rootPath = invocation.getArgument(2);
            String provider = invocation.getArgument(3);
            String semanticModel = invocation.getArgument(4);
            Map<String, Object> metadata = invocation.getArgument(5);
            runs.put(scanRunId, new CodeScanRunRecord(scanRunId, projectKey, rootPath, "queued",
                    Instant.now(), null, provider, semanticModel, false, 0, 0, 0, 0, 0, metadata));
            return null;
        }).when(repository).queueRun(any(), anyString(), anyString(), anyString(), any(), any());
        doAnswer(invocation -> {
            UUID scanRunId = invocation.getArgument(0);
            String projectKey = invocation.getArgument(1);
            String rootPath = invocation.getArgument(2);
            String provider = invocation.getArgument(3);
            String semanticModel = invocation.getArgument(4);
            boolean dataEgress = invocation.getArgument(6);
            Map<String, Object> metadata = invocation.getArgument(7);
            runs.put(scanRunId, new CodeScanRunRecord(scanRunId, projectKey, rootPath, "running",
                    Instant.now(), null, provider, semanticModel, dataEgress, 0, 0, 0, 0, 0, metadata));
            return null;
        }).when(repository).startRun(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyBoolean(), any());
        doAnswer(invocation -> {
            UUID scanRunId = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            CodeScanRunRecord run = runs.get(scanRunId);
            if (run == null || run.status().equals("cancelled")) {
                return null;
            }
            runs.put(scanRunId, new CodeScanRunRecord(scanRunId, run.projectKey(), run.rootPath(), status,
                    run.startedAt(), Instant.now(), run.provider(), run.semanticModel(), run.dataEgress(),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(5), invocation.getArgument(6), invocation.getArgument(7)));
            return null;
        }).when(repository).completeRun(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        return repository;
    }

    private static CodeScanRunRecord copy(CodeScanRunRecord run, String status, Map<String, Object> metadata) {
        return new CodeScanRunRecord(run.id(), run.projectKey(), run.rootPath(), status, run.startedAt(),
                Instant.now(), run.provider(), run.semanticModel(), run.dataEgress(), run.filesDiscovered(),
                run.filesScanned(), run.filesSkipped(), run.filesRejected(), run.candidatesCreated(), metadata);
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition was not met before timeout");
    }
}
