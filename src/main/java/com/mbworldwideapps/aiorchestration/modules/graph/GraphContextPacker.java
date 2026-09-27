package com.mbworldwideapps.aiorchestration.modules.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.config.GraphContextRetrievalProperties;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.Metadata;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.SourceRef;
import org.springframework.stereotype.Service;

@Service
class GraphContextPacker {

    private final GraphContextRetrievalProperties properties;
    private final GraphContextRedactor redactor;

    GraphContextPacker(GraphContextRetrievalProperties properties, GraphContextRedactor redactor) {
        this.properties = properties;
        this.redactor = redactor;
    }

    GraphContextRetrieveResponse pack(String projectKey, String retrievalMode, GraphContextSeedResult seedResult,
            GraphContextExpansion expansion, List<GraphWarning> requestWarnings, Instant started) {
        Map<String, GraphNode> nodeMap = new LinkedHashMap<>();
        Map<String, GraphEdge> edgeMap = new LinkedHashMap<>();
        List<GraphWarning> warnings = new ArrayList<>();
        warnings.addAll(seedResult.warnings());
        warnings.addAll(requestWarnings);
        if (expansion != null) {
            warnings.addAll(expansion.warnings());
            for (GraphNode node : expansion.nodes()) {
                nodeMap.putIfAbsent(node.id(), sanitize(node));
            }
            for (GraphEdge edge : expansion.edges()) {
                edgeMap.putIfAbsent(edge.id(), sanitize(edge));
            }
        }
        for (GraphContextSeed seed : seedResult.seeds()) {
            GraphNode seedNode = sanitize(seedNode(seed));
            nodeMap.merge(seed.key(), seedNode, GraphContextPacker::mergeSeedData);
        }

        List<GraphPath> pathCandidates = expansion == null ? List.of()
                : GraphContextPathSelector.selectWithReservations(
                        expansion.paths(), properties.maxPaths(), true,
                        GraphContextPathSelector.lexicalSeedKeys(seedResult.seeds()));
        PackSelection selection = selectWithinBudget(pathCandidates, nodeMap, edgeMap);
        List<GraphPath> paths = selection.paths();
        List<GraphNode> nodes = selection.nodes();
        List<GraphEdge> edges = selection.edges();
        if (selection.budgetApplied()) {
            warnings.add(new GraphWarning("token_budget_applied",
                    "Graph context response was packed to the configured token budget"));
        }
        List<SourceRef> sourceRefs = sourceRefs(nodes);
        List<String> memoryIds = ids(nodes, "Memory");
        List<String> symbolIds = ids(nodes, "CodeSymbol");
        List<String> capsuleKeys = ids(nodes, "CodeCapsule");
        long tokenEstimate = estimateTokens(paths, nodes, edges);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tokenBudget", properties.tokenBudget());
        details.put("tokenBudgetApplied", selection.budgetApplied());
        details.put("droppedPathCount", selection.droppedPaths());
        details.put("droppedNodeCount", selection.droppedNodes());
        details.put("droppedEdgeCount", selection.droppedEdges());
        Metadata metadata = new Metadata(
                seedResult.codeSeedCount(),
                seedResult.memorySeedCount(),
                nodes.size(),
                edges.size(),
                paths.size(),
                expansion != null && expansion.available(),
                seedResult.codePostgresFallbackUsed(),
                seedResult.memoryPostgresFallbackUsed(),
                Duration.between(started, Instant.now()).toMillis(),
                Map.copyOf(details));
        return new GraphContextRetrieveResponse(projectKey, retrievalMode, paths, nodes, edges, sourceRefs,
                memoryIds, symbolIds, capsuleKeys, List.copyOf(warnings), tokenEstimate, metadata);
    }

