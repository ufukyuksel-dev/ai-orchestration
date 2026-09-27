package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class CodeFlowContextAssembler {

    private static final int DEFAULT_DEPTH = 2;
    private static final int DEFAULT_MAX_SYMBOLS = 12;
    private static final int DEFAULT_MAX_EDGES = 80;
    private static final int DEFAULT_MAX_SNIPPETS = 8;
    private static final int EDGE_QUERY_LIMIT = 80;
    private static final int REF_QUERY_LIMIT = 8;
    private static final Set<String> WALK_EDGE_TYPES = Set.of("DECLARES", "CALLS", "INJECTS", "EXPOSES_ENDPOINT",
            "ANNOTATED_WITH");
    private static final Set<String> FLOW_EDGE_TYPES = Set.of("DECLARES", "CALLS", "INJECTS", "EXPOSES_ENDPOINT");
    private static final Pattern CACHE_KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{4,}");

    private final CodeBaselineRepository repository;

    public CodeFlowContextAssembler(CodeBaselineRepository repository) {
        this.repository = repository;
    }

    public List<CodeFlowSeed> deduplicateEndpointSeeds(List<CodeFlowSeed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        Set<String> methodOwners = new LinkedHashSet<>();
        for (CodeFlowSeed seed : seeds) {
            if (!CodeFlowSeedBuilder.ENDPOINT_FLOW.equals(seed.capsuleKind()) || !isMethodRef(seed.entryRef())) {
                continue;
            }
            methodOwners.add(ownerRef(seed.entryRef()));
        }
        List<CodeFlowSeed> deduped = new ArrayList<>();
        Set<String> seenEndpoints = new LinkedHashSet<>();
        for (CodeFlowSeed seed : seeds) {
            boolean classLevelEndpoint = CodeFlowSeedBuilder.ENDPOINT_FLOW.equals(seed.capsuleKind())
                    && !isMethodRef(seed.entryRef())
                    && methodOwners.contains(seed.entryRef());
            boolean duplicateMethodEndpoint = CodeFlowSeedBuilder.ENDPOINT_FLOW.equals(seed.capsuleKind())
                    && isMethodRef(seed.entryRef())
                    && !blank(seed.endpoint()).isBlank()
                    && !seenEndpoints.add(seed.endpoint());
            if (!classLevelEndpoint && !duplicateMethodEndpoint) {
                deduped.add(seed);
            }
        }
        return List.copyOf(deduped);
    }

    public CodeFlowContext assemble(CodeFlowSeed seed, String rootPath) {
        return assemble(seed, rootPath, DEFAULT_DEPTH, DEFAULT_MAX_SYMBOLS, DEFAULT_MAX_EDGES, DEFAULT_MAX_SNIPPETS);
    }

    CodeFlowContext assemble(CodeFlowSeed seed, String rootPath, int maxDepth, int maxSymbols, int maxEdges,
            int maxSnippets) {
        if (seed == null) {
            return empty(null, "Missing flow seed");
        }
        Optional<CodeSymbolRecord> root = repository.findSymbolById(seed.symbolId())
                .filter(symbol -> seed.projectKey().equals(symbol.projectKey()));
        if (root.isEmpty()) {
            return empty(seed, "Seed symbol not found: " + seed.symbolId());
        }
        int depthLimit = clamp(maxDepth, 1, 4);
        int symbolLimit = clamp(maxSymbols, 1, 50);
        int edgeLimit = clamp(maxEdges, 1, 250);
        int snippetLimit = clamp(maxSnippets, 0, 20);
        Map<UUID, CodeSymbolRecord> symbols = new LinkedHashMap<>();
        Map<UUID, String> reasons = new LinkedHashMap<>();
        Map<UUID, CodeEdgeRecord> edges = new LinkedHashMap<>();
        LinkedHashSet<String> services = new LinkedHashSet<>();
        LinkedHashSet<String> externalClients = new LinkedHashSet<>();
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        LinkedHashSet<String> cacheKeys = new LinkedHashSet<>();
        LinkedHashSet<String> requestModels = new LinkedHashSet<>();
        LinkedHashSet<String> responseModels = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        CodeSymbolRecord rootSymbol = root.get();
        queue.add(new NodeDepth(rootSymbol.id(), 0, "entry"));
        if ("method".equals(rootSymbol.symbolKind())) {
            List<CodeEdgeRecord> declaringEdges = repository.findEdgesTo(seed.projectKey(), rootSymbol.id(),
                    Set.of("DECLARES"), EDGE_QUERY_LIMIT);
            for (CodeEdgeRecord edge : declaringEdges == null ? List.<CodeEdgeRecord>of() : declaringEdges) {
                edges.putIfAbsent(edge.id(), edge);
                UUID ownerId = edge.sourceSymbolId();
                if (ownerId != null && !ownerId.equals(rootSymbol.id())) {
                    queue.add(new NodeDepth(ownerId, 0, "declaring-class"));
                }
            }
        }
        boolean truncated = false;
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            Optional<CodeSymbolRecord> currentSymbol = repository.findSymbolById(current.symbolId())
                    .filter(symbol -> seed.projectKey().equals(symbol.projectKey()));
            if (currentSymbol.isEmpty()) {
                continue;
            }
            CodeSymbolRecord symbol = currentSymbol.get();
            if (symbols.putIfAbsent(symbol.id(), symbol) == null) {
                reasons.put(symbol.id(), current.reason());
                collectSymbolFacts(symbol, services, externalClients, repositories, requestModels, responseModels);
            }
            if (symbols.size() >= symbolLimit) {
                if (!queue.isEmpty()) {
                    truncated = true;
                }
                continue;
            }
            if (current.depth() >= depthLimit) {
                continue;
            }
            for (CodeEdgeRecord edge : repository.findEdgesFrom(seed.projectKey(), symbol.id(), WALK_EDGE_TYPES,
                    EDGE_QUERY_LIMIT)) {
                collectEdgeFacts(edge, cacheKeys, requestModels, responseModels);
                if (FLOW_EDGE_TYPES.contains(edge.edgeType())) {
                    edges.putIfAbsent(edge.id(), edge);
                }
                if (edges.size() >= edgeLimit) {
                    truncated = true;
                    break;
                }
                for (CodeSymbolRecord candidate : resolveTargets(seed.projectKey(), edge)) {
                    if (candidate.id().equals(symbol.id()) || symbols.containsKey(candidate.id())) {
                        continue;
                    }
                    if (!shouldFollow(edge, candidate)) {
                        continue;
                    }
                    queue.add(new NodeDepth(candidate.id(), current.depth() + 1,
                            edge.edgeType() + ":" + edge.targetRef()));
                    collectSymbolFacts(candidate, services, externalClients, repositories, requestModels,
                            responseModels);
                    if (symbols.size() + queue.size() >= symbolLimit) {
                        truncated = true;
                        break;
                    }
                }
                if (truncated) {
                    break;
                }
            }
        }
        if (truncated) {
            warnings.add("Flow context reached configured symbol or edge bounds");
        }
        List<CodeFlowContextSymbol> contextSymbols = symbols.values().stream()
                .map(symbol -> toContextSymbol(seed.projectKey(), symbol, reasons.getOrDefault(symbol.id(), "")))
                .toList();
        List<CodeFlowSnippet> snippets = snippets(rootPath, symbols.values().stream().toList(), snippetLimit,
                seed.projectKey(), warnings);
        snippets.stream()
                .map(CodeFlowSnippet::text)
                .forEach(text -> collectCacheKeyTokens(text, cacheKeys));
        return new CodeFlowContext(seed, contextSymbols, edges.values().stream().map(CodeFlowContextAssembler::toEdge)
                .toList(), List.copyOf(services), List.copyOf(externalClients), List.copyOf(repositories),
                List.copyOf(cacheKeys), List.copyOf(requestModels), List.copyOf(responseModels), snippets, truncated,
                warnings);
    }

    private List<CodeSymbolRecord> resolveTargets(String projectKey, CodeEdgeRecord edge) {
        if (edge.targetSymbolId() != null) {
            return repository.findSymbolById(edge.targetSymbolId())
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .map(List::of)
                    .orElseGet(List::of);
        }
        if (edge.targetRef() == null || edge.targetRef().isBlank()
                || edge.edgeType().equals("ANNOTATED_WITH")
                || edge.edgeType().equals("EXPOSES_ENDPOINT")) {
            return List.of();
        }
        return repository.findSymbolsByRef(projectKey, edge.targetRef(), REF_QUERY_LIMIT);
    }

    private static boolean shouldFollow(CodeEdgeRecord edge, CodeSymbolRecord candidate) {
        if (edge.edgeType().equals("DECLARES")) {
            return "method".equals(candidate.symbolKind());
        }
        if (edge.edgeType().equals("CALLS")) {
            return "method".equals(candidate.symbolKind()) || isFlowRelevant(candidate);
        }
        if (edge.edgeType().equals("INJECTS")) {
            return isFlowRelevant(candidate);
        }
        return false;
    }

    private static boolean isFlowRelevant(CodeSymbolRecord symbol) {
        String name = lower(symbol.name());
        String role = lower(symbol.role());
        String fqn = lower(symbol.fqn());
        return role.equals("service")
                || role.equals("controller")
                || role.equals("repository")
                || name.endsWith("service")
                || name.endsWith("client")
                || name.endsWith("repository")
                || name.contains("redis")
                || name.contains("cache")
                || fqn.contains(".redis.")
                || fqn.contains(".cache.");
    }

    private void collectSymbolFacts(CodeSymbolRecord symbol, Set<String> services, Set<String> externalClients,
            Set<String> repositories, Set<String> requestModels, Set<String> responseModels) {
        String name = blank(symbol.name());
        String role = lower(symbol.role());
        String fqn = lower(symbol.fqn());
        if (role.equals("service") || name.endsWith("Service")) {
            services.add(entryRef(symbol));
        }
        if (name.endsWith("Client") || fqn.contains(".client.")) {
            externalClients.add(entryRef(symbol));
        }
        if (role.equals("repository") || name.endsWith("Repository")) {
            repositories.add(entryRef(symbol));
        }
        collectModelRefs(symbol.signature(), requestModels, responseModels);
        Object returnType = symbol.metadata().get("returnType");
        collectModelRefs(returnType == null ? "" : String.valueOf(returnType), requestModels, responseModels);
        collectModelRefs(name, requestModels, responseModels);
    }

    private static void collectEdgeFacts(CodeEdgeRecord edge, Set<String> cacheKeys, Set<String> requestModels,
            Set<String> responseModels) {
        collectCacheRefs(edge.targetRef(), cacheKeys);
        collectModelRefs(edge.targetRef(), requestModels, responseModels);
    }

    private static void collectCacheRefs(String value, Set<String> cacheKeys) {
        if (value == null || value.isBlank()) {
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("redis") || lower.contains("cache") || CACHE_KEY_PATTERN.matcher(value).matches()) {
            cacheKeys.add(value.trim());
        }
    }

    private static void collectCacheKeyTokens(String value, Set<String> cacheKeys) {
        if (value == null || value.isBlank()) {
            return;
        }
        var matcher = CACHE_KEY_PATTERN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.contains("cache") || lower.contains("redis") || lower.contains("key")) {
                cacheKeys.add(token);
            }
        }
    }

    private static void collectModelRefs(String value, Set<String> requestModels, Set<String> responseModels) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split("[^A-Za-z0-9_]+")) {
            if (token.endsWith("Request") || token.endsWith("RequestModel") || token.endsWith("RequestDto")
                    || token.endsWith("RequestDTO")) {
                requestModels.add(token);
            }
            if (token.endsWith("Response") || token.endsWith("ResponseModel") || token.endsWith("ResponseDto")
                    || token.endsWith("ResponseDTO")) {
                responseModels.add(token);
            }
        }
    }

    private CodeFlowContextSymbol toContextSymbol(String projectKey, CodeSymbolRecord symbol, String reason) {
        String filePath = repository.findFileById(projectKey, symbol.fileId())
                .map(CodeFileRecord::filePath)
                .orElse("");
        return new CodeFlowContextSymbol(symbol.id(), symbol.symbolKind(), symbol.name(), symbol.fqn(),
                symbol.signature(), symbol.role(), filePath, symbol.startLine(), symbol.endLine(),
                annotations(symbol), reason);
    }

    private List<CodeFlowSnippet> snippets(String rootPath, List<CodeSymbolRecord> symbols, int limit, String projectKey,
            List<String> warnings) {
        if (limit <= 0 || rootPath == null || rootPath.isBlank()) {
            return List.of();
        }
        List<CodeFlowSnippet> snippets = new ArrayList<>();
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        for (CodeSymbolRecord symbol : symbols) {
            if (snippets.size() >= limit) {
                break;
            }
            Optional<CodeFileRecord> file = repository.findFileById(projectKey, symbol.fileId());
            if (file.isEmpty()) {
                continue;
            }
            Path path = root.resolve(file.get().filePath()).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                snippets.add(new CodeFlowSnippet(symbol.id(), symbol.name(), file.get().filePath(), symbol.startLine(),
                        symbol.endLine(), boundedLines(path, symbol.startLine(), symbol.endLine())));
            } catch (IOException e) {
                warnings.add("Could not read snippet for " + file.get().filePath() + ": " + e.getClass().getSimpleName());
            }
        }
        return snippets;
    }

    private static String boundedLines(Path path, Integer startLine, Integer endLine) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return "";
        }
        int start = Math.min(lines.size(), Math.max(1, startLine == null ? 1 : startLine));
        int end = Math.min(lines.size(), endLine == null ? start + 40 : endLine);
        if (end < start) {
            end = start;
        }
        String text = String.join("\n", lines.subList(start - 1, end));
        return text.substring(0, Math.min(text.length(), 2500));
    }

    private static List<String> annotations(CodeSymbolRecord symbol) {
        Object value = symbol.metadata().get("annotations");
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> annotations = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                annotations.add(String.valueOf(item));
            }
        }
        return List.copyOf(annotations);
    }

    private static CodeFlowContextEdge toEdge(CodeEdgeRecord edge) {
        return new CodeFlowContextEdge(edge.id(), edge.sourceSymbolId(), edge.targetSymbolId(), edge.targetRef(),
                edge.edgeType(), edge.resolution(), edge.confidence());
    }

    private static CodeFlowContext empty(CodeFlowSeed seed, String warning) {
        return new CodeFlowContext(seed, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), false, List.of(warning));
    }

    private static boolean isMethodRef(String ref) {
        return ref != null && ref.contains("#");
    }

    private static String ownerRef(String ref) {
        int index = ref == null ? -1 : ref.indexOf('#');
        return index < 0 ? blank(ref) : ref.substring(0, index);
    }

    private static String entryRef(CodeSymbolRecord symbol) {
        return symbol.fqn() == null || symbol.fqn().isBlank() ? symbol.name() : symbol.fqn();
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

    private record NodeDepth(UUID symbolId, int depth, String reason) {
    }
}
