package com.mbworldwideapps.aiorchestration.modules.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.GraphContextRetrievalProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryInjectionFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GraphContextRetrievalService {

    private static final String MODE_HYBRID = "hybrid";
    private static final String MODE_QDRANT_SEED = "qdrant-seed";
    private static final String MODE_NEO4J_VECTOR = "neo4j-vector";

    private final GraphContextRetrievalProperties properties;
    private final GraphContextSeedProvider seedProvider;
    private final GraphContextNeo4jReader neo4jReader;
    private final GraphContextPacker packer;
    private final RuleMemoryInjectionFilter ruleMemoryInjectionFilter;

    @Autowired
    public GraphContextRetrievalService(GraphContextRetrievalProperties properties,
            GraphContextSeedProvider seedProvider, GraphContextNeo4jReader neo4jReader,
            GraphContextPacker packer, RuleMemoryInjectionFilter ruleMemoryInjectionFilter) {
        this.properties = properties;
        this.seedProvider = seedProvider;
        this.neo4jReader = neo4jReader;
        this.packer = packer;
        this.ruleMemoryInjectionFilter = java.util.Objects.requireNonNull(
                ruleMemoryInjectionFilter, "ruleMemoryInjectionFilter");
    }

    GraphContextRetrievalService(GraphContextRetrievalProperties properties,
            GraphContextSeedProvider seedProvider, GraphContextNeo4jReader neo4jReader,
            GraphContextPacker packer) {
        this(properties, seedProvider, neo4jReader, packer,
                new RuleMemoryInjectionFilter(
                        new RulesProperties(false, 20, 256, 4096, 200), ignored -> false));
    }

    public GraphContextRetrieveResponse retrieve(GraphContextRetrieveRequest request) {
        Instant started = Instant.now();
        String query = required(request.query(), "query");
        String projectKey = required(request.projectKey(), "projectKey");
        int topK = clamp(request.topK(), properties.defaultTopK(), properties.maxTopK());
        int seedLimit = Math.min(properties.seedLimit(), topK);
        int maxDepth = clamp(request.maxDepth(), properties.defaultMaxDepth(), properties.maxDepth());
        boolean includeStale = request.includeStale() == null
                ? properties.includeStaleByDefault()
                : request.includeStale();
        boolean includeSimilarityBackfill = request.includeSimilarityBackfill() == null
                ? properties.includeSimilarityBackfillByDefault()
                : request.includeSimilarityBackfill();
        List<GraphWarning> warnings = new ArrayList<>();
        if (!request.includeSharedReferences()) warnings.add(new GraphWarning("shared_references_omitted",
                "Shared reference context is omitted for this caller"));
        if (Boolean.TRUE.equals(request.includeRawSourceLines())) {
            warnings.add(new GraphWarning("raw_source_lines_omitted",
                    "Raw source line windows are deferred; returning capsule text and file:line refs only"));
        }
        String retrievalMode = normalizeMode(request.retrievalMode(), warnings);
        boolean includeMemorySeeds = request.includeMemorySeeds() == null || request.includeMemorySeeds();
        boolean includeMemoryTraversal = request.includeMemoryTraversal() == null
                ? includeMemorySeeds
                : request.includeMemoryTraversal();
        if (!properties.enabled()) {
            warnings.add(new GraphWarning("graph_context_disabled", "Graph context retrieval is disabled"));
        }

        GraphContextSeedResult seeds = seedProvider.seeds(query, projectKey, seedLimit, includeMemorySeeds,
                MODE_HYBRID.equals(retrievalMode));
        GraphContextExpansion expansion = properties.enabled()
                ? neo4jReader.expand(projectKey, seeds.seeds(), new GraphContextExpansionOptions(
                        maxDepth,
                        properties.maxNodes(),
                        properties.maxEdges(),
                        properties.maxPaths(),
                        properties.neighborsPerNode(),
                        properties.frontierPerHop(),
                        includeStale,
                        includeSimilarityBackfill, request.includeSharedReferences()))
                : GraphContextExpansion.unavailable("Graph context retrieval is disabled");
        if (!includeMemoryTraversal) {
            expansion = withoutMemoryContext(expansion);
        } else {
            expansion = withoutExcludedRuleOrigins(expansion);
        }
        return packer.pack(projectKey, retrievalMode, seeds, expansion, warnings, started);
    }

    private static GraphContextExpansion withoutMemoryContext(GraphContextExpansion expansion) {
        if (expansion == null) {
            return null;
        }
        Set<String> memoryNodeIds = new LinkedHashSet<>();
        for (GraphNode node : expansion.nodes()) {
            if ("Memory".equals(node.kind())) {
                memoryNodeIds.add(node.id());
            }
        }
        List<GraphNode> nodes = expansion.nodes().stream()
                .filter(node -> !memoryNodeIds.contains(node.id()))
                .toList();
        List<GraphEdge> edges = expansion.edges().stream()
                .filter(edge -> !memoryNodeIds.contains(edge.sourceNodeId()))
                .filter(edge -> !memoryNodeIds.contains(edge.targetNodeId()))
                .toList();
        List<GraphPath> paths = expansion.paths().stream()
                .filter(path -> path.seedKind() == null || !"Memory".equalsIgnoreCase(path.seedKind()))
                .filter(path -> path.nodeIds().stream().noneMatch(memoryNodeIds::contains))
                .toList();
        return new GraphContextExpansion(expansion.available(), expansion.unavailableReason(), paths, nodes, edges,
                expansion.warnings());
    }

    private GraphContextExpansion withoutExcludedRuleOrigins(GraphContextExpansion expansion) {
        if (expansion == null || expansion.nodes().isEmpty()) {
            return expansion;
        }
        List<UUID> candidateIds = expansion.nodes().stream()
                .filter(node -> "Memory".equals(node.kind()))
                .map(GraphNode::id)
                .map(GraphContextRetrievalService::uuidOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<UUID> excluded = ruleMemoryInjectionFilter.excludedMemoryIds(candidateIds);
        if (excluded.isEmpty()) {
            return expansion;
        }
        Set<String> excludedNodeIds = excluded.stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<GraphNode> nodes = expansion.nodes().stream()
                .filter(node -> !excludedNodeIds.contains(node.id()))
                .toList();
        List<GraphEdge> edges = expansion.edges().stream()
                .filter(edge -> !excludedNodeIds.contains(edge.sourceNodeId()))
                .filter(edge -> !excludedNodeIds.contains(edge.targetNodeId()))
                .toList();
        List<GraphPath> paths = expansion.paths().stream()
                .filter(path -> path.nodeIds().stream().noneMatch(excludedNodeIds::contains))
                .toList();
        return new GraphContextExpansion(expansion.available(), expansion.unavailableReason(), paths, nodes, edges,
                expansion.warnings());
    }

    private static UUID uuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeMode(String requested, List<GraphWarning> warnings) {
        String mode = requested == null || requested.isBlank()
                ? MODE_HYBRID
                : requested.trim().toLowerCase(Locale.ROOT);
        if (MODE_NEO4J_VECTOR.equals(mode)) {
            warnings.add(new GraphWarning("neo4j_vector_deferred",
                    "Neo4j vector retrieval is deferred; using Qdrant seed mode"));
            return MODE_QDRANT_SEED;
        }
        if (!MODE_HYBRID.equals(mode) && !MODE_QDRANT_SEED.equals(mode)) {
            warnings.add(new GraphWarning("retrieval_mode_defaulted",
                    "Unknown retrievalMode '%s'; using hybrid".formatted(mode)));
            return MODE_HYBRID;
        }
        return mode;
    }

    private static int clamp(Integer requested, int defaultValue, int maxValue) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