    private PackSelection selectWithinBudget(List<GraphPath> pathCandidates, Map<String, GraphNode> nodeCandidates,
            Map<String, GraphEdge> edgeCandidates) {
        List<GraphPath> paths = new ArrayList<>();
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        int droppedPaths = 0;

        for (GraphPath path : pathCandidates) {
            Map<String, GraphNode> trialNodes = new LinkedHashMap<>(nodes);
            path.nodeIds().forEach(id -> putIfPresent(trialNodes, id, nodeCandidates.get(id)));
            Map<String, GraphEdge> trialEdges = new LinkedHashMap<>(edges);
            path.edgeIds().forEach(id -> putIfPresent(trialEdges, id, edgeCandidates.get(id)));
            List<GraphPath> trialPaths = new ArrayList<>(paths);
            trialPaths.add(path);
            if (trialNodes.size() > properties.maxNodes() || trialEdges.size() > properties.maxEdges()
                    || estimateTokens(trialPaths, List.copyOf(trialNodes.values()), List.copyOf(trialEdges.values()))
                    > properties.tokenBudget()) {
                droppedPaths++;
                continue;
            }
            paths = trialPaths;
            nodes = trialNodes;
            edges = trialEdges;
        }

        List<GraphNode> orderedNodes = nodeCandidates.values().stream()
                .sorted(Comparator.comparingDouble((GraphNode node) -> safeScore(node.confidence())).reversed()
                        .thenComparing(GraphNode::kind)
                        .thenComparing(GraphNode::id))
                .toList();
        int droppedNodes = 0;
        for (GraphNode node : orderedNodes) {
            if (nodes.containsKey(node.id())) {
                continue;
            }
            if (nodes.size() >= properties.maxNodes()) {
                droppedNodes++;
                continue;
            }
            List<GraphNode> trialNodes = new ArrayList<>(nodes.values());
            trialNodes.add(node);
            if (estimateTokens(paths, trialNodes, List.copyOf(edges.values())) > properties.tokenBudget()) {
                droppedNodes++;
                continue;
            }
            nodes.put(node.id(), node);
        }

        List<GraphEdge> orderedEdges = edgeCandidates.values().stream()
                .sorted(Comparator.comparingDouble((GraphEdge edge) -> safeScore(edge.confidence())).reversed()
                        .thenComparing(GraphEdge::id))
                .toList();
        int droppedEdges = 0;
        for (GraphEdge edge : orderedEdges) {
            if (edges.containsKey(edge.id())) {
                continue;
            }
            if (!nodes.containsKey(edge.sourceNodeId()) || !nodes.containsKey(edge.targetNodeId())
                    || edges.size() >= properties.maxEdges()) {
                droppedEdges++;
                continue;
            }
            List<GraphEdge> trialEdges = new ArrayList<>(edges.values());
            trialEdges.add(edge);
            if (estimateTokens(paths, List.copyOf(nodes.values()), trialEdges) > properties.tokenBudget()) {
                droppedEdges++;
                continue;
            }
            edges.put(edge.id(), edge);
        }

        boolean applied = droppedPaths > 0 || droppedNodes > 0 || droppedEdges > 0;
        return new PackSelection(List.copyOf(paths), List.copyOf(nodes.values()), List.copyOf(edges.values()),
                applied, droppedPaths, droppedNodes, droppedEdges);
    }

    private static <T> void putIfPresent(Map<String, T> target, String key, T value) {
        if (key != null && value != null) {
            target.putIfAbsent(key, value);
        }
    }

    private static double safeScore(Double value) {
        return value == null || value.isNaN() ? 0.0 : value;
    }

    private GraphNode seedNode(GraphContextSeed seed) {
        Map<String, Object> props = new LinkedHashMap<>(seed.metadata());
        if (seed.memoryId() != null) {
            props.put("memoryId", seed.memoryId().toString());
        }
        if (seed.capsuleId() != null) {
            props.put("capsuleId", seed.capsuleId().toString());
        }
        if (seed.symbolId() != null) {
            props.put("symbolId", seed.symbolId().toString());
        }
        if (seed.fileId() != null) {
            props.put("fileId", seed.fileId().toString());
        }
        if (seed.sourceRef() != null) {
            props.put("sourceRef", seed.sourceRef());
        }
        String kind = switch (seed.kind()) {
            case CODE_CAPSULE -> "CodeCapsule";
            case CODE_SYMBOL -> "CodeSymbol";
            case CODE_FILE -> "CodeFile";
            case MEMORY -> "Memory";
        };
        return new GraphNode(seed.key(), kind, seed.projectKey(), seed.title(), seed.summary(), seed.text(),
                stringValue(props.get("filePath")), integerValue(props.get("lineStart")),
                integerValue(props.get("lineEnd")), seed.score(), false, props);
    }

