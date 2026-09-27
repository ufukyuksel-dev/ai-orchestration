package com.mbworldwideapps.aiorchestration.modules.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.LearningContextProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveRequest;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.Metadata;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.EdgeSummary;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.SymbolNeighborsResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.SymbolSummary;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class LearningContextServiceTest {

    private final MemoryRetrievalService memoryRetrievalService = mock(MemoryRetrievalService.class);
    private final GraphContextRetrievalService graphContextRetrievalService = mock(GraphContextRetrievalService.class);
    private final CodebaseService codebaseService = mock(CodebaseService.class);

    @Test
    void explicitResolveUsesRetrievalWithoutOpeningAutomaticInjectionGate() {
        LearningContextService service = service(properties(false, 1200, 4000, 800, true, false));
        when(memoryRetrievalService.injectionEnabled()).thenReturn(false);
        when(memoryRetrievalService.retrieve("change payment fallback", "PROJECT", null))
                .thenReturn(memory("learned fallback route"));

        LearningContextRequest request = new LearningContextRequest("change payment fallback", "PROJECT",
                null, "change", 3, 1, 1200, null, false, null, false, false, null);
        LearningContextResponse response = service.retrieveExplicit(request);

        assertThat(response.items()).extracting(LearningContextItem::kind).containsExactly("memory");
        verify(memoryRetrievalService).retrieve("change payment fallback", "PROJECT", null);
        verify(memoryRetrievalService, org.mockito.Mockito.never())
                .retrieveForInjection(any(), any(), any());
        verifyNoInteractions(graphContextRetrievalService);

        LearningContextResponse automatic = service.retrieve(request);
        assertThat(automatic.items()).isEmpty();
        assertThat(automatic.warnings()).contains("memory_abstained:memory_injection_disabled");
    }

    @Test
    void disabledFacadeReturnsMemoryFallbackWithoutCallingGraphOrSemantic() {
        LearningContextService service = service(properties(false, 1200, 4000, 800, true, true));
        when(memoryRetrievalService.retrieveForInjection("find scanner", "PROJECT", "user-1"))
                .thenReturn(memory("memory text"));

        LearningContextResponse response = service.retrieve(new LearningContextRequest("find scanner", "PROJECT",
                "user-1", "test", 5, 2, 1200, null, null, null, null, null, "COMBINED"));

        assertThat(response.items()).extracting(LearningContextItem::kind).containsExactly("memory");
        assertThat(response.warnings()).contains("learning_context_disabled");
        assertThat(response.graphContext()).isNull();
        assertThat(response.fallbackUsed()).isTrue();
        verifyNoInteractions(graphContextRetrievalService);
    }

    @Test
    void combinedModeDeduplicatesMemoryIdRepresentedInsideGraphPath() {
        UUID memoryId = UUID.randomUUID();
        when(memoryRetrievalService.retrieveForInjection("why scanner service", "PROJECT", null))
                .thenReturn(memory(memoryId, "Scanner decisions stay project scoped"));
        when(graphContextRetrievalService.retrieve(any(GraphContextRetrieveRequest.class)))
                .thenReturn(graphContextWithMemoryPath(memoryId));
        LearningContextService service = service(properties(true, 500, 800, 800, true, false));

        LearningContextResponse response = service.retrieve(new LearningContextRequest(
                "why scanner service", "PROJECT", null, "test", 5, 2, 500,
                null, false, null, null, null, "COMBINED"));

        assertThat(response.items()).extracting(LearningContextItem::kind).containsExactly("graph_path");
        assertThat(response.items().getFirst().metadata().get("memoryIds"))
                .isEqualTo(List.of(memoryId.toString()));
        assertThat(response.memoryContext().items()).extracting(MemoryContextItem::memoryId)
                .containsExactly(memoryId);
        assertThat(response.metadata())
                .containsEntry("requestedContextMode", "COMBINED")
                .containsEntry("effectiveContextMode", "COMBINED")
                .containsEntry("selectedMemoryCount", 1)
                .containsEntry("crossLaneMemoryDeduplicatedCount", 1);
        assertThat(response.warnings()).contains("learning_context_cross_lane_memory_deduplicated");
    }

    @Test
    void graphPathDedupKeepsOverlappingPathsWhenTheyAddNovelNodes() {
        LearningContextService service = service(properties(true, 500, 800, 800, true, false));
        when(memoryRetrievalService.retrieveForInjection("overlap", "PROJECT", null))
                .thenReturn(MemoryContextResponse.empty());
        when(graphContextRetrievalService.retrieve(any(GraphContextRetrieveRequest.class)))
                .thenReturn(overlappingGraphContext());

        LearningContextResponse response = service.retrieve(new LearningContextRequest("overlap", "PROJECT",
                null, "test", 5, 2, 500, null, false, null, null, null, "SCANNER_ONLY"));

        assertThat(response.items()).extracting(LearningContextItem::id)
                .containsExactly("path-a", "path-b");
        assertThat(response.warnings()).contains("learning_context_deduplicated");
        assertThat(response.metadata()).containsEntry("deduplicatedCount", 1);
    }

    @Test
    void compactHybridBundleKeepsMeaningStructureAndTestsInsideRenderedBudget() {
        UUID classId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        UUID helperId = UUID.randomUUID();
        UUID testClassId = UUID.randomUUID();
        UUID testMethodId = UUID.randomUUID();
        UUID unrelatedTestClassId = UUID.randomUUID();
        UUID unrelatedTestMethodId = UUID.randomUUID();
        String productionPath = "src/main/java/example/ReversalRequestHandler.java";
        String testPath = "src/test/java/example/ReversalRequestHandlerTest.java";
        SymbolSummary clazz = symbol(classId, "class", "ReversalRequestHandler",
                "example.ReversalRequestHandler", 9, 41);
        SymbolNeighborsResponse classGraph = new SymbolNeighborsResponse("PROJECT",
                List.of(clazz,
                        symbol(processId, "method", "processRequest",
                                "example.ReversalRequestHandler#processRequest", 13, 40),
                        symbol(helperId, "method", "copyResponseFields",
                                "example.ReversalRequestHandler#copyResponseFields", 30, 38)),
                List.of(
                        edge(classId, processId, processId.toString(), "DECLARES", productionPath),
                        edge(classId, helperId, helperId.toString(), "DECLARES", productionPath),
                        edge(classId, null, "Transactional", "ANNOTATED_WITH", productionPath)),
                false, false, List.of(), null);
        SymbolSummary testClass = symbol(testClassId, "class", "ReversalRequestHandlerTest",
                "example.ReversalRequestHandlerTest", 17, 61);
        SymbolSummary unrelatedTestClass = symbol(unrelatedTestClassId, "class", "OtherReversalRequestHandlerTest",
                "other.OtherReversalRequestHandlerTest", 10, 40);
        SymbolNeighborsResponse testGraph = new SymbolNeighborsResponse("PROJECT",
                List.of(unrelatedTestClass,
                        symbol(unrelatedTestMethodId, "method", "testUnrelatedBehavior",
                                "other.OtherReversalRequestHandlerTest#testUnrelatedBehavior", 12, 30),
                        testClass,
                        symbol(testMethodId, "method", "testProcessRequest",
                                "example.ReversalRequestHandlerTest#testProcessRequest", 25, 60)),
                List.of(
                        edge(unrelatedTestClassId, unrelatedTestMethodId, unrelatedTestMethodId.toString(),
                                "DECLARES", "src/test/java/other/OtherReversalRequestHandlerTest.java"),
                        edge(testClassId, testMethodId, testMethodId.toString(), "DECLARES", testPath)),
                false, false, List.of(), null);
        when(codebaseService.neighbors(eq(classId.toString()), eq(1), any(), eq("PROJECT")))
                .thenReturn(classGraph);
        when(codebaseService.neighbors(eq("ReversalRequestHandlerTest"), eq(1), any(), eq("PROJECT")))
                .thenReturn(testGraph);
        when(graphContextRetrievalService.retrieve(any(GraphContextRetrieveRequest.class)))
                .thenReturn(graphContextWithCapsule(classId, productionPath));

        LearningContextService service = service(properties(true, 600, 600, 1600, false, false));
        LearningContextResponse response = service.retrieve(new LearningContextRequest(
                "reversal idempotency", "PROJECT", null, "analyst", 1, 1, 600,
                false, false, null, null, null, "SCANNER_ONLY"));
        String prompt = new LearningContextBuilder(redactor()).buildPromptBlock(response);

        assertThat(response.items()).extracting(LearningContextItem::kind).containsExactly("graph_path");
        assertThat(prompt).contains(
                "- [graph_path] ReversalRequestHandler @ " + productionPath + ":9-41",
                "meaning: Purpose: Handles reversal requests",
                "annotations: @Transactional",
                "transaction: class-level @Transactional",
                "methods: processRequest@13, copyResponseFields@30",
                "exact test: ReversalRequestHandlerTest#testProcessRequest@25 @ " + testPath + ":17-61",
                "unknown:",
                "Method/test bodies are not supplied; unlisted behavior and test assertions are unknown.",
                "Important dependencies", "Side effects");
        assertThat(prompt).doesNotContain("testUnrelatedBehavior", "OtherReversalRequestHandlerTest.java");
        assertThat(prompt.indexOf("evidence:")).isLessThan(prompt.indexOf("details:"));
        assertThat(prompt.indexOf("details:")).isLessThan(prompt.indexOf("unknown:"));
        assertThat(TokenEstimator.estimate(prompt)).isEqualTo(response.tokenEstimate());
        assertThat(response.tokenEstimate()).isLessThanOrEqualTo(600);

        LearningContextResponse tightlyBounded = service(properties(true, 600, 600, 180, false, false))
                .retrieve(new LearningContextRequest(
                        "reversal idempotency", "PROJECT", null, "analyst", 1, 1, 600,
                        false, false, null, null, null, "SCANNER_ONLY"));
        assertThat(tightlyBounded.items().getFirst().text())
                .hasSizeLessThanOrEqualTo(180)
                .endsWith("unknown:\n" +
                        "Method/test bodies are not supplied; unlisted behavior and test assertions are unknown.");

        SymbolNeighborsResponse fuzzyOnlyTestGraph = new SymbolNeighborsResponse("PROJECT",
                List.of(unrelatedTestClass,
                        symbol(unrelatedTestMethodId, "method", "testUnrelatedBehavior",
                                "other.OtherReversalRequestHandlerTest#testUnrelatedBehavior", 12, 30)),
                List.of(edge(unrelatedTestClassId, unrelatedTestMethodId, unrelatedTestMethodId.toString(),
                        "DECLARES", "src/test/java/other/OtherReversalRequestHandlerTest.java")),
                false, false, List.of(), null);
        when(codebaseService.neighbors(eq("ReversalRequestHandlerTest"), eq(1), any(), eq("PROJECT")))
                .thenReturn(fuzzyOnlyTestGraph);

        LearningContextResponse withoutExactTest = service(properties(true, 600, 600, 1600, false, false))
                .retrieve(new LearningContextRequest(
                        "reversal idempotency", "PROJECT", null, "analyst", 1, 1, 600,
                        false, false, null, null, null, "SCANNER_ONLY"));
        assertThat(withoutExactTest.items().getFirst().text())
                .doesNotContain("exact test", "testUnrelatedBehavior", "OtherReversalRequestHandlerTest");
    }

    @Test
    void combinedBudgetPrioritizesPostgresLexicalPathAndRelevantMemoryOverExtraSemanticPath() {
        when(memoryRetrievalService.retrieveForInjection("exact manager tool", "PROJECT", null))
                .thenReturn(memory("Remember the project-specific audit rationale."));
        when(graphContextRetrievalService.retrieve(any(GraphContextRetrieveRequest.class)))
                .thenReturn(graphContextWithLexicalAndSemanticPaths());
        LearningContextService service = service(properties(true, 180, 180, 500, true, false));

        LearningContextResponse response = service.retrieve(new LearningContextRequest(
                "exact manager tool", "PROJECT", null, "analyst", 2, 1, 180,
                true, false, null, null, null, "COMBINED"));

        assertThat(response.items()).extracting(LearningContextItem::id)
                .startsWith("lexical-path")
                .contains("lexical-path");
        assertThat(response.items()).extracting(LearningContextItem::kind).contains("memory");
        assertThat(response.items().stream()
                .filter(item -> "lexical-path".equals(item.id()))
                .findFirst().orElseThrow().metadata())
                .containsEntry("retrievalLane", "postgres-lexical");
        assertThat(response.tokenEstimate()).isLessThanOrEqualTo(180);
    }

    @Test
    void enabledFacadeFallsBackToMemoryWhenGraphThrows() {
        LearningContextService service = service(properties(true, 500, 800, 800, true, false));
        when(memoryRetrievalService.retrieveForInjection("graph down", "PROJECT", null))
                .thenReturn(memory("memory rule"));
        when(graphContextRetrievalService.retrieve(any(GraphContextRetrieveRequest.class)))
                .thenThrow(new IllegalStateException("graph down"));

        LearningContextResponse response = service.retrieve(new LearningContextRequest("graph down", "PROJECT",
                null, "test", 5, 2, 500, null, null, null, null, null, "COMBINED"));

        assertThat(response.items()).extracting(LearningContextItem::kind).containsExactly("memory");
        assertThat(response.graphAvailable()).isFalse();
        assertThat(response.fallbackUsed()).isTrue();
        assertThat(response.warnings()).contains("graph_context_unavailable:IllegalStateException");
    }

    @Test
    void nonCatastrophicItemsAreTrimmedWhenTokenBudgetIsExhausted() {
        LearningContextService service = service(properties(false, 40, 100, 4000, true, false, 10));
        when(memoryRetrievalService.retrieveForInjection("budget", "PROJECT", null))
                .thenReturn(memory("x".repeat(40), "y".repeat(40)));

        LearningContextResponse response = service.retrieve(new LearningContextRequest("budget", "PROJECT",
                null, "test", 5, 2, 40, null, null, null, null, null, "MEMORY_ONLY"));

        assertThat(response.items()).hasSize(1);
        assertThat(response.warnings()).contains("learning_context_budget_applied");
        assertThat(response.metadata()).containsEntry("droppedByBudgetCount", 1);
    }

    @Test
    void dropsCatastrophicItemsBeforePromptAssembly() {
        LearningContextService service = service(properties(false, 10, 100, 4000, true, false));
        when(memoryRetrievalService.retrieveForInjection("large", "PROJECT", null))
                .thenReturn(memory("x".repeat(1000)));

        LearningContextResponse response = service.retrieve(new LearningContextRequest("large", "PROJECT",
                null, "test", 5, 2, 10, null, null, null, null, null, "MEMORY_ONLY"));

        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).contains("learning_context_item_too_large:memory");
        assertThat(response.metadata()).containsEntry("droppedCatastrophicCount", 1);
    }

    private LearningContextService service(LearningContextProperties properties) {
        when(memoryRetrievalService.injectionEnabled()).thenReturn(true);
        return new LearningContextService(properties, memoryRetrievalService, graphContextRetrievalService,
                codebaseService, redactor());
    }

    private static LearningContextProperties properties(boolean enabled, int defaultBudget, int maxBudget,
            int maxRenderedChars, boolean includeMemory, boolean includeSemantic) {
        return properties(enabled, defaultBudget, maxBudget, maxRenderedChars, includeMemory, includeSemantic, 1);
    }

    private static LearningContextProperties properties(boolean enabled, int defaultBudget, int maxBudget,
            int maxRenderedChars, boolean includeMemory, boolean includeSemantic, int catastrophicMultiplier) {
        return new LearningContextProperties(enabled, 5, 20, 2, 3, defaultBudget, maxBudget, catastrophicMultiplier,
                maxRenderedChars, includeMemory, includeSemantic, false, false, false);
    }

    private static MemoryContextResponse memory(String... texts) {
        List<MemoryContextItem> items = new java.util.ArrayList<>();
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            String summary = "Memory summary " + i;
            String text = texts[i];
            items.add(new MemoryContextItem(id, UUID.randomUUID(), "project:" + id,
                    MemoryScope.PROJECT, "PROJECT", MemoryType.RULE, summary, text, 0.9, false,
                    Math.max(1, text.length() / 4), 0.77, Instant.now()));
        }
        int tokenEstimate = items.stream().mapToInt(MemoryContextItem::tokenEstimate).sum();
        return new MemoryContextResponse(items, ids, items.size(), tokenEstimate,
                Map.of("user", 0, "global", 0, "project", items.size(), "episodic", 0), 0, 0, 5L);
    }

    private static MemoryContextResponse memory(UUID memoryId, String text) {
        MemoryContextItem item = new MemoryContextItem(memoryId, UUID.randomUUID(), "project:" + memoryId,
                MemoryScope.PROJECT, "PROJECT", MemoryType.RULE, "Memory summary", text, 0.9, false,
                Math.max(1, text.length() / 4), 0.77, Instant.now());
        return new MemoryContextResponse(List.of(item), List.of(memoryId), 1, item.tokenEstimate(),
                Map.of("user", 0, "global", 0, "project", 1, "episodic", 0), 0, 0, 5L);
    }

    private static GraphContextRetrieveResponse graphContext() {
        GraphNode node = new GraphNode("node-1", "CodeSymbol", "PROJECT", "FooService",
                "Foo flow summary", "Foo details", "src/main/java/FooService.java", 12, 32,
                0.91, false, Map.of());
        GraphEdge edge = new GraphEdge("edge-1", "CALLS", "node-1", "node-1", "resolved", 0.9,
                1.0, false, Map.of());
        GraphPath path = new GraphPath("path-1", List.of("node-1"), List.of("edge-1"), 0.92,
                "CODE_CAPSULE", "capsule-1");
        return new GraphContextRetrieveResponse("PROJECT", "hybrid", List.of(path), List.of(node), List.of(edge),
                List.of(), List.of(), List.of("node-1"), List.of(), List.of(), 40,
                new Metadata(1, 0, 1, 1, 1, true, false, 3L, Map.of()));
    }

    private static GraphContextRetrieveResponse graphContextWithCapsule(UUID classId, String filePath) {
        String capsuleId = "PROJECT|" + classId + "|class";
        String meaning = "Purpose: Handles reversal requests by producing the reversal response.";
        GraphNode symbol = new GraphNode(classId.toString(), "CodeSymbol", "PROJECT",
                "example.ReversalRequestHandler", "", "", filePath, 9, 41, 1.0, false,
                Map.of("symbolId", classId.toString()));
        GraphNode capsule = new GraphNode(capsuleId, "CodeCapsule", "PROJECT", classId.toString(), meaning,
                meaning + "\n\nImportant dependencies: hceAuditService and TmsRequestCommon."
                        + "\nSide effects: writes audit information and constructs a response message.",
                filePath, 9, 41, 0.9, false, Map.of("symbolId", classId.toString(), "targetKey", classId.toString()));
        GraphEdge summary = new GraphEdge("summary-edge", "SUMMARIZES", classId.toString(), capsuleId,
                "resolved", 1.0, 1.0, false, Map.of());
        GraphPath path = new GraphPath("path-hybrid", List.of(capsuleId, classId.toString()),
                List.of(summary.id()), 0.95, "CODE_CAPSULE", capsuleId);
        return new GraphContextRetrieveResponse("PROJECT", "hybrid", List.of(path), List.of(capsule, symbol),
                List.of(summary), List.of(), List.of(), List.of(classId.toString()), List.of(capsuleId), List.of(),
                120, new Metadata(1, 0, 2, 1, 1, true, false, 3L, Map.of()));
    }

    private static GraphContextRetrieveResponse graphContextWithMemoryPath(UUID memoryId) {
        GraphNode memory = new GraphNode(memoryId.toString(), "Memory", "PROJECT", "Scanner rule",
                "Scanner decisions stay project scoped", "", null, null, null, 0.88, false, Map.of());
        GraphNode code = new GraphNode("scanner-service", "CodeSymbol", "PROJECT", "ScannerService",
                "Current scanner implementation", "", "src/main/java/ScannerService.java", 10, 40,
                0.92, false, Map.of());
        GraphEdge edge = new GraphEdge("memory-code", "EVIDENCES", memory.id(), code.id(), "exact",
                0.9, 1.0, false, Map.of());
        GraphPath path = new GraphPath("memory-code-path", List.of(memory.id(), code.id()), List.of(edge.id()),
                0.94, "MEMORY", memory.id());
        return new GraphContextRetrieveResponse("PROJECT", "hybrid", List.of(path), List.of(memory, code),
                List.of(edge), List.of(), List.of(memory.id()), List.of(code.id()), List.of(), List.of(), 80,
                new Metadata(1, 1, 2, 1, 1, true, false, false, 3L, Map.of()));
    }

    private static GraphContextRetrieveResponse overlappingGraphContext() {
        GraphNode node1 = new GraphNode("node-1", "CodeSymbol", "PROJECT", "FooService",
                "Foo summary", "Foo details", "src/Foo.java", 10, 20, 0.91, false, Map.of());
        GraphNode node2 = new GraphNode("node-2", "CodeSymbol", "PROJECT", "BarService",
                "Bar summary", "Bar details", "src/Bar.java", 11, 21, 0.9, false, Map.of());
        GraphNode node3 = new GraphNode("node-3", "CodeSymbol", "PROJECT", "BazService",
                "Baz summary", "Baz details", "src/Baz.java", 12, 22, 0.89, false, Map.of());
        GraphEdge edge = new GraphEdge("edge-1", "CALLS", "node-1", "node-2", "resolved", 0.9,
                1.0, false, Map.of());
        GraphPath pathA = new GraphPath("path-a", List.of("node-1", "node-2"), List.of("edge-1"), 0.92,
                "CODE_CAPSULE", "capsule-a");
        GraphPath pathB = new GraphPath("path-b", List.of("node-2", "node-3"), List.of("edge-1"), 0.91,
                "CODE_CAPSULE", "capsule-b");
        GraphPath subset = new GraphPath("path-subset", List.of("node-1"), List.of(), 0.9,
                "CODE_CAPSULE", "capsule-c");
        return new GraphContextRetrieveResponse("PROJECT", "hybrid", List.of(pathA, pathB, subset),
                List.of(node1, node2, node3), List.of(edge), List.of(), List.of(),
                List.of("node-1", "node-2", "node-3"), List.of(), List.of(), 40,
                new Metadata(3, 0, 3, 1, 1, true, false, 3L, Map.of()));
    }

    private static GraphContextRetrieveResponse graphContextWithLexicalAndSemanticPaths() {
        GraphNode lexical = new GraphNode("lexical", "CodeSymbol", "PROJECT", "ContextGraphMcpTool",
                "Exact identifier evidence", "", "src/ContextGraphMcpTool.java", 10, 30, 0.40, false,
                Map.of("retrievalLane", "postgres-lexical"));
        GraphNode lexicalTarget = new GraphNode("lexical-target", "CodeFile", "PROJECT",
                "ContextGraphMcpTool.java", "", "", "src/ContextGraphMcpTool.java", null, null,
                0.40, false, Map.of());
        GraphNode semantic = new GraphNode("semantic", "CodeCapsule", "PROJECT", "OtherService",
                "A higher-scoring but less exact semantic result", "x".repeat(500), "src/OtherService.java",
                1, 80, 0.99, false, Map.of("retrievalLane", "qdrant-code-semantic"));
        GraphNode semanticTarget = new GraphNode("semantic-target", "CodeSymbol", "PROJECT", "OtherService",
                "", "", "src/OtherService.java", 1, 80, 0.99, false, Map.of());
        GraphEdge lexicalEdge = new GraphEdge("lexical-edge", "DECLARES", lexicalTarget.id(), lexical.id(),
                "resolved", 1.0, 1.0, false, Map.of());
        GraphEdge semanticEdge = new GraphEdge("semantic-edge", "SUMMARIZES", semanticTarget.id(), semantic.id(),
                "resolved", 1.0, 1.0, false, Map.of());
        GraphPath lexicalPath = new GraphPath("lexical-path", List.of(lexical.id(), lexicalTarget.id()),
                List.of(lexicalEdge.id()), 0.40, "CODE_SYMBOL", lexical.id());
        GraphPath semanticPath = new GraphPath("semantic-path", List.of(semantic.id(), semanticTarget.id()),
                List.of(semanticEdge.id()), 0.99, "CODE_CAPSULE", semantic.id());
        return new GraphContextRetrieveResponse("PROJECT", "hybrid", List.of(semanticPath, lexicalPath),
                List.of(lexical, lexicalTarget, semantic, semanticTarget), List.of(lexicalEdge, semanticEdge),
                List.of(), List.of(), List.of(lexical.id(), semanticTarget.id()), List.of(semantic.id()), List.of(),
                180, new Metadata(2, 0, 4, 2, 2, true, false, 3L, Map.of()));
    }

    private static SymbolSummary symbol(UUID id, String kind, String name, String fqn, int start, int end) {
        return new SymbolSummary(id, kind, name, fqn, "", "method".equals(kind) ? "method" : "component",
                start, end);
    }

    private static EdgeSummary edge(UUID source, UUID target, String targetRef, String edgeType, String filePath) {
        return new EdgeSummary(UUID.randomUUID(), source, target, targetRef, edgeType, "syntactic", 1.0,
                Map.of("filePath", filePath));
    }

    private static ScannerPayloadRedactor redactor() {
        return new ScannerPayloadRedactor(new PiiScrubber(), new SecretScanService(), new MemoryPolicyEvaluator(),
                new PolicyProperties(true, false, "", List.of("ACME-LOAN-001", "internal-prod-svc")));
    }
}
