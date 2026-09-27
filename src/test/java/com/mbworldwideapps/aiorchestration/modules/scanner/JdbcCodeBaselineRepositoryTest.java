package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.scanner.GraphProjectionKeys;
import org.eclipse.jgit.api.Git;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcCodeBaselineRepositoryTest {

    @TempDir
    Path tempDir;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcCodeBaselineRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcCodeBaselineRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void directoryExistenceUsesLiteralPrefixAndProjectBoundary() {
        UUID run = UUID.randomUUID();
        repository.startRun(run, "P", "/repo", "extractive", "", "v1", false, Map.of());
        for (String path : List.of("a/bc/C.java", "a/b", "a/percent%/C.java"))
            repository.upsertFile(new CodeFileRecord(UUID.randomUUID(), "P", path, "h", "java", run, Map.of()));
        assertThat(repository.directoryExists("P", "a/b")).isFalse();
        assertThat(repository.directoryExists("P", "a/per%")).isFalse();
        assertThat(repository.directoryExists("P", "a/percent%")).isTrue();
        repository.upsertFile(new CodeFileRecord(UUID.randomUUID(), "P", "a/b/sub/C.java", "h", "java", run, Map.of()));
        assertThat(repository.directoryExists("P", "a/b")).isTrue();
        assertThat(repository.directoryExists("OTHER", "a/b")).isFalse();
    }

    @Test
    void queuedRunIsPromotedToRunningAndCanBeFetchedById() {
        String projectKey = "PAYMENTS";
        UUID scanRunId = UUID.randomUUID();

        repository.queueRun(scanRunId, projectKey, "/repo", "codex", "gpt-5", Map.of("async", true));
        CodeScanRunRecord queued = repository.findRun(projectKey, scanRunId).orElseThrow();
        repository.startRun(scanRunId, projectKey, "/repo", "codex", "gpt-5", "scanner-semantic-v1",
                true, Map.of("semanticEnabled", true));

        CodeScanRunRecord running = repository.findRun(projectKey, scanRunId).orElseThrow();
        assertThat(queued.status()).isEqualTo("queued");
        assertThat(running.status()).isEqualTo("running");
        assertThat(running.provider()).isEqualTo("codex");
        assertThat(running.semanticModel()).isEqualTo("gpt-5");
        assertThat(running.dataEgress()).isTrue();
    }

    @Test
    void scannerFileStateIsIsolatedByProjectKey() {
        JdbcScannerFileStateRepository stateRepository = new JdbcScannerFileStateRepository(
                jdbcTemplate, new ObjectMapper());
        String path = "src/main/java/com/example/Application.java";
        stateRepository.upsert(new ScannerFileState("PROJECT_A", path, "hash-a", List.of(), Instant.now()));
        stateRepository.upsert(new ScannerFileState("PROJECT_B", path, "hash-b", List.of(), Instant.now()));

        assertThat(stateRepository.find("PROJECT_A", path)).map(ScannerFileState::contentHash).contains("hash-a");
        assertThat(stateRepository.find("PROJECT_B", path)).map(ScannerFileState::contentHash).contains("hash-b");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scanner_file_state WHERE file_path = ?", Integer.class, path))
                .isEqualTo(2);
    }

    @Test
    void failIncompleteRunsMarksQueuedAndRunningAsFailed() {
        String projectKey = "PAYMENTS";
        UUID queuedRunId = UUID.randomUUID();
        UUID runningRunId = UUID.randomUUID();
        UUID completedRunId = UUID.randomUUID();
        repository.queueRun(queuedRunId, projectKey, "/queued", "codex", "", Map.of());
        repository.startRun(runningRunId, projectKey, "/running", "codex", "", "scanner-semantic-v1",
                false, Map.of());
        repository.startRun(completedRunId, projectKey, "/completed", "codex", "", "scanner-semantic-v1",
                false, Map.of());
        repository.completeRun(completedRunId, "completed", 1, 1, 0, 0, 0, Map.of());

        int updated = repository.failIncompleteRuns("interrupted_by_restart");

        assertThat(updated).isEqualTo(2);
        assertThat(repository.findRun(projectKey, queuedRunId).orElseThrow().status()).isEqualTo("failed");
        assertThat(repository.findRun(projectKey, runningRunId).orElseThrow().status()).isEqualTo("failed");
        assertThat(repository.findRun(projectKey, completedRunId).orElseThrow().status()).isEqualTo("completed");
    }

    @Test
    void cancelledRunIsNotOverwrittenByLateCompletion() {
        String projectKey = "PAYMENTS";
        UUID scanRunId = UUID.randomUUID();
        repository.startRun(scanRunId, projectKey, "/repo", "codex", "", "scanner-semantic-v1",
                false, Map.of());

        boolean cancelled = repository.cancelRun(projectKey, scanRunId, "user requested");
        repository.completeRun(scanRunId, "completed", 5, 5, 0, 0, 0, Map.of());

        CodeScanRunRecord run = repository.findRun(projectKey, scanRunId).orElseThrow();
        assertThat(cancelled).isTrue();
        assertThat(run.status()).isEqualTo("cancelled");
        assertThat(run.filesDiscovered()).isZero();
    }

    @Test
    void deleteFileFactsRemovesSymbolsEdgesAndCapsulesThroughPostgresConstraints() {
        String projectKey = "PAYMENTS";
        UUID scanRunId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID classSymbolId = UUID.randomUUID();
        UUID methodSymbolId = UUID.randomUUID();
        UUID methodCapsuleId = UUID.randomUUID();
        UUID fileCapsuleId = UUID.randomUUID();

        repository.startRun(scanRunId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/App.java", "hash-1", "java",
                scanRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(classSymbolId, projectKey, fileId, "class", "App",
                "com.example.App", "", "component", 1, 20, "hash-1", scanRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(methodSymbolId, projectKey, fileId, "method", "run",
                "com.example.App#run", "run()", "method", 2, 4, "hash-1", scanRunId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, classSymbolId, methodSymbolId,
                methodSymbolId.toString(), "DECLARES", "syntactic", 1.0, scanRunId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(methodCapsuleId, projectKey, methodSymbolId, fileId,
                "method", "summary", "text", "extractive", "", "scanner-semantic-v1", "input-1", "output-1",
                false, 0.55, scanRunId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(fileCapsuleId, projectKey, null, fileId,
                "file", "file summary", "file text", "extractive", "", "scanner-semantic-v1", "input-2",
                "output-2", false, 0.55, scanRunId, Map.of()));

        assertThat(repository.findCapsuleIdsForFile(projectKey, fileId))
                .containsExactlyInAnyOrder(methodCapsuleId, fileCapsuleId);

        repository.deleteFileFacts(projectKey, fileId);

        assertThat(count("code_files")).isEqualTo(1);
        assertThat(count("code_symbols")).isZero();
        assertThat(count("code_edges")).isZero();
        assertThat(count("code_semantic_capsules")).isZero();
    }

    @Test
    void upsertSymbolAllowsSameMethodSignatureInDifferentOwnersInOneFile() {
        String projectKey = "PAYMENTS";
        UUID scanRunId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID statusA = UUID.randomUUID();
        UUID statusB = UUID.randomUUID();

        repository.startRun(scanRunId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/Fakes.java", "hash-1", "java",
                scanRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(ownerA, projectKey, fileId, "class", "FakeA",
                "com.example.Fakes.FakeA", "", "test-double", 1, 20, "hash-1", scanRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(ownerB, projectKey, fileId, "class", "FakeB",
                "com.example.Fakes.FakeB", "", "test-double", 21, 40, "hash-1", scanRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(statusA, projectKey, fileId, "method", "status",
                "com.example.Fakes.FakeA#status", "status()", "method", 5, 8, "hash-1", scanRunId,
                Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(statusB, projectKey, fileId, "method", "status",
                "com.example.Fakes.FakeB#status", "status()", "method", 25, 28, "hash-1", scanRunId,
                Map.of()));

        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, ownerA, statusA,
                statusA.toString(), "DECLARES", "syntactic", 1.0, scanRunId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, ownerB, statusB,
                statusB.toString(), "DECLARES", "syntactic", 1.0, scanRunId, Map.of()));

        assertThat(count("code_symbols")).isEqualTo(4);
        assertThat(count("code_edges")).isEqualTo(2);
    }

    @Test
    void exactLocatorResolutionUsesProjectPathFqnAndOptionalSignature() {
        String projectKey = "PAYMENTS";
        UUID run = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.startRun(run, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/Parser.java", "hash", "java",
                run, Map.of()));
        UUID stringOverload = UUID.randomUUID();
        UUID integerOverload = UUID.randomUUID();
        repository.upsertSymbol(new CodeSymbolRecord(stringOverload, projectKey, fileId, "method", "parse",
                "com.acme.Parser#parse", "parse(String)", "method", 3, 5, "hash", run, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(integerOverload, projectKey, fileId, "method", "parse",
                "com.acme.Parser#parse", "parse(Integer)", "method", 7, 9, "hash", run, Map.of()));

        assertThat(repository.findSymbolsByLocator(projectKey, "src/Parser.java",
                "com.acme.Parser#parse", "parse(String)", 2))
                .extracting(CodeSymbolRecord::id).containsExactly(stringOverload);
        assertThat(repository.findSymbolsByLocator(projectKey, "src/Parser.java",
                "com.acme.Parser#parse", null, 2))
                .extracting(CodeSymbolRecord::id).containsExactlyInAnyOrder(stringOverload, integerOverload);
        assertThat(repository.findSymbolsByLocator("OTHER", "src/Parser.java",
                "com.acme.Parser#parse", "parse(String)", 2)).isEmpty();
    }

    @Test
    void findSymbolsForRunReturnsOnlySymbolsForProjectAndRun() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID otherFileId = UUID.randomUUID();
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.startRun(otherRunId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/App.java", "hash-1", "java",
                runId, Map.of()));
        repository.upsertFile(new CodeFileRecord(otherFileId, projectKey, "src/Other.java", "hash-2", "java",
                otherRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, fileId, "class", "App",
                "com.example.App", "", "component", 1, 20, "hash-1", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, fileId, "method", "run",
                "com.example.App#run", "run()", "method", 2, 4, "hash-1", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, otherFileId, "class", "Other",
                "com.example.Other", "", "component", 1, 3, "hash-2", otherRunId, Map.of()));

        assertThat(repository.findSymbolsForRun(projectKey, runId, 10))
                .extracting(CodeSymbolRecord::name)
                .containsExactly("run", "App");
    }

    @Test
    void findFileByIdIsScopedByProjectKey() {
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        repository.startRun(runId, "PAYMENTS", "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, "PAYMENTS", "src/App.java", "hash-1", "java",
                runId, Map.of()));

        assertThat(repository.findFileById("PAYMENTS", fileId))
                .map(CodeFileRecord::filePath)
                .contains("src/App.java");
        assertThat(repository.findFileById("OTHER", fileId)).isEmpty();
    }

    @Test
    void projectBaselineReadsAreKeysetPaginated() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID fileA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID fileB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID symbolA = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID symbolB = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID edgeA = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID edgeB = UUID.fromString("00000000-0000-0000-0000-000000000022");
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileA, projectKey, "src/A.java", "hash-a", "java", runId,
                Map.of()));
        repository.upsertFile(new CodeFileRecord(fileB, projectKey, "src/B.java", "hash-b", "java", runId,
                Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(symbolA, projectKey, fileA, "class", "A", "p.A", "",
                "component", 1, 5, "hash-a", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(symbolB, projectKey, fileB, "class", "B", "p.B", "",
                "component", 1, 5, "hash-b", runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(edgeA, projectKey, symbolA, symbolB, "p.B", "CALLS",
                "parser_resolved", 0.9, runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(edgeB, projectKey, symbolB, null, "GET /b", "EXPOSES_ENDPOINT",
                "parser_ref", 0.7, runId, Map.of()));

        assertThat(repository.findFilesForProject(projectKey, null, 1))
                .extracting(CodeFileRecord::filePath)
                .containsExactly("src/A.java");
        assertThat(repository.findFilesForProject(projectKey, "src/A.java", 10))
                .extracting(CodeFileRecord::filePath)
                .containsExactly("src/B.java");
        assertThat(repository.findSymbolsForProject(projectKey, symbolA, 10))
                .extracting(CodeSymbolRecord::id)
                .containsExactly(symbolB);
        assertThat(repository.findEdgesForProject(projectKey, edgeA, 10))
                .extracting(CodeEdgeRecord::id)
                .containsExactly(edgeB);
    }

    @Test
    void anchorCandidateReadsAggregateSymbolEdgesAndEndpointReferenceKeys() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID serviceId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID methodId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID endpointHandlerId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID endpointEdgeId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/PaymentController.java", "hash",
                "java", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(serviceId, projectKey, fileId, "class", "PaymentService",
                "com.example.PaymentService", "", "service", 1, 50, "hash", runId,
                Map.of("annotations", List.of("Service"))));
        repository.upsertSymbol(new CodeSymbolRecord(methodId, projectKey, fileId, "method", "loadPayment",
                "com.example.PaymentService#loadPayment", "loadPayment()", "method", 10, 20, "hash",
                runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(endpointHandlerId, projectKey, fileId, "method",
                "getPayment", "com.example.PaymentController#getPayment", "getPayment()", "method", 30, 40,
                "hash", runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, serviceId, methodId,
                "com.example.PaymentService#loadPayment", "CALLS", "parser_resolved", 0.9, runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, endpointHandlerId, serviceId,
                "com.example.PaymentService", "CALLS", "parser_resolved", 0.9, runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(UUID.randomUUID(), projectKey, serviceId, null, "Service",
                "ANNOTATED_WITH", "parser_ref", 1.0, runId, Map.of()));
        repository.upsertEdge(new CodeEdgeRecord(endpointEdgeId, projectKey, endpointHandlerId, null,
                "GET /payments/{id}", "EXPOSES_ENDPOINT", "parser_ref", 0.95, runId, Map.of()));

        List<CodeSymbolAnchorCandidateRecord> symbols = repository.findSymbolAnchorCandidates(projectKey, null,
                10);
        List<CodeEndpointAnchorCandidateRecord> endpoints = repository.findEndpointAnchorCandidates(projectKey,
                null, 10);

        CodeSymbolAnchorCandidateRecord service = symbols.stream()
                .filter(candidate -> candidate.symbol().id().equals(serviceId))
                .findFirst()
                .orElseThrow();
        CodeSymbolAnchorCandidateRecord endpointHandler = symbols.stream()
                .filter(candidate -> candidate.symbol().id().equals(endpointHandlerId))
                .findFirst()
                .orElseThrow();
        assertThat(service.outgoingEdges()).isEqualTo(1);
        assertThat(service.incomingEdges()).isEqualTo(1);
        assertThat(service.annotations()).contains("Service");
        assertThat(endpointHandler.endpointEdges()).isEqualTo(1);
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.getFirst().edgeId()).isEqualTo(endpointEdgeId);
        assertThat(endpoints.getFirst().referenceKey()).isEqualTo(GraphProjectionKeys.codeReferenceKey(projectKey,
                "EXPOSES_ENDPOINT", "GET /payments/{id}"));
    }

    @Test
    void currentCapsuleProjectionReadUsesLatestVersionAndTupleCursor() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID symbolA = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID symbolB = UUID.fromString("00000000-0000-0000-0000-000000000112");
        UUID oldCapsule = UUID.fromString("00000000-0000-0000-0000-000000000121");
        UUID currentCapsule = UUID.fromString("00000000-0000-0000-0000-000000000122");
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey, "src/A.java", "hash-a", "java", runId,
                Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(symbolA, projectKey, fileId, "class", "A", "p.A", "",
                "component", 1, 5, "hash-a", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(symbolB, projectKey, fileId, "class", "B", "p.B", "",
                "component", 6, 10, "hash-a", runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(oldCapsule, projectKey, symbolA, fileId, "method",
                "old", "old text", "extractive", "", "scanner-semantic-v1", "input-old", "output-old",
                false, 0.4, runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(currentCapsule, projectKey, symbolA, fileId, "method",
                "current", "current text", "extractive", "", "scanner-semantic-v1", "input-current",
                "output-current", false, 0.8, runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(UUID.randomUUID(), projectKey, symbolB, fileId,
                "method", "b", "b text", "extractive", "", "scanner-semantic-v1", "input-b", "output-b",
                false, 0.6, runId, Map.of()));
        jdbcTemplate.update("""
                UPDATE code_semantic_capsules
                SET updated_at = TIMESTAMPTZ '2000-01-01T00:00:00Z'
                WHERE id = ?
                """, oldCapsule);
        jdbcTemplate.update("""
                UPDATE code_semantic_capsules
                SET updated_at = TIMESTAMPTZ '2001-01-01T00:00:00Z'
                WHERE id = ?
                """, currentCapsule);

        List<CodeCapsuleProjectionRecord> firstPage = repository.findCurrentCapsulesForProject(projectKey, null,
                null, 1);
        List<CodeCapsuleProjectionRecord> secondPage = repository.findCurrentCapsulesForProject(projectKey,
                firstPage.get(0).targetKey(), firstPage.get(0).capsuleKind(), 10);

        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).targetKey()).isEqualTo(symbolA.toString());
        assertThat(firstPage.get(0).outputHash()).isEqualTo("output-current");
        assertThat(secondPage).extracting(CodeCapsuleProjectionRecord::targetKey)
                .containsExactly(symbolB.toString());
        assertThat(repository.findCurrentCapsulesForProject(projectKey, null, null, 10))
                .extracting(CodeCapsuleProjectionRecord::outputHash)
                .doesNotContain("output-old");

        CodeCapsuleLinkTargetRecord oldTarget = repository.findCapsuleLinkTargetById(oldCapsule).orElseThrow();
        CodeCapsuleLinkTargetRecord currentTarget = repository.findCurrentCapsuleLinkTarget(projectKey,
                oldTarget.targetKey(), oldTarget.capsuleKind()).orElseThrow();
        assertThat(oldTarget.targetKey()).isEqualTo(symbolA.toString());
        assertThat(oldTarget.outputHash()).isEqualTo("output-old");
        assertThat(currentTarget.id()).isEqualTo(currentCapsule);
        assertThat(currentTarget.outputHash()).isEqualTo("output-current");
        assertThat(repository.findFileByPath(projectKey, "src/A.java")).isPresent();
    }

    @Test
    void deleteFilesForRootNotInRemovesOnlyMissingFilesFromSameScanRoot() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID otherRootRunId = UUID.randomUUID();
        UUID keptFileId = UUID.randomUUID();
        UUID deletedFileId = UUID.randomUUID();
        UUID otherRootFileId = UUID.randomUUID();
        UUID keptCapsuleId = UUID.randomUUID();
        UUID deletedCapsuleId = UUID.randomUUID();
        UUID otherRootCapsuleId = UUID.randomUUID();

        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.startRun(otherRootRunId, projectKey, "/other-repo", "extractive", "", "scanner-semantic-v1",
                false, Map.of());
        repository.upsertFile(new CodeFileRecord(keptFileId, projectKey, "src/App.java", "hash-1", "java",
                runId, Map.of()));
        repository.upsertFile(new CodeFileRecord(deletedFileId, projectKey, "src/Deleted.java", "hash-2", "java",
                runId, Map.of()));
        repository.upsertFile(new CodeFileRecord(otherRootFileId, projectKey, "src/OtherDeleted.java", "hash-3", "java",
                otherRootRunId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, deletedFileId, "class",
                "Deleted", "com.example.Deleted", "", "component", 1, 3, "hash-2", runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(keptCapsuleId, projectKey, null, keptFileId,
                "file", "kept", "kept", "extractive", "", "scanner-semantic-v1", "input-kept",
                "output-kept", false, 0.55, runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(deletedCapsuleId, projectKey, null, deletedFileId,
                "file", "deleted", "deleted", "extractive", "", "scanner-semantic-v1", "input-deleted",
                "output-deleted", false, 0.55, runId, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(otherRootCapsuleId, projectKey, null, otherRootFileId,
                "file", "other", "other", "extractive", "", "scanner-semantic-v1", "input-other",
                "output-other", false, 0.55, otherRootRunId, Map.of()));

        assertThat(repository.findCapsuleIdsForRootNotIn(projectKey, "/repo", Set.of("src/App.java")))
                .containsExactly(deletedCapsuleId);

        repository.deleteFilesForRootNotIn(projectKey, "/repo", Set.of("src/App.java"));

        assertThat(count("code_files")).isEqualTo(2);
        assertThat(countFiles("/repo", "src/App.java")).isEqualTo(1);
        assertThat(countFiles("/repo", "src/Deleted.java")).isZero();
        assertThat(countFiles("/other-repo", "src/OtherDeleted.java")).isEqualTo(1);
        assertThat(count("code_symbols")).isZero();
    }

    @Test
    void projectRootBindingRejectsASecondRepositoryRoot() {
        JdbcScannerProjectRootRegistry roots = new JdbcScannerProjectRootRegistry(jdbcTemplate);

        ScannerRootPreparation first = roots.prepare("PAYMENTS", "/repo/payments");

        assertThat(first.changed()).isFalse();
        assertThatThrownBy(() -> roots.prepare("PAYMENTS", "/repo/other"))
                .isInstanceOf(ScannerProjectRootMismatchException.class)
                .hasMessageContaining("/repo/payments")
                .hasMessageContaining("scanner.project.resolve");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT root_path FROM scanner_project_roots WHERE project_key = 'PAYMENTS'
                """, String.class)).isEqualTo("/repo/payments");
    }

    @Test
    void workspaceResolverReturnsCanonicalRegisteredGitRootAndChecksFingerprint() throws Exception {
        Path repositoryRoot = gitRepository("payments");
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "PAYMENTS", repositoryRoot.toString());
        ProjectWorkspaceResolver resolver = workspaceResolver();

        ProjectWorkspace workspace = resolver.resolve(" PAYMENTS ");

        assertThat(workspace.projectKey()).isEqualTo("PAYMENTS");
        assertThat(workspace.repositoryRoot()).isEqualTo(repositoryRoot.toRealPath());
        assertThat(workspace.repositoryFingerprint()).hasSize(64);
        assertThat(resolver.resolve("PAYMENTS", workspace.repositoryFingerprint())).isEqualTo(workspace);
        assertThatThrownBy(() -> resolver.resolve("PAYMENTS", "0".repeat(64)))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("fingerprint_mismatch");
    }

    @Test
    void workspaceResolverFailsClosedForUnknownBrokenAndNonGitBindings() throws Exception {
        ProjectWorkspaceResolver resolver = workspaceResolver();
        Path missing = tempDir.resolve("missing");
        Path nonGit = Files.createDirectories(tempDir.resolve("aggregate")).toRealPath();
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "BROKEN", missing.toString());
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "AGGREGATE", nonGit.toString());

        assertThatThrownBy(() -> resolver.resolve("UNKNOWN"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("unknown_project");
        assertThatThrownBy(() -> resolver.resolve("BROKEN"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("broken_root_binding");
        assertThatThrownBy(() -> resolver.resolve("AGGREGATE"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("not_git_worktree_root");
    }

    @Test
    void workspaceResolverUsesLongestRootAndAllowsLegacyAliases() throws Exception {
        Path parent = gitRepository("parent");
        Path nested = Files.createDirectories(parent.resolve("nested"));
        try (Git ignored = Git.init().setDirectory(nested.toFile()).call()) {
            // independent nested repository
        }
        Path nestedFile = Files.createDirectories(nested.resolve("src")).resolve("App.java");
        Files.writeString(nestedFile, "class App {}\n");
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "PARENT", parent.toString());
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "NESTED", nested.toString());
        ProjectWorkspaceResolver resolver = workspaceResolver();

        assertThat(resolver.resolveByPath(nestedFile).projectKey()).isEqualTo("NESTED");
        assertThat(resolver.resolveByPath(parent.resolve("README.md")).projectKey()).isEqualTo("PARENT");
        assertThatThrownBy(() -> resolver.resolve("PARENT").resolveInside("nested/New.java"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("nested_project_escape");

        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "NESTED_ALIAS", nested.toString());
        assertThat(resolver.resolve("NESTED").repositoryRoot()).isEqualTo(nested);
        assertThat(resolver.resolve("NESTED_ALIAS").repositoryRoot()).isEqualTo(nested);
        assertThat(resolver.resolveByPath(nestedFile).projectKey()).isEqualTo("NESTED");
    }

    @Test
    void workspaceResolverNormalizesPersistedRootAndRejectsTargetEscape() throws Exception {
        Path repositoryRoot = gitRepository("safe").toRealPath();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(repositoryRoot.resolve("escape"), outside);
        Files.createSymbolicLink(repositoryRoot.resolve("dangling"), outside.resolve("missing"));
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "SAFE", repositoryRoot.toString());
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "NON_CANONICAL", repositoryRoot.resolve(".").resolve("..")
                        .resolve(repositoryRoot.getFileName()).toString());
        ProjectWorkspaceResolver resolver = workspaceResolver();

        ProjectWorkspace workspace = resolver.resolve("SAFE");
        assertThat(workspace.resolveInside("src/New.java"))
                .isEqualTo(repositoryRoot.resolve("src/New.java"));
        assertThatThrownBy(() -> workspace.resolveInside("../outside/secret.txt"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("root_escape");
        assertThatThrownBy(() -> workspace.resolveInside("escape/secret.txt"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("root_escape");
        assertThatThrownBy(() -> workspace.resolveInside("dangling/New.java"))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("root_escape");
        assertThat(resolver.resolve("NON_CANONICAL").repositoryRoot()).isEqualTo(repositoryRoot);
    }

    @Test
    void workspaceFingerprintDetectsRepositoryReplacementAtTheSamePath() throws Exception {
        Path repositoryRoot = gitRepository("replaceable");
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "REPLACEABLE", repositoryRoot.toString());
        ProjectWorkspaceResolver resolver = workspaceResolver();
        ProjectWorkspace original = resolver.resolve("REPLACEABLE");

        deleteTree(repositoryRoot);
        Files.createDirectories(repositoryRoot);
        try (Git ignored = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            // same path, different physical repository identity
        }

        assertThatThrownBy(() -> resolver.resolve("REPLACEABLE", original.repositoryFingerprint()))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("fingerprint_mismatch");
    }

    @Test
    void aggregateDirectoryIsNotMistakenForItsFourRegisteredChildRepositories() throws Exception {
        Path aggregate = Files.createDirectories(tempDir.resolve("card")).toRealPath();
        ProjectWorkspaceResolver resolver = workspaceResolver();
        for (String name : List.of("paygate-aes", "paygate-cms", "paygate-commons", "paygate-tms")) {
            Path root = Files.createDirectories(aggregate.resolve(name)).toRealPath();
            try (Git ignored = Git.init().setDirectory(root.toFile()).call()) {
                // four independent repositories under one non-repository directory
            }
            jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                    name.toUpperCase().replace('-', '_'), root.toString());
            assertThat(resolver.resolveByPath(root).projectKey())
                    .isEqualTo(name.toUpperCase().replace('-', '_'));
        }

        assertThatThrownBy(() -> resolver.resolveByPath(aggregate))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("ambiguous_project_container");
    }

    @Test
    void staleAggregateBindingDoesNotBlockARegisteredChildRepository() throws Exception {
        Path aggregate = Files.createDirectories(tempDir.resolve("legacy-card")).toRealPath();
        Path child = Files.createDirectories(aggregate.resolve("paygate-aes")).toRealPath();
        try (Git ignored = Git.init().setDirectory(child.toFile()).call()) {
            // independent repository below a stale, non-Git aggregate binding
        }
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "LEGACY_HCE", aggregate.toString());
        jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                "HCE_AES", child.toString());
        ProjectWorkspaceResolver resolver = workspaceResolver();

        assertThat(resolver.resolveByPath(child.resolve("pom.xml")).projectKey()).isEqualTo("HCE_AES");
        assertThatThrownBy(() -> resolver.resolveByPath(aggregate))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("not_git_worktree_root");
    }

    @Test
    @EnabledIfSystemProperty(named = "card.root", matches = ".+")
    void workspaceResolverValidatesRealHceFixtureWithoutTreatingUmbrellaAsProject() throws Exception {
        Path hceRoot = Path.of(System.getProperty("card.root")).toRealPath();
        assertThat(Files.exists(hceRoot.resolve(".git"))).isFalse();
        Map<String, String> projects = Map.of(
                "HCE_AES", "paygate-aes",
                "HCE_CMS", "paygate-cms",
                "HCE_COMMONS", "paygate-commons",
                "HCE_TMS", "paygate-tms");
        for (Map.Entry<String, String> project : projects.entrySet()) {
            Path root = hceRoot.resolve(project.getValue()).toRealPath();
            jdbcTemplate.update("INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)",
                    project.getKey(), root.toString());
        }
        ProjectWorkspaceResolver resolver = new JdbcProjectWorkspaceResolver(jdbcTemplate);

        Set<String> fingerprints = new java.util.HashSet<>();
        for (Map.Entry<String, String> project : projects.entrySet()) {
            Path root = hceRoot.resolve(project.getValue()).toRealPath();
            ProjectWorkspace workspace = resolver.resolve(project.getKey());
            assertThat(workspace.repositoryRoot()).isEqualTo(root);
            assertThat(resolver.resolveByPath(root.resolve("pom.xml")).projectKey())
                    .isEqualTo(project.getKey());
            fingerprints.add(workspace.repositoryFingerprint());
        }
        assertThat(fingerprints).hasSize(4);
        assertThatThrownBy(() -> resolver.resolveByPath(hceRoot))
                .isInstanceOf(ProjectWorkspaceResolutionException.class)
                .hasMessageContaining("ambiguous_project_container");

        Path benchmarkWorkspaces = hceRoot.resolve("output/card-code-generation-benchmark/workspaces");
        if (Files.isDirectory(benchmarkWorkspaces)) {
            assertThatThrownBy(() -> resolver.resolveByPath(benchmarkWorkspaces))
                    .isInstanceOf(ProjectWorkspaceResolutionException.class)
                    .hasMessageContaining("unknown_project_path");
        }
    }

    @Test
    void firstLocalResolveRegistersWithoutScanThenInstructionsCanLoad() throws Exception {
        Path root = gitRepository("fresh-instruction-project");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scanner_project_roots", Integer.class)).isZero();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        var scanner = org.mockito.Mockito.mock(ScannerService.class);
        var tool = new com.mbworldwideapps.aiorchestration.modules.mcp.server.ScannerMcpTool(scanner,
                org.mockito.Mockito.mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger.class),
                new com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer(), workspaceResolver());
        com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.set(
                new com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext(
                        "AI_ORCHESTRATION", "test", "local", List.of("scanner.scan")));
        try {
            var resolved = transactions.execute(status -> tool.resolveProject(root.toString(), null));
            assertThat(resolved.bindingVerified()).isTrue();
            assertThat(resolved.projectKey()).startsWith("FRESH_INSTRUCTION_PROJECT_");
            var result = new com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService(jdbcTemplate, new ObjectMapper())
                    .read(resolved.projectKey(), null, null);
            assertThat(result.complete()).isTrue();
            assertThat(result.instructions()).isEmpty();
            var repeated = transactions.execute(status -> tool.resolveProject(root.toString(), null));
            assertThat(repeated).isEqualTo(resolved);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scanner_project_roots", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM code_scan_runs", Integer.class)).isZero();
            org.mockito.Mockito.verifyNoInteractions(scanner);
        } finally {
            com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.clear();
        }
    }

    @Test
    void metadataRegistrationMovesProjectKeyAndAllowsAliasesButRejectsMissingGit() throws Exception {
        Path first = gitRepository("registration-a");
        Path second = gitRepository("registration-b");
        ProjectWorkspaceResolver resolver = workspaceResolver();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        var original = transactions.execute(s -> resolver.registerRoot("A", first));
        var moved = transactions.execute(s -> resolver.registerRoot("A", second));
        assertThat(moved.projectKey()).isEqualTo("A");
        assertThat(resolver.resolve("A").repositoryRoot()).isEqualTo(second);
        assertThat(moved.repositoryFingerprint()).isNotEqualTo(original.repositoryFingerprint());
        transactions.execute(s -> resolver.registerRoot("B", second));
        assertThat(resolver.resolveByPath(second).projectKey()).isEqualTo("A");
        assertThat(resolver.resolve("B").repositoryRoot()).isEqualTo(second);
        Path nongit = Files.createDirectories(tempDir.resolve("nongit"));
        assertThatThrownBy(() -> transactions.execute(s -> resolver.registerRoot("A", nongit)))
                .hasMessageContaining("not_git_worktree_root");
        assertThatThrownBy(() -> transactions.execute(s -> resolver.registerRoot("A", Path.of("relative"))))
                .hasMessageContaining("absolute_root_required");
        assertThat(resolver.resolve("A").repositoryRoot()).isEqualTo(second);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scanner_project_roots", Integer.class)).isEqualTo(2);
    }

    @Test
    void metadataRegistrationNeedsOnlyGitMarkerAndCanonicalizesSymlinkRoot() throws Exception {
        Path directoryRoot = Files.createDirectories(tempDir.resolve("marker-directory")).toRealPath();
        Files.createDirectory(directoryRoot.resolve(".git")); // no HEAD/JGit repository validation
        Path fileRoot = Files.createDirectories(tempDir.resolve("marker-file")).toRealPath();
        Files.writeString(fileRoot.resolve(".git"), "gitdir: /unavailable/worktree/metadata");
        Path alias = tempDir.resolve("checkout-link");
        Files.createSymbolicLink(alias, fileRoot);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        ProjectWorkspaceResolver resolver = workspaceResolver();
        assertThat(transactions.execute(s -> resolver.registerRoot("DIRECTORY", directoryRoot)).repositoryRoot())
                .isEqualTo(directoryRoot);
        assertThat(transactions.execute(s -> resolver.registerRoot("FILE", alias)).repositoryRoot())
                .isEqualTo(fileRoot);
        assertThat(resolver.resolveByPath(alias).projectKey()).isEqualTo("FILE");
    }

    @Test
    void localResolveMovesExistingProjectAndSubsequentInstructionsAndScanUseNewRoot() throws Exception {
        Path first = gitRepository("old-checkout");
        Path second = gitRepository("new-checkout");
        var resolver = workspaceResolver();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        transactions.execute(s -> resolver.registerRoot("SAME_PROJECT", first));
        // Legacy duplicate bindings no longer prevent path-only resolve.
        transactions.execute(s -> resolver.registerRoot("SAME_PROJECT_ALIAS", first));
        var scanner = org.mockito.Mockito.mock(ScannerService.class);
        var tool = new com.mbworldwideapps.aiorchestration.modules.mcp.server.ScannerMcpTool(scanner,
                org.mockito.Mockito.mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger.class),
                new com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer(), resolver);
        com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.set(
                new com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext(
                        "AI_ORCHESTRATION", "test", "local", List.of("scanner.scan")));
        try {
            assertThat(transactions.execute(s -> tool.resolveProject(first.toString(), null)).projectKey())
                    .isEqualTo("SAME_PROJECT");
            var moved = transactions.execute(s -> tool.resolveProject(second.toString(), "SAME_PROJECT"));
            assertThat(moved.projectKey()).isEqualTo("SAME_PROJECT");
            assertThat(moved.rootPath()).isEqualTo(second.toString());
            assertThat(moved.bindingVerified()).isTrue();
            assertThat(resolver.resolve("SAME_PROJECT").repositoryRoot()).isEqualTo(second);
            assertThat(resolver.resolve("SAME_PROJECT_ALIAS").repositoryRoot()).isEqualTo(first);
            assertThat(new com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService(jdbcTemplate, new ObjectMapper())
                    .read(moved.projectKey(), null, null).complete()).isTrue();
            assertThat(new JdbcScannerProjectRootRegistry(jdbcTemplate).prepare(moved.projectKey(), moved.rootPath())
                    .rootPath()).isEqualTo(second.toString());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM code_scan_runs", Integer.class)).isZero();
            org.mockito.Mockito.verifyNoInteractions(scanner);
        } finally {
            com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.clear();
        }
    }

    @Test
    void localResolveRegistersNestedGitRootInsteadOfReturningParent() throws Exception {
        Path parent = gitRepository("registered-parent");
        Path child = Files.createDirectories(parent.resolve("child")).toRealPath();
        Files.createDirectory(child.resolve(".git"));
        var resolver = workspaceResolver();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        transactions.execute(s -> resolver.registerRoot("PARENT", parent));
        var tool = new com.mbworldwideapps.aiorchestration.modules.mcp.server.ScannerMcpTool(
                org.mockito.Mockito.mock(ScannerService.class),
                org.mockito.Mockito.mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger.class),
                new com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer(), resolver);
        com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.set(
                new com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext(
                        "AI_ORCHESTRATION", "test", "local", List.of("scanner.scan")));
        try {
            var childResolution = transactions.execute(s -> tool.resolveProject(child.toString(), null));
            assertThat(childResolution.rootPath()).isEqualTo(child.toString());
            assertThat(childResolution.projectKey()).startsWith("CHILD_");
            assertThat(resolver.resolve("PARENT").repositoryRoot()).isEqualTo(parent);
        } finally {
            com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.clear();
        }
    }

    @Test
    void bearerResolveSelectsOwnAliasWithoutMovingItsRoot() throws Exception {
        Path root = gitRepository("bearer-root");
        Path another = gitRepository("bearer-other");
        var resolver = workspaceResolver();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        transactions.execute(s -> resolver.registerRoot("A_ALIAS", root));
        transactions.execute(s -> resolver.registerRoot("B_CALLER", root));
        transactions.execute(s -> resolver.registerRoot("OTHER", another));
        var tool = new com.mbworldwideapps.aiorchestration.modules.mcp.server.ScannerMcpTool(
                org.mockito.Mockito.mock(ScannerService.class),
                org.mockito.Mockito.mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger.class),
                new com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer(), resolver);
        com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.set(
                new com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext(
                        "B_CALLER", "test", "bearer-key", List.of("scanner.scan")));
        try {
            assertThat(tool.resolveProject(root.toString(), null).projectKey()).isEqualTo("B_CALLER");
            assertThatThrownBy(() -> tool.resolveProject(another.toString(), "B_CALLER"))
                    .hasMessageContaining("project_binding_mismatch");
            assertThatThrownBy(() -> tool.resolveProject(root.toString(), "A_ALIAS"))
                    .isInstanceOf(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException.class);
            assertThat(resolver.resolve("B_CALLER").repositoryRoot()).isEqualTo(root);
        } finally {
            com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder.clear();
        }
    }

    private Path gitRepository(String name) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(name));
        try (Git ignored = Git.init().setDirectory(root.toFile()).call()) {
            return root.toRealPath();
        }
    }

    private ProjectWorkspaceResolver workspaceResolver() {
        return new JdbcProjectWorkspaceResolver(jdbcTemplate);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void rootPreparationPurgesForeignFacts() {
        String projectKey = "PAYMENTS";
        UUID currentRun = UUID.randomUUID();
        UUID foreignRun = UUID.randomUUID();
        UUID currentFile = UUID.randomUUID();
        UUID foreignFile = UUID.randomUUID();
        UUID foreignSymbol = UUID.randomUUID();
        UUID foreignCapsule = UUID.randomUUID();
        repository.startRun(currentRun, projectKey, "/repo/payments", "extractive", "",
                "scanner-semantic-v1", false, Map.of());
        repository.startRun(foreignRun, projectKey, "/repo/foreign", "extractive", "",
                "scanner-semantic-v1", false, Map.of());
        repository.upsertFile(new CodeFileRecord(currentFile, projectKey, "src/Current.java", "current", "java",
                currentRun, Map.of()));
        repository.upsertFile(new CodeFileRecord(foreignFile, projectKey, "src/Foreign.java", "foreign", "java",
                foreignRun, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(foreignSymbol, projectKey, foreignFile, "class", "Foreign",
                "foreign.Foreign", "", "component", 1, 4, "foreign", foreignRun, Map.of()));
        repository.upsertCapsule(new CodeSemanticCapsuleRecord(foreignCapsule, projectKey, foreignSymbol,
                foreignFile, "class", "foreign", "foreign", "extractive", "", "scanner-semantic-v1",
                "input", "output", false, 0.8, foreignRun, Map.of("filePath", "src/Foreign.java")));
        new JdbcScannerFileStateRepository(jdbcTemplate, new ObjectMapper()).upsert(
                new ScannerFileState(projectKey, "src/Foreign.java", "foreign", List.of(), Instant.now()));
        ScannerRootPreparation result = new JdbcScannerProjectRootRegistry(jdbcTemplate)
                .prepare(projectKey, "/repo/payments");

        assertThat(result.foreignFilesDeleted()).isEqualTo(1);
        assertThat(result.foreignFileStatesDeleted()).isEqualTo(1);
        assertThat(result.removedCapsuleIds()).containsExactly(foreignCapsule);
        assertThat(repository.findFileById(projectKey, currentFile)).isPresent();
        assertThat(repository.findFileById(projectKey, foreignFile)).isEmpty();
    }

    @Test
    void findSymbolsByRefReturnsExactNameTierWithoutFuzzyTestFileMatches() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID productionFileId = UUID.randomUUID();
        UUID testFileId = UUID.randomUUID();
        UUID productionSymbolId = UUID.randomUUID();
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(productionFileId, projectKey,
                "src/main/java/com/example/MemoryRetrievalService.java", "hash-main", "java", runId, Map.of()));
        repository.upsertFile(new CodeFileRecord(testFileId, projectKey,
                "src/test/java/com/example/MemoryRetrievalServiceTest.java", "hash-test", "java", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(productionSymbolId, projectKey, productionFileId, "class",
                "MemoryRetrievalService", "com.example.MemoryRetrievalService", "", "service", 1, 40,
                "hash-main", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, testFileId, "class",
                "MemoryRetrievalServiceTest", "com.example.MemoryRetrievalServiceTest", "", "test", 1, 60,
                "hash-test", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(UUID.randomUUID(), projectKey, testFileId, "method",
                "retrievesMemory", "com.example.MemoryRetrievalServiceTest#retrievesMemory", "retrievesMemory()",
                "test", 10, 20, "hash-test", runId, Map.of()));

        List<CodeSymbolRecord> matches = repository.findSymbolsByRef(projectKey, "MemoryRetrievalService", 20);

        assertThat(matches)
                .extracting(CodeSymbolRecord::id)
                .containsExactly(productionSymbolId);
    }

    @Test
    void findSymbolsByRefFallsBackToFuzzyFilePathWhenNoExactTierMatches() {
        String projectKey = "PAYMENTS";
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        repository.startRun(runId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());
        repository.upsertFile(new CodeFileRecord(fileId, projectKey,
                "features/payments/src/PaymentController.java", "hash", "java", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(classId, projectKey, fileId, "class", "PaymentController",
                "com.example.payments.PaymentController", "", "controller", 1, 40, "hash", runId, Map.of()));
        repository.upsertSymbol(new CodeSymbolRecord(methodId, projectKey, fileId, "method", "getPayment",
                "com.example.payments.PaymentController#getPayment", "getPayment()", "method", 10, 20,
                "hash", runId, Map.of()));

        List<CodeSymbolRecord> matches = repository.findSymbolsByRef(projectKey,
                "payments/src/PaymentController.java", 20);

        assertThat(matches)
                .extracting(CodeSymbolRecord::id)
                .containsExactly(classId, methodId);
    }

    @Test
    void scanRunStatusWriterPersistsFailedStatusAcrossOuterRollback() {
        String projectKey = "PAYMENTS";
        UUID scanRunId = UUID.randomUUID();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        ScanRunStatusWriter writer = new ScanRunStatusWriter(repository, transactionManager);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        repository.startRun(scanRunId, projectKey, "/repo", "extractive", "", "scanner-semantic-v1", false,
                Map.of());

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
            writer.completeFailedRun(scanRunId, 2, 1, 0, 1, 0, Map.of("error", "forced"));
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status
                FROM code_scan_runs
                WHERE id = ?
                """, String.class, scanRunId)).isEqualTo("failed");
        assertThat(count("code_scan_runs")).isEqualTo(1);
    }

    private int count(String tableName) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count == null ? 0 : count;
    }

    private int countFiles(String rootPath, String filePath) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM code_files file
                JOIN code_scan_runs run ON run.id = file.last_scan_run_id
                WHERE run.root_path = ?
                  AND file.file_path = ?
                """, Integer.class, rootPath, filePath);
        return count == null ? 0 : count;
    }
}
