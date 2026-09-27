package com.mbworldwideapps.aiorchestration.modules.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.GraphContextRetrievalProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryInjectionFilter;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.junit.jupiter.api.Test;

class GraphContextRetrievalServiceTest {

    @Test
    void referencesDefaultToOmittedWithGenericWarning() {
        var service = service(new StubSeedProvider(new GraphContextSeedResult(List.of(), List.of(), false)),
                (project, seeds, options) -> {
                    assertThat(options.includeSharedReferences()).isFalse();
                    return emptyExpansion();
                });
        var result = service.retrieve(new GraphContextRetrieveRequest("query","PROJECT_A",5,1,false,"hybrid",false,false));
        assertThat(result.warnings()).extracting(GraphWarning::code).contains("shared_references_omitted");
    }

    @Test
    void fallsBackToSeedOnlyWhenNeo4jUnavailable() {
        GraphContextSeed seed = memorySeed("Memory summary");
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(List.of(seed),
                List.of(), false)), (projectKey, seeds, options) -> GraphContextExpansion.unavailable("down"));

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 5, 2, false, "hybrid", false, false));

        assertThat(response.nodes()).extracting(GraphNode::kind).contains("Memory");
        assertThat(response.warnings()).extracting(GraphWarning::code).contains("graph_unavailable");
        assertThat(response.metadata().graphAvailable()).isFalse();
    }

    @Test
    void neo4jVectorModeIsDeferredToQdrantSeed() {
        ModeAwareSeedProvider seedProvider = new ModeAwareSeedProvider();
        GraphContextRetrievalService service = service(seedProvider,
                (projectKey, seeds, options) -> emptyExpansion());

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 5, 2, false, "neo4j-vector", false, false));

        assertThat(response.retrievalMode()).isEqualTo("qdrant-seed");
        assertThat(response.warnings()).extracting(GraphWarning::code).contains("neo4j_vector_deferred");
        assertThat(seedProvider.hybridFusionEnabled).isFalse();
    }

    @Test
    void hybridModeExplicitlyEnablesSemanticLexicalFusion() {
        ModeAwareSeedProvider seedProvider = new ModeAwareSeedProvider();
        GraphContextRetrievalService service = service(seedProvider,
                (projectKey, seeds, options) -> emptyExpansion());

        service.retrieve(new GraphContextRetrieveRequest("scanner", "PROJECT_A", 5, 2, false,
                "hybrid", false, false));

        assertThat(seedProvider.hybridFusionEnabled).isTrue();
    }

    @Test
    void includeMemorySeedsFalseKeepsExplicitGraphRetrievalCodeOnly() {
        GraphContextSeed code = codeSeed("PROJECT_A|symbol-1|class");
        GraphContextSeed memory = memorySeed("Unrelated global rule");
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(
                List.of(code, memory), List.of(), false)),
                (projectKey, seeds, options) -> emptyExpansion());

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 5, 1, false, "hybrid", false, false, false));

        assertThat(response.nodes()).extracting(GraphNode::kind).containsExactly("CodeCapsule");
        assertThat(response.metadata().codeSeedCount()).isEqualTo(1);
        assertThat(response.metadata().memorySeedCount()).isZero();
    }

    @Test
    void includeMemorySeedsFalseRemovesMemoryReachedWhileExpandingCodeSeed() {
        String codeKey = "PROJECT_A|symbol-1|class";
        GraphContextSeed code = codeSeed(codeKey);
        GraphNode codeNode = new GraphNode(codeKey, "CodeCapsule", "PROJECT_A", "ExampleService",
                "Purpose: example", "Purpose: example", null, null, null, 0.9, false, Map.of());
        GraphNode neighborCodeNode = new GraphNode("symbol-2", "CodeSymbol", "PROJECT_A", "ExampleDependency",
                "Dependency summary", null, "src/main/java/ExampleDependency.java", 10, 20, 0.8, false, Map.of());
        GraphNode globalMemoryNode = new GraphNode("memory-global", "Memory", "GLOBAL", "Global rule",
                "Global memory summary", null, null, null, null, 0.7, false, Map.of());
        GraphEdge memoryEdge = new GraphEdge("edge-memory", "RELATED_TO", codeNode.id(), globalMemoryNode.id(),
                "exact", 0.8, 1.0, false, Map.of());
        GraphEdge codeEdge = new GraphEdge("edge-code", "CALLS", codeNode.id(), neighborCodeNode.id(),
                "exact", 0.9, 1.0, false, Map.of());
        GraphPath memoryPath = new GraphPath("path-memory", List.of(codeNode.id(), globalMemoryNode.id()),
                List.of(memoryEdge.id()), 0.9, "CODE_CAPSULE", codeKey);
        GraphPath codePath = new GraphPath("path-code", List.of(codeNode.id(), neighborCodeNode.id()),
                List.of(codeEdge.id()), 0.8, "CODE_CAPSULE", codeKey);
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(memoryPath, codePath),
                List.of(codeNode, globalMemoryNode, neighborCodeNode), List.of(memoryEdge, codeEdge), List.of());
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(
                List.of(code), List.of(), false)), (projectKey, seeds, options) -> expansion);

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 5, 1, false, "hybrid", false, false, false));

        assertThat(response.nodes()).extracting(GraphNode::id).containsExactly(codeKey, neighborCodeNode.id());
        assertThat(response.edges()).extracting(GraphEdge::id).containsExactly(codeEdge.id());
        assertThat(response.paths()).extracting(GraphPath::pathId).containsExactly(codePath.pathId());
        assertThat(response.memoryIds()).isEmpty();
        assertThat(response.sourceRefs()).noneMatch(ref -> "memory".equals(ref.kind()));
        assertThat(response.metadata().expandedNodeCount()).isEqualTo(2);
        assertThat(response.metadata().expandedEdgeCount()).isEqualTo(1);
        assertThat(response.metadata().expandedPathCount()).isEqualTo(1);
    }

    @Test
    void combinedModeKeepsMemoryReachedDuringTraversal() {
        String codeKey = "PROJECT_A|symbol-1|class";
        GraphContextSeed code = codeSeed(codeKey);
        GraphContextSeed memorySeed = memorySeed("Why the scanner is project scoped");
        GraphNode codeNode = new GraphNode(codeKey, "CodeCapsule", "PROJECT_A", "ScannerService",
                "Scanner purpose", "", "src/ScannerService.java", 10, 20, 0.9, false, Map.of());
        GraphNode memoryNode = new GraphNode(memorySeed.key(), "Memory", "PROJECT_A", "Scanner decision",
                "Why the scanner is project scoped", "", null, null, null, 0.85, false, Map.of());
        GraphEdge edge = new GraphEdge("memory-code", "EVIDENCES", memoryNode.id(), codeNode.id(), "exact",
                0.9, 1.0, false, Map.of());
        GraphPath path = new GraphPath("combined-path", List.of(memoryNode.id(), codeNode.id()),
                List.of(edge.id()), 0.92, "MEMORY", memoryNode.id());
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(path),
                List.of(memoryNode, codeNode), List.of(edge), List.of());
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(
                List.of(code, memorySeed), List.of(), false)), (projectKey, seeds, options) -> expansion);

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner why",
                "PROJECT_A", 5, 2, false, "hybrid", false, false, true, true));

        assertThat(response.nodes()).extracting(GraphNode::id).contains(memoryNode.id(), codeNode.id());
        assertThat(response.paths()).extracting(GraphPath::pathId).containsExactly(path.pathId());
        assertThat(response.memoryIds()).contains(memoryNode.id());
        assertThat(response.metadata().memorySeedCount()).isEqualTo(1);
    }

    @Test
    void linkedRuleOriginReachedFromCodeTraversalIsRemovedWithItsEdgesAndPaths() {
        String codeKey = "PROJECT_A|symbol-1|class";
        UUID promotedOriginId = UUID.randomUUID();
        GraphContextSeed code = codeSeed(codeKey);
        GraphNode codeNode = new GraphNode(codeKey, "CodeCapsule", "PROJECT_A", "Controller",
                "Controller summary", "", "src/Controller.java", 1, 20, 0.9, false, Map.of());
        GraphNode promotedMemory = new GraphNode(promotedOriginId.toString(), "Memory", "PROJECT_A",
                "Controller rule", "Business logic must stay out", "", null, null, null, 0.95, false, Map.of());
        GraphEdge edge = new GraphEdge("promoted-memory-edge", "EVIDENCES", promotedMemory.id(), codeNode.id(),
                "exact", 1.0, 1.0, false, Map.of());
        GraphPath path = new GraphPath("promoted-memory-path", List.of(codeNode.id(), promotedMemory.id()),
                List.of(edge.id()), 1.0, "CODE_CAPSULE", codeKey);
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(path),
                List.of(codeNode, promotedMemory), List.of(edge), List.of());
        RuleMemoryInjectionFilter filter = new RuleMemoryInjectionFilter(
                new RulesProperties(true, 20, 256, 4096, 200),
                memoryId -> Set.of(promotedOriginId).contains(memoryId));
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(
                List.of(code), List.of(), false)), (projectKey, seeds, options) -> expansion, filter);

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("controller",
                "PROJECT_A", 5, 2, false, "hybrid", false, false, true, true));

        assertThat(response.nodes()).extracting(GraphNode::id).containsExactly(codeNode.id());
        assertThat(response.edges()).isEmpty();
        assertThat(response.paths()).isEmpty();
        assertThat(response.memoryIds()).isEmpty();
        assertThat(response.sourceRefs()).noneMatch(ref -> promotedOriginId.toString().equals(ref.ref()));
    }

    @Test
    void seedLocatorEnrichesMatchingExpandedCapsuleNode() {
        String key = "PROJECT_A|symbol-1|class";
        GraphContextSeed seed = new GraphContextSeed(GraphContextSeedKind.CODE_CAPSULE, key, "PROJECT_A", 0.9,
                "ExampleService", "Current summary", "Current text", null, UUID.randomUUID(), key,
                UUID.randomUUID(), UUID.randomUUID(), null,
                Map.of("filePath", "src/main/java/ExampleService.java", "lineStart", 11, "lineEnd", 42));
        GraphNode projected = new GraphNode(key, "CodeCapsule", "PROJECT_A", "symbol-1",
                "Projected summary", "Projected text", "", null, null, 0.7, false, Map.of());
        GraphContextExpansion expansion = new GraphContextExpansion(true, null, List.of(), List.of(projected),
                List.of(), List.of());
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(
                List.of(seed), List.of(), false)), (projectKey, seeds, options) -> expansion);

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 1, 1, false, "hybrid", false, false, false));

        GraphNode capsule = response.nodes().getFirst();
        assertThat(capsule.summary()).isEqualTo("Current summary");
        assertThat(capsule.filePath()).isEqualTo("src/main/java/ExampleService.java");
        assertThat(capsule.startLine()).isEqualTo(11);
        assertThat(capsule.endLine()).isEqualTo(42);
    }

    @Test
    void redactsMemorySummaryCapsuleTextAndSensitivePropertiesBeforeResponse() {
        GraphNode memory = new GraphNode("memory-1", "Memory", "PROJECT_A",
                "owner test@example.com", "mail test@example.com", null, null, null, null, 0.9, false,
                Map.of("sourceRef", "manual:password=supersecret123"));
        GraphNode capsule = new GraphNode("PROJECT_A|target|project_insight", "CodeCapsule", "PROJECT_A",
                "target", "ACME-LOAN-001 summary", "password=supersecret123\nsafe second line",
                null, null, null, 0.8, false, Map.of("evidence", "internal-prod-svc"));
        GraphEdge edge = new GraphEdge("edge-1", "EVIDENCES", memory.id(), capsule.id(), "source_ref", 0.9,
                1.0, false, Map.of());
        GraphContextExpansion expansion = new GraphContextExpansion(true, null,
                List.of(new GraphPath("path-1", List.of(memory.id(), capsule.id()), List.of(edge.id()), 0.9,
                        "MEMORY", memory.id())),
                List.of(memory, capsule), List.of(edge), List.of());
        GraphContextRetrievalService service = service(new StubSeedProvider(new GraphContextSeedResult(List.of(),
                List.of(), false)), (projectKey, seeds, options) -> expansion);

        GraphContextRetrieveResponse response = service.retrieve(new GraphContextRetrieveRequest("scanner",
                "PROJECT_A", 5, 2, false, "hybrid", false, false));

        String rendered = response.toString();
        assertThat(rendered).doesNotContain("test@example.com", "supersecret123", "ACME-LOAN-001",
                "internal-prod-svc");
        assertThat(rendered).contains("[REDACTED]", "[REDACTED_SECRET_LINE]");
    }

    private static GraphContextRetrievalService service(GraphContextSeedProvider seedProvider,
            GraphContextNeo4jReader reader) {
        return service(seedProvider, reader, new RuleMemoryInjectionFilter(
                new RulesProperties(false, 20, 256, 4096, 200), ignored -> false));
    }

    private static GraphContextRetrievalService service(GraphContextSeedProvider seedProvider,
            GraphContextNeo4jReader reader, RuleMemoryInjectionFilter ruleMemoryInjectionFilter) {
        GraphContextRetrievalProperties properties = new GraphContextRetrievalProperties(true, 5, 20, 2, 3,
                8, 80, 160, 12, 12, 32, 800, 500, 4000, false, false);
        GraphContextRedactor redactor = new GraphContextRedactor(new ScannerPayloadRedactor(new PiiScrubber(),
                new SecretScanService(), new MemoryPolicyEvaluator(),
                new PolicyProperties(true, false, "", List.of("ACME-LOAN-001", "internal-prod-svc"))));
        return new GraphContextRetrievalService(properties, seedProvider, reader,
                new GraphContextPacker(properties, redactor), ruleMemoryInjectionFilter);
    }

    private static GraphContextExpansion emptyExpansion() {
        return new GraphContextExpansion(true, null, List.of(), List.of(), List.of(), List.of());
    }

    private static GraphContextSeed memorySeed(String summary) {
        UUID memoryId = UUID.randomUUID();
        return new GraphContextSeed(GraphContextSeedKind.MEMORY, memoryId.toString(), "PROJECT_A", 0.8,
                summary, summary, null, memoryId, null, null, null, null, "manual:test", Map.of());
    }

    private static GraphContextSeed codeSeed(String key) {
        return new GraphContextSeed(GraphContextSeedKind.CODE_CAPSULE, key, "PROJECT_A", 0.9,
                "ExampleService", "Purpose: example", "Purpose: example", null, UUID.randomUUID(), key,
                UUID.randomUUID(), UUID.randomUUID(), null, Map.of());
    }

    private record StubSeedProvider(GraphContextSeedResult result) implements GraphContextSeedProvider {
        @Override
        public GraphContextSeedResult seeds(String query, String projectKey, int limit) {
            return result;
        }
    }

    private static final class ModeAwareSeedProvider implements GraphContextSeedProvider {

        private boolean hybridFusionEnabled;

        @Override
        public GraphContextSeedResult seeds(String query, String projectKey, int limit) {
            return new GraphContextSeedResult(List.of(), List.of(), false);
        }

        @Override
        public GraphContextSeedResult seeds(String query, String projectKey, int limit, boolean includeMemory,
                boolean hybridFusionEnabled) {
            this.hybridFusionEnabled = hybridFusionEnabled;
            return new GraphContextSeedResult(List.of(), List.of(), false);
        }
    }
}
