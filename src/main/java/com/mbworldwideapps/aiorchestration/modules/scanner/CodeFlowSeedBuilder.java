package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class CodeFlowSeedBuilder {

    public static final String ENDPOINT_FLOW = "endpoint_flow";
    public static final String DOMAIN_FLOW = "domain_flow";
    public static final String INTEGRATION_FLOW = "integration_flow";
    public static final String CACHE_FLOW = "cache_flow";
    public static final String PROJECT_INSIGHT = "project_insight";

    private static final int MAX_SYMBOLS_PER_RUN = 20_000;
    private static final int MAX_EDGES_PER_SYMBOL = 200;
    private static final Set<String> SEED_EDGE_TYPES = Set.of("EXPOSES_ENDPOINT", "ANNOTATED_WITH", "INJECTS",
            "CALLS");
    private static final Set<String> HTTP_MAPPING_ANNOTATIONS = Set.of("RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Set<String> INTEGRATION_ANNOTATIONS = Set.of("FeignClient", "WebServiceClient", "WebService",
            "KafkaListener");
    private static final Set<String> SCHEDULE_ANNOTATIONS = Set.of("Scheduled", "KafkaListener");

    private final CodeBaselineRepository repository;

    public CodeFlowSeedBuilder(CodeBaselineRepository repository) {
        this.repository = repository;
    }

    public List<CodeFlowSeed> build(String projectKey, UUID scanRunId, int limit) {
        if (projectKey == null || projectKey.isBlank() || scanRunId == null) {
            return List.of();
        }
        int max = clamp(limit, 1, 200);
        List<CodeFlowSeed> seeds = new ArrayList<>();
        for (CodeSymbolRecord symbol : repository.findSymbolsForRun(projectKey, scanRunId, MAX_SYMBOLS_PER_RUN)) {
            List<CodeEdgeRecord> edges = repository.findEdgesFrom(projectKey, symbol.id(), SEED_EDGE_TYPES,
                    MAX_EDGES_PER_SYMBOL);
            toSeed(symbol, edges).ifPresent(seeds::add);
        }
        return seeds.stream()
                .sorted(Comparator.comparingDouble(CodeFlowSeed::score).reversed()
                        .thenComparing(CodeFlowSeed::title)
                        .thenComparing(seed -> seed.symbolId().toString()))
                .limit(max)
                .toList();
    }

    private Optional<CodeFlowSeed> toSeed(CodeSymbolRecord symbol, List<CodeEdgeRecord> edges) {
        List<String> annotations = mergeAnnotations(symbol, edges);
        List<String> endpoints = targetRefs(edges, "EXPOSES_ENDPOINT", 3);
        List<String> injectedTypes = targetRefs(edges, "INJECTS", 12);
        List<String> callRefs = targetRefs(edges, "CALLS", 20);
        String endpoint = endpoints.stream().findFirst().orElse("");
        String filePath = firstFilePath(edges);
        SeedClassification classification = classify(symbol, annotations, endpoint, injectedTypes, callRefs);
        if (classification == null) {
            return Optional.empty();
        }
        String title = title(symbol, endpoint, classification);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("role", blank(symbol.role()));
        evidence.put("symbolKind", symbol.symbolKind());
        evidence.put("fanOut", callRefs.size() + injectedTypes.size());
        evidence.put("endpoints", endpoints);
        evidence.put("entryRef", entryRef(symbol));
        if (!filePath.isBlank()) {
            evidence.put("filePath", filePath);
        }
        return Optional.of(new CodeFlowSeed(
                symbol.projectKey(),
                symbol.scanRunId(),
                symbol.id(),
                classification.capsuleKind(),
                classification.triggerKind(),
                title,
                entryRef(symbol),
                filePath,
                endpoint,
                annotations,
                injectedTypes,
                callRefs,
                classification.score(),
                evidence));
    }

    private static SeedClassification classify(CodeSymbolRecord symbol, List<String> annotations, String endpoint,
            List<String> injectedTypes, List<String> callRefs) {
        String role = lower(symbol.role());
        String name = lower(symbol.name());
        String fqn = lower(symbol.fqn());
        if ("extension".equals(symbol.symbolKind())) {
            return null; // a Swift extension adds to a type; the type itself is the entry
        }
        boolean method = "method".equals(symbol.symbolKind());
        // By name only for types: UIKit methods like `pushViewController` are not controllers.
        boolean member = method || "function".equals(symbol.symbolKind()) || "property".equals(symbol.symbolKind());
        boolean controller = role.equals("controller") || !member && name.endsWith("controller") || has(annotations,
                "RestController") || has(annotations, "Controller");
        boolean endpointMapped = !endpoint.isBlank() || annotations.stream().anyMatch(HTTP_MAPPING_ANNOTATIONS::contains);
        if (endpointMapped || controller) {
            double score = 95.0 + (method ? 6.0 : 0.0) + Math.min(10.0, callRefs.size() * 0.5);
            return new SeedClassification(ENDPOINT_FLOW, endpointMapped ? "http_endpoint" : "controller", score);
        }
        if (role.equals("screen")) { // Android activity/fragment/Compose screen: the mobile entry point
            return new SeedClassification(ENDPOINT_FLOW, "ui_screen", 90.0 + Math.min(10.0, callRefs.size() * 0.5));
        }
        if (annotations.stream().anyMatch(INTEGRATION_ANNOTATIONS::contains) || name.endsWith("client")
                || name.endsWith("repository") || role.equals("repository") || role.equals("client")) {
            double score = 82.0 + (has(annotations, "FeignClient") ? 8.0 : 0.0)
                    + (has(annotations, "KafkaListener") ? 5.0 : 0.0);
            return new SeedClassification(INTEGRATION_FLOW, integrationTrigger(annotations, role, name), score);
        }
        if (cacheLike(name, fqn, injectedTypes)) {
            return new SeedClassification(CACHE_FLOW, "cache_boundary",
                    78.0 + Math.min(12.0, callRefs.size() * 0.75));
        }
        if (annotations.stream().anyMatch(SCHEDULE_ANNOTATIONS::contains)) {
            return new SeedClassification(DOMAIN_FLOW, "background_trigger", 76.0);
        }
        if (role.equals("viewmodel")) {
            return new SeedClassification(DOMAIN_FLOW, "viewmodel",
                    70.0 + Math.min(20.0, callRefs.size() + injectedTypes.size() * 2.0));
        }
        if (role.equals("service") && (callRefs.size() >= 4 || injectedTypes.size() >= 2)) {
            return new SeedClassification(DOMAIN_FLOW, "service_fanout",
                    55.0 + Math.min(25.0, callRefs.size() + injectedTypes.size() * 2.0));
        }
        return null;
    }

    private static List<String> mergeAnnotations(CodeSymbolRecord symbol, List<CodeEdgeRecord> edges) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Object metadataAnnotations = symbol.metadata().get("annotations");
        if (metadataAnnotations instanceof Iterable<?> iterable) {
            for (Object annotation : iterable) {
                add(values, String.valueOf(annotation));
            }
        }
        targetRefs(edges, "ANNOTATED_WITH", 40).forEach(value -> add(values, value));
        return List.copyOf(values);
    }

    private static List<String> targetRefs(List<CodeEdgeRecord> edges, String edgeType, int limit) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (CodeEdgeRecord edge : edges) {
            if (edgeType.equals(edge.edgeType())) {
                add(refs, edge.targetRef());
            }
            if (refs.size() >= limit) {
                break;
            }
        }
        return List.copyOf(refs);
    }

    private static String firstFilePath(List<CodeEdgeRecord> edges) {
        return edges.stream()
                .map(edge -> edge.evidence().get("filePath"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .findFirst()
                .orElse("");
    }

    private static String title(CodeSymbolRecord symbol, String endpoint, SeedClassification classification) {
        if (!endpoint.isBlank()) {
            return endpoint + " -> " + symbol.name();
        }
        return switch (classification.capsuleKind()) {
            case INTEGRATION_FLOW -> "Integration boundary: " + symbol.name();
            case CACHE_FLOW -> "Cache boundary: " + symbol.name();
            case DOMAIN_FLOW -> "Domain flow seed: " + symbol.name();
            default -> symbol.name();
        };
    }

    private static String entryRef(CodeSymbolRecord symbol) {
        return symbol.fqn() == null || symbol.fqn().isBlank() ? symbol.name() : symbol.fqn();
    }

    private static boolean cacheLike(String name, String fqn, List<String> injectedTypes) {
        if (name.contains("redis") || name.contains("cache") || fqn.contains(".cache.")
                || fqn.contains(".redis.")) {
            return true;
        }
        return injectedTypes.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("redistemplate")
                        || value.contains("stringredistemplate")
                        || value.contains("cachemanager")
                        || value.contains("cachetemplate"));
    }

    private static String integrationTrigger(List<String> annotations, String role, String name) {
        if (has(annotations, "FeignClient")) {
            return "feign_client";
        }
        if (has(annotations, "KafkaListener")) {
            return "kafka_listener";
        }
        if (has(annotations, "WebServiceClient") || has(annotations, "WebService")) {
            return "soap_client";
        }
        if (role.equals("repository") || name.endsWith("repository")) {
            return "repository";
        }
        return "external_client";
    }

    private static boolean has(List<String> values, String expected) {
        return values.stream().anyMatch(expected::equals);
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value <= 0 ? min : value));
    }

    private record SeedClassification(String capsuleKind, String triggerKind, double score) {
    }
}