    private GraphNode sanitize(GraphNode node) {
        return new GraphNode(
                node.id(),
                node.kind(),
                node.projectKey(),
                truncate(redactor.redact(node.title()), properties.maxMemorySummaryChars()),
                truncate(redactor.redact(node.summary()), properties.maxMemorySummaryChars()),
                truncate(redactor.redact(node.text()), properties.maxCapsuleTextChars()),
                truncate(redactor.redact(node.filePath()), properties.maxMemorySummaryChars()),
                node.startLine(),
                node.endLine(),
                node.confidence(),
                node.stale(),
                sanitizeMap(node.properties()));
    }

    private static GraphNode mergeSeedData(GraphNode expanded, GraphNode seed) {
        Map<String, Object> properties = new LinkedHashMap<>(expanded.properties());
        properties.putAll(seed.properties());
        return new GraphNode(
                expanded.id(),
                expanded.kind(),
                firstNonBlank(expanded.projectKey(), seed.projectKey()),
                firstNonBlank(expanded.title(), seed.title()),
                firstNonBlank(seed.summary(), expanded.summary()),
                firstNonBlank(seed.text(), expanded.text()),
                firstNonBlank(seed.filePath(), expanded.filePath()),
                seed.startLine() == null ? expanded.startLine() : seed.startLine(),
                seed.endLine() == null ? expanded.endLine() : seed.endLine(),
                seed.confidence() == null ? expanded.confidence() : seed.confidence(),
                expanded.stale() || seed.stale(),
                Map.copyOf(properties));
    }

    private GraphEdge sanitize(GraphEdge edge) {
        return new GraphEdge(edge.id(), edge.type(), edge.sourceNodeId(), edge.targetNodeId(), edge.resolution(),
                edge.confidence(), edge.weight(), edge.stale(), sanitizeMap(edge.properties()));
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (value instanceof String stringValue) {
                sanitized.put(key, truncate(redactor.redact(stringValue), properties.maxCapsuleTextChars()));
            } else if (value != null) {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private List<SourceRef> sourceRefs(List<GraphNode> nodes) {
        List<SourceRef> refs = new ArrayList<>();
        for (GraphNode node : nodes) {
            switch (node.kind()) {
                case "Memory" -> {
                    Object sourceRef = node.properties().get("sourceRef");
                    refs.add(new SourceRef("memory", node.id(), sourceRef == null ? node.id() : sourceRef.toString(),
                            null, null));
                }
                case "CodeCapsule" -> refs.add(new SourceRef("capsule", node.id(), node.id(), null, null));
                case "CodeSymbol" -> refs.add(new SourceRef("symbol", node.id(), node.title(), node.startLine(),
                        node.endLine()));
                case "CodeFile" -> refs.add(new SourceRef("file", node.id(), node.filePath(), null, null));
                default -> {
                }
            }
        }
        return refs;
    }

    private static List<String> ids(List<GraphNode> nodes, String kind) {
        return nodes.stream()
                .filter(node -> kind.equals(node.kind()))
                .map(GraphNode::id)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    private static long estimateTokens(List<GraphPath> paths, List<GraphNode> nodes, List<GraphEdge> edges) {
        long chars = 0;
        for (GraphNode node : nodes) {
            chars += length(node.title()) + length(node.summary()) + length(node.text()) + length(node.filePath());
        }
        chars += (long) paths.size() * 80L;
        chars += (long) edges.size() * 40L;
        return Math.max(1L, chars / 4L);
    }

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private record PackSelection(List<GraphPath> paths, List<GraphNode> nodes, List<GraphEdge> edges,
            boolean budgetApplied, int droppedPaths, int droppedNodes, int droppedEdges) {
    }
}
