package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CodebaseService {

    private static final Logger log = LoggerFactory.getLogger(CodebaseService.class);
    private static final int MAX_TOP_K = 50;
    private static final int MAX_NEIGHBOR_DEPTH = 3;
    private static final int MAX_GRAPH_NODES = 200;
    private static final int MAX_GRAPH_EDGES = 500;
    private static final int EDGE_QUERY_LIMIT = 100;
    private static final int DEFAULT_SYMBOL_EDGE_LIMIT = 10;
    private static final int MAX_SYMBOL_EDGE_LIMIT = 100;
    private static final Set<String> FLOW_CAPSULE_KINDS = Set.of(
            CodeFlowSeedBuilder.ENDPOINT_FLOW,
            CodeFlowSeedBuilder.DOMAIN_FLOW,
            CodeFlowSeedBuilder.INTEGRATION_FLOW,
            CodeFlowSeedBuilder.CACHE_FLOW,
            CodeFlowSeedBuilder.PROJECT_INSIGHT);

    private final CodeBaselineRepository repository;
    private final CodeBaselineVectorIndex vectorIndex;
    private final ScannerBaselineFreshness baselineFreshness;

    @Autowired
    public CodebaseService(CodeBaselineRepository repository, CodeBaselineVectorIndex vectorIndex,
            ScannerBaselineFreshness baselineFreshness) {
        this.repository = repository;
        this.vectorIndex = vectorIndex;
        this.baselineFreshness = baselineFreshness;
    }

    public CodebaseService(CodeBaselineRepository repository, CodeBaselineVectorIndex vectorIndex) {
        this(repository, vectorIndex, ScannerBaselineFreshness.defaults());
    }

    public BaselineSearchResponse search(String query, String projectKey, Integer topK) {
        int limit = clamp(topK, 5, MAX_TOP_K);
        List<SearchHit> hits = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        boolean degraded = false;
        String degradedReason = "";
        boolean usedFallback = false;
        try {
            int vectorFetchLimit = Math.min(MAX_TOP_K, Math.max(limit, limit * 4));
            for (ScoredCodeCapsuleRef ref : vectorIndex.search(query, vectorFetchLimit, projectKey)) {
                repository.findCapsuleById(ref.capsuleId())
                        .filter(capsule -> projectKey.equals(capsule.projectKey()))
                        .ifPresent(capsule -> {
                            if (seen.add(capsule.id())) {
                                hits.add(toHit(capsule, ref.similarityScore()));
                            }
                        });
            }
        } catch (RuntimeException e) {
            log.warn("Codebase vector search failed; falling back to Postgres text search for projectKey={} exception={}: {}",
                    projectKey, e.getClass().getSimpleName(), e.getMessage());
            degraded = true;
            degradedReason = e.getClass().getSimpleName();
            hits.clear();
            seen.clear();
        }
        if (hits.isEmpty()) {
            usedFallback = true;
            for (CodeSemanticCapsuleRecord capsule : fallbackCapsules(projectKey, query, limit)) {
                if (seen.add(capsule.id())) {
                    hits.add(toHit(capsule, 0.35));
                }
            }
        }
        List<SearchHit> orderedHits = hits.stream()
                .sorted(Comparator.comparingInt((SearchHit hit) -> capsulePriority(hit.capsuleKind()))
                .thenComparing(Comparator.comparingDouble(SearchHit::score).reversed())
                .thenComparing(SearchHit::summary))
                .limit(limit)
                .toList();
        String source = usedFallback ? "postgres_text" : "vector";
        String scoreSource = orderedHits.isEmpty()
                ? "none"
                : usedFallback ? "postgres_text_synthetic" : "vector_similarity";
        return new BaselineSearchResponse(projectKey, List.copyOf(orderedHits), staleness(projectKey),
                new SearchMetadata(source, scoreSource, degraded, degradedReason));
    }

    private List<CodeSemanticCapsuleRecord> fallbackCapsules(String projectKey, String query, int limit) {
        List<CodeSemanticCapsuleRecord> capsules = new ArrayList<>(repository.searchCapsulesText(projectKey, query,
                limit));
        if (capsules.size() >= limit || query == null || query.isBlank()) {
            return orderCapsules(capsules, limit);
        }
        Set<UUID> seen = capsules.stream()
                .map(CodeSemanticCapsuleRecord::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> terms = Arrays.stream(query.split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 3)
                .distinct()
                .limit(8)
                .toList();
        for (String term : terms) {
            for (CodeSemanticCapsuleRecord capsule : repository.searchCapsulesText(projectKey, term, limit)) {
                if (seen.add(capsule.id())) {
                    capsules.add(capsule);
                    if (capsules.size() >= limit) {
                        return capsules;
                    }
                }
            }
        }
        return orderCapsules(capsules, limit);
    }

    private static List<CodeSemanticCapsuleRecord> orderCapsules(List<CodeSemanticCapsuleRecord> capsules,
            int limit) {
        return capsules.stream()
                .sorted(Comparator.comparingInt((CodeSemanticCapsuleRecord capsule) ->
                                capsulePriority(capsule.capsuleKind()))
                        .thenComparing(CodeSemanticCapsuleRecord::summary))
                .limit(limit)
                .toList();
    }

    public SymbolGetResponse symbolGet(String ref, String projectKey) {
        return symbolGet(ref, projectKey, DEFAULT_SYMBOL_EDGE_LIMIT);
    }

    public SymbolGetResponse symbolGet(String ref, String projectKey, Integer edgeLimit) {
        int effectiveEdgeLimit = clamp(edgeLimit, DEFAULT_SYMBOL_EDGE_LIMIT, MAX_SYMBOL_EDGE_LIMIT);
        List<SymbolDetail> symbols = repository.findSymbolsByRef(projectKey, ref, 20).stream()
                .map(symbol -> toDetail(symbol, effectiveEdgeLimit))
                .toList();
        return new SymbolGetResponse(projectKey, symbols, staleness(projectKey));
    }

    public SymbolNeighborsResponse neighbors(String symbolRef, Integer depth, List<String> edgeTypes,
            String projectKey) {
        int maxDepth = clamp(depth, 1, MAX_NEIGHBOR_DEPTH);
        Set<String> edgeTypeSet = normalizeEdgeTypes(edgeTypes);
        Map<UUID, SymbolSummary> nodes = new LinkedHashMap<>();
        List<EdgeSummary> edges = new ArrayList<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        Set<UUID> visited = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<CodeSymbolRecord> roots = repository.findSymbolsByRef(projectKey, symbolRef, 20);
        if (roots.isEmpty()) {
            warnings.add("No symbol matched ref: " + blank(symbolRef));
            return new SymbolNeighborsResponse(projectKey, List.of(), List.of(), false, false, List.copyOf(warnings),
                    staleness(projectKey));
        }
        for (CodeSymbolRecord root : roots) {
            nodes.put(root.id(), toSummary(root));
            visited.add(root.id());
            queue.add(new NodeDepth(root.id(), 0));
        }
        boolean nodeTruncated = false;
        boolean edgeTruncated = false;
        while (!queue.isEmpty() && !nodeTruncated && !edgeTruncated) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= maxDepth) {
                continue;
            }
            List<CodeEdgeRecord> adjacent = new ArrayList<>();
            List<CodeEdgeRecord> outgoing = repository.findEdgesFrom(projectKey, current.symbolId(), edgeTypeSet,
                    EDGE_QUERY_LIMIT);
            List<CodeEdgeRecord> incoming = repository.findEdgesTo(projectKey, current.symbolId(), edgeTypeSet,
                    EDGE_QUERY_LIMIT);
            if (outgoing.size() >= EDGE_QUERY_LIMIT || incoming.size() >= EDGE_QUERY_LIMIT) {
                edgeTruncated = true;
                warnings.add("At least one edge query reached the per-direction limit of " + EDGE_QUERY_LIMIT);
            }
            adjacent.addAll(outgoing);
            adjacent.addAll(incoming);
            for (CodeEdgeRecord edge : adjacent) {
                UUID neighbor = neighbor(edge, current.symbolId());
                if (neighbor == null || visited.contains(neighbor)) {
                    if (nodes.containsKey(edge.sourceSymbolId())
                            && edge.targetSymbolId() != null
                            && nodes.containsKey(edge.targetSymbolId())
                            && edges.size() < MAX_GRAPH_EDGES) {
                        edges.add(toEdge(edge));
                    }
                    continue;
                }
                Optional<CodeSymbolRecord> symbol = repository.findSymbolById(neighbor)
                        .filter(candidate -> projectKey.equals(candidate.projectKey()));
                if (symbol.isEmpty()) {
                    continue;
                }
                if (edges.size() >= MAX_GRAPH_EDGES) {
                    edgeTruncated = true;
                    warnings.add("Graph edge output reached limit " + MAX_GRAPH_EDGES);
                    break;
                }
                nodes.put(neighbor, toSummary(symbol.get()));
                visited.add(neighbor);
                edges.add(toEdge(edge));
                queue.add(new NodeDepth(neighbor, current.depth() + 1));
                if (nodes.size() >= MAX_GRAPH_NODES) {
                    nodeTruncated = true;
                    warnings.add("Graph node output reached limit " + MAX_GRAPH_NODES);
                    break;
                }
            }
        }
        return new SymbolNeighborsResponse(projectKey, List.copyOf(nodes.values()), List.copyOf(edges), nodeTruncated,
                edgeTruncated, List.copyOf(warnings), staleness(projectKey));
    }

    public ImpactAnalyzeResponse impact(String ref, String projectKey) {
        List<CodeSymbolRecord> targets = repository.findSymbolsByRef(projectKey, ref, 10);
        Map<UUID, ImpactHit> resolvedImpact = new LinkedHashMap<>();
        Map<UUID, ImpactHit> possibleImpact = new LinkedHashMap<>();
        Map<UUID, EdgeSummary> evidence = new LinkedHashMap<>();
        for (CodeSymbolRecord target : targets) {
            Set<String> refs = targetRefs(target);
            Map<UUID, CodeEdgeRecord> incomingById = new LinkedHashMap<>();
            repository.findEdgesTo(projectKey, target.id(), Set.of("CALLS", "INJECTS", "DECLARES"),
                    EDGE_QUERY_LIMIT).forEach(edge -> incomingById.putIfAbsent(edge.id(), edge));
            repository.findEdgesByTargetRefs(projectKey, refs, Set.of("CALLS", "INJECTS"),
                    EDGE_QUERY_LIMIT).forEach(edge -> incomingById.putIfAbsent(edge.id(), edge));
            Set<UUID> sourceIds = incomingById.values().stream()
                    .map(CodeEdgeRecord::sourceSymbolId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, CodeSymbolRecord> sourceSymbols = repository.findSymbolsByIds(projectKey, sourceIds,
                    Math.max(1, sourceIds.size())).stream()
                    .collect(java.util.stream.Collectors.toMap(CodeSymbolRecord::id, symbol -> symbol,
                            (first, second) -> first, LinkedHashMap::new));
            for (CodeEdgeRecord edge : incomingById.values()) {
                EdgeSummary edgeSummary = toEdge(edge);
                evidence.putIfAbsent(edge.id(), edgeSummary);
                CodeSymbolRecord source = sourceSymbols.get(edge.sourceSymbolId());
                if (source != null) {
                    addImpact(target, edge, edgeSummary, source, resolvedImpact, possibleImpact);
                }
            }
        }
        return new ImpactAnalyzeResponse(projectKey, targets.stream().map(CodebaseService::toSummary).toList(),
                List.copyOf(resolvedImpact.values()), List.copyOf(possibleImpact.values()),
                List.copyOf(evidence.values()), staleness(projectKey));
    }

    private static void addImpact(CodeSymbolRecord target, CodeEdgeRecord edge, EdgeSummary edgeSummary,
            CodeSymbolRecord source, Map<UUID, ImpactHit> resolvedImpact, Map<UUID, ImpactHit> possibleImpact) {
        boolean precise = preciseImpact(edge, target);
        double confidence = precise ? edge.confidence() : Math.min(edge.confidence(), 0.49);
        ImpactHit hit = new ImpactHit(toSummary(source), edgeSummary, impactResolution(edge, target, precise),
                confidence, precise ? "exact target_symbol_id or fully-qualified target_ref match"
                        : "D1 syntactic name-only target_ref; may match same-named symbols");
        Map<UUID, ImpactHit> destination = precise ? resolvedImpact : possibleImpact;
        if (precise) {
            possibleImpact.remove(source.id());
        } else if (resolvedImpact.containsKey(source.id())) {
            return;
        }
        ImpactHit existing = destination.get(source.id());
        if (existing == null || hit.confidence() > existing.confidence()) {
            destination.put(source.id(), hit);
        }
    }

    private static boolean preciseImpact(CodeEdgeRecord edge, CodeSymbolRecord target) {
        if (target.id().equals(edge.targetSymbolId())) {
            return true;
        }
        return target.fqn() != null && !target.fqn().isBlank() && target.fqn().equals(edge.targetRef());
    }

    private static String impactResolution(CodeEdgeRecord edge, CodeSymbolRecord target, boolean precise) {
        if (!precise) {
            return "syntactic_name_only";
        }
        if (target.id().equals(edge.targetSymbolId())) {
            return edge.resolution() == null || edge.resolution().isBlank() ? "resolved" : edge.resolution();
        }
        return "syntactic_fqn";
    }

    public DiagnoseResponse diagnose(String symptom, String errorText, String stackTrace, List<String> changedFiles,
            String projectKey) {
        String query = String.join("\n", List.of(blank(symptom), blank(errorText), blank(stackTrace))).trim();
        BaselineSearchResponse search = search(query, projectKey, 8);
        List<CodeDiagnosticRecord> diagnostics = repository.recentDiagnostics(projectKey, 10);
        List<DiagnosisSuspect> suspects = new ArrayList<>();
        Set<String> changed = changedFiles == null ? Set.of() : new LinkedHashSet<>(changedFiles);
        List<DiagnosisCandidate> candidates = new ArrayList<>();
        for (SearchHit hit : search.hits()) {
            double confidence = hit.score();
            if (changed.contains(hit.filePath())) {
                confidence += 0.15;
            }
            if (diagnostics.stream().anyMatch(diagnostic -> hit.filePath().equals(diagnostic.filePath()))) {
                confidence += 0.10;
            }
            DiagnosisSuspect suspect = new DiagnosisSuspect(hit.symbolId(), hit.fqn(), hit.filePath(), hit.lineStart(),
                    hit.lineEnd(), Math.min(confidence, 1.0), hit.summary(), hit.text());
            candidates.add(new DiagnosisCandidate(hit.capsuleKind(), suspect));
        }
        candidates.sort(Comparator.comparingInt((DiagnosisCandidate candidate) ->
                        capsulePriority(candidate.capsuleKind()))
                .thenComparing(Comparator.comparingDouble((DiagnosisCandidate candidate) ->
                        candidate.suspect().confidence()).reversed()));
        suspects.addAll(candidates.stream().map(DiagnosisCandidate::suspect).toList());
        return new DiagnoseResponse(projectKey, List.copyOf(suspects), diagnostics, search.staleness());
    }

    private SymbolDetail toDetail(CodeSymbolRecord symbol, int edgeLimit) {
        List<CodeSemanticCapsuleRecord> capsules = repository.findCapsulesBySymbolId(symbol.projectKey(),
                symbol.id());
        List<CodeEdgeRecord> fetchedEdges = repository.findEdgesFrom(symbol.projectKey(), symbol.id(), Set.of(),
                edgeLimit + 1);
        boolean edgesTruncated = fetchedEdges.size() > edgeLimit;
        List<CodeEdgeRecord> edges = edgesTruncated ? fetchedEdges.subList(0, edgeLimit) : fetchedEdges;
        String filePath = repository.findFileById(symbol.projectKey(), symbol.fileId())
                .filter(file -> symbol.projectKey().equals(file.projectKey()))
                .map(CodeFileRecord::filePath).orElse(null);
        return new SymbolDetail(toSummary(symbol), filePath, capsules.stream().map(this::toCapsuleSummary).toList(),
                edges.stream().map(CodebaseService::toEdge).toList(), edgesTruncated);
    }

    private SearchHit toHit(CodeSemanticCapsuleRecord capsule, double score) {
        CodeSymbolRecord symbol = capsule.symbolId() == null ? null
                : repository.findSymbolsByIds(capsule.projectKey(), Set.of(capsule.symbolId()), 1).stream()
                        .findFirst()
                        .orElse(null);
        return new SearchHit(capsule.id(), capsule.symbolId(), symbol == null ? "" : symbol.fqn(),
                stringEvidence(capsule, "filePath"), intEvidence(capsule, "lineStart"),
                intEvidence(capsule, "lineEnd"), capsule.capsuleKind(), capsule.summary(), capsule.text(),
                capsule.provider(), capsule.semanticModel(), score);
    }

    private CapsuleSummary toCapsuleSummary(CodeSemanticCapsuleRecord capsule) {
        return new CapsuleSummary(capsule.id(), capsule.capsuleKind(), capsule.summary(), capsule.text(),
                capsule.provider(), capsule.semanticModel(), stringEvidence(capsule, "filePath"),
                intEvidence(capsule, "lineStart"), intEvidence(capsule, "lineEnd"));
    }

    private static SymbolSummary toSummary(CodeSymbolRecord symbol) {
        return new SymbolSummary(symbol.id(), symbol.symbolKind(), symbol.name(), symbol.fqn(), symbol.signature(),
                symbol.role(), symbol.startLine(), symbol.endLine());
    }

    private static EdgeSummary toEdge(CodeEdgeRecord edge) {
        return new EdgeSummary(edge.id(), edge.sourceSymbolId(), edge.targetSymbolId(), edge.targetRef(),
                edge.edgeType(), edge.resolution(), edge.confidence(), edge.evidence());
    }

    private CodebaseStaleness staleness(String projectKey) {
        Optional<CodeScanRunRecord> latest = repository.latestCompletedRun(projectKey);
        if (latest.isEmpty()) {
            return new CodebaseStaleness(true, "no_completed_scan", null, null, true);
        }
        CodeScanRunRecord run = latest.get();
        Instant newestMtime = baselineFreshness.newestMtime(run.rootPath());
        boolean stale = newestMtime != null && run.completedAt() != null && newestMtime.isAfter(run.completedAt());
        return new CodebaseStaleness(stale, stale ? "filesystem_newer_than_baseline" : "fresh",
                run.completedAt(), newestMtime, stale);
    }

    private static UUID neighbor(CodeEdgeRecord edge, UUID current) {
        if (edge.sourceSymbolId().equals(current)) {
            return edge.targetSymbolId();
        }
        return edge.sourceSymbolId();
    }

    private static Set<String> targetRefs(CodeSymbolRecord symbol) {
        Set<String> refs = new LinkedHashSet<>();
        refs.add(symbol.name());
        refs.add(symbol.fqn());
        if (symbol.fqn() != null && symbol.fqn().contains("#")) {
            refs.add(symbol.fqn().substring(symbol.fqn().indexOf('#') + 1));
        }
        return refs;
    }

    private static Set<String> normalizeEdgeTypes(List<String> edgeTypes) {
        if (edgeTypes == null || edgeTypes.isEmpty()) {
            return Set.of();
        }
        return edgeTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static int clamp(Integer requested, int defaultValue, int max) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, max);
    }

    private static String stringEvidence(CodeSemanticCapsuleRecord capsule, String key) {
        Object value = capsule.evidence().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intEvidence(CodeSemanticCapsuleRecord capsule, String key) {
        Object value = capsule.evidence().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static int capsulePriority(String capsuleKind) {
        return FLOW_CAPSULE_KINDS.contains(capsuleKind) ? 0 : 1;
    }

    private record NodeDepth(UUID symbolId, int depth) {
    }

    private record DiagnosisCandidate(String capsuleKind, DiagnosisSuspect suspect) {
    }

    public record BaselineSearchResponse(String projectKey, List<SearchHit> hits, CodebaseStaleness staleness,
            SearchMetadata metadata) {
    }

    public record SearchMetadata(String source, String scoreSource, boolean degraded, String degradedReason) {
    }

    public record SearchHit(UUID capsuleId, UUID symbolId, String fqn, String filePath, Integer lineStart,
            Integer lineEnd, String capsuleKind, String summary, String text, String provider, String semanticModel,
            double score) {
    }

    /** {@code rules}/{@code memories}: what the user attached to these symbols' files or to the symbols themselves. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record SymbolGetResponse(String projectKey, List<SymbolDetail> symbols, CodebaseStaleness staleness,
            List<Attached> rules, List<Attached> memories) {
        public SymbolGetResponse(String projectKey, List<SymbolDetail> symbols, CodebaseStaleness staleness) {
            this(projectKey, symbols, staleness, List.of(), List.of());
        }

        public SymbolGetResponse withAttached(List<Attached> rules, List<Attached> memories) {
            return new SymbolGetResponse(projectKey, symbols, staleness, rules, memories);
        }
    }

    /** An attached rule statement or memory summary and the code it is bound to. */
    public record Attached(String id, String text, String target) {
    }

    public record SymbolDetail(SymbolSummary symbol, String filePath, List<CapsuleSummary> capsules, List<EdgeSummary> edges,
            boolean edgesTruncated) {
    }

    public record SymbolSummary(UUID symbolId, String kind, String name, String fqn, String signature, String role,
            Integer startLine, Integer endLine) {
    }

    public record CapsuleSummary(UUID capsuleId, String kind, String summary, String text, String provider,
            String semanticModel, String filePath, Integer lineStart, Integer lineEnd) {
    }

    public record EdgeSummary(UUID edgeId, UUID sourceSymbolId, UUID targetSymbolId, String targetRef,
            String edgeType, String resolution, double confidence, Map<String, Object> evidence) {
    }

    public record SymbolNeighborsResponse(String projectKey, List<SymbolSummary> nodes, List<EdgeSummary> edges,
            boolean nodeTruncated, boolean edgeTruncated, List<String> warnings, CodebaseStaleness staleness) {

        public boolean truncated() {
            return nodeTruncated || edgeTruncated;
        }
    }

    public record ImpactAnalyzeResponse(String projectKey, List<SymbolSummary> targets, List<ImpactHit> resolvedImpact,
            List<ImpactHit> possibleImpact,
            List<EdgeSummary> evidence, CodebaseStaleness staleness) {
    }

    public record ImpactHit(SymbolSummary symbol, EdgeSummary evidence, String resolution, double confidence,
            String rationale) {
    }

    public record DiagnoseResponse(String projectKey, List<DiagnosisSuspect> suspects,
            List<CodeDiagnosticRecord> diagnostics, CodebaseStaleness staleness) {
    }

    public record DiagnosisSuspect(UUID symbolId, String fqn, String filePath, Integer lineStart, Integer lineEnd,
            double confidence, String reason, String evidence) {
    }

    public record CodebaseStaleness(boolean stale, String reason, Instant baselineCompletedAt,
            Instant newestFileMtime, boolean suggestScannerScan) {
    }
}
