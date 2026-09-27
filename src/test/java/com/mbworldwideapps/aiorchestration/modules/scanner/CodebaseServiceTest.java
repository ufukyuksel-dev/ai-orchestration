package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodebaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchUsesVectorIndexAndReportsStaleFilesystem() throws Exception {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CodeSemanticCapsuleRecord capsule = capsule(symbolId, fileId, "src/main/java/PaymentService.java",
                "PaymentService authorizes transfers", "authorize transfer limits");
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "PaymentService",
                "com.acme.PaymentService"));
        repository.capsules.put(capsule.id(), capsule);
        Path source = tempDir.resolve("src/main/java/PaymentService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class PaymentService {}");
        Instant completedAt = Instant.now().minusSeconds(120);
        Files.setLastModifiedTime(source, FileTime.from(Instant.now()));
        repository.latestRun = new CodeScanRunRecord(UUID.randomUUID(), "PROJECT_A", tempDir.toString(),
                "completed", completedAt.minusSeconds(10), completedAt, Map.of());
        FakeVectorIndex vectorIndex = new FakeVectorIndex(List.of(new ScoredCodeCapsuleRef(capsule.id(), 0.91)));

        CodebaseService.BaselineSearchResponse response = new CodebaseService(repository, vectorIndex)
                .search("transfer limits", "PROJECT_A", 5);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).score()).isEqualTo(0.91);
        assertThat(response.hits().get(0).fqn()).isEqualTo("com.acme.PaymentService");
        assertThat(response.metadata().source()).isEqualTo("vector");
        assertThat(response.metadata().scoreSource()).isEqualTo("vector_similarity");
        assertThat(response.metadata().degraded()).isFalse();
        assertThat(response.staleness().stale()).isTrue();
        assertThat(response.staleness().suggestScannerScan()).isTrue();
    }

    @Test
    void searchKeepsBaselineFreshWhenOnlyExcludedOrUnsupportedFilesAreNewer() throws Exception {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        Path generatedSource = tempDir.resolve("target/generated-sources/Generated.java");
        Path telemetryLog = tempDir.resolve("logs/telemetry.jsonl");
        Files.createDirectories(generatedSource.getParent());
        Files.createDirectories(telemetryLog.getParent());
        Files.writeString(generatedSource, "class Generated {}");
        Files.writeString(telemetryLog, "{}");
        Files.writeString(tempDir.resolve("README.md"), "new documentation");
        Instant completedAt = Instant.now().minusSeconds(60);
        repository.latestRun = new CodeScanRunRecord(UUID.randomUUID(), "PROJECT_A", tempDir.toString(),
                "completed", completedAt.minusSeconds(10), completedAt, Map.of());

        CodebaseService.BaselineSearchResponse response = new CodebaseService(repository,
                new FakeVectorIndex(List.of())).search("anything", "PROJECT_A", 5);

        assertThat(response.staleness().stale()).isFalse();
        assertThat(response.staleness().reason()).isEqualTo("fresh");
        assertThat(response.staleness().newestFileMtime()).isNull();
    }

    @Test
    void searchFallsBackToPostgresTextWhenVectorUnavailable() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CodeSemanticCapsuleRecord capsule = capsule(symbolId, fileId, "src/RefundService.java",
                "Refund workflow", "refund validation and posting");
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "RefundService", "com.acme.RefundService"));
        repository.capsules.put(capsule.id(), capsule);
        FakeVectorIndex vectorIndex = FakeVectorIndex.throwing();

        CodebaseService.BaselineSearchResponse response = new CodebaseService(repository, vectorIndex)
                .search("refund", "PROJECT_A", 5);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).capsuleId()).isEqualTo(capsule.id());
        assertThat(response.hits().get(0).score()).isEqualTo(0.35);
        assertThat(response.metadata().source()).isEqualTo("postgres_text");
        assertThat(response.metadata().scoreSource()).isEqualTo("postgres_text_synthetic");
        assertThat(response.metadata().degraded()).isTrue();
        assertThat(response.metadata().degradedReason()).isEqualTo("IllegalStateException");
    }

    @Test
    void searchRanksFlowCapsulesBeforeSymbolCapsulesEvenWithLowerVectorScore() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "CampaignController",
                "com.acme.CampaignController"));
        CodeSemanticCapsuleRecord symbolCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                "CampaignController count", "30 controllers exist");
        CodeSemanticCapsuleRecord flowCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                CodeFlowSeedBuilder.ENDPOINT_FLOW, "Campaign list flow",
                "GET /campaigns reads campaign service and Redis cache");
        repository.capsules.put(symbolCapsule.id(), symbolCapsule);
        repository.capsules.put(flowCapsule.id(), flowCapsule);
        FakeVectorIndex vectorIndex = new FakeVectorIndex(List.of(
                new ScoredCodeCapsuleRef(symbolCapsule.id(), 0.98),
                new ScoredCodeCapsuleRef(flowCapsule.id(), 0.72)));

        CodebaseService.BaselineSearchResponse response = new CodebaseService(repository, vectorIndex)
                .search("campaign redis", "PROJECT_A", 2);

        assertThat(response.hits()).extracting(CodebaseService.SearchHit::capsuleKind)
                .containsExactly(CodeFlowSeedBuilder.ENDPOINT_FLOW, "symbol_summary");
        assertThat(vectorIndex.lastTopK).isEqualTo(8);
    }

    @Test
    void postgresFallbackAlsoRanksFlowCapsulesFirst() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "CampaignController",
                "com.acme.CampaignController"));
        CodeSemanticCapsuleRecord symbolCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                "Campaign symbol", "campaign redis");
        CodeSemanticCapsuleRecord flowCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                CodeFlowSeedBuilder.ENDPOINT_FLOW, "Campaign flow", "campaign redis");
        repository.capsules.put(symbolCapsule.id(), symbolCapsule);
        repository.capsules.put(flowCapsule.id(), flowCapsule);

        CodebaseService.BaselineSearchResponse response = new CodebaseService(repository, FakeVectorIndex.throwing())
                .search("campaign redis", "PROJECT_A", 2);

        assertThat(response.hits()).extracting(CodebaseService.SearchHit::capsuleKind)
                .containsExactly(CodeFlowSeedBuilder.ENDPOINT_FLOW, "symbol_summary");
        assertThat(response.metadata().source()).isEqualTo("postgres_text");
    }

    @Test
    void searchClampsTopKToSingleServiceLimit() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        FakeVectorIndex vectorIndex = new FakeVectorIndex(List.of());

        new CodebaseService(repository, vectorIndex).search("anything", "PROJECT_A", 500);

        assertThat(vectorIndex.lastTopK).isEqualTo(50);
    }

    @Test
    void structuralSymbolIncludesScannedPathButNeverAnotherProjectsFile() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "PaymentService", "com.acme.PaymentService"));
        CodebaseService service = new CodebaseService(repository, new FakeVectorIndex(List.of()));
        assertThat(service.symbolGet("PaymentService", "PROJECT_A").symbols().getFirst().filePath()).isNull();
        repository.files.put(fileId, new CodeFileRecord(fileId, "PROJECT_A", "src/PaymentService.java", "hash",
                "java", UUID.randomUUID(), Map.of()));
        CodebaseService.SymbolDetail detail = service.symbolGet("PaymentService", "PROJECT_A").symbols().getFirst();
        assertThat(detail.filePath()).isEqualTo("src/PaymentService.java");
        assertThat(detail.symbol().startLine()).isEqualTo(10);
        assertThat(detail.symbol().endLine()).isEqualTo(20);
        assertThat(detail.capsules()).isEmpty();
        repository.files.put(fileId, new CodeFileRecord(fileId, "PROJECT_B", "private/Other.java", "hash",
                "java", UUID.randomUUID(), Map.of()));
        assertThat(service.symbolGet("PaymentService", "PROJECT_A").symbols().getFirst().filePath()).isNull();
        assertThat(repository.lastFileProject).isEqualTo("PROJECT_A");
    }

    @Test
    void symbolGetBoundsOutgoingEdgesAndReportsTruncation() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "PaymentService",
                "com.acme.PaymentService"));
        for (int i = 0; i < 12; i++) {
            repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", symbolId, null,
                    "target" + i, "CALLS", "syntactic", 0.65, UUID.randomUUID(), Map.of()));
        }

        CodebaseService service = new CodebaseService(repository, new FakeVectorIndex(List.of()));

        CodebaseService.SymbolDetail defaultDetail = service.symbolGet("PaymentService", "PROJECT_A")
                .symbols().getFirst();
        CodebaseService.SymbolDetail expandedDetail = service.symbolGet("PaymentService", "PROJECT_A", 20)
                .symbols().getFirst();

        assertThat(defaultDetail.edges()).hasSize(10);
        assertThat(defaultDetail.edgesTruncated()).isTrue();
        assertThat(expandedDetail.edges()).hasSize(12);
        assertThat(expandedDetail.edgesTruncated()).isFalse();
    }

    @Test
    void impactIncludesSyntacticTargetRefEdges() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID targetId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(targetId, symbol(targetId, fileId, "PaymentService", "com.acme.PaymentService"));
        repository.symbols.put(callerId, symbol(callerId, fileId, "CheckoutController",
                "com.acme.CheckoutController"));
        repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", callerId, null,
                "com.acme.PaymentService", "INJECTS", "syntactic", 0.8, UUID.randomUUID(), Map.of()));

        CodebaseService.ImpactAnalyzeResponse response = new CodebaseService(repository, new FakeVectorIndex(List.of()))
                .impact("PaymentService", "PROJECT_A");

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.targets()).extracting(CodebaseService.SymbolSummary::symbolId)
                .containsExactly(targetId);
        assertThat(response.resolvedImpact()).extracting(hit -> hit.symbol().symbolId())
                .containsExactly(callerId);
        assertThat(response.possibleImpact()).isEmpty();
        assertThat(response.evidence()).extracting(CodebaseService.EdgeSummary::targetRef)
                .containsExactly("com.acme.PaymentService");
        assertThat(repository.findSymbolsByIdsCalls).isEqualTo(1);
        assertThat(repository.findSymbolByIdCalls).isZero();
    }

    @Test
    void impactSeparatesNameOnlyCollisionFromResolvedImpact() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID targetId = UUID.randomUUID();
        UUID resolvedCallerId = UUID.randomUUID();
        UUID possibleCallerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(targetId, symbol(targetId, fileId, "save", "com.acme.PaymentRepository#save"));
        repository.symbols.put(resolvedCallerId, symbol(resolvedCallerId, fileId, "PaymentService",
                "com.acme.PaymentService"));
        repository.symbols.put(possibleCallerId, symbol(possibleCallerId, fileId, "AuditService",
                "com.acme.AuditService"));
        repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", resolvedCallerId, targetId,
                targetId.toString(), "CALLS", "resolved", 0.92, UUID.randomUUID(), Map.of()));
        repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", possibleCallerId, null,
                "save", "CALLS", "syntactic", 0.8, UUID.randomUUID(), Map.of()));

        CodebaseService.ImpactAnalyzeResponse response = new CodebaseService(repository, new FakeVectorIndex(List.of()))
                .impact("com.acme.PaymentRepository#save", "PROJECT_A");

        assertThat(response.resolvedImpact()).extracting(hit -> hit.symbol().symbolId())
                .containsExactly(resolvedCallerId);
        assertThat(response.resolvedImpact().getFirst().resolution()).isEqualTo("resolved");
        assertThat(response.possibleImpact()).extracting(hit -> hit.symbol().symbolId())
                .containsExactly(possibleCallerId);
        assertThat(response.possibleImpact().getFirst().resolution()).isEqualTo("syntactic_name_only");
        assertThat(response.possibleImpact().getFirst().rationale()).contains("name-only");
        assertThat(response.possibleImpact().getFirst().confidence()).isLessThan(0.5);
    }

    @Test
    void impactDedupsEvidenceAndBatchLoadsSourceSymbols() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID targetId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(targetId, symbol(targetId, fileId, "PaymentService", "com.acme.PaymentService"));
        repository.symbols.put(callerId, symbol(callerId, fileId, "CheckoutController",
                "com.acme.CheckoutController"));
        CodeEdgeRecord duplicateMatchedEdge = new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", callerId,
                targetId, "com.acme.PaymentService", "INJECTS", "resolved", 0.9, UUID.randomUUID(), Map.of());
        repository.edges.add(duplicateMatchedEdge);

        CodebaseService.ImpactAnalyzeResponse response = new CodebaseService(repository, new FakeVectorIndex(List.of()))
                .impact("PaymentService", "PROJECT_A");

        assertThat(response.resolvedImpact()).hasSize(1);
        assertThat(response.evidence()).hasSize(1);
        assertThat(repository.findSymbolsByIdsCalls).isEqualTo(1);
        assertThat(repository.findSymbolByIdCalls).isZero();
    }

    @Test
    void neighborsAcceptsFqnRefAndReturnsBoundedGraph() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID rootId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(rootId, symbol(rootId, fileId, "CheckoutController",
                "com.acme.CheckoutController"));
        repository.symbols.put(neighborId, symbol(neighborId, fileId, "PaymentService",
                "com.acme.PaymentService"));
        repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", rootId, neighborId,
                neighborId.toString(), "CALLS", "resolved", 0.9, UUID.randomUUID(), Map.of()));

        CodebaseService.SymbolNeighborsResponse response = new CodebaseService(repository,
                new FakeVectorIndex(List.of()))
                .neighbors("com.acme.CheckoutController", 1, null, "PROJECT_A");

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.nodes()).extracting(CodebaseService.SymbolSummary::symbolId)
                .containsExactly(rootId, neighborId);
        assertThat(response.edges()).hasSize(1);
        assertThat(response.truncated()).isFalse();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void neighborsReportsEdgeTruncation() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID rootId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(rootId, symbol(rootId, fileId, "Hub", "com.acme.Hub"));
        for (int i = 0; i < 120; i++) {
            UUID neighborId = UUID.randomUUID();
            repository.symbols.put(neighborId, symbol(neighborId, fileId, "Leaf" + i,
                    "com.acme.Leaf" + i));
            repository.edges.add(new CodeEdgeRecord(UUID.randomUUID(), "PROJECT_A", rootId, neighborId,
                    neighborId.toString(), "CALLS", "resolved", 0.8, UUID.randomUUID(), Map.of()));
        }

        CodebaseService.SymbolNeighborsResponse response = new CodebaseService(repository,
                new FakeVectorIndex(List.of()))
                .neighbors("com.acme.Hub", 1, null, "PROJECT_A");

        assertThat(response.edgeTruncated()).isTrue();
        assertThat(response.truncated()).isTrue();
        assertThat(response.edges()).hasSizeLessThanOrEqualTo(500);
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("per-direction limit"));
    }

    @Test
    void diagnoseCombinesSearchDiagnosticsAndChangedFiles() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "LedgerService", "com.acme.LedgerService"));
        CodeSemanticCapsuleRecord capsule = capsule(symbolId, fileId, "src/LedgerService.java",
                "Ledger posting retry", "timeout while posting ledger entry");
        repository.capsules.put(capsule.id(), capsule);
        repository.diagnostics.add(new CodeDiagnosticRecord(UUID.randomUUID(), "PROJECT_A", UUID.randomUUID(),
                "WARN", "scanner_semantic_failed", "semantic timeout", "src/LedgerService.java", symbolId,
                Map.of()));

        CodebaseService.DiagnoseResponse response = new CodebaseService(repository, new FakeVectorIndex(List.of()))
                .diagnose("ledger timeout", null, null, List.of("src/LedgerService.java"), "PROJECT_A");

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.suspects()).hasSize(1);
        assertThat(response.suspects().get(0).confidence()).isGreaterThan(0.35);
        assertThat(response.diagnostics()).hasSize(1);
    }

    @Test
    void diagnoseKeepsFlowCapsuleSuspectsBeforeSymbolSuspects() {
        FakeCodeBaselineRepository repository = new FakeCodeBaselineRepository();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.symbols.put(symbolId, symbol(symbolId, fileId, "CampaignController",
                "com.acme.CampaignController"));
        CodeSemanticCapsuleRecord symbolCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                "CampaignController", "controller symbol summary");
        CodeSemanticCapsuleRecord flowCapsule = capsule(symbolId, fileId, "src/CampaignController.java",
                CodeFlowSeedBuilder.ENDPOINT_FLOW, "Campaign Redis flow", "campaigns come from Redis first");
        repository.capsules.put(symbolCapsule.id(), symbolCapsule);
        repository.capsules.put(flowCapsule.id(), flowCapsule);
        FakeVectorIndex vectorIndex = new FakeVectorIndex(List.of(
                new ScoredCodeCapsuleRef(symbolCapsule.id(), 0.95),
                new ScoredCodeCapsuleRef(flowCapsule.id(), 0.60)));

        CodebaseService.DiagnoseResponse response = new CodebaseService(repository, vectorIndex)
                .diagnose("campaign redis", null, null, List.of(), "PROJECT_A");

        assertThat(response.suspects()).extracting(CodebaseService.DiagnosisSuspect::reason)
                .containsExactly("Campaign Redis flow", "CampaignController");
    }

    private static CodeSymbolRecord symbol(UUID id, UUID fileId, String name, String fqn) {
        return new CodeSymbolRecord(id, "PROJECT_A", fileId, "class", name, fqn, "", "service", 10, 20,
                "hash", UUID.randomUUID(), Map.of());
    }

    private static CodeSemanticCapsuleRecord capsule(UUID symbolId, UUID fileId, String filePath, String summary,
            String text) {
        return capsule(symbolId, fileId, filePath, "symbol_summary", summary, text);
    }

    private static CodeSemanticCapsuleRecord capsule(UUID symbolId, UUID fileId, String filePath, String kind,
            String summary, String text) {
        return new CodeSemanticCapsuleRecord(UUID.randomUUID(), "PROJECT_A", symbolId, fileId, kind,
                summary, text, "local-qwen", "qwen3:8b", "scanner-semantic-v1", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), false, 0.7, UUID.randomUUID(),
                Map.of("filePath", filePath, "lineStart", 10, "lineEnd", 20));
    }

    private static final class FakeVectorIndex implements CodeBaselineVectorIndex {
        private final List<ScoredCodeCapsuleRef> results;
        private final boolean throwOnSearch;
        private int lastTopK;

        private FakeVectorIndex(List<ScoredCodeCapsuleRef> results) {
            this(results, false);
        }

        private FakeVectorIndex(List<ScoredCodeCapsuleRef> results, boolean throwOnSearch) {
            this.results = results;
            this.throwOnSearch = throwOnSearch;
        }

        static FakeVectorIndex throwing() {
            return new FakeVectorIndex(List.of(), true);
        }

        @Override
        public void upsert(CodeSemanticCapsuleRecord capsule) {
        }

        @Override
        public List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey) {
            this.lastTopK = topK;
            if (throwOnSearch) {
                throw new IllegalStateException("qdrant unavailable");
            }
            return results;
        }
    }

    private static final class FakeCodeBaselineRepository implements CodeBaselineRepository {
        private final Map<UUID, CodeSymbolRecord> symbols = new HashMap<>();
        private final Map<UUID, CodeFileRecord> files = new HashMap<>();
        private String lastFileProject;

        @Override
        public Optional<CodeFileRecord> findFileById(String projectKey, UUID fileId) {
            lastFileProject = projectKey;
            return Optional.ofNullable(files.get(fileId));
        }
        private final Map<UUID, CodeSemanticCapsuleRecord> capsules = new HashMap<>();
        private final List<CodeEdgeRecord> edges = new ArrayList<>();
        private final List<CodeDiagnosticRecord> diagnostics = new ArrayList<>();
        private CodeScanRunRecord latestRun;
        private int findSymbolByIdCalls;
        private int findSymbolsByIdsCalls;

        @Override
        public void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider,
                String semanticModel, Map<String, Object> metadata) {
        }

        @Override
        public void startRun(UUID scanRunId, String projectKey, String rootPath, String provider,
                String semanticModel, String promptVersion, boolean dataEgress, Map<String, Object> metadata) {
        }

        @Override
        public void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned,
                int filesSkipped, int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
        }

        @Override
        public int failIncompleteRuns(String reason) {
            return 0;
        }

        @Override
        public boolean cancelRun(String projectKey, UUID scanRunId, String reason) {
            return false;
        }

        @Override
        public void upsertFile(CodeFileRecord file) {
        }

        @Override
        public void deleteFileFacts(String projectKey, UUID fileId) {
        }

        @Override
        public void deleteFilesForRootNotIn(String projectKey, String rootPath, Set<String> keepRelativePaths) {
        }

        @Override
        public void upsertSymbol(CodeSymbolRecord symbol) {
            symbols.put(symbol.id(), symbol);
        }

        @Override
        public void upsertEdge(CodeEdgeRecord edge) {
            edges.add(edge);
        }

        @Override
        public boolean capsuleExists(String projectKey, String targetKey, String capsuleKind, String provider,
                String semanticModel, String promptVersion, String summarizerInputHash) {
            return false;
        }

        @Override
        public void upsertCapsule(CodeSemanticCapsuleRecord capsule) {
            capsules.put(capsule.id(), capsule);
        }

        @Override
        public void insertDiagnostic(CodeDiagnosticRecord diagnostic) {
            diagnostics.add(diagnostic);
        }

        @Override
        public Optional<CodeSemanticCapsuleRecord> findCapsuleById(UUID capsuleId) {
            return Optional.ofNullable(capsules.get(capsuleId));
        }

        @Override
        public List<CodeSemanticCapsuleRecord> findCapsulesBySymbolId(String projectKey, UUID symbolId) {
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()))
                    .filter(capsule -> symbolId.equals(capsule.symbolId()))
                    .toList();
        }

        @Override
        public List<CodeSemanticCapsuleRecord> searchCapsulesText(String projectKey, String query, int limit) {
            String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()))
                    .filter(capsule -> capsule.summary().toLowerCase(java.util.Locale.ROOT).contains(needle)
                            || capsule.text().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeSemanticCapsuleRecord> findCapsulesForRoot(String projectKey, String rootPath, int limit) {
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<CodeSymbolRecord> findSymbolById(UUID symbolId) {
            findSymbolByIdCalls++;
            return Optional.ofNullable(symbols.get(symbolId));
        }

        @Override
        public List<CodeSymbolRecord> findSymbolsByIds(String projectKey, Set<UUID> symbolIds, int limit) {
            findSymbolsByIdsCalls++;
            if (symbolIds == null || symbolIds.isEmpty()) {
                return List.of();
            }
            return symbols.values().stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .filter(symbol -> symbolIds.contains(symbol.id()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeSymbolRecord> findSymbolsByRef(String projectKey, String ref, int limit) {
            UUID id = parseUuid(ref);
            return symbols.values().stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .filter(symbol -> (id != null && id.equals(symbol.id()))
                            || ref.equals(symbol.name())
                            || ref.equals(symbol.fqn()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes,
                int limit) {
            return edges.stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> symbolId.equals(edge.sourceSymbolId()))
                    .filter(edge -> edgeTypes == null || edgeTypes.isEmpty() || edgeTypes.contains(edge.edgeType()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesTo(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
            return edges.stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> symbolId.equals(edge.targetSymbolId()))
                    .filter(edge -> edgeTypes == null || edgeTypes.isEmpty() || edgeTypes.contains(edge.edgeType()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesByTargetRefs(String projectKey, Set<String> targetRefs,
                Set<String> edgeTypes, int limit) {
            return edges.stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> targetRefs.contains(edge.targetRef()))
                    .filter(edge -> edgeTypes == null || edgeTypes.isEmpty() || edgeTypes.contains(edge.edgeType()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeDiagnosticRecord> recentDiagnostics(String projectKey, int limit) {
            return diagnostics.stream()
                    .filter(diagnostic -> projectKey.equals(diagnostic.projectKey()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeDiagnosticRecord> diagnosticsForRun(String projectKey, UUID scanRunId, int limit) {
            return diagnostics.stream()
                    .filter(diagnostic -> projectKey.equals(diagnostic.projectKey()))
                    .filter(diagnostic -> scanRunId.equals(diagnostic.scanRunId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<CodeScanRunRecord> findRun(String projectKey, UUID scanRunId) {
            return latestRun == null || !projectKey.equals(latestRun.projectKey()) || !scanRunId.equals(latestRun.id())
                    ? Optional.empty()
                    : Optional.of(latestRun);
        }

        @Override
        public Optional<CodeScanRunRecord> latestCompletedRun(String projectKey) {
            return latestRun == null || !projectKey.equals(latestRun.projectKey())
                    ? Optional.empty()
                    : Optional.of(latestRun);
        }

        private static UUID parseUuid(String value) {
            try {
                return value == null ? null : UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
