package com.mbworldwideapps.aiorchestration.modules.scanner;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkProjectionStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkProjectionScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationRequest;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationResponse;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMGateway;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryEvent;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryServiceTestFixture;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ReviewQueueItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class ScannerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesGraphFactsAndSkipsUnchangedFilesWithoutLegacyClassMemories() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeScannerStateRepository stateRepository = new FakeScannerStateRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(memoryRepository, stateRepository, baselineRepository, null,
                disabledSemantic());

        ScanCodebaseResponse first = scannerService.scan(new ScanCodebaseRequest(
                sourceRoot.toString(),
                "PAYMENTS",
                false));
        ScanCodebaseResponse second = scannerService.scan(new ScanCodebaseRequest(
                sourceRoot.toString(),
                "PAYMENTS",
                false));

        assertThat(first.candidatesCreated()).isZero();
        assertThat(memoryRepository.items).isEmpty();
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController"))
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController#approvePayment"));
        assertThat(second.filesSkipped()).isEqualTo(first.filesDiscovered());
        assertThat(second.unchangedSkipRate()).isEqualTo(1.0);
    }

    @Test
    void scansPythonClassesMethodsAndTestSymbolsStructurally() throws Exception {
        Path root = tempDir.resolve("python-project");
        Path source = root.resolve("src/kestrel/routing.py");
        Path test = root.resolve("tests/test_routing.py");
        Files.createDirectories(source.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(source, """
                class TenantRouteResolver:
                    def resolve(
                        self,
                        configured_route: str | None,
                    ) -> str:
                        return configured_route or "regional"
                """);
        Files.writeString(test, """
                class TenantRouteResolverTest:
                    def test_blank_route_uses_regional_fallback_without_recording(self):
                        assert True
                """);
        FakeCodeBaselineRepository baseline = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baseline, null, disabledSemantic());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(
                root.toString(), "PYTHON", false, null, null, false, null,
                null, null, 0, 0, 0, false));

        assertThat(response.filesDiscovered()).isEqualTo(2);
        assertThat(response.filesScanned()).isEqualTo(2);
        assertThat(baseline.files.values()).allMatch(file -> "python".equals(file.language()));
        assertThat(baseline.symbols.values())
                .anyMatch(symbol -> symbol.fqn().equals("kestrel.routing.TenantRouteResolver"))
                .anyMatch(symbol -> symbol.fqn().equals("kestrel.routing.TenantRouteResolver#resolve")
                        && symbol.startLine() == 2 && symbol.endLine() == 6)
                .anyMatch(symbol -> symbol.fqn().equals("tests.test_routing.TenantRouteResolverTest"))
                .anyMatch(symbol -> symbol.fqn().equals(
                        "tests.test_routing.TenantRouteResolverTest#test_blank_route_uses_regional_fallback_without_recording"));
    }

    @Test
    void rechecksMemoryCodeLinksAfterSuccessfulScan() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeLinkScheduler projectionScheduler = new FakeCodeLinkScheduler(false);
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                new FakeCodeBaselineRepository(), null, disabledSemantic(),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), null,
                ScannerProperties.ResolvedEdges.defaults(), projectionScheduler);

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(projectionScheduler.projectKeys).containsExactly("PAYMENTS");
        assertThat(response.filesScanned()).isGreaterThan(0);
    }

    @Test
    void codeLinkRecheckFailureDoesNotFailSuccessfulScan() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeLinkScheduler projectionScheduler = new FakeCodeLinkScheduler(true);
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic(),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), null,
                ScannerProperties.ResolvedEdges.defaults(), projectionScheduler);

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(response.filesDiscovered()).isGreaterThan(0);
        assertThat(baselineRepository.completedStatuses).containsExactly("completed");
        assertThat(projectionScheduler.projectKeys).containsExactly("PAYMENTS");
    }

    @Test
    void scansRepositoryOutsideConfiguredAllowedRoots() throws Exception {
        Path allowedRoot = Files.createDirectories(tempDir.resolve("allowed"));
        Path sourceRoot = createSampleProject(false);
        Files.createDirectories(sourceRoot.resolve(".git"));
        FakeCodeBaselineRepository baseline = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baseline, null, disabledSemantic(), List.of(allowedRoot.toString()));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(response.rootPath()).isEqualTo(sourceRoot.toRealPath().toString());
        assertThat(response.filesScanned()).isGreaterThan(0);
        assertThat(baseline.completedStatuses).containsExactly("completed");
        assertThat(baseline.edges.values()).anyMatch(edge -> edge.edgeType().equals("EXPOSES_ENDPOINT"));
    }

    @Test
    void ignoresUnresolvedAllowedRootsAndScansCanonicalSymlinkTarget() throws Exception {
        Path sourceRoot = createSampleProject(false);
        Path alias = tempDir.resolve("checkout-link");
        Files.createSymbolicLink(alias, sourceRoot);
        Path missingRoot = tempDir.resolve("missing-projects-root");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                new FakeCodeBaselineRepository(), null, disabledSemantic(), List.of(missingRoot.toString()));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(alias.toString(),
                "PAYMENTS", false));

        assertThat(response.rootPath()).isEqualTo(sourceRoot.toRealPath().toString());
        assertThat(response.filesScanned()).isGreaterThan(0);
    }

    @Test
    void stillRejectsMissingScanDirectoryWithoutAnAllowlist() {
        FakeCodeBaselineRepository baseline = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baseline, null, disabledSemantic(), List.of());

        assertThatThrownBy(() -> scannerService.scan(new ScanCodebaseRequest(
                tempDir.resolve("missing").toString(), "PAYMENTS", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scan root must be a directory");
        assertThat(baseline.completedStatuses).isEmpty();
    }

    @Test
    void writesGraphEdgesWithEndpointRoutes() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.edges.values())
                .anyMatch(edge -> edge.edgeType().equals("DECLARES") && edge.targetSymbolId() != null)
                .anyMatch(edge -> edge.edgeType().equals("CALLS") && edge.targetRef().equals("serviceApprove"))
                .anyMatch(edge -> edge.edgeType().equals("EXPOSES_ENDPOINT")
                        && edge.targetRef().equals("GET /api/payments/{id}"));
    }

    @Test
    void primaryScannerParsesJava17Syntax() throws Exception {
        Path sourceRoot = createJava17SyntaxProject();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(response.filesRejected()).isZero();
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.name().equals("ModernSyntaxService"))
                .anyMatch(symbol -> symbol.name().equals("describe"));
    }

    @Test
    void resolvedEdgesAddPreciseCallEdgesAlongsideSyntacticEdges() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic(), List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()),
                null, enabledResolvedEdges());

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));

        CodeSymbolRecord source = baselineRepository.symbols.values().stream()
                .filter(symbol -> "com.example.payments.PaymentController#approvePayment".equals(symbol.fqn()))
                .findFirst()
                .orElseThrow();
        CodeSymbolRecord target = baselineRepository.symbols.values().stream()
                .filter(symbol -> "com.example.payments.PaymentController#serviceApprove".equals(symbol.fqn()))
                .findFirst()
                .orElseThrow();
        assertThat(baselineRepository.edges.values())
                .anyMatch(edge -> edge.sourceSymbolId().equals(source.id())
                        && edge.edgeType().equals("CALLS")
                        && edge.targetSymbolId() == null
                        && edge.targetRef().equals("serviceApprove")
                        && edge.resolution().equals("syntactic"))
                .anyMatch(edge -> edge.sourceSymbolId().equals(source.id())
                        && edge.edgeType().equals("CALLS")
                        && target.id().equals(edge.targetSymbolId())
                        && edge.targetRef().equals(target.id().toString())
                        && edge.resolution().equals("resolved"));
    }

    @Test
    void resolvedEdgeFailureWritesDiagnosticAndPreservesSyntacticEdges() throws Exception {
        Path root = tempDir.resolve("resolver-failure-project");
        Path packageDir = root.resolve("src/main/java/com/example/payments");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("BrokenResolution.java"), """
                package com.example.payments;
                class BrokenResolution {
                  void source() {
                    doesNotExist();
                    target();
                  }
                  void target() {}
                }
                """);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic(), List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()),
                null, enabledResolvedEdges());

        scannerService.scan(new ScanCodebaseRequest(root.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.diagnostics)
                .anyMatch(diagnostic -> diagnostic.code().equals("resolved_edges_symbol_failed"));
        assertThat(baselineRepository.edges.values())
                .anyMatch(edge -> edge.edgeType().equals("CALLS")
                        && edge.targetRef().equals("doesNotExist")
                        && edge.resolution().equals("syntactic"));
    }

    @Test
    void catastrophicResolvedEdgeFailureWritesDiagnosticAndPreservesD1Graph() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        baselineRepository.failOnFindSymbolByIdWithError = true;
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic(), List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()),
                null, enabledResolvedEdges());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(response.filesRejected()).isZero();
        assertThat(baselineRepository.diagnostics)
                .anyMatch(diagnostic -> diagnostic.code().equals("resolved_edges_failed")
                        && diagnostic.metadata().get("exception").equals("AssertionError"));
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> "com.example.payments.PaymentController#approvePayment".equals(symbol.fqn()));
        assertThat(baselineRepository.edges.values())
                .anyMatch(edge -> edge.edgeType().equals("CALLS")
                        && edge.targetSymbolId() == null
                        && edge.targetRef().equals("serviceApprove")
                        && edge.resolution().equals("syntactic"));
    }

    @Test
    void deletesStaleGraphFactsWhenChangedFileIsRescanned() throws Exception {
        Path sourceRoot = createSampleProject(false);
        Path controller = sourceRoot.resolve("src/main/java/com/example/payments/PaymentController.java");
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));
        Files.writeString(controller, """
                package com.example.payments;
                @RestController
                @RequestMapping("/api")
                class PaymentController {
                  @GetMapping("/payments/{id}")
                  void approvePayment(String id) {}
                }
                """);
        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.symbols.values())
                .noneMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController#approvePayment")
                        && symbol.signature().equals("approvePayment()"))
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController#approvePayment")
                        && symbol.signature().equals("approvePayment(String)"));
    }

    @Test
    void deletesRemovedFileFactsWhenFileDisappearsBetweenScans() throws Exception {
        Path sourceRoot = createSampleProject(false);
        Path serviceFile = sourceRoot.resolve("src/main/java/com/example/payments/PaymentService.java");
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));
        Files.delete(serviceFile);
        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.symbols.values())
                .noneMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentService"))
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController"));
        assertThat(baselineRepository.files.values())
                .noneMatch(file -> file.filePath().endsWith("PaymentService.java"))
                .anyMatch(file -> file.filePath().endsWith("PaymentController.java"));
    }

    @Test
    void scopedScanDoesNotSweepUnrelatedBaselineRows() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));
        assertThat(baselineRepository.files.values())
                .anyMatch(file -> file.filePath().endsWith("PaymentController.java"))
                .anyMatch(file -> file.filePath().endsWith("PaymentService.java"));
        baselineRepository.failOnSweep = true;

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", true, null, null, null, null,
                List.of(sourceRoot.resolve("src/main/java/com/example/payments/PaymentController.java").toString()),
                null, null, null, null));

        assertThat(response.filesDiscovered()).isEqualTo(1);
        assertThat(baselineRepository.files.values())
                .anyMatch(file -> file.filePath().endsWith("PaymentController.java"))
                .anyMatch(file -> file.filePath().endsWith("PaymentService.java"));
        assertThat(baselineRepository.scanRuns.get(response.scanRunId()).metadata())
                .containsEntry("scopedScan", true)
                .containsEntry("staleSweepSkipped", true);
    }

    @Test
    void codexSymbolTimeoutWritesExtractiveFallbackCapsule() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        LLMGateway timingOutGateway = request ->
                { throw new com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException(
                        "codex", 90000L, new java.util.concurrent.TimeoutException("timeout")); };
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, timingOutGateway, enabledSemantic(true, List.of("local-qwen", "codex")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false, "codex", "gpt-5"));

        assertThat(baselineRepository.capsules.values())
                .filteredOn(capsule -> capsule.capsuleKind().equals("class") || capsule.capsuleKind().equals("method"))
                .isNotEmpty()
                .allMatch(capsule -> capsule.provider().equals("extractive"));
        assertThat(baselineRepository.diagnostics)
                .anyMatch(diagnostic -> diagnostic.code().equals("scanner_semantic_fallback"));
    }

    @Test
    void symbolTimeoutFallbackRecordsAttemptWrittenAndFallbackCounters() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        LLMGateway timingOutGateway = request -> {
            throw new com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException(
                    "codex", 90000L, new java.util.concurrent.TimeoutException("timeout"));
        };
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, timingOutGateway, enabledSemantic(true, List.of("local-qwen", "codex")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "codex", "gpt-5"));

        Map<String, Object> metadata = baselineRepository.scanRuns.get(response.scanRunId()).metadata();
        // G1 on a live branch: LLM was attempted, an (extractive) capsule was written, and it is counted as fallback.
        assertThat((Integer) metadata.get("llmSummariesAttempted")).isPositive();
        assertThat((Integer) metadata.get("semanticCapsulesWritten")).isPositive();
        assertThat((Integer) metadata.get("extractiveFallbacks")).isPositive();
        assertThat(baselineRepository.capsules.values())
                .filteredOn(capsule -> capsule.capsuleKind().equals("class") || capsule.capsuleKind().equals("method"))
                .allSatisfy(capsule -> {
                    assertThat(capsule.provider()).isEqualTo("extractive");
                    assertThat(capsule.evidence())
                            .containsEntry("requestedProvider", "codex")
                            .containsEntry("fallbackReason", "LLMProviderTimeoutException");
                });
        // Outcome heartbeat on the fallback path refreshes lastProgressAt after the timeout.
        assertThat(baselineRepository.progressUpdates).anyMatch(delta ->
                delta.get("lastProgressAt") != null
                        && ((Integer) delta.getOrDefault("extractiveFallbacks", 0)) > 0);
    }

    @Test
    void codexFlowTimeoutWritesExtractiveFallbackCapsule() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        LLMGateway timingOutFlowGateway = request -> {
            if ("scanner-flow".equals(request.role())) {
                throw new com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException(
                        "codex", 90000L, new java.util.concurrent.TimeoutException("timeout"));
            }
            return new GenerationResponse("Purpose: local summary.", request.requestedProvider(), 1L, false,
                    DegradedReason.NONE, 10, 5, 0, request.sources().size(), 0L);
        };
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, timingOutFlowGateway, enabledSemantic(true, List.of("local-qwen", "codex")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "codex", "gpt-5", null, null, null, null, null, null, 4));

        assertThat(baselineRepository.capsules.values())
                .filteredOn(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW))
                .isNotEmpty()
                .allSatisfy(capsule -> {
                    assertThat(capsule.provider()).isEqualTo("extractive");
                    assertThat(capsule.evidence())
                            .containsEntry("requestedProvider", "codex")
                            .containsEntry("fallbackReason", "LLMProviderTimeoutException");
                });
        assertThat(baselineRepository.diagnostics)
                .anyMatch(diagnostic -> diagnostic.code().equals("scanner_flow_semantic_fallback"));
        // Flow counters on the fallback path: attempt entered flowSummary (past empty-context/cache-hit),
        // fallback on timeout, completed on the extractive upsert.
        Map<String, Object> metadata = baselineRepository.scanRuns.get(response.scanRunId()).metadata();
        assertThat((Integer) metadata.get("flowCapsulesAttempted")).isPositive();
        assertThat((Integer) metadata.get("flowCapsulesFallbacks")).isPositive();
        assertThat((Integer) metadata.get("flowCapsulesCompleted")).isPositive();
    }

    @Test
    void scanEmitsRunningProgressHeartbeatDuringFlowCapsules() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: scoped semantic summary.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b"));

        assertThat(baselineRepository.progressUpdates)
                .anyMatch(delta -> "flow-capsules".equals(delta.get("phase"))
                        && delta.containsKey("flowCapsulesWritten")
                        && delta.containsKey("flowCapsulesTarget")
                        && delta.containsKey("flowCapsulesAttempted")
                        && delta.containsKey("flowCapsulesCompleted"));
    }

    @Test
    void scopedScanCapsFlowCapsulesByRequest() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: scoped semantic summary.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b", null, null,
                null, null, null, null, 1));

        assertThat(baselineRepository.scanRuns.get(response.scanRunId()).metadata())
                .containsEntry("effectiveMaxSemanticFlows", 1);
        long flowLlmCalls = gateway.requests.stream()
                .filter(request -> request.role().equals("scanner-flow"))
                .count();
        assertThat(flowLlmCalls).isLessThanOrEqualTo(1);
        long flowCapsules = baselineRepository.capsules.values().stream()
                .filter(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW))
                .count();
        assertThat(flowCapsules).isLessThanOrEqualTo(1);
    }

    @Test
    void scopedSemanticLimitsCapFilesAndSymbolTargets() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: scoped semantic summary.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b", null, null,
                List.of("src/main/java/com/example/payments"),
                List.of("src/main/java/com/example/payments/PaymentService.java"),
                1, 1, null));

        assertThat(response.filesDiscovered()).isEqualTo(1);
        assertThat(baselineRepository.capsules.values()
                .stream()
                .filter(capsule -> capsule.capsuleKind().equals("class") || capsule.capsuleKind().equals("method"))
                .toList())
                .hasSize(1);
        assertThat(baselineRepository.scanRuns.get(response.scanRunId()).metadata())
                .containsEntry("scopedScan", true)
                .containsEntry("maxSemanticFiles", 1)
                .containsEntry("maxSemanticSymbols", 1)
                .containsEntry("semanticSymbols", 1);
    }

    @Test
    void maxSemanticFilesZeroDisablesSemanticSymbolCapsules() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: should not be requested.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b", null, null, null, null, 0, null, null));

        assertThat(baselineRepository.capsules.values())
                .filteredOn(capsule -> capsule.capsuleKind().equals("class") || capsule.capsuleKind().equals("method"))
                .isEmpty();
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController"));
        assertThat(response.filesScanned()).isGreaterThan(0);
        // symbol-only disable: no per-symbol semantic LLM call (role "scanner"); flow axis is separate.
        assertThat(gateway.requests).noneMatch(request -> request.role().equals("scanner"));
    }

    @Test
    void maxSemanticFlowsZeroDisablesFlowCapsules() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("{\"summary\":\"flow\",\"steps\":[],\"narrative\":\"n\"}");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b", null, null, null, null, null, null, 0));

        assertThat(baselineRepository.capsules.values())
                .noneMatch(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW));
        assertThat(gateway.requests).noneMatch(request -> request.role().equals("scanner-flow"));
    }

    @Test
    void negativeSemanticCapIsRejectedAtRequestConstruction() {
        assertThatThrownBy(() -> new ScanCodebaseRequest("/tmp/x", "PAYMENTS", false, null, null, null, null,
                null, null, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSemanticFiles");
    }

    @Test
    void semanticEnabledFalseDisablesAllSemanticCapsulesAndLlmCalls() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("{\"summary\":\"flow\",\"steps\":[],\"narrative\":\"n\"}");
        FakeCodeLinkScheduler projectionScheduler = new FakeCodeLinkScheduler(false);
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), null,
                ScannerProperties.ResolvedEdges.defaults(), projectionScheduler);

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b", null, null, null, null, null, null, null, false));

        assertThat(baselineRepository.capsules).isEmpty();
        assertThat(gateway.requests).isEmpty();
        assertThat(response.filesScanned()).isGreaterThan(0);
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.PaymentController"));
        assertThat(projectionScheduler.projectKeys).containsExactly("PAYMENTS");
    }

    @Test
    void scanRecordsWrittenAndLlmSemanticCountersDistinctly() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: summary.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, enabledSemantic(false, List.of("local-qwen")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b"));

        Map<String, Object> metadata = baselineRepository.scanRuns.get(response.scanRunId()).metadata();
        assertThat((Integer) metadata.get("semanticCapsulesWritten")).isPositive();
        assertThat((Integer) metadata.get("llmSummariesAttempted")).isPositive();
        // reused counter is recorded in run metadata; behavioral reused>0 test lands in the next slice.
        assertThat(metadata).containsKey("semanticCapsulesReused");
        assertThat((Integer) metadata.get("semanticCandidatesSelected")).isPositive();
    }

    @Test
    void preCallHeartbeatShowsCurrentSymbolBeforeLlmReturns() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        List<Map<String, Object>> capturedAtLlmCall = new ArrayList<>();
        LLMGateway recordingGateway = request -> {
            if (request.role().equals("scanner") && capturedAtLlmCall.isEmpty()) {
                capturedAtLlmCall.addAll(baselineRepository.progressUpdates);
            }
            return new GenerationResponse("Purpose: summary.", request.requestedProvider(), 1L, false,
                    DegradedReason.NONE, 10, 5, 0, request.sources().size(), 0L);
        };
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, recordingGateway, enabledSemantic(false, List.of("local-qwen")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));

        // Before the LLM call returns, a pre-call heartbeat already exposes the current symbol and the
        // incremented attempt counter, while no capsule has been written yet.
        assertThat(capturedAtLlmCall).anyMatch(delta ->
                "semantic-symbols".equals(delta.get("phase"))
                        && delta.get("currentFile") != null
                        && delta.get("currentSymbolKind") != null
                        && delta.get("lastProgressAt") != null
                        && String.valueOf(delta.get("currentSymbolFqn")).contains("com.example.payments")
                        && ((Integer) delta.getOrDefault("llmSummariesAttempted", 0)) > 0
                        && ((Integer) delta.getOrDefault("semanticCapsulesWritten", 0)) == 0);
    }

    @Test
    void scanExposesSelectionModeAllByDefaultWithNoLowValueSkips() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        Map<String, Object> metadata = baselineRepository.scanRuns.get(response.scanRunId()).metadata();
        assertThat(metadata).containsEntry("semanticSelectionMode", "ALL");
        assertThat(metadata).containsEntry("semanticCandidatesSkippedLowValue", 0);
    }

    @Test
    void symbolFirstSkipsAccessorsKeepsBusinessAndReducesLlmCalls() throws Exception {
        Path root = tempDir.resolve("sel-fixture");
        Path pkg = root.resolve("src/main/java/com/example/sel");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OrderService.java"), """
                package com.example.sel;
                class OrderService {
                  private final OrderRepo repo;
                  OrderService(OrderRepo repo) { this.repo = repo; }
                  void placeOrder(String id) { repo.save(id); }
                  boolean validateLimit(String id) { return id != null; }
                }
                """);
        Files.writeString(pkg.resolve("OrderDto.java"), """
                package com.example.sel;
                class OrderDto {
                  private String name;
                  String getName() { return name; }
                  void setName(String value) { this.name = value; }
                }
                """);
        Files.writeString(pkg.resolve("OrderRepo.java"), """
                package com.example.sel;
                interface OrderRepo { void save(String id); }
                """);

        FakeCodeBaselineRepository allRepo = new FakeCodeBaselineRepository();
        FakeLlmGateway allGateway = new FakeLlmGateway("Purpose: summary.");
        ScanCodebaseResponse all = service(new FakeMemoryRepository(), new FakeScannerStateRepository(), allRepo,
                allGateway, enabledSemantic(false, List.of("local-qwen")))
                .scan(new ScanCodebaseRequest(root.toString(), "SEL", false, "local-qwen", "qwen3:8b"));
        FakeCodeBaselineRepository sfRepo = new FakeCodeBaselineRepository();
        FakeLlmGateway sfGateway = new FakeLlmGateway("Purpose: summary.");
        ScanCodebaseResponse sf = service(new FakeMemoryRepository(), new FakeScannerStateRepository(), sfRepo,
                sfGateway, enabledSemanticSymbolFirst(List.of("local-qwen")))
                .scan(new ScanCodebaseRequest(root.toString(), "SEL", false, "local-qwen", "qwen3:8b"));

        long allScannerCalls = allGateway.requests.stream().filter(r -> r.role().equals("scanner")).count();
        long sfScannerCalls = sfGateway.requests.stream().filter(r -> r.role().equals("scanner")).count();
        Map<String, Object> allMeta = allRepo.scanRuns.get(all.scanRunId()).metadata();
        Map<String, Object> sfMeta = sfRepo.scanRuns.get(sf.scanRunId()).metadata();

        // Structural parity: gate only affects capsule/LLM selection, not symbols/edges.
        assertThat(sfRepo.symbols.size()).isEqualTo(allRepo.symbols.size());
        assertThat(sfRepo.edges.size()).isEqualTo(allRepo.edges.size());
        // Lower scanner-role LLM calls (accessors no longer hit the LLM; extractive-default has no LLM call).
        assertThat(sfScannerCalls).isLessThan(allScannerCalls);
        // LLM-path method capsules: high-value business methods only.
        java.util.Set<String> sfLlmMethodFqns = sfRepo.capsules.values().stream()
                .filter(c -> c.capsuleKind().equals("method") && !c.provider().equals("extractive"))
                .map(c -> sfRepo.symbols.get(c.symbolId()).fqn())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(sfLlmMethodFqns)
                .contains("com.example.sel.OrderService#placeOrder", "com.example.sel.OrderService#validateLimit")
                .doesNotContain("com.example.sel.OrderDto#getName", "com.example.sel.OrderDto#setName");
        // Extractive-default method capsules: exactly the skipped accessors (retrieval coverage preserved).
        java.util.Set<String> sfExtractiveMethodFqns = sfRepo.capsules.values().stream()
                .filter(c -> c.capsuleKind().equals("method") && c.provider().equals("extractive"))
                .map(c -> sfRepo.symbols.get(c.symbolId()).fqn())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(sfExtractiveMethodFqns)
                .contains("com.example.sel.OrderDto#getName", "com.example.sel.OrderDto#setName")
                .doesNotContain("com.example.sel.OrderService#placeOrder");
        // Coverage preserved: total method capsules equal ALL (extractive-default fills the skips).
        assertThat(sfRepo.capsules.values().stream().filter(c -> c.capsuleKind().equals("method")).count())
                .isEqualTo(allRepo.capsules.values().stream().filter(c -> c.capsuleKind().equals("method")).count());
        // Skip + extractive-default counts; zero under ALL.
        assertThat((Integer) sfMeta.get("semanticCandidatesSkippedLowValue")).isEqualTo(2);
        assertThat((Integer) sfMeta.get("extractiveDefaultCapsules")).isEqualTo(2);
        assertThat(allMeta).containsEntry("semanticCandidatesSkippedLowValue", 0);
        assertThat(allMeta).containsEntry("extractiveDefaultCapsules", 0);
        assertThat(sfMeta).containsEntry("semanticSelectionMode", "SYMBOL_FIRST");
        // Budget honesty: extractive-default did NOT consume reserved budget or LLM (both lower than ALL).
        assertThat((Integer) sfMeta.get("semanticCandidatesSelected"))
                .isLessThan((Integer) allMeta.get("semanticCandidatesSelected"));
        assertThat((Integer) sfMeta.get("llmSummariesAttempted"))
                .isLessThan((Integer) allMeta.get("llmSummariesAttempted"));
        // Counter invariant: reserved-path writes + extractive-default == actual class/method capsule rows.
        long sfClassMethodCapsules = sfRepo.capsules.values().stream()
                .filter(c -> c.capsuleKind().equals("class") || c.capsuleKind().equals("method")).count();
        assertThat((Integer) sfMeta.get("semanticCapsulesWritten") + (Integer) sfMeta.get("extractiveDefaultCapsules"))
                .isEqualTo((int) sfClassMethodCapsules);
        long allClassMethodCapsules = allRepo.capsules.values().stream()
                .filter(c -> c.capsuleKind().equals("class") || c.capsuleKind().equals("method")).count();
        assertThat((Integer) allMeta.get("semanticCapsulesWritten")).isEqualTo((int) allClassMethodCapsules);
    }

    @Test
    void extractiveDefaultRunsForAccessorEvenWhenMethodBudgetFull() throws Exception {
        Path root = tempDir.resolve("budget-full");
        Path pkg = root.resolve("src/main/java/com/example/bf");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Svc.java"), """
                package com.example.bf;
                class Svc {
                  private final Dep dep;
                  Svc(Dep dep) { this.dep = dep; }
                  void doWork(String id) { dep.use(id); }
                  String getName() { return "x"; }
                }
                """);
        Files.writeString(pkg.resolve("Dep.java"), """
                package com.example.bf;
                interface Dep { void use(String id); }
                """);
        // maxMethodsPerFile=1: doWork consumes the reserved-path budget before getName is reached.
        ScannerProperties.Semantic sem = new ScannerProperties.Semantic(true, "local-qwen", "qwen3:8b", false,
                List.of("local-qwen"), 8000L, 200, 1, 6000, 512, "scanner-semantic-v1", 24,
                SemanticSelectionMode.SYMBOL_FIRST);
        FakeCodeBaselineRepository repo = new FakeCodeBaselineRepository();
        ScanCodebaseResponse r = service(new FakeMemoryRepository(), new FakeScannerStateRepository(), repo,
                new FakeLlmGateway("Purpose: summary."), sem)
                .scan(new ScanCodebaseRequest(root.toString(), "BF", false, "local-qwen", "qwen3:8b"));

        Map<String, Object> meta = repo.scanRuns.get(r.scanRunId()).metadata();
        java.util.Set<String> extractiveFqns = repo.capsules.values().stream()
                .filter(c -> c.provider().equals("extractive") && c.capsuleKind().equals("method"))
                .map(c -> repo.symbols.get(c.symbolId()).fqn()).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> llmFqns = repo.capsules.values().stream()
                .filter(c -> !c.provider().equals("extractive") && c.capsuleKind().equals("method"))
                .map(c -> repo.symbols.get(c.symbolId()).fqn()).collect(java.util.stream.Collectors.toSet());
        // Accessor still gets extractive-default even though methodBudget was full from doWork (order-independent).
        assertThat(extractiveFqns).contains("com.example.bf.Svc#getName");
        assertThat(llmFqns).contains("com.example.bf.Svc#doWork");
        assertThat((Integer) meta.get("extractiveDefaultCapsules")).isEqualTo(1);
    }

    @Test
    void extractiveDefaultCapsulesReachVectorIndexSubstrate() throws Exception {
        Path root = tempDir.resolve("sel-retrieval");
        Path pkg = root.resolve("src/main/java/com/example/sel");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OrderDto.java"), """
                package com.example.sel;
                class OrderDto {
                  private String name;
                  String getName() { return name; }
                  void setName(String value) { this.name = value; }
                }
                """);
        FakeCodeBaselineRepository repo = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        ScannerService scanner = service(new FakeMemoryRepository(), new FakeScannerStateRepository(), repo,
                new FakeLlmGateway("Purpose: summary."), enabledSemanticSymbolFirst(List.of("local-qwen")),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), vectorIndex);

        scanner.scan(new ScanCodebaseRequest(root.toString(), "SEL", false, "local-qwen", "qwen3:8b"));

        java.util.Set<UUID> extractiveIds = repo.capsules.values().stream()
                .filter(c -> c.provider().equals("extractive")).map(CodeSemanticCapsuleRecord::id)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<UUID> indexedIds = vectorIndex.upserted.stream()
                .map(CodeSemanticCapsuleRecord::id).collect(java.util.stream.Collectors.toSet());
        // Skipped anchors keep searchable text: extractive-default capsules are persisted AND reach the
        // vector-index substrate (this is substrate coverage, NOT an MCP retrieval-quality eval — that is PR-5).
        assertThat(extractiveIds).hasSize(2);
        assertThat(indexedIds).containsAll(extractiveIds);
    }

    @Test
    void extractiveDefaultCapsulesHaveStableIdsAcrossRepeatScan() throws Exception {
        Path root = tempDir.resolve("sel-idem");
        Path pkg = root.resolve("src/main/java/com/example/sel");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OrderDto.java"), """
                package com.example.sel;
                class OrderDto {
                  private String name;
                  String getName() { return name; }
                  void setName(String value) { this.name = value; }
                }
                """);
        FakeCodeBaselineRepository repo = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: summary.");
        ScannerService scanner = service(new FakeMemoryRepository(), new FakeScannerStateRepository(), repo,
                gateway, enabledSemanticSymbolFirst(List.of("local-qwen")));

        scanner.scan(new ScanCodebaseRequest(root.toString(), "SEL", false, "local-qwen", "qwen3:8b"));
        java.util.Set<UUID> firstExtractiveIds = repo.capsules.values().stream()
                .filter(c -> c.provider().equals("extractive")).map(CodeSemanticCapsuleRecord::id)
                .collect(java.util.stream.Collectors.toSet());
        scanner.scan(new ScanCodebaseRequest(root.toString(), "SEL", true, "local-qwen", "qwen3:8b"));
        java.util.Set<UUID> secondExtractiveIds = repo.capsules.values().stream()
                .filter(c -> c.provider().equals("extractive")).map(CodeSemanticCapsuleRecord::id)
                .collect(java.util.stream.Collectors.toSet());

        // Stable final state (NOT cache-hit reuse): a forced re-scan rewrites the same deterministic
        // capsule IDs, so there are no duplicate rows.
        assertThat(firstExtractiveIds).hasSize(2);
        assertThat(secondExtractiveIds).isEqualTo(firstExtractiveIds);
    }

    @Test
    void runMetadataExposesFilesDiscoveredForLiveProgress() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        // Start-path is the point (mid-run progress denominator): the metadata GIVEN TO startRun must carry
        // filesDiscovered, not just the final completeRun snapshot (fake repo overwrites on completeRun).
        assertThat(response.filesDiscovered()).isGreaterThan(0);
        assertThat(baselineRepository.startRunMetadata.get(response.scanRunId()))
                .containsEntry("filesDiscovered", response.filesDiscovered());
        // Final snapshot also carries it.
        assertThat(baselineRepository.scanRuns.get(response.scanRunId()).metadata())
                .containsEntry("filesDiscovered", response.filesDiscovered());
    }

    @Test
    void marksRunFailedWhenFatalScannerErrorEscapes() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        baselineRepository.failOnSweep = true;
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        assertThatThrownBy(() -> scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS",
                false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced sweep failure");

        assertThat(baselineRepository.completedStatuses).containsExactly("failed");
    }

    @Test
    void usesNestedFqnAndDoesNotDuplicateInnerMethodsUnderOuterClass() throws Exception {
        Path root = tempDir.resolve("nested-project");
        Path packageDir = root.resolve("src/main/java/com/example/payments");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("Outer.java"), """
                package com.example.payments;
                class Outer {
                  void outerOnly() {}
                  static class Inner {
                    void innerOnly() {}
                  }
                }
                """);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, null, disabledSemantic());

        scannerService.scan(new ScanCodebaseRequest(root.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.fqn().equals("com.example.payments.Outer.Inner#innerOnly"))
                .noneMatch(symbol -> symbol.fqn().equals("com.example.payments.Outer#innerOnly"));
    }

    @Test
    void keepsStructuralFactsWhenSourceFileContainsPiiLikeText() throws Exception {
        Path sourceRoot = createSampleProject(true);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                null, disabledSemantic());

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(
                sourceRoot.toString(),
                "PAYMENTS",
                false));

        assertThat(response.filesRejected()).isZero();
        assertThat(memoryRepository.items.values())
                .noneMatch(item -> item.text().contains("dev@example.com"));
        assertThat(baselineRepository.symbols.values())
                .anyMatch(symbol -> symbol.name().equals("PiiCommentService"));
    }

    @Test
    void writesSemanticCapsulesWithSelectedLocalProvider() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: approves payment requests.");
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                gateway, enabledSemantic(false, List.of("local-qwen")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));

        assertThat(gateway.requests).isNotEmpty();
        assertThat(gateway.requests).allMatch(request -> request.requestedProvider().equals("local-qwen"));
        assertThat(baselineRepository.capsules.values())
                .anyMatch(capsule -> capsule.capsuleKind().equals("method")
                        && capsule.text().contains("approves payment requests")
                        && capsule.provider().equals("local-qwen")
                        && capsule.semanticModel().equals("qwen3:8b"));
    }

    @Test
    void writesFlowCapsulesFromRankedSeedsWithStructuredMetadataAndIndexesThem() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        String json = """
                {
                  "summary": "GET payment approval flow delegates to serviceApprove.",
                  "trigger": "GET /api/payments/{id}",
                  "steps": ["PaymentController.approvePayment handles the endpoint", "serviceApprove is called"],
                  "cacheKeys": ["PAYMENT_CACHE"],
                  "externalClients": ["PaymentClient"],
                  "repositories": ["PaymentRepository"],
                  "requestModels": ["PaymentRequest"],
                  "responseModels": ["PaymentResponse"],
                  "branches": ["missing payment returns empty response"],
                  "failurePoints": ["serviceApprove can fail"],
                  "evidence": ["src/main/java/com/example/payments/PaymentController.java:4-5"],
                  "narrative": "The endpoint is represented as a flow narrative, not a class count."
                }
                """;
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                new FakeLlmGateway(json), enabledSemantic(false, List.of("local-qwen")),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), vectorIndex);

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));

        assertThat(baselineRepository.capsules.values())
                .anySatisfy(capsule -> {
                    assertThat(capsule.capsuleKind()).isEqualTo(CodeFlowSeedBuilder.ENDPOINT_FLOW);
                    assertThat(capsule.summary()).contains("GET payment approval flow");
                    assertThat(capsule.text()).contains("flow narrative");
                    assertThat(capsule.promptVersion()).endsWith("scanner-flow-v1");
                    assertThat(capsule.evidence())
                            .containsEntry("endpoint", "GET /api/payments/{id}")
                            .containsKey("steps")
                            .containsKey("branches")
                            .containsKey("failurePoints");
                });
        assertThat(vectorIndex.upserted)
                .anyMatch(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW));
    }

    @Test
    void fixtureE2eCapturesCampaignEndpointServiceRedisClientResponseFlow() throws Exception {
        Path sourceRoot = createCampaignFlowProject();
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        FakeLlmGateway gateway = new FakeLlmGateway("""
                {
                  "summary": "Campaign list flow returns campaigns through service, Redis cache, and specialist client.",
                  "trigger": "GET /api/campaigns/list",
                  "steps": [
                    "CampaignController.listCampaigns handles the HTTP request",
                    "CampaignService.listCampaigns checks RedisCampaignCacheService with CAMPAIGN_LIST_CACHE_KEY",
                    "On a cache miss CampaignServiceClient.fetchCampaigns is called",
                    "CampaignListResponse is built for the controller response"
                  ],
                  "cacheKeys": ["CAMPAIGN_LIST_CACHE_KEY"],
                  "externalClients": ["CampaignServiceClient"],
                  "repositories": ["CampaignRepository"],
                  "requestModels": ["CampaignListRequest"],
                  "responseModels": ["CampaignListResponse", "CampaignResponse"],
                  "branches": ["cached campaigns return immediately", "cache miss calls specialist client"],
                  "failurePoints": ["specialist client may return null or timeout"],
                  "evidence": [
                    "src/main/java/com/example/campaign/CampaignController.java",
                    "src/main/java/com/example/campaign/CampaignService.java"
                  ],
                  "narrative": "The campaign list endpoint delegates to CampaignService, uses Redis via RedisCampaignCacheService first, falls back to CampaignServiceClient on cache miss, audits through CampaignRepository, and returns CampaignListResponse."
                }
                """);
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                gateway, enabledSemantic(false, List.of("local-qwen")),
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()), vectorIndex);

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "MOBILEAPP", false, "local-qwen", "qwen3:8b"));

        assertThat(response.filesRejected()).isZero();
        assertThat(gateway.requests).anyMatch(request -> request.role().equals("scanner-flow"));
        GenerationRequest flowRequest = gateway.requests.stream()
                .filter(request -> request.role().equals("scanner-flow"))
                .findFirst()
                .orElseThrow();
        assertThat(flowRequest.question())
                .contains("Prefer useful human-level flow analysis over class counts")
                .contains("GET /api/campaigns/list")
                .contains("CampaignService")
                .contains("RedisCampaignCacheService")
                .contains("CampaignServiceClient")
                .contains("CampaignRepository")
                .contains("CAMPAIGN_LIST_CACHE_KEY")
                .contains("CampaignListRequest")
                .contains("CampaignListResponse");

        CodeSemanticCapsuleRecord endpointFlow = baselineRepository.capsules.values().stream()
                .filter(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW))
                .filter(capsule -> capsule.summary().contains("Campaign list flow"))
                .findFirst()
                .orElseThrow();
        assertThat(endpointFlow.text())
                .contains("CampaignController.listCampaigns")
                .contains("RedisCampaignCacheService")
                .contains("CampaignServiceClient.fetchCampaigns")
                .contains("CampaignListResponse")
                .contains("cache miss");
        assertThat(endpointFlow.evidence())
                .containsEntry("endpoint", "GET /api/campaigns/list");
        assertThat(evidenceList(endpointFlow, "cacheKeys")).contains("CAMPAIGN_LIST_CACHE_KEY");
        assertThat(evidenceList(endpointFlow, "externalClients")).contains("CampaignServiceClient");
        assertThat(evidenceList(endpointFlow, "repositories")).contains("CampaignRepository");
        assertThat(evidenceList(endpointFlow, "requestModels")).contains("CampaignListRequest");
        assertThat(evidenceList(endpointFlow, "responseModels"))
                .contains("CampaignListResponse", "CampaignResponse");
        assertThat(evidenceList(endpointFlow, "branches"))
                .contains("cached campaigns return immediately", "cache miss calls specialist client");
        assertThat(evidenceList(endpointFlow, "failurePoints"))
                .contains("specialist client may return null or timeout");
        assertThat(vectorIndex.upserted).contains(endpointFlow);
    }

    @Test
    void writesSmallActiveInsightMemoriesOnlyWhenScannerInsightScopeIsPresent() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                new FakeLlmGateway("""
                        {"summary":"Payment endpoint flow","steps":["approvePayment calls serviceApprove"],"narrative":"flow"}
                        """), enabledSemantic(false, List.of("local-qwen")));

        McpClientContextHolder.set(new McpClientContext("PAYMENTS", "codex", "local",
                List.of("provider.local-qwen", "scanner.insights.write-memory")));
        try {
            scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                    "local-qwen", "qwen3:8b"));
        } finally {
            McpClientContextHolder.clear();
        }

        List<MemoryItem> insights = memoryRepository.items.values().stream()
                .filter(item -> item.sourceRef() != null && item.sourceRef().startsWith("scanner-flow-insight:"))
                .toList();
        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(insights.getFirst().metadata())
                .containsEntry("scanner", "flow-insight")
                .containsEntry("gateVerdict", "AUTO_ACTIVE")
                .containsKeys("targetKey", "filePath", "symbolId", MemoryCodeLocatorMetadata.METADATA_KEY);
        assertThat(MemoryCodeLocatorMetadata.read(insights.getFirst().metadata()).items())
                .extracting(locator -> locator.kind())
                .contains(MemoryCodeLocatorKind.CAPSULE, MemoryCodeLocatorKind.FILE,
                        MemoryCodeLocatorKind.SYMBOL);
    }

    @Test
    void doesNotWriteInsightMemoriesWithoutScannerInsightScope() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                new FakeLlmGateway("""
                        {"summary":"Payment endpoint flow","steps":["approvePayment calls serviceApprove"],"narrative":"flow"}
                        """), enabledSemantic(false, List.of("local-qwen")));

        McpClientContextHolder.set(new McpClientContext("PAYMENTS", "codex", "local",
                List.of("provider.local-qwen")));
        try {
            scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                    "local-qwen", "qwen3:8b"));
        } finally {
            McpClientContextHolder.clear();
        }

        assertThat(memoryRepository.items.values())
                .noneMatch(item -> item.sourceRef() != null && item.sourceRef().startsWith("scanner-flow-insight:"));
    }

    @Test
    void doesNotWriteInsightMemoriesForTestSourceFlows() throws Exception {
        Path sourceRoot = createTestOnlyFlowProject();
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(memoryRepository, new FakeScannerStateRepository(), baselineRepository,
                new FakeLlmGateway("""
                        {"summary":"Test-only endpoint flow","steps":["test method checks controller annotations"],"narrative":"test flow"}
                        """), enabledSemantic(false, List.of("local-qwen")));

        McpClientContextHolder.set(new McpClientContext("PAYMENTS", "codex", "local",
                List.of("provider.local-qwen", "scanner.insights.write-memory")));
        try {
            scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                    "local-qwen", "qwen3:8b"));
        } finally {
            McpClientContextHolder.clear();
        }

        assertThat(baselineRepository.capsules.values())
                .anyMatch(capsule -> capsule.capsuleKind().equals(CodeFlowSeedBuilder.ENDPOINT_FLOW));
        assertThat(memoryRepository.items.values())
                .noneMatch(item -> item.sourceRef() != null && item.sourceRef().startsWith("scanner-flow-insight:"));
    }

    @Test
    void forceReindexIndexesExistingCapsulesWithoutReparsingUnchangedFiles() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeScannerStateRepository stateRepository = new FakeScannerStateRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        ScannerService scannerService = service(memoryRepository, stateRepository, baselineRepository,
                new FakeLlmGateway("Purpose: approves payment requests."), enabledSemantic(false,
                        List.of("local-qwen")), List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()),
                vectorIndex);

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));
        assertThat(baselineRepository.capsules).isNotEmpty();
        vectorIndex.upserted.clear();

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, null, null, true));

        assertThat(response.filesSkipped()).isEqualTo(response.filesDiscovered());
        assertThat(vectorIndex.upserted).hasSize(baselineRepository.capsules.size());
    }

    @Test
    void changedFileRemovesPreviousCapsuleVectorsBeforeWritingReplacementCapsules() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeScannerStateRepository stateRepository = new FakeScannerStateRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        ScannerService scannerService = service(memoryRepository, stateRepository, baselineRepository,
                new FakeLlmGateway("Purpose: approves payment requests."), enabledSemantic(false,
                        List.of("local-qwen")), List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()),
                vectorIndex);

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));
        UUID controllerFileId = baselineRepository.files.values().stream()
                .filter(file -> file.filePath().endsWith("PaymentController.java"))
                .map(CodeFileRecord::id)
                .findFirst()
                .orElseThrow();
        List<UUID> previousCapsuleIds = baselineRepository.findCapsuleIdsForFile("PAYMENTS", controllerFileId);
        assertThat(previousCapsuleIds).isNotEmpty();
        Path controller = sourceRoot.resolve("src/main/java/com/example/payments/PaymentController.java");
        Files.writeString(controller, Files.readString(controller).replace("serviceApprove();",
                "serviceApprove(); // changed"));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));

        assertThat(vectorIndex.deleted).containsAll(previousCapsuleIds);
        assertThat(baselineRepository.capsules.keySet()).doesNotContainAnyElementsOf(previousCapsuleIds);
        assertThat(baselineRepository.findCapsuleIdsForFile("PAYMENTS", controllerFileId)).isNotEmpty();
    }

    @Test
    void semanticDisabledForcedUnchangedScanPreservesSemanticCapsulesAndReindexesThem() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeScannerStateRepository stateRepository = new FakeScannerStateRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeCodeBaselineVectorIndex vectorIndex = new FakeCodeBaselineVectorIndex();
        List<String> allowedRoots = List.of(Path.of(System.getProperty("java.io.tmpdir")).toString());
        ScannerService semanticScanner = service(memoryRepository, stateRepository, baselineRepository,
                new FakeLlmGateway("Purpose: approves payment requests."), enabledSemantic(false,
                        List.of("local-qwen")), allowedRoots, vectorIndex);
        ScanCodebaseResponse first = semanticScanner.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "local-qwen", "qwen3:8b"));
        CodeFileRecord controllerFile = baselineRepository.files.values().stream()
                .filter(file -> file.filePath().endsWith("PaymentController.java"))
                .findFirst()
                .orElseThrow();
        CodeSymbolRecord classSymbol = baselineRepository.symbols.values().stream()
                .filter(symbol -> "com.example.payments.PaymentController".equals(symbol.fqn()))
                .findFirst()
                .orElseThrow();
        CodeSymbolRecord methodSymbol = baselineRepository.symbols.values().stream()
                .filter(symbol -> "com.example.payments.PaymentController#approvePayment".equals(symbol.fqn()))
                .findFirst()
                .orElseThrow();
        CodeSemanticCapsuleRecord fileCapsule = new CodeSemanticCapsuleRecord(UUID.randomUUID(), "PAYMENTS",
                null, controllerFile.id(), "file", "controller file", "controller file text", "local-qwen",
                "qwen3:8b", "scanner-semantic-v1", "file-input", "file-output", false, 0.55,
                first.scanRunId(), Map.of("filePath", controllerFile.filePath()));
        baselineRepository.upsertCapsule(fileCapsule);
        int capsuleCount = baselineRepository.capsules.size();
        vectorIndex.upserted.clear();
        ScannerService structuralScanner = service(memoryRepository, stateRepository, baselineRepository, null,
                disabledSemantic(), allowedRoots, vectorIndex);

        ScanCodebaseResponse response = structuralScanner.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", true, null, null, true));

        assertThat(response.filesScanned()).isZero();
        assertThat(response.filesSkipped()).isEqualTo(response.filesDiscovered());
        assertThat(baselineRepository.capsules).hasSize(capsuleCount);
        assertThat(baselineRepository.capsules).containsKey(fileCapsule.id());
        assertThat(baselineRepository.symbols).containsKeys(classSymbol.id(), methodSymbol.id());
        assertNoDanglingCapsuleSymbols(baselineRepository);
        assertThat(baselineRepository.diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code())
                            .isEqualTo("scanner_force_semantic_disabled_skipped_unchanged");
                    assertThat(diagnostic.metadata())
                            .containsEntry("skippedUnchangedFiles", response.filesDiscovered())
                            .containsEntry("force", true)
                            .containsEntry("semanticEnabled", false)
                            .containsEntry("capsulesPreserved", true);
                });
        assertThat(vectorIndex.upserted).hasSize(capsuleCount);
    }

    @Test
    void semanticDisabledChangedScanDoesNotPreserveStaleCapsulesForRemovedSymbols() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeMemoryRepository memoryRepository = new FakeMemoryRepository();
        FakeScannerStateRepository stateRepository = new FakeScannerStateRepository();
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        List<String> allowedRoots = List.of(Path.of(System.getProperty("java.io.tmpdir")).toString());
        ScannerService semanticScanner = service(memoryRepository, stateRepository, baselineRepository,
                new FakeLlmGateway("Purpose: approves payment requests."), enabledSemantic(false,
                        List.of("local-qwen")), allowedRoots);
        semanticScanner.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false,
                "local-qwen", "qwen3:8b"));
        CodeFileRecord controllerFile = baselineRepository.files.values().stream()
                .filter(file -> file.filePath().endsWith("PaymentController.java"))
                .findFirst()
                .orElseThrow();
        assertThat(baselineRepository.capsules.values())
                .anyMatch(capsule -> controllerFile.id().equals(capsule.fileId()));
        Files.writeString(sourceRoot.resolve("src/main/java/com/example/payments/PaymentController.java"), """
                package com.example.payments;
                @RestController
                @RequestMapping("/api")
                class PaymentController {
                  @GetMapping("/payments/{id}")
                  void approvePayment() {}
                }
                """);
        ScannerService structuralScanner = service(memoryRepository, stateRepository, baselineRepository, null,
                disabledSemantic(), allowedRoots);

        structuralScanner.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false));

        assertThat(baselineRepository.capsules.values())
                .noneMatch(capsule -> controllerFile.id().equals(capsule.fileId()));
        assertThat(baselineRepository.symbols.values())
                .noneMatch(symbol -> "com.example.payments.PaymentController#serviceApprove".equals(symbol.fqn()));
        assertNoDanglingCapsuleSymbols(baselineRepository);
    }

    @Test
    void refusesExternalSemanticProviderWhenEgressNotAllowed() throws Exception {
        Path sourceRoot = createSampleProject(false);
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                new FakeCodeBaselineRepository(), new FakeLlmGateway("unused"),
                enabledSemantic(false, List.of("local-qwen", "claude")));

        assertThatThrownBy(() -> scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS",
                false, "claude", "sonnet")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-external");
    }

    @Test
    void redactsExternalSemanticPayloadBeforeCallingProvider() throws Exception {
        Path root = tempDir.resolve("secret-project");
        Path packageDir = root.resolve("src/main/java/com/example/payments");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("SecretService.java"), """
                package com.example.payments;
                class SecretService {
                  void leak() {
                    String email = "dev@example.com";
                    String token = "superSecretToken123456789";
                    String term = "ACME-LOAN-001";
                    publish(term);
                  }
                  void publish(String value) {}
                }
                """);
        FakeLlmGateway gateway = new FakeLlmGateway("Purpose: redacted external summary.");
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                new FakeCodeBaselineRepository(), gateway, enabledSemantic(true, List.of("local-qwen", "claude")));

        scannerService.scan(new ScanCodebaseRequest(root.toString(), "PAYMENTS", false, "claude", "sonnet"));

        assertThat(gateway.requests).isNotEmpty();
        String prompt = gateway.requests.getFirst().question();
        assertThat(prompt)
                .doesNotContain("dev@example.com")
                .doesNotContain("superSecretToken123456789")
                .doesNotContain("ACME-LOAN-001")
                .contains("[REDACTED");
    }

    @Test
    void redactsExternalFlowPayloadBeforeCallingProvider() throws Exception {
        Path root = tempDir.resolve("secret-flow-project");
        Path packageDir = root.resolve("src/main/java/com/example/payments");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("SecretController.java"), """
                package com.example.payments;
                @RestController
                @RequestMapping("/api")
                class SecretController {
                  @GetMapping("/secrets")
                  SecretResponse secrets(SecretRequest request) {
                    String email = "dev@example.com";
                    String token = "superSecretToken123456789";
                    return new SecretResponse(email + token + "ACME-LOAN-001");
                  }
                }
                record SecretRequest(String id) {}
                record SecretResponse(String value) {}
                """);
        List<GenerationRequest> requests = new ArrayList<>();
        LLMGateway gateway = request -> {
            requests.add(request);
            return new GenerationResponse("""
                    {"summary":"secret flow","steps":["handle endpoint"],"narrative":"redacted"}
                    """, request.requestedProvider(), 1L, false, DegradedReason.NONE, 10, 5, 0,
                    request.sources().size(), 0L);
        };
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                new FakeCodeBaselineRepository(), gateway, enabledSemantic(true, List.of("local-qwen", "claude")));

        scannerService.scan(new ScanCodebaseRequest(root.toString(), "PAYMENTS", false, "claude", "sonnet"));

        GenerationRequest flowRequest = requests.stream()
                .filter(request -> request.role().equals("scanner-flow"))
                .findFirst()
                .orElseThrow();
        assertThat(flowRequest.question())
                .contains("Return only compact JSON")
                .doesNotContain("dev@example.com")
                .doesNotContain("superSecretToken123456789")
                .doesNotContain("ACME-LOAN-001")
                .contains("[REDACTED");
    }

    @Test
    void configuredExtractiveProviderWritesCodeCapsulesWithoutAnyLlmCall() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        FakeLlmGateway gateway = new FakeLlmGateway("unused");
        ScannerProperties.Semantic extractive = new ScannerProperties.Semantic(true, "extractive", "none", false,
                List.of("local-qwen"), 8000L, 200, 12, 6000, 512, "scanner-semantic-v1", 24,
                SemanticSelectionMode.ALL);
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, gateway, extractive);

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false));

        assertThat(response.filesScanned()).isGreaterThan(0);
        assertThat(gateway.requests).isEmpty();
        assertThat(baselineRepository.capsules).isNotEmpty();
        assertThat(baselineRepository.capsules.values()).allSatisfy(capsule ->
                assertThat(capsule.provider()).isEqualTo("extractive"));
    }

    @Test
    void unavailableExternalProviderFallsBackToExtractiveCapsules() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, request -> {
                    throw new IllegalStateException("connection refused");
                }, enabledSemantic(true, List.of("local-qwen", "claude")));

        scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(), "PAYMENTS", false, "claude", "sonnet"));

        // Symbol capsules survive a dead provider with honest EXTRACTIVE provenance (flows use their own path).
        assertThat(baselineRepository.capsules.values())
                .filteredOn(capsule -> !capsule.capsuleKind().contains("flow"))
                .isNotEmpty()
                .allSatisfy(capsule -> assertThat(capsule.provider()).isEqualTo("extractive"));
    }

    @Test
    void continuesScanAndWritesDiagnosticWhenExternalSemanticProviderFails() throws Exception {
        Path sourceRoot = createSampleProject(false);
        FakeCodeBaselineRepository baselineRepository = new FakeCodeBaselineRepository();
        ScannerService scannerService = service(new FakeMemoryRepository(), new FakeScannerStateRepository(),
                baselineRepository, request -> {
                    throw new IllegalStateException("provider unavailable");
                }, enabledSemantic(true, List.of("local-qwen", "claude")));

        ScanCodebaseResponse response = scannerService.scan(new ScanCodebaseRequest(sourceRoot.toString(),
                "PAYMENTS", false, "claude", "sonnet"));

        assertThat(response.filesRejected()).isZero();
        assertThat(response.filesScanned()).isEqualTo(response.filesDiscovered());
        assertThat(baselineRepository.symbols).isNotEmpty();
        assertThat(baselineRepository.diagnostics)
                .anyMatch(diagnostic -> diagnostic.code().equals("scanner_semantic_error")
                        && diagnostic.message().contains("provider unavailable")
                        && Boolean.TRUE.equals(diagnostic.metadata().get("dataEgress")));
        assertThat((Integer) baselineRepository.scanRuns.get(response.scanRunId()).metadata().get("semanticErrors"))
                .isPositive();
    }

    private Path createSampleProject(boolean includePii) throws Exception {
        Path root = tempDir.resolve(includePii ? "pii-project" : "clean-project");
        Path packageDir = root.resolve("src/main/java/com/example/payments");
        Files.createDirectories(packageDir);
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>payments</artifactId>
                </project>
                """);
        Files.writeString(packageDir.resolve("PaymentController.java"), """
                package com.example.payments;
                @RestController
                @RequestMapping("/api")
                class PaymentController {
                  @GetMapping("/payments/{id}")
                  void approvePayment() { serviceApprove(); }
                  void serviceApprove() {}
                }
                """);
        Files.writeString(packageDir.resolve("PaymentService.java"), """
                package com.example.payments;
                class PaymentService {
                  void calculateMoneyTl() {}
                }
                """);
        if (includePii) {
            Files.writeString(packageDir.resolve("PiiCommentService.java"), """
                    package com.example.payments;
                    // contact dev@example.com before release
                    class PiiCommentService {
                      void unsafe() {}
                    }
                    """);
        }
        return root;
    }

    private Path createCampaignFlowProject() throws Exception {
        Path root = tempDir.resolve("campaign-flow-project");
        Path packageDir = root.resolve("src/main/java/com/example/campaign");
        Files.createDirectories(packageDir);
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>campaign-flow</artifactId>
                </project>
                """);
        Files.writeString(packageDir.resolve("CampaignController.java"), """
                package com.example.campaign;
                @RequestMapping("/api/campaigns")
                class CampaignController {
                  private final CampaignService campaignService;
                  CampaignController(CampaignService campaignService) {
                    this.campaignService = campaignService;
                  }
                  @GetMapping("/list")
                  CampaignListResponse listCampaigns(CampaignListRequest request) {
                    return campaignService.listCampaigns(request);
                  }
                }
                """);
        Files.writeString(packageDir.resolve("CampaignService.java"), """
                package com.example.campaign;
                import java.util.List;
                class CampaignService {
                  private static final String CAMPAIGN_LIST_CACHE_KEY = "WM:CAMPAIGN:LIST";
                  private final RedisCampaignCacheService cacheService;
                  private final CampaignServiceClient campaignServiceClient;
                  private final CampaignRepository campaignRepository;
                  CampaignService(RedisCampaignCacheService cacheService,
                      CampaignServiceClient campaignServiceClient,
                      CampaignRepository campaignRepository) {
                    this.cacheService = cacheService;
                    this.campaignServiceClient = campaignServiceClient;
                    this.campaignRepository = campaignRepository;
                  }
                  CampaignListResponse listCampaigns(CampaignListRequest request) {
                    List<CampaignResponse> cached = cacheService.getCampaigns(CAMPAIGN_LIST_CACHE_KEY,
                        request.customerNo());
                    if (!cached.isEmpty()) {
                      return CampaignListResponse.fromCache(cached);
                    }
                    SpecialistCampaignResponse specialist = campaignServiceClient.fetchCampaigns(request.customerNo());
                    if (specialist == null) {
                      return CampaignListResponse.empty();
                    }
                    campaignRepository.saveAudit(request.customerNo());
                    return mapResponse(specialist);
                  }
                  CampaignListResponse mapResponse(SpecialistCampaignResponse specialist) {
                    return CampaignListResponse.fromSpecialist(specialist);
                  }
                }
                """);
        Files.writeString(packageDir.resolve("RedisCampaignCacheService.java"), """
                package com.example.campaign;
                import java.util.List;
                class RedisCampaignCacheService {
                  List<CampaignResponse> getCampaigns(String cacheKey, String customerNo) {
                    return List.of();
                  }
                }
                """);
        Files.writeString(packageDir.resolve("CampaignServiceClient.java"), """
                package com.example.campaign;
                @FeignClient(name = "campaign-specialist")
                interface CampaignServiceClient {
                  SpecialistCampaignResponse fetchCampaigns(String customerNo);
                }
                """);
        Files.writeString(packageDir.resolve("CampaignRepository.java"), """
                package com.example.campaign;
                interface CampaignRepository {
                  void saveAudit(String customerNo);
                }
                """);
        Files.writeString(packageDir.resolve("CampaignModels.java"), """
                package com.example.campaign;
                import java.util.List;
                record CampaignListRequest(String customerNo) {}
                record CampaignResponse(String id) {}
                record SpecialistCampaignResponse(List<CampaignResponse> campaigns) {}
                record CampaignListResponse(List<CampaignResponse> campaigns, boolean cacheHit) {
                  static CampaignListResponse fromCache(List<CampaignResponse> campaigns) {
                    return new CampaignListResponse(campaigns, true);
                  }
                  static CampaignListResponse fromSpecialist(SpecialistCampaignResponse response) {
                    return new CampaignListResponse(response.campaigns(), false);
                  }
                  static CampaignListResponse empty() {
                    return new CampaignListResponse(List.of(), false);
                  }
                }
                """);
        return root;
    }

    private Path createTestOnlyFlowProject() throws Exception {
        Path root = tempDir.resolve("test-only-flow-project");
        Path packageDir = root.resolve("src/test/java/com/example/arch");
        Files.createDirectories(packageDir);
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-only-flow</artifactId>
                </project>
                """);
        Files.writeString(packageDir.resolve("ControllerArchitectureTest.java"), """
                package com.example.arch;
                @RestController
                class ControllerArchitectureTest {
                  @GetMapping("/arch/controller-rule")
                  void controllersShouldBeAnnotatedWithRestController() {
                    assertControllerAnnotation();
                  }
                  void assertControllerAnnotation() {}
                }
                """);
        return root;
    }

    private Path createJava17SyntaxProject() throws Exception {
        Path root = tempDir.resolve("java17-project");
        Path packageDir = root.resolve("src/main/java/com/example/java17");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("ModernSyntaxService.java"), """
                package com.example.java17;
                record CampaignSnapshot(String id, String status) {}
                class ModernSyntaxService {
                  String describe(Object value) {
                    if (value instanceof CampaignSnapshot snapshot) {
                      return switch (snapshot.status()) {
                        case "ACTIVE" -> \"""
                            active campaign
                            \""";
                        default -> snapshot.id();
                      };
                    }
                    return "unknown";
                  }
                }
                """);
        return root;
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository) {
        return service(memoryRepository, stateRepository, new FakeCodeBaselineRepository(), null, disabledSemantic());
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository, FakeCodeBaselineRepository baselineRepository,
            LLMGateway gateway, ScannerProperties.Semantic semantic) {
        return service(memoryRepository, stateRepository, baselineRepository, gateway, semantic,
                List.of(Path.of(System.getProperty("java.io.tmpdir")).toString()));
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository, FakeCodeBaselineRepository baselineRepository,
            LLMGateway gateway, ScannerProperties.Semantic semantic, List<String> allowedRoots) {
        return service(memoryRepository, stateRepository, baselineRepository, gateway, semantic, allowedRoots,
                null);
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository, FakeCodeBaselineRepository baselineRepository,
            LLMGateway gateway, ScannerProperties.Semantic semantic, List<String> allowedRoots,
            CodeBaselineVectorIndex vectorIndex) {
        return service(memoryRepository, stateRepository, baselineRepository, gateway, semantic, allowedRoots,
                vectorIndex, ScannerProperties.ResolvedEdges.defaults());
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository, FakeCodeBaselineRepository baselineRepository,
            LLMGateway gateway, ScannerProperties.Semantic semantic, List<String> allowedRoots,
            CodeBaselineVectorIndex vectorIndex, ScannerProperties.ResolvedEdges resolvedEdges) {
        return service(memoryRepository, stateRepository, baselineRepository, gateway, semantic, allowedRoots,
                vectorIndex, resolvedEdges, null);
    }

    private static ScannerService service(FakeMemoryRepository memoryRepository,
            FakeScannerStateRepository stateRepository, FakeCodeBaselineRepository baselineRepository,
            LLMGateway gateway, ScannerProperties.Semantic semantic, List<String> allowedRoots,
            CodeBaselineVectorIndex vectorIndex, ScannerProperties.ResolvedEdges resolvedEdges,
            MemoryCodeLinkProjectionScheduler codeLinkScheduler) {
        PiiScrubber piiScrubber = new PiiScrubber();
        MemoryService memoryService = MemoryServiceTestFixture.create(memoryRepository, properties(), event -> {
        });
        ScannerService scanner = new ScannerService(
                new ScannerProperties("AI_ORCHESTRATION", List.of(".java", ".py", "pom.xml"), List.of("target"),
                        512_000L, 0.6, allowedRoots, semantic, resolvedEdges),
                stateRepository,
                memoryService,
                memoryRepository,
                piiScrubber,
                baselineRepository,
                gateway,
                new ProviderOverrideSanitizer(),
                new ScannerPayloadRedactor(piiScrubber, new SecretScanService(), new MemoryPolicyEvaluator(),
                        new PolicyProperties(true, false, "", List.of("ACME-LOAN-001", "internal-prod-svc"))),
                null,
                null,
                vectorIndex);
        if (codeLinkScheduler != null) {
            scanner.setMemoryCodeLinkScheduler(codeLinkScheduler);
        }
        return scanner;
    }

    private static ScannerProperties.Semantic disabledSemantic() {
        return new ScannerProperties.Semantic(false, "local-qwen", "qwen3:8b", false, List.of("local-qwen"),
                8000L, 200, 12, 6000, 512, "scanner-semantic-v1", 24, SemanticSelectionMode.ALL);
    }

    private static ScannerProperties.Semantic enabledSemantic(boolean allowExternal, List<String> allowedProviders) {
        return new ScannerProperties.Semantic(true, "local-qwen", "qwen3:8b", allowExternal, allowedProviders,
                8000L, 200, 12, 6000, 512, "scanner-semantic-v1", 24, SemanticSelectionMode.ALL);
    }

    private static ScannerProperties.Semantic enabledSemanticSymbolFirst(List<String> allowedProviders) {
        return new ScannerProperties.Semantic(true, "local-qwen", "qwen3:8b", false, allowedProviders,
                8000L, 200, 12, 6000, 512, "scanner-semantic-v1", 24, SemanticSelectionMode.SYMBOL_FIRST);
    }

    private static ScannerProperties.ResolvedEdges enabledResolvedEdges() {
        return new ScannerProperties.ResolvedEdges(true, 1000, 200);
    }

    private static List<String> evidenceList(CodeSemanticCapsuleRecord capsule, String key) {
        Object value = capsule.evidence().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static void assertNoDanglingCapsuleSymbols(FakeCodeBaselineRepository repository) {
        assertThat(repository.capsules.values())
                .allMatch(capsule -> capsule.symbolId() == null
                        || repository.symbols.containsKey(capsule.symbolId()));
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", 1234, 0.42),
                365);
    }

    private static final class FakeScannerStateRepository implements ScannerFileStateRepository {
        private final Map<String, ScannerFileState> states = new HashMap<>();

        @Override
        public Optional<ScannerFileState> find(String filePath) {
            return Optional.ofNullable(states.get(filePath));
        }

        @Override
        public void upsert(ScannerFileState state) {
            states.put(state.filePath(), state);
        }
    }

    private static final class FakeCodeBaselineRepository implements CodeBaselineRepository {
        private final Map<UUID, CodeFileRecord> files = new HashMap<>();
        private final Map<UUID, CodeSymbolRecord> symbols = new HashMap<>();
        private final Map<UUID, CodeEdgeRecord> edges = new HashMap<>();
        private final Map<UUID, CodeSemanticCapsuleRecord> capsules = new HashMap<>();
        private final Map<UUID, String> runRootPaths = new HashMap<>();
        private final Map<UUID, CodeScanRunRecord> scanRuns = new HashMap<>();
        private final List<String> completedStatuses = new ArrayList<>();
        private final Map<UUID, Map<String, Object>> startRunMetadata = new HashMap<>();
        private final List<CodeDiagnosticRecord> diagnostics = new ArrayList<>();
        private final List<Map<String, Object>> progressUpdates = new ArrayList<>();
        private boolean failOnSweep;
        private boolean failOnFindSymbolByIdWithError;

        @Override
        public void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
                Map<String, Object> metadata) {
            runRootPaths.put(scanRunId, rootPath);
            scanRuns.put(scanRunId, new CodeScanRunRecord(scanRunId, projectKey, rootPath, "queued",
                    Instant.now(), null, provider, semanticModel, false, 0, 0, 0, 0, 0, metadata));
        }

        @Override
        public void startRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
                String promptVersion, boolean dataEgress, Map<String, Object> metadata) {
            runRootPaths.put(scanRunId, rootPath);
            startRunMetadata.put(scanRunId, metadata);
            scanRuns.put(scanRunId, new CodeScanRunRecord(scanRunId, projectKey, rootPath, "running",
                    Instant.now(), null, provider, semanticModel, dataEgress, 0, 0, 0, 0, 0, metadata));
        }

        @Override
        public void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned, int filesSkipped,
                int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
            completedStatuses.add(status);
            CodeScanRunRecord existing = scanRuns.get(scanRunId);
            if (existing != null) {
                if ("cancelled".equals(existing.status())) {
                    return;
                }
                scanRuns.put(scanRunId, new CodeScanRunRecord(scanRunId, existing.projectKey(), existing.rootPath(),
                        status, existing.startedAt(), Instant.now(), existing.provider(), existing.semanticModel(),
                        existing.dataEgress(), filesDiscovered, filesScanned, filesSkipped, filesRejected,
                        candidatesCreated, metadata));
            }
        }

        @Override
        public void updateRunProgress(UUID scanRunId, Integer filesScanned, Map<String, Object> metadataDelta) {
            progressUpdates.add(metadataDelta);
        }

        @Override
        public int failIncompleteRuns(String reason) {
            int updated = 0;
            for (CodeScanRunRecord run : List.copyOf(scanRuns.values())) {
                if (run.status().equals("queued") || run.status().equals("running")) {
                    scanRuns.put(run.id(), new CodeScanRunRecord(run.id(), run.projectKey(), run.rootPath(),
                            "failed", run.startedAt(), Instant.now(), run.provider(), run.semanticModel(),
                            run.dataEgress(), run.filesDiscovered(), run.filesScanned(), run.filesSkipped(),
                            run.filesRejected(), run.candidatesCreated(), Map.of("reason", reason)));
                    updated++;
                }
            }
            return updated;
        }

        @Override
        public boolean cancelRun(String projectKey, UUID scanRunId, String reason) {
            CodeScanRunRecord run = scanRuns.get(scanRunId);
            if (run == null || !projectKey.equals(run.projectKey())
                    || !(run.status().equals("queued") || run.status().equals("running"))) {
                return false;
            }
            scanRuns.put(run.id(), new CodeScanRunRecord(run.id(), run.projectKey(), run.rootPath(),
                    "cancelled", run.startedAt(), Instant.now(), run.provider(), run.semanticModel(),
                    run.dataEgress(), run.filesDiscovered(), run.filesScanned(), run.filesSkipped(),
                    run.filesRejected(), run.candidatesCreated(), Map.of("reason", reason)));
            return true;
        }

        @Override
        public void upsertFile(CodeFileRecord file) {
            files.put(file.id(), file);
        }

        @Override
        public void deleteFileFacts(String projectKey, UUID fileId) {
            List<UUID> deletedSymbols = symbols.values().stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()) && fileId.equals(symbol.fileId()))
                    .map(CodeSymbolRecord::id)
                    .toList();
            deletedSymbols.forEach(symbols::remove);
            edges.values().removeIf(edge -> deletedSymbols.contains(edge.sourceSymbolId())
                    || (edge.targetSymbolId() != null && deletedSymbols.contains(edge.targetSymbolId())));
            capsules.values().removeIf(capsule -> projectKey.equals(capsule.projectKey())
                    && (fileId.equals(capsule.fileId())
                            || (capsule.symbolId() != null && deletedSymbols.contains(capsule.symbolId()))));
        }

        @Override
        public List<UUID> findCapsuleIdsForFile(String projectKey, UUID fileId) {
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()) && fileId.equals(capsule.fileId()))
                    .map(CodeSemanticCapsuleRecord::id)
                    .sorted()
                    .toList();
        }

        @Override
        public void deleteFilesForRootNotIn(String projectKey, String rootPath, Set<String> keepRelativePaths) {
            if (failOnSweep) {
                throw new IllegalStateException("forced sweep failure");
            }
            List<UUID> deletedFiles = files.values().stream()
                    .filter(file -> projectKey.equals(file.projectKey()))
                    .filter(file -> rootPath.equals(runRootPaths.get(file.scanRunId())))
                    .filter(file -> !keepRelativePaths.contains(file.filePath()))
                    .map(CodeFileRecord::id)
                    .toList();
            deletedFiles.forEach(fileId -> {
                deleteFileFacts(projectKey, fileId);
                files.remove(fileId);
            });
        }

        @Override
        public List<UUID> findCapsuleIdsForRootNotIn(String projectKey, String rootPath,
                Set<String> keepRelativePaths) {
            Set<UUID> staleFileIds = files.values().stream()
                    .filter(file -> projectKey.equals(file.projectKey()))
                    .filter(file -> rootPath.equals(runRootPaths.get(file.scanRunId())))
                    .filter(file -> keepRelativePaths == null || !keepRelativePaths.contains(file.filePath()))
                    .map(CodeFileRecord::id)
                    .collect(java.util.stream.Collectors.toSet());
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()))
                    .filter(capsule -> staleFileIds.contains(capsule.fileId()))
                    .map(CodeSemanticCapsuleRecord::id)
                    .sorted()
                    .toList();
        }

        @Override
        public void upsertSymbol(CodeSymbolRecord symbol) {
            symbols.put(symbol.id(), symbol);
        }

        @Override
        public void upsertEdge(CodeEdgeRecord edge) {
            edges.put(edge.id(), edge);
        }

        @Override
        public boolean capsuleExists(String projectKey, String targetKey, String capsuleKind, String provider,
                String semanticModel, String promptVersion, String summarizerInputHash) {
            return capsules.values().stream()
                    .anyMatch(capsule -> projectKey.equals(capsule.projectKey())
                            && (targetKey.equals(String.valueOf(capsule.symbolId()))
                                    || targetKey.equals(String.valueOf(capsule.fileId())))
                            && capsuleKind.equals(capsule.capsuleKind())
                            && provider.equals(capsule.provider())
                            && semanticModel.equals(capsule.semanticModel())
                            && promptVersion.equals(capsule.promptVersion())
                            && summarizerInputHash.equals(capsule.summarizerInputHash()));
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
                    .filter(capsule -> needle.isBlank()
                            || capsule.summary().toLowerCase(java.util.Locale.ROOT).contains(needle)
                            || capsule.text().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeSemanticCapsuleRecord> findCapsulesForRoot(String projectKey, String rootPath, int limit) {
            return capsules.values().stream()
                    .filter(capsule -> projectKey.equals(capsule.projectKey()))
                    .filter(capsule -> rootPath.equals(runRootPaths.get(capsule.scanRunId())))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<CodeSymbolRecord> findSymbolById(UUID symbolId) {
            if (failOnFindSymbolByIdWithError) {
                throw new AssertionError("forced resolver error");
            }
            return Optional.ofNullable(symbols.get(symbolId));
        }

        @Override
        public Optional<CodeFileRecord> findFileById(String projectKey, UUID fileId) {
            return Optional.ofNullable(files.get(fileId))
                    .filter(file -> projectKey.equals(file.projectKey()));
        }

        @Override
        public List<CodeSymbolRecord> findSymbolsForRun(String projectKey, UUID scanRunId, int limit) {
            return symbols.values().stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .filter(symbol -> scanRunId.equals(symbol.scanRunId()))
                    .sorted(java.util.Comparator
                            .comparing((CodeSymbolRecord symbol) -> "method".equals(symbol.symbolKind()) ? 0 : 1)
                            .thenComparing(CodeSymbolRecord::fqn))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeSymbolRecord> findSymbolsByIds(String projectKey, Set<UUID> symbolIds, int limit) {
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
            String value = ref == null ? "" : ref;
            return symbols.values().stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .filter(symbol -> id != null && id.equals(symbol.id())
                            || value.equals(symbol.name())
                            || value.equals(symbol.fqn())
                            || value.equals(symbol.signature())
                            || files.values().stream()
                                    .anyMatch(file -> file.id().equals(symbol.fileId())
                                            && value.equals(file.filePath())))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes,
                int limit) {
            return edges.values().stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> symbolId.equals(edge.sourceSymbolId()))
                    .filter(edge -> edgeTypes == null || edgeTypes.isEmpty() || edgeTypes.contains(edge.edgeType()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesTo(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
            return edges.values().stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> symbolId.equals(edge.targetSymbolId()))
                    .filter(edge -> edgeTypes == null || edgeTypes.isEmpty() || edgeTypes.contains(edge.edgeType()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CodeEdgeRecord> findEdgesByTargetRefs(String projectKey, Set<String> targetRefs,
                Set<String> edgeTypes, int limit) {
            return edges.values().stream()
                    .filter(edge -> projectKey.equals(edge.projectKey()))
                    .filter(edge -> targetRefs != null && targetRefs.contains(edge.targetRef()))
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
            return Optional.ofNullable(scanRuns.get(scanRunId))
                    .filter(run -> projectKey.equals(run.projectKey()));
        }

        @Override
        public Optional<CodeScanRunRecord> latestCompletedRun(String projectKey) {
            return scanRuns.values().stream()
                    .filter(run -> projectKey.equals(run.projectKey()))
                    .filter(run -> "completed".equals(run.status()) && run.completedAt() != null)
                    .reduce((first, second) -> second);
        }

        private static UUID parseUuid(String value) {
            try {
                return value == null ? null : UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static final class FakeLlmGateway implements LLMGateway {
        private final String answer;
        private final List<GenerationRequest> requests = new ArrayList<>();

        private FakeLlmGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            requests.add(request);
            return new GenerationResponse(answer, request.requestedProvider(), 1L, false, DegradedReason.NONE,
                    10, 5, 0, request.sources().size(), 0L);
        }
    }

    private static final class FakeCodeBaselineVectorIndex implements CodeBaselineVectorIndex {
        private final List<CodeSemanticCapsuleRecord> upserted = new ArrayList<>();
        private final List<UUID> deleted = new ArrayList<>();

        @Override
        public void upsert(CodeSemanticCapsuleRecord capsule) {
            upserted.add(capsule);
        }

        @Override
        public void deleteAll(List<UUID> capsuleIds) {
            deleted.addAll(capsuleIds);
        }

        @Override
        public List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey) {
            return List.of();
        }
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new HashMap<>();
        private final List<MemoryEvent> events = new ArrayList<>();
        private final List<ReviewQueueItem> reviewQueue = new ArrayList<>();

        @Override
        public MemoryItem save(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public Optional<MemoryItem> findById(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<MemoryItem> findBySourceRef(String sourceRef) {
            return items.values().stream()
                    .filter(item -> sourceRef.equals(item.sourceRef()))
                    .findFirst();
        }

        @Override
        public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
            return items.values().stream().toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            return false;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
            events.add(event);
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return events.stream().filter(event -> memoryId.equals(event.memoryId())).toList();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
            reviewQueue.add(item);
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return reviewQueue.stream().filter(item -> memoryId.equals(item.candidateMemoryId())).toList();
        }

        @Override
        public int updateReviewQueueStatus(UUID memoryId, ReviewStatus expectedStatus, ReviewStatus status,
                Instant reviewedAt, Map<String, Object> metadata) {
            return 0;
        }
    }

    private static final class FakeCodeLinkScheduler implements MemoryCodeLinkProjectionScheduler {
        private final boolean throwOnLink;
        private final List<String> projectKeys = new ArrayList<>();

        private FakeCodeLinkScheduler(boolean throwOnLink) {
            this.throwOnLink = throwOnLink;
        }

        @Override
        public void linkMemory(UUID memoryId) {
        }

        @Override
        public void linkAll(String projectKey) {
            projectKeys.add(projectKey);
            if (throwOnLink) {
                throw new IllegalStateException("code-link recheck enqueue failed");
            }
        }

        @Override
        public MemoryCodeLinkProjectionStatus status() {
            return MemoryCodeLinkProjectionStatus.disabled();
        }
    }
}
