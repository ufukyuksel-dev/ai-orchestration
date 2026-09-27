package com.mbworldwideapps.aiorchestration.modules.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.config.GraphContextRetrievalProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.junit.jupiter.api.Test;

class GraphContextPackerTest {

    @Test
    void enforcesTokenBudgetAndReportsDroppedOversizedEvidence() {
        GraphContextRetrievalProperties properties = properties(30);
        GraphContextPacker packer = new GraphContextPacker(properties, redactor());
        GraphNode compact = new GraphNode("compact", "CodeSymbol", "PROJECT", "Foo", "small evidence", "",
                "src/Foo.java", 1, 2, 0.95, false, Map.of());
        GraphNode oversized = new GraphNode("oversized", "CodeCapsule", "PROJECT", "Large capsule",
                "x".repeat(500), "y".repeat(500), "src/Large.java", 1, 200, 0.90, false, Map.of());
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(),
                List.of(compact, oversized), List.of(), List.of());

        GraphContextRetrieveResponse response = packer.pack("PROJECT", "hybrid",
                new GraphContextSeedResult(List.of(), List.of(), false), expansion, List.of(), Instant.now());

        assertThat(response.tokenEstimate()).isLessThanOrEqualTo(properties.tokenBudget());
        assertThat(response.nodes()).extracting(GraphNode::id).containsExactly("compact");
        assertThat(response.warnings()).extracting(GraphWarning::code).contains("token_budget_applied");
        assertThat(response.metadata().details())
                .containsEntry("tokenBudget", 30)
                .containsEntry("tokenBudgetApplied", true)
                .containsEntry("droppedNodeCount", 1);
    }

    @Test
    void tokenEstimateNeverExceedsBudgetWhenEveryCandidateIsTooLarge() {
        GraphContextRetrievalProperties properties = properties(10);
        GraphContextPacker packer = new GraphContextPacker(properties, redactor());
        GraphNode oversized = new GraphNode("oversized", "Memory", "PROJECT", "Rule",
                "x".repeat(500), "", null, null, null, 0.99, false, Map.of());
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(), List.of(oversized),
                List.of(), List.of());

        GraphContextRetrieveResponse response = packer.pack("PROJECT", "hybrid",
                new GraphContextSeedResult(List.of(), List.of(), false), expansion, List.of(), Instant.now());

        assertThat(response.nodes()).isEmpty();
        assertThat(response.tokenEstimate()).isLessThanOrEqualTo(10);
        assertThat(response.metadata().details()).containsEntry("tokenBudgetApplied", true);
    }

    @Test
    void maxPathPackingReservesMemoryAndPostgresLexicalPathsBeforeHigherScoringSemanticPaths() {
        GraphContextRetrievalProperties properties = properties(4_000, 2);
        GraphContextPacker packer = new GraphContextPacker(properties, redactor());
        GraphNode memory = new GraphNode("memory", "Memory", "PROJECT", "decision", "why", "", null,
                null, null, 0.8, false, Map.of());
        GraphNode memoryFile = new GraphNode("memory-file", "CodeFile", "PROJECT", "MemoryFile", "", "",
                "src/MemoryFile.java", null, null, 0.8, false, Map.of());
        GraphNode code = new GraphNode("code", "CodeSymbol", "PROJECT", "Code", "", "", "src/Code.java",
                1, 2, 0.99, false, Map.of());
        GraphNode codeTarget = new GraphNode("code-target", "CodeSymbol", "PROJECT", "Target", "", "",
                "src/Target.java", 1, 2, 0.99, false, Map.of());
        GraphNode codeTwo = new GraphNode("code-two", "CodeSymbol", "PROJECT", "CodeTwo", "", "",
                "src/CodeTwo.java", 1, 2, 0.98, false, Map.of());
        GraphEdge memoryEdge = new GraphEdge("memory-edge", "MENTIONS", "memory", "memory-file",
                "typed_locator", 0.8, 1.0, false, Map.of());
        GraphEdge codeEdge = new GraphEdge("code-edge", "CALLS", "code", "code-target", "resolved", 0.99,
                1.0, false, Map.of());
        GraphEdge codeTwoEdge = new GraphEdge("code-two-edge", "CALLS", "code-two", "code-target", "resolved",
                0.98, 1.0, false, Map.of());
        GraphPath memoryPath = new GraphPath("memory-path", List.of("memory", "memory-file"),
                List.of("memory-edge"), 0.60, GraphContextSeedKind.MEMORY.name(), "memory");
        GraphPath codePath = new GraphPath("code-path", List.of("code", "code-target"), List.of("code-edge"),
                0.99, GraphContextSeedKind.CODE_SYMBOL.name(), "code");
        GraphPath codeTwoPath = new GraphPath("code-two-path", List.of("code-two", "code-target"),
                List.of("code-two-edge"), 0.98, GraphContextSeedKind.CODE_SYMBOL.name(), "code-two");
        GraphContextExpansion expansion = new GraphContextExpansion(true, null,
                List.of(codePath, codeTwoPath, memoryPath),
                List.of(memory, memoryFile, code, codeTarget, codeTwo),
                List.of(memoryEdge, codeEdge, codeTwoEdge), List.of());
        GraphContextSeed lexicalSeed = new GraphContextSeed(GraphContextSeedKind.CODE_SYMBOL, "code", "PROJECT",
                0.40, "Code", "", "", null, null, null, null, null, null,
                Map.of(GraphContextSeed.METADATA_RETRIEVAL_LANE, GraphContextSeed.LANE_POSTGRES_LEXICAL));

        GraphContextRetrieveResponse response = packer.pack("PROJECT", "hybrid",
                new GraphContextSeedResult(List.of(lexicalSeed), List.of(), false), expansion, List.of(),
                Instant.now());

        assertThat(response.paths()).extracting(GraphPath::pathId)
                .containsExactly("memory-path", "code-path");
        assertThat(response.edges()).extracting(GraphEdge::id).contains("memory-edge", "code-edge");
    }

    private static GraphContextRetrievalProperties properties(int tokenBudget) {
        return properties(tokenBudget, 12);
    }

    private static GraphContextRetrievalProperties properties(int tokenBudget, int maxPaths) {
        return new GraphContextRetrievalProperties(true, 5, 20, 2, 3, 8, 80, 160, maxPaths, 12, 32,
                800, 500, tokenBudget, false, false);
    }

    private static GraphContextRedactor redactor() {
        ScannerPayloadRedactor payloadRedactor = new ScannerPayloadRedactor(new PiiScrubber(),
                new SecretScanService(), new MemoryPolicyEvaluator(),
                new PolicyProperties(true, false, "", List.of()));
        return new GraphContextRedactor(payloadRedactor);
    }
}
