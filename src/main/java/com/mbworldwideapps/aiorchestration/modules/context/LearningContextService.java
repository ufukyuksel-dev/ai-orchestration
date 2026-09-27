package com.mbworldwideapps.aiorchestration.modules.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.LearningContextProperties;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveRequest;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.EdgeSummary;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.SymbolNeighborsResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService.SymbolSummary;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LearningContextService {

    private static final String MODE_HYBRID = "hybrid";
    private static final int MAX_ENRICHED_GRAPH_PATHS = 1;
    private static final int MAX_CAPSULE_DETAIL_CHARS = 240;
    private static final String CODE_EVIDENCE_LIMIT =
            "Method/test bodies are not supplied; unlisted behavior and test assertions are unknown.";
    private static final List<String> STRUCTURAL_EDGE_TYPES = List.of(
            "DECLARES", "CALLS", "INJECTS", "ANNOTATED_WITH", "EXPOSES_ENDPOINT");

    private final LearningContextProperties properties;
    private final MemoryRetrievalService memoryRetrievalService;
    private final GraphContextRetrievalService graphContextRetrievalService;
    private final CodebaseService codebaseService;
    private final ScannerPayloadRedactor redactor;
    private final LearningContextRoutingPolicy routingPolicy;

    @Autowired
    public LearningContextService(LearningContextProperties properties,
            MemoryRetrievalService memoryRetrievalService,
            GraphContextRetrievalService graphContextRetrievalService,
            CodebaseService codebaseService,
            ScannerPayloadRedactor redactor,
            LearningContextRoutingPolicy routingPolicy) {
        this.properties = properties;
        this.memoryRetrievalService = memoryRetrievalService;
        this.graphContextRetrievalService = graphContextRetrievalService;
        this.codebaseService = codebaseService;
        this.redactor = redactor;
        this.routingPolicy = routingPolicy;
    }

    public LearningContextService(LearningContextProperties properties,
            MemoryRetrievalService memoryRetrievalService,
            GraphContextRetrievalService graphContextRetrievalService,
            CodebaseService codebaseService,
            ScannerPayloadRedactor redactor) {
        this(properties, memoryRetrievalService, graphContextRetrievalService,
                codebaseService, redactor, new LearningContextRoutingPolicy());
    }

    public LearningContextResponse retrieve(LearningContextRequest request) {
        return retrieve(request, false);
    }

    /** Explicit user/runtime request; distinct from automatic prompt injection. */
    public LearningContextResponse retrieveExplicit(LearningContextRequest request) {
        return retrieve(request, true);
    }

    private LearningContextResponse retrieve(LearningContextRequest request, boolean explicitResolve) {
        String query = required(request.query(), "query");
        String projectKey = required(request.projectKey(), "projectKey");
        int topK = clamp(request.topK(), properties.defaultTopK(), properties.maxTopK());
        int maxDepth = clamp(request.maxDepth(), properties.defaultMaxDepth(), properties.maxDepth());
        int tokenBudget = clamp(request.tokenBudget(), properties.defaultTokenBudget(), properties.maxTokenBudget());
        LearningContextRoutingDecision routing = routingPolicy.decide(request, properties.includeMemoryFallback(),
                memoryRetrievalService.injectionEnabled(), explicitResolve);
        boolean includeMemory = routing.effectiveMode().includesMemory();
        boolean includeScanner = routing.effectiveMode().includesScanner();
        List<String> warnings = new ArrayList<>();
        if (!routing.memoryAbstentionReason().isBlank()) {
            warnings.add("memory_abstained:" + routing.memoryAbstentionReason());
        }

        MemoryContextResponse memoryContext = retrieveMemory(query, projectKey, request.userId(), includeMemory,
                explicitResolve, warnings);
        GraphContextRetrieveResponse graphContext = null;

        if (!includeScanner) {
            // An explicit memory-only/no-context route must not pay Scanner/Neo4j retrieval cost.
        } else if (!properties.enabled()) {
            warnings.add("learning_context_disabled");
        } else {
            graphContext = retrieveGraph(request, query, projectKey, topK, maxDepth,
                    routing.effectiveMode() == LearningContextMode.COMBINED, warnings);
        }

        CandidateAssembly assembly = candidates(graphContext, memoryContext, warnings);
        List<LearningContextItem> candidates = assembly.items();
        Deduplication deduplication = deduplicate(candidates, warnings);
        Selection selection = select(deduplication.items(), tokenBudget, request.role(), warnings);
        boolean graphAvailable = graphContext != null && graphContext.metadata() != null
                && graphContext.metadata().graphAvailable();
        boolean graphCodeSeedFallbackUsed = graphContext != null && graphContext.metadata() != null
                && graphContext.metadata().codePostgresFallbackUsed();
        boolean graphMemorySeedFallbackUsed = graphContext != null && graphContext.metadata() != null
                && graphContext.metadata().memoryPostgresFallbackUsed();
        boolean graphSeedFallbackUsed = graphCodeSeedFallbackUsed || graphMemorySeedFallbackUsed;
        LearningContextMode effectiveMode = effectiveMode(selection.items());
        String memoryAbstentionReason = routing.memoryAbstentionReason();
        if (routing.requestedMode().includesMemory() && !effectiveMode.includesMemory()
                && memoryAbstentionReason.isBlank()) {
            memoryAbstentionReason = memoryContext.items().isEmpty()
                    ? "no_relevant_memory"
                    : "memory_dropped_by_budget";
            warnings.add("memory_abstained:" + memoryAbstentionReason);
        }
        boolean fallbackUsed = routing.requestedMode().includesScanner()
                && !effectiveMode.includesScanner()
                && effectiveMode.includesMemory();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("topK", topK);
        metadata.put("maxDepth", maxDepth);
        metadata.put("tokenBudget", tokenBudget);
        metadata.put("role", request.role() == null || request.role().isBlank() ? "" : request.role().trim());
        metadata.put("candidateCount", candidates.size());
        metadata.put("deduplicatedCount", deduplication.deduplicatedCount());
        metadata.put("droppedByBudgetCount", selection.droppedByBudgetCount());
        metadata.put("droppedCatastrophicCount", selection.droppedCatastrophicCount());
        metadata.put("requestedContextMode", routing.requestedMode().name());
        metadata.put("effectiveContextMode", effectiveMode.name());
        metadata.put("routingReason", routing.reason());
        metadata.put("memoryAbstentionReason", memoryAbstentionReason);
        metadata.put("memoryCandidateCount", memoryContext.items().size());
        metadata.put("selectedMemoryCount", selectedMemoryIds(selection.items()).size());
        metadata.put("selectedScannerCount", countScannerItems(selection.items()));
        metadata.put("crossLaneMemoryDeduplicatedCount", assembly.crossLaneMemoryDeduplicatedCount());
        metadata.put("graphSeedFallbackUsed", graphSeedFallbackUsed);
        metadata.put("graphCodeSeedFallbackUsed", graphCodeSeedFallbackUsed);
        metadata.put("graphMemorySeedFallbackUsed", graphMemorySeedFallbackUsed);

        MemoryContextResponse selectedMemoryContext = selectedMemoryContext(memoryContext, selection.items());
        return new LearningContextResponse(projectKey, selection.items(), selectedMemoryContext, graphContext,
                selection.tokenEstimate(), graphAvailable, graphSeedFallbackUsed,
                graphCodeSeedFallbackUsed, graphMemorySeedFallbackUsed, fallbackUsed, warnings,
                metadata);
    }

    private MemoryContextResponse retrieveMemory(String query, String projectKey, String userId,
            boolean includeMemory, boolean explicitResolve, List<String> warnings) {
        if (!includeMemory) {
            return MemoryContextResponse.empty();
        }
        try {
            MemoryContextResponse response = explicitResolve
                    ? memoryRetrievalService.retrieve(query, projectKey, userId)
                    : memoryRetrievalService.retrieveForInjection(query, projectKey, userId);
            return response == null ? MemoryContextResponse.empty() : response;
        } catch (RuntimeException e) {
            warnings.add("memory_context_unavailable:" + e.getClass().getSimpleName());
            return MemoryContextResponse.empty();
        }
    }

    private GraphContextRetrieveResponse retrieveGraph(LearningContextRequest request, String query,
            String projectKey, int topK, int maxDepth, boolean includeMemoryGraph, List<String> warnings) {
        try {
            GraphContextRetrieveResponse response = graphContextRetrievalService.retrieve(new GraphContextRetrieveRequest(
                    query, projectKey, topK, maxDepth, properties.includeRawSourceLines(),
                    blankToDefault(request.retrievalMode(), MODE_HYBRID),
                    request.includeStale() == null ? properties.includeStale() : request.includeStale(),
                    request.includeSimilarityBackfill() == null
                            ? properties.includeSimilarityBackfill()
                            : request.includeSimilarityBackfill(),
                    includeMemoryGraph,
                    includeMemoryGraph));
            if (response == null) {
                warnings.add("graph_context_unavailable:null_response");
                return null;
            }
            if (response.warnings() != null) {
                response.warnings().forEach(warning -> warnings.add("graph:" + warning.code()));
            }
            return response;
        } catch (RuntimeException e) {
            warnings.add("graph_context_unavailable:" + e.getClass().getSimpleName());
            return null;
        }
    }

    private CandidateAssembly candidates(GraphContextRetrieveResponse graphContext,
            MemoryContextResponse memoryContext,
            List<String> warnings) {
        List<LearningContextItem> candidates = new ArrayList<>();
        int crossLaneDeduplicated = 0;
        Set<String> directMemoryIds = memoryContext.items().stream()
                .map(item -> item.memoryId().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> graphPathMemoryIds = new LinkedHashSet<>();
        if (graphContext != null) {
            for (LearningContextItem item : graphItems(graphContext, warnings)) {
                if (isDuplicateGraphMemoryNode(item, directMemoryIds)) {
                    crossLaneDeduplicated++;
                    continue;
                }
                candidates.add(item);
                graphPathMemoryIds.addAll(memoryIds(item));
            }
        }
        for (LearningContextItem item : memoryItems(memoryContext)) {
            if (graphPathMemoryIds.contains(item.id())) {
                crossLaneDeduplicated++;
                continue;
            }
            candidates.add(item);
        }
        if (crossLaneDeduplicated > 0) {
            warnings.add("learning_context_cross_lane_memory_deduplicated");
        }
        return new CandidateAssembly(List.copyOf(candidates), crossLaneDeduplicated);
    }

    private static boolean isDuplicateGraphMemoryNode(LearningContextItem item, Set<String> directMemoryIds) {
        return "graph_node".equals(item.kind())
                && "Memory".equals(item.metadata().get("kind"))
                && directMemoryIds.contains(item.id());
    }

    private static List<String> memoryIds(LearningContextItem item) {
        Object value = item.metadata().get("memoryIds");
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private List<LearningContextItem> graphItems(GraphContextRetrieveResponse response, List<String> warnings) {
        Map<String, GraphNode> nodesById = new LinkedHashMap<>();
        for (GraphNode node : response.nodes()) {
            nodesById.put(node.id(), node);
        }
        Map<String, GraphEdge> edgesById = new LinkedHashMap<>();
        for (GraphEdge edge : response.edges()) {
            edgesById.put(edge.id(), edge);
        }
        if (!response.paths().isEmpty()) {
            List<GraphPath> ordered = response.paths().stream()
                    .sorted(Comparator.comparingDouble(GraphPath::score).reversed())
                    .toList();
            List<LearningContextItem> items = new ArrayList<>();
            for (int index = 0; index < ordered.size(); index++) {
                items.add(graphPathItem(ordered.get(index), nodesById, edgesById, response.projectKey(),
                        index < MAX_ENRICHED_GRAPH_PATHS, warnings));
            }
            return List.copyOf(items);
        }
        return response.nodes().stream()
                .sorted(Comparator.comparingDouble((GraphNode node) -> score(node.confidence())).reversed()
                        .thenComparing(GraphNode::id))
                .map(this::graphNodeItem)
                .toList();
    }

    private LearningContextItem graphPathItem(GraphPath path, Map<String, GraphNode> nodesById,
            Map<String, GraphEdge> edgesById, String projectKey, boolean enrich, List<String> warnings) {
        List<GraphNode> pathNodes = path.nodeIds().stream()
                .map(nodesById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        GraphNode capsule = pathNodes.stream()
                .filter(node -> "CodeCapsule".equals(node.kind()))
                .findFirst()
                .orElse(null);
        GraphNode primarySymbol = pathNodes.stream()
                .filter(node -> "CodeSymbol".equals(node.kind()))
                .findFirst()
                .orElse(null);
        String meaning = capsuleMeaning(capsule);
        String details = capsuleDetails(capsule, meaning);
        StructuralEvidence structural = enrich && primarySymbol != null
                ? structuralEvidence(primarySymbol, projectKey, path, nodesById, edgesById, warnings)
                : graphStructuralEvidence(path, nodesById, edgesById);

        StringBuilder text = new StringBuilder();
        if (!meaning.isBlank()) {
            text.append("meaning: ").append(meaning);
        }
        if (!structural.text().isBlank()) {
            appendSection(text, "evidence", structural.text());
        }
        if (!details.isBlank()) {
            appendSection(text, "details", details);
        }
        if (text.isEmpty()) {
            text.append("seed: ").append(path.seedKind()).append(':').append(path.seedKey());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeIds", path.nodeIds());
        metadata.put("edgeIds", path.edgeIds());
        metadata.put("memoryIds", pathNodes.stream()
                .filter(node -> "Memory".equals(node.kind()))
                .map(GraphNode::id)
                .toList());
        metadata.put("seedKind", path.seedKind());
        metadata.put("seedKey", path.seedKey());
        GraphNode seedNode = nodesById.get(path.seedKey());
        if (seedNode != null) {
            Object retrievalLane = seedNode.properties().get("retrievalLane");
            if (retrievalLane != null && !retrievalLane.toString().isBlank()) {
                metadata.put("retrievalLane", retrievalLane.toString());
            }
        }
        metadata.put("semanticEvidence", !meaning.isBlank() || !details.isBlank());
        metadata.put("structuralEvidence", structural.available());
        String title = primarySymbol == null
                ? path.seedKind() + ":" + path.seedKey()
                : shortName(nonBlank(primarySymbol.title(), primarySymbol.id()));
        String itemLocator = nonBlank(structural.locator(), firstLocator(path, nodesById));
        String renderedText = structural.available()
                ? truncatePreservingSuffix(text.toString(), "unknown:\n" + CODE_EVIDENCE_LIMIT,
                        properties.maxRenderedItemChars())
                : truncate(text.toString(), properties.maxRenderedItemChars());
        return item("graph_path", path.pathId(), title, itemLocator,
                renderedText, path.score(), Map.copyOf(metadata));
    }

    private StructuralEvidence structuralEvidence(GraphNode primarySymbol, String projectKey, GraphPath path,
            Map<String, GraphNode> nodesById, Map<String, GraphEdge> edgesById, List<String> warnings) {
        if (codebaseService == null || projectKey == null || projectKey.isBlank()) {
            return graphStructuralEvidence(path, nodesById, edgesById);
        }
        try {
            SymbolNeighborsResponse immediate = codebaseService.neighbors(primarySymbol.id(), 1,
                    STRUCTURAL_EDGE_TYPES, projectKey);
            if (immediate == null || immediate.nodes().isEmpty()) {
                return graphStructuralEvidence(path, nodesById, edgesById);
            }
            SymbolSummary root = immediate.nodes().stream()
                    .filter(node -> node.symbolId().toString().equals(primarySymbol.id()))
                    .findFirst()
                    .orElse(immediate.nodes().getFirst());
            SymbolSummary owningClass = owningClass(root, immediate);
            SymbolNeighborsResponse classGraph = owningClass != null && !owningClass.symbolId().equals(root.symbolId())
                    ? codebaseService.neighbors(owningClass.symbolId().toString(), 1, STRUCTURAL_EDGE_TYPES, projectKey)
                    : immediate;
            if (classGraph == null) {
                classGraph = immediate;
            }
            SymbolNeighborsResponse testGraph = owningClass == null
                    ? null
                    : codebaseService.neighbors(owningClass.name() + "Test", 1,
                            List.of("DECLARES", "ANNOTATED_WITH"), projectKey);
            return renderStructuralEvidence(root, owningClass, immediate, classGraph, testGraph);
        } catch (RuntimeException e) {
            String warning = "code_evidence_unavailable:" + e.getClass().getSimpleName();
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
            return graphStructuralEvidence(path, nodesById, edgesById);
        }
    }

    private static SymbolSummary owningClass(SymbolSummary root, SymbolNeighborsResponse response) {
        if ("class".equalsIgnoreCase(root.kind())) {
            return root;
        }
        Map<UUID, SymbolSummary> byId = response.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(SymbolSummary::symbolId, node -> node,
                        (left, right) -> left, LinkedHashMap::new));
        for (EdgeSummary edge : response.edges()) {
            if ("DECLARES".equalsIgnoreCase(edge.edgeType()) && root.symbolId().equals(edge.targetSymbolId())) {
                SymbolSummary source = byId.get(edge.sourceSymbolId());
                if (source != null && "class".equalsIgnoreCase(source.kind())) {
                    return source;
                }
            }
        }
        return response.nodes().stream()
                .filter(node -> "class".equalsIgnoreCase(node.kind()))
                .findFirst()
                .orElse(null);
    }

    private static StructuralEvidence renderStructuralEvidence(SymbolSummary root, SymbolSummary owningClass,
            SymbolNeighborsResponse immediate, SymbolNeighborsResponse classGraph,
            SymbolNeighborsResponse testGraph) {
        StringBuilder text = new StringBuilder();
        SymbolSummary container = owningClass == null ? root : owningClass;
        String locator = symbolLocator(container, classGraph.edges());
        if (!root.symbolId().equals(container.symbolId())) {
            appendEvidenceLine(text, "focus", root.name(), lineRange(root.startLine(), root.endLine()));
        }
        appendNamedEdges(text, "annotations", classGraph.edges(), Set.of("ANNOTATED_WITH"), 8);
        if (hasClassAnnotation(container, classGraph.edges(), "Transactional")) {
            appendLine(text, "transaction: class-level @Transactional");
        }
        appendMethods(text, "methods", classGraph.nodes(), 5);
        appendNamedEdges(text, "relations", immediate.edges(),
                Set.of("CALLS", "INJECTS", "EXPOSES_ENDPOINT"), 8);

        if (testGraph != null && !testGraph.nodes().isEmpty()) {
            SymbolSummary testClass = testGraph.nodes().stream()
                    .filter(node -> "class".equalsIgnoreCase(node.kind())
                            && node.name().equals(container.name() + "Test"))
                    .findFirst()
                    .orElse(null);
            if (testClass != null) {
                Set<UUID> testMethodIds = testGraph.edges().stream()
                        .filter(edge -> "DECLARES".equalsIgnoreCase(edge.edgeType()))
                        .filter(edge -> testClass.symbolId().equals(edge.sourceSymbolId()))
                        .map(EdgeSummary::targetSymbolId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                List<SymbolSummary> allTestMethods = testGraph.nodes().stream()
                        .filter(node -> "method".equalsIgnoreCase(node.kind()))
                        .filter(node -> testMethodIds.contains(node.symbolId()))
                        .sorted(Comparator.comparing(
                                node -> node.startLine() == null ? Integer.MAX_VALUE : node.startLine()))
                        .toList();
                List<SymbolSummary> testMethods = boundedScenarioMethods(allTestMethods, 8, 180);
                if (!testMethods.isEmpty()) {
                    if (testMethods.size() == 1) {
                        SymbolSummary method = testMethods.getFirst();
                        appendEvidenceLine(text, "exact test", testClass.name() + "#" + method.name()
                                + lineSuffix(method.startLine()), symbolLocator(testClass, testGraph.edges()));
                    } else {
                        String scenarios = testMethods.stream()
                                .map(method -> compactTestScenario(method.name()))
                                .collect(java.util.stream.Collectors.joining(", "));
                        appendEvidenceLine(text, "existing test coverage",
                                testClass.name() + " covers " + scenarios,
                                symbolLocator(testClass, testGraph.edges()));
                    }
                    List<String> existingEdgeScenarios = allTestMethods.stream()
                            .filter(method -> isEdgeScenario(method.name()))
                            .map(method -> compactTestScenario(method.name()))
                            .limit(6)
                            .toList();
                    if (!existingEdgeScenarios.isEmpty()) {
                        appendLine(text, "coverage boundary: " + String.join(", ", existingEdgeScenarios)
                                + " already present; do not report as missing");
                    }
                } else {
                    appendEvidenceLine(text, "test", testClass.name(), symbolLocator(testClass, testGraph.edges()));
                }
            }
        }
        return new StructuralEvidence(text.toString().trim(), locator, !text.isEmpty());
    }

    private static boolean hasClassAnnotation(SymbolSummary container, List<EdgeSummary> edges, String annotation) {
        return edges.stream().anyMatch(edge -> container.symbolId().equals(edge.sourceSymbolId())
                && "ANNOTATED_WITH".equalsIgnoreCase(edge.edgeType())
                && annotation.equalsIgnoreCase(shortName(edge.targetRef()).replaceFirst("^@", "")));
    }

    private static StructuralEvidence graphStructuralEvidence(GraphPath path, Map<String, GraphNode> nodesById,
            Map<String, GraphEdge> edgesById) {
        StringBuilder text = new StringBuilder();
        boolean available = false;
        for (String nodeId : path.nodeIds()) {
            GraphNode node = nodesById.get(nodeId);
            if (node != null && "CodeSymbol".equals(node.kind())) {
                appendEvidenceLine(text, "symbol", nonBlank(node.title(), node.id()), locator(node));
                available = true;
            }
        }
        for (String edgeId : path.edgeIds()) {
            GraphEdge edge = edgesById.get(edgeId);
            if (edge == null) {
                continue;
            }
            String source = graphNodeTitle(nodesById.get(edge.sourceNodeId()), edge.sourceNodeId());
            String target = graphNodeTitle(nodesById.get(edge.targetNodeId()), edge.targetNodeId());
            appendEvidenceLine(text, "relation", edge.type(), shortName(source) + " -> " + shortName(target));
            available = true;
        }
        return new StructuralEvidence(text.toString().trim(), firstLocator(path, nodesById), available);
    }

    private static void appendMethods(StringBuilder text, String label, List<SymbolSummary> nodes, int limit) {
        List<SymbolSummary> allMethods = nodes.stream()
                .filter(node -> "method".equalsIgnoreCase(node.kind()))
                .sorted(Comparator.comparingInt(LearningContextService::methodPriority)
                        .thenComparing(node -> node.startLine() == null ? Integer.MAX_VALUE : node.startLine()))
                .toList();
        List<SymbolSummary> methods = allMethods.stream()
                .limit(limit)
                .sorted(Comparator.comparing(node -> node.startLine() == null ? Integer.MAX_VALUE : node.startLine()))
                .toList();
        if (methods.isEmpty()) {
            return;
        }
        appendLine(text, label + ": " + methods.stream()
                .map(method -> method.name() + lineSuffix(method.startLine()))
                .collect(java.util.stream.Collectors.joining(", ")));
    }

    private static int methodPriority(SymbolSummary method) {
        String name = method.name().toLowerCase(Locale.ROOT);
        return name.startsWith("process") || name.startsWith("validate") || name.startsWith("handle") ? 0 : 1;
    }

    private static List<SymbolSummary> representativeMethods(List<SymbolSummary> methods, int limit) {
        if (limit <= 0 || methods.isEmpty()) {
            return List.of();
        }
        if (methods.size() <= limit) {
            return methods;
        }
        if (limit == 1) {
            return List.of(methods.getFirst());
        }
        List<SymbolSummary> selected = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            int sourceIndex = (int) Math.round(index * (methods.size() - 1.0) / (limit - 1.0));
            selected.add(methods.get(sourceIndex));
        }
        return List.copyOf(selected);
    }

    private static String compactTestScenario(String methodName) {
        String compact = methodName == null ? "" : methodName.replaceFirst("^test", "");
        int when = compact.indexOf("_when");
        if (when >= 0) {
            return compact.substring(when + "_when".length());
        }
        return compact.replaceFirst("^Validate", "")
                .replaceFirst("^TroyCryptogram_", "Troy ")
                .replace('_', ' ');
    }

    private static List<SymbolSummary> boundedScenarioMethods(List<SymbolSummary> methods, int maxCount,
            int charBudget) {
        for (int count = Math.min(maxCount, methods.size()); count > 0; count--) {
            List<SymbolSummary> selected = representativeMethods(methods, count);
            int chars = selected.stream().mapToInt(method -> compactTestScenario(method.name()).length() + 2).sum();
            if (chars <= charBudget) {
                return selected;
            }
        }
        return List.of();
    }

    private static boolean isEdgeScenario(String methodName) {
        String normalized = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        return List.of("failure", "invalid", "error", "empty", "null", "padding", "multiblock", "unknown")
                .stream().anyMatch(normalized::contains);
    }

    private static void appendNamedEdges(StringBuilder text, String label, List<EdgeSummary> edges,
            Set<String> types, int limit) {
        List<String> values = edges.stream()
                .filter(edge -> types.contains(edge.edgeType().toUpperCase(Locale.ROOT)))
                .map(edge -> "ANNOTATED_WITH".equalsIgnoreCase(edge.edgeType())
                        ? "@" + shortName(edge.targetRef()).replaceFirst("^@", "")
                        : edge.edgeType() + ":" + shortName(edge.targetRef()))
                .filter(value -> !value.endsWith(":"))
                .distinct()
                .limit(limit)
                .toList();
        if (!values.isEmpty()) {
            appendLine(text, label + ": " + String.join(", ", values));
        }
    }

    private static void appendEvidenceLine(StringBuilder text, String label, String value, String locator) {
        if (value == null || value.isBlank()) {
            return;
        }
        appendLine(text, label + ": " + value.trim()
                + (locator == null || locator.isBlank() ? "" : " @ " + locator.trim()));
    }

    private static void appendLine(StringBuilder text, String line) {
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(line);
    }

    private static String symbolLocator(SymbolSummary symbol, List<EdgeSummary> edges) {
        for (EdgeSummary edge : edges) {
            if (!symbol.symbolId().equals(edge.sourceSymbolId())
                    && !symbol.symbolId().equals(edge.targetSymbolId())) {
                continue;
            }
            Object value = edge.evidence().get("filePath");
            if (value != null && !value.toString().isBlank()) {
                return value + lineSuffix(symbol.startLine(), symbol.endLine());
            }
        }
        return lineRange(symbol.startLine(), symbol.endLine());
    }

    private static String lineRange(Integer start, Integer end) {
        if (start == null) {
            return "";
        }
        return end == null || end.equals(start) ? "line " + start : "lines " + start + "-" + end;
    }

    private static String lineSuffix(Integer line) {
        return line == null ? "" : "@" + line;
    }

    private static String lineSuffix(Integer start, Integer end) {
        if (start == null) {
            return "";
        }
        return end == null || end.equals(start) ? ":" + start : ":" + start + "-" + end;
    }

    private static String graphNodeTitle(GraphNode node, String fallback) {
        return node == null ? fallback : nonBlank(node.title(), node.id());
    }

    private static String shortName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        int hash = normalized.lastIndexOf('#');
        int dot = normalized.lastIndexOf('.');
        int separator = Math.max(hash, dot);
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private static String capsuleMeaning(GraphNode capsule) {
        if (capsule == null) {
            return "";
        }
        if (capsule.summary() != null && !capsule.summary().isBlank()) {
            return capsule.summary().trim();
        }
        return truncate(capsule.text(), 220);
    }

    private static String capsuleDetails(GraphNode capsule, String meaning) {
        if (capsule == null || capsule.text() == null || capsule.text().isBlank()) {
            return "";
        }
        String details = capsule.text().trim();
        if (!meaning.isBlank() && details.startsWith(meaning)) {
            details = details.substring(meaning.length()).stripLeading();
        }
        while (details.startsWith("-") || details.startsWith(":")) {
            details = details.substring(1).stripLeading();
        }
        return truncate(details, MAX_CAPSULE_DETAIL_CHARS);
    }

    private static void appendSection(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(label).append(":\n").append(value.trim());
    }

    private LearningContextItem graphNodeItem(GraphNode node) {
        StringBuilder text = new StringBuilder();
        text.append(node.kind()).append(' ').append(nonBlank(node.title(), node.id()));
        appendLocator(text, locator(node));
        appendText(text, node.summary());
        appendText(text, node.text());
        return item("graph_node", node.id(), nonBlank(node.title(), node.id()), locator(node),
                truncate(text.toString(), properties.maxRenderedItemChars()), score(node.confidence()),
                Map.of("kind", node.kind()));
    }

    private List<LearningContextItem> memoryItems(MemoryContextResponse memoryContext) {
        return memoryContext.items().stream()
                .map(this::memoryItem)
                .toList();
    }

    private LearningContextItem memoryItem(MemoryContextItem item) {
        StringBuilder text = new StringBuilder();
        String memoryType = item.memoryType() == null ? "memory" : item.memoryType().value();
        text.append(memoryType).append(' ').append(item.promptText());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", item.scope() == null ? "" : item.scope().value());
        metadata.put("citationId", item.citationId() == null ? "" : item.citationId());
        metadata.put("sourceRef", item.sourceRef());
        metadata.put("stale", item.stale());
        return item("memory", item.memoryId().toString(), item.summary(), item.citationId(),
                truncate(text.toString(), properties.maxRenderedItemChars()), item.semanticScore(), metadata);
    }

    private static MemoryContextResponse selectedMemoryContext(MemoryContextResponse original,
            List<LearningContextItem> selectedItems) {
        if (original == null || original.items().isEmpty()) {
            return MemoryContextResponse.empty();
        }
        Set<String> representedMemoryIds = selectedMemoryIds(selectedItems);
        if (representedMemoryIds.isEmpty()) {
            return MemoryContextResponse.empty();
        }
        List<MemoryContextItem> kept = original.items().stream()
                .filter(item -> representedMemoryIds.contains(item.memoryId().toString()))
                .toList();
        Map<String, Integer> scopes = new LinkedHashMap<>();
        original.injectedScopes().keySet().forEach(scope -> scopes.put(scope, 0));
        for (MemoryContextItem item : kept) {
            if (item.scope() != null) {
                scopes.merge(item.scope().value(), 1, Integer::sum);
            }
        }
        boolean unchanged = kept.size() == original.items().size();
        return new MemoryContextResponse(
                kept,
                kept.stream().map(MemoryContextItem::memoryId).toList(),
                kept.size(),
                kept.stream().mapToInt(MemoryContextItem::tokenEstimate).sum(),
                scopes,
                (int) kept.stream().filter(MemoryContextItem::stale).count(),
                unchanged ? original.conflictFlaggedCount() : 0,
                original.relevanceFilteredCount(),
                original.latencyMs());
    }

    private LearningContextItem item(String kind, String id, String title, String locator, String text,
            double score, Map<String, Object> metadata) {
        String safeId = redactor.redact(id);
        String safeTitle = redactor.redact(title);
        String safeLocator = redactor.redact(locator);
        String safeText = redactor.redact(text);
        return new LearningContextItem(kind, safeId, safeTitle, safeLocator, safeText, score,
                LearningContextBuilder.estimateItemTokens(kind, safeTitle, safeLocator, safeText),
                redactMetadata(metadata));
    }

    private Map<String, Object> redactMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> sanitized.put(key, redactMetadataValue(value)));
        return Map.copyOf(sanitized);
    }

    private Object redactMetadataValue(Object value) {
        if (value instanceof String stringValue) {
            return redactor.redact(stringValue);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::redactMetadataValue)
                    .toList();
        }
        return value;
    }

    private Deduplication deduplicate(List<LearningContextItem> candidates, List<String> warnings) {
        List<LearningContextItem> selected = new ArrayList<>();
        List<Set<String>> selectedKeys = new ArrayList<>();
        int deduplicated = 0;
        for (LearningContextItem candidate : candidates) {
            Set<String> candidateKeys = dedupKeys(candidate);
            DuplicateMatch duplicate = findDuplicate(selected, selectedKeys, candidate, candidateKeys);
            if (duplicate.index() < 0) {
                selected.add(candidate);
                selectedKeys.add(candidateKeys);
                continue;
            }
            deduplicated++;
            selectedKeys.get(duplicate.index()).addAll(candidateKeys);
            LearningContextItem existing = selected.get(duplicate.index());
            if (duplicate.replaceAllowed() && candidate.score() > existing.score()) {
                selected.set(duplicate.index(), candidate);
            }
        }
        if (deduplicated > 0) {
            warnings.add("learning_context_deduplicated");
        }
        return new Deduplication(List.copyOf(selected), deduplicated);
    }

    private DuplicateMatch findDuplicate(List<LearningContextItem> selected, List<Set<String>> selectedKeys,
            LearningContextItem candidate, Set<String> candidateKeys) {
        if (candidateKeys.isEmpty()) {
            return DuplicateMatch.none();
        }
        if ("graph_path".equals(candidate.kind())) {
            Set<String> candidateNodes = nodeIds(candidate);
            for (int i = 0; i < selected.size(); i++) {
                if (selectedKeys.get(i).contains("graph_path:" + normalize(candidate.id()))) {
                    return new DuplicateMatch(i, true);
                }
                Set<String> coveredNodes = coveredNodeIds(selected.get(i));
                if (!candidateNodes.isEmpty() && coveredNodes.containsAll(candidateNodes)) {
                    return new DuplicateMatch(i, false);
                }
            }
            return DuplicateMatch.none();
        }
        for (int i = 0; i < selectedKeys.size(); i++) {
            for (String key : candidateKeys) {
                if (selectedKeys.get(i).contains(key)) {
                    LearningContextItem existing = selected.get(i);
                    if ("semantic_anchor".equals(candidate.kind()) && "graph_path".equals(existing.kind())
                            && !booleanMetadata(existing, "semanticEvidence")) {
                        continue;
                    }
                    return new DuplicateMatch(i, !"graph_path".equals(existing.kind()));
                }
            }
        }
        return DuplicateMatch.none();
    }

    private Set<String> dedupKeys(LearningContextItem item) {
        Set<String> keys = new LinkedHashSet<>();
        switch (item.kind()) {
            case "graph_path" -> {
                addKey(keys, "locator", item.locator());
                addCollectionKeys(keys, "target", item.metadata().get("nodeIds"));
                addKey(keys, "graph_path", item.id());
            }
            case "graph_node" -> {
                addKey(keys, "locator", item.locator());
                addKey(keys, "target", item.id());
                addKey(keys, "graph_node", item.id());
            }
            case "semantic_anchor" -> {
                addKey(keys, "locator", item.locator());
                addKey(keys, "target", item.metadata().get("targetKey"));
                addKey(keys, "semantic_anchor", item.id());
            }
            case "memory" -> {
                addKey(keys, "memory", item.id());
                addKey(keys, "memory", item.metadata().get("citationId"));
            }
            default -> addKey(keys, item.kind(), item.id());
        }
        return keys;
    }

    private static boolean booleanMetadata(LearningContextItem item, String key) {
        Object value = item.metadata().get(key);
        return value instanceof Boolean bool && bool;
    }

    private Set<String> coveredNodeIds(LearningContextItem item) {
        Set<String> ids = new LinkedHashSet<>();
        switch (item.kind()) {
            case "graph_path" -> ids.addAll(nodeIds(item));
            case "graph_node" -> addNormalized(ids, item.id());
            case "semantic_anchor" -> addNormalized(ids, item.metadata().get("targetKey"));
            default -> {
            }
        }
        return ids;
    }

    private Set<String> nodeIds(LearningContextItem item) {
        Set<String> ids = new LinkedHashSet<>();
        Object value = item.metadata().get("nodeIds");
        if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> addNormalized(ids, entry));
        } else {
            addNormalized(ids, value);
        }
        return ids;
    }

    private static void addNormalized(Set<String> values, Object value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
    }

    private static void addCollectionKeys(Set<String> keys, String prefix, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> addKey(keys, prefix, entry));
        } else {
            addKey(keys, prefix, value);
        }
    }

    private static void addKey(Set<String> keys, String prefix, Object value) {
        if (value == null) {
            return;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            keys.add(prefix + ":" + normalized);
        }
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private Selection select(List<LearningContextItem> candidates, int tokenBudget, String role,
            List<String> warnings) {
        int droppedByBudget = 0;
        int droppedCatastrophic = 0;
        int catastrophicLimit = Math.max(tokenBudget, tokenBudget * properties.catastrophicItemMultiplier());
        List<LearningContextItem> selected = new ArrayList<>();
        for (LearningContextItem candidate : prioritized(candidates, role)) {
            if (candidate.tokenEstimate() > catastrophicLimit) {
                droppedCatastrophic++;
                warnings.add("learning_context_item_too_large:" + candidate.kind());
                continue;
            }
            List<LearningContextItem> trial = new ArrayList<>(selected);
            trial.add(candidate);
            if (LearningContextBuilder.estimatePromptTokens(trial) > tokenBudget) {
                droppedByBudget++;
                continue;
            }
            selected.add(candidate);
        }
        if (droppedByBudget > 0) {
            warnings.add("learning_context_budget_applied");
        }
        int renderedTokens = LearningContextBuilder.estimatePromptTokens(selected);
        return new Selection(List.copyOf(selected), renderedTokens, droppedByBudget, droppedCatastrophic);
    }

    private static List<LearningContextItem> prioritized(List<LearningContextItem> candidates, String role) {
        List<LearningContextItem> ordered = new ArrayList<>();
        boolean codeRole = role == null || role.isBlank()
                || !"orchestrator".equalsIgnoreCase(role.trim());
        LearningContextItem lexicalReservation = null;
        if (codeRole) {
            lexicalReservation = candidates.stream()
                    .filter(item -> "graph_path".equals(item.kind()))
                    .filter(LearningContextService::isPostgresLexical)
                    .max(Comparator.comparingDouble(LearningContextItem::score))
                    .orElse(null);
            if (lexicalReservation != null) {
                ordered.add(lexicalReservation);
            }
        }
        if (lexicalReservation != null) {
            candidates.stream()
                    .filter(LearningContextService::isMemoryBearing)
                    .max(Comparator.comparingDouble(LearningContextItem::score))
                    .filter(item -> !ordered.contains(item))
                    .ifPresent(ordered::add);
        }
        if (codeRole) {
            candidates.stream()
                    .filter(item -> "graph_path".equals(item.kind()))
                    .filter(item -> booleanMetadata(item, "semanticEvidence")
                            && booleanMetadata(item, "structuralEvidence"))
                    .filter(item -> !ordered.contains(item))
                    .max(Comparator.comparingDouble(LearningContextItem::score))
                    .ifPresent(ordered::add);
        }
        candidates.stream()
                .filter(item -> !ordered.contains(item))
                .sorted(Comparator.comparingDouble(LearningContextItem::score).reversed()
                        .thenComparingInt(LearningContextService::kindPriority)
                        .thenComparing(LearningContextItem::id))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static boolean isPostgresLexical(LearningContextItem item) {
        return "postgres-lexical".equals(item.metadata().get("retrievalLane"));
    }

    private static boolean isMemoryBearing(LearningContextItem item) {
        return "memory".equals(item.kind()) || !memoryIds(item).isEmpty();
    }

    private static int kindPriority(LearningContextItem item) {
        return switch (item.kind()) {
            case "memory" -> 0;
            case "graph_path" -> 1;
            case "semantic_anchor" -> 2;
            default -> 3;
        };
    }

    private static LearningContextMode effectiveMode(List<LearningContextItem> items) {
        boolean memory = !selectedMemoryIds(items).isEmpty();
        boolean scanner = items.stream().anyMatch(item -> item.kind().startsWith("graph")
                || item.kind().startsWith("semantic"));
        if (memory && scanner) {
            return LearningContextMode.COMBINED;
        }
        if (scanner) {
            return LearningContextMode.SCANNER_ONLY;
        }
        if (memory) {
            return LearningContextMode.MEMORY_ONLY;
        }
        return LearningContextMode.NONE;
    }

    private static int countScannerItems(List<LearningContextItem> items) {
        return (int) items.stream().filter(item -> item.kind().startsWith("graph")
                || item.kind().startsWith("semantic")).count();
    }

    private static Set<String> selectedMemoryIds(List<LearningContextItem> items) {
        // A graph path may represent a MemoryAI item without rendering a second standalone memory block.
        Set<String> ids = new LinkedHashSet<>();
        for (LearningContextItem item : items) {
            if ("memory".equals(item.kind())) {
                ids.add(item.id());
            }
            ids.addAll(memoryIds(item));
        }
        return ids;
    }

    private static String firstLocator(GraphPath path, Map<String, GraphNode> nodesById) {
        for (String nodeId : path.nodeIds()) {
            String locator = locator(nodesById.get(nodeId));
            if (!locator.isBlank()) {
                return locator;
            }
        }
        return "";
    }

    private static String locator(GraphNode node) {
        if (node == null || node.filePath() == null || node.filePath().isBlank()) {
            return "";
        }
        if (node.startLine() == null) {
            return node.filePath();
        }
        return node.filePath() + ":" + node.startLine();
    }

    private static void appendLocator(StringBuilder text, String locator) {
        if (locator != null && !locator.isBlank()) {
            text.append(" @ ").append(locator);
        }
    }

    private static void appendText(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append("\n").append(value.trim());
        }
    }

    private static double score(Double value) {
        if (value == null || value.isNaN()) {
            return 0.0;
        }
        return value;
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

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String truncatePreservingSuffix(String value, String requiredSuffix, int limit) {
        String body = value == null ? "" : value.trim();
        String suffix = requiredSuffix == null ? "" : requiredSuffix.trim();
        String combined = body.isBlank() ? suffix : body + "\n" + suffix;
        if (combined.length() <= limit) {
            return combined;
        }
        if (suffix.length() >= limit) {
            return truncate(suffix, limit);
        }
        int bodyLimit = limit - suffix.length() - 4;
        if (bodyLimit <= 0) {
            return suffix;
        }
        return body.substring(0, Math.min(body.length(), bodyLimit)).trim() + "...\n" + suffix;
    }

    private record Selection(List<LearningContextItem> items, int tokenEstimate, int droppedByBudgetCount,
            int droppedCatastrophicCount) {
    }

    private record CandidateAssembly(List<LearningContextItem> items, int crossLaneMemoryDeduplicatedCount) {
    }

    private record StructuralEvidence(String text, String locator, boolean available) {
    }

    private record Deduplication(List<LearningContextItem> items, int deduplicatedCount) {
    }

    private record DuplicateMatch(int index, boolean replaceAllowed) {
        private static DuplicateMatch none() {
            return new DuplicateMatch(-1, false);
        }
    }
}
