package com.mbworldwideapps.aiorchestration.modules.graph;

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

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryEligibilityFilter;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRankingProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryInjectionFilter;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ScoredMemoryRef;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeCapsuleLinkTargetRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSemanticCapsuleRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScoredCodeCapsuleRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class DefaultGraphContextSeedProvider implements GraphContextSeedProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultGraphContextSeedProvider.class);
    private static final double POSTGRES_FALLBACK_SCORE = 0.35;
    private static final int STRUCTURAL_CANDIDATE_CAP = 5_000;
    private static final int MAX_VECTOR_REPAIR_SEARCH_PASSES = 3;
    private static final int MAX_MEMORY_AUTHORITY_REFILL_CANDIDATES = 200;
    private static final String LANE_QDRANT_CODE = "qdrant-code-semantic";
    private static final String LANE_POSTGRES_CAPSULE = "postgres-capsule-fallback";
    private static final String LANE_QDRANT_MEMORY = "qdrant-memory-semantic";
    private static final String LANE_POSTGRES_MEMORY = "postgres-memory-fallback";
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "where", "what", "which", "with", "from", "this", "that", "defined",
            "ve", "ile", "icin", "hangi", "nerede", "nasil", "tanimli", "dosyada", "sinifta");

    private final CodeBaselineVectorIndex codeBaselineVectorIndex;
    private final CodeBaselineRepository codeBaselineRepository;
    private final MemoryVectorIndex memoryVectorIndex;
    private final MemoryRepository memoryRepository;
    private final MemoryRankingProperties memoryRanking;
    private final RuleMemoryInjectionFilter ruleMemoryInjectionFilter;

    @Autowired
    DefaultGraphContextSeedProvider(CodeBaselineVectorIndex codeBaselineVectorIndex,
            CodeBaselineRepository codeBaselineRepository, MemoryVectorIndex memoryVectorIndex,
            MemoryRepository memoryRepository, MemoryRankingProperties memoryRanking,
            RuleMemoryInjectionFilter ruleMemoryInjectionFilter) {
        this.codeBaselineVectorIndex = codeBaselineVectorIndex;
        this.codeBaselineRepository = codeBaselineRepository;
        this.memoryVectorIndex = memoryVectorIndex;
        this.memoryRepository = memoryRepository;
        this.memoryRanking = memoryRanking == null ? MemoryRankingProperties.defaults() : memoryRanking;
        this.ruleMemoryInjectionFilter = java.util.Objects.requireNonNull(
                ruleMemoryInjectionFilter, "ruleMemoryInjectionFilter");
    }

    DefaultGraphContextSeedProvider(CodeBaselineVectorIndex codeBaselineVectorIndex,
            CodeBaselineRepository codeBaselineRepository, MemoryVectorIndex memoryVectorIndex,
            MemoryRepository memoryRepository) {
        this(codeBaselineVectorIndex, codeBaselineRepository, memoryVectorIndex, memoryRepository,
                MemoryRankingProperties.defaults(), legacyTestFilter());
    }

    DefaultGraphContextSeedProvider(CodeBaselineVectorIndex codeBaselineVectorIndex,
            CodeBaselineRepository codeBaselineRepository, MemoryVectorIndex memoryVectorIndex,
            MemoryRepository memoryRepository, MemoryRankingProperties memoryRanking) {
        this(codeBaselineVectorIndex, codeBaselineRepository, memoryVectorIndex, memoryRepository,
                memoryRanking, legacyTestFilter());
    }

    @Override
    public GraphContextSeedResult seeds(String query, String projectKey, int limit) {
        return seeds(query, projectKey, limit, true, true);
    }

    @Override
    public GraphContextSeedResult seeds(String query, String projectKey, int limit, boolean includeMemory) {
        return seeds(query, projectKey, limit, includeMemory, true);
    }

    @Override
    public GraphContextSeedResult seeds(String query, String projectKey, int limit, boolean includeMemory,
            boolean hybridFusionEnabled) {
        List<GraphWarning> warnings = new ArrayList<>();
        Map<String, GraphContextSeed> codeSeeds = new LinkedHashMap<>();
        Map<String, GraphContextSeed> memorySeeds = new LinkedHashMap<>();
        boolean codePostgresFallbackUsed = false;
        boolean memoryPostgresFallbackUsed = false;

        boolean codeVectorOk = addCodeVectorSeeds(query, projectKey, limit, codeSeeds, warnings);
        boolean codeVectorDegraded = !codeVectorOk || codeSeeds.values().stream().noneMatch(GraphContextSeed::isCode);
        if (codeVectorDegraded) {
            codePostgresFallbackUsed = true;
            addCodePostgresSeeds(query, projectKey, limit, codeSeeds, warnings);
            addCodeStructuralSeeds(query, projectKey, limit, codeSeeds, warnings, true);
        } else if (hybridFusionEnabled) {
            // Hybrid means semantic discovery plus bounded identifier/path lexical
            // evidence. Keep the lanes separate until composition: cosine and lexical
            // scores are not globally comparable.
            addCodeStructuralSeeds(query, projectKey, limit, codeSeeds, warnings, false);
        }

        if (includeMemory) {
            boolean memoryVectorOk = addMemoryVectorSeeds(query, projectKey, limit, memorySeeds, warnings);
            if (!memoryVectorOk) {
                memoryPostgresFallbackUsed = true;
                addMemoryPostgresSeeds(query, projectKey, limit, memorySeeds, warnings);
            } else if (memorySeeds.values().stream().noneMatch(GraphContextSeed::isMemory)) {
                warnings.add(new GraphWarning("memory_seed_abstained_no_relevant_match",
                        "Memory vector search found no injection-eligible match"));
            }
        }

        List<GraphContextSeed> ordered = new ArrayList<>(selectCodeLane(codeSeeds.values(), limit));
        ordered.addAll(rank(memorySeeds.values()).stream().limit(Math.max(1, limit)).toList());
        return new GraphContextSeedResult(ordered, warnings, codePostgresFallbackUsed,
                memoryPostgresFallbackUsed);
    }

    private static List<GraphContextSeed> selectCodeLane(java.util.Collection<GraphContextSeed> candidates,
            int limit) {
        int boundedLimit = Math.max(1, limit);
        List<GraphContextSeed> lexical = rank(candidates.stream()
                .filter(GraphContextSeed::isPostgresLexical)
                .toList());
        List<GraphContextSeed> semantic = rank(candidates.stream()
                .filter(seed -> !seed.isPostgresLexical())
                .toList());
        if (lexical.isEmpty()) {
            return semantic.stream().limit(boundedLimit).toList();
        }
        if (semantic.isEmpty()) {
            return lexical.stream().limit(boundedLimit).toList();
        }

        // One lexical and one semantic slot make hybrid retrieval real even when raw
        // scores live on different scales. Remaining capacity is filled round-robin by
        // rank, never by cross-lane score comparison.
        List<GraphContextSeed> selected = new ArrayList<>(boundedLimit);
        Set<String> selectedKeys = new LinkedHashSet<>();
        addIfRoom(selected, selectedKeys, lexical.getFirst(), boundedLimit);
        addIfRoom(selected, selectedKeys, semantic.getFirst(), boundedLimit);
        int lexicalIndex = 1;
        int semanticIndex = 1;
        while (selected.size() < boundedLimit
                && (lexicalIndex < lexical.size() || semanticIndex < semantic.size())) {
            if (semanticIndex < semantic.size()) {
                addIfRoom(selected, selectedKeys, semantic.get(semanticIndex++), boundedLimit);
            }
            if (lexicalIndex < lexical.size()) {
                addIfRoom(selected, selectedKeys, lexical.get(lexicalIndex++), boundedLimit);
            }
        }
        return List.copyOf(selected);
    }

    private static void addIfRoom(List<GraphContextSeed> selected, Set<String> selectedKeys,
            GraphContextSeed seed, int limit) {
        String identity = seed.kind() + ":" + seed.key();
        if (selected.size() < limit && selectedKeys.add(identity)) {
            selected.add(seed);
        }
    }

    private static List<GraphContextSeed> rank(java.util.Collection<GraphContextSeed> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(GraphContextSeed::score).reversed()
                        .thenComparingInt(DefaultGraphContextSeedProvider::kindPriority)
                        .thenComparing(GraphContextSeed::key))
                .toList();
    }

    private static int kindPriority(GraphContextSeed seed) {
        return switch (seed.kind()) {
            case CODE_SYMBOL -> 0;
            case CODE_FILE -> 1;
            case CODE_CAPSULE -> 2;
            case MEMORY -> 3;
        };
    }

    private boolean addCodeVectorSeeds(String query, String projectKey, int limit, Map<String, GraphContextSeed> seeds,
            List<GraphWarning> warnings) {
        try {
            int requested = Math.max(limit * 4, limit + 8);
            Set<UUID> staleCapsuleIds = new LinkedHashSet<>();
            for (int pass = 0; pass < MAX_VECTOR_REPAIR_SEARCH_PASSES; pass++) {
                List<UUID> passStale = hydrateCodeVectorSeeds(
                        codeBaselineVectorIndex.search(query, requested, projectKey), projectKey, limit, seeds);
                Set<UUID> newlyDiscoveredStale = new LinkedHashSet<>(passStale);
                newlyDiscoveredStale.removeAll(staleCapsuleIds);
                staleCapsuleIds.addAll(passStale);
                repairStaleCodeVectors(newlyDiscoveredStale, warnings);
                if (seeds.values().stream().filter(GraphContextSeed::isCode).count() >= limit
                        || passStale.isEmpty()) {
                    break;
                }
                // Widen only while stale vectors are consuming the result window. This
                // repairs legacy residue in the first user query without a permanent
                // high-cardinality search cost.
                requested *= 4;
            }
            if (!staleCapsuleIds.isEmpty()) {
                warnings.add(new GraphWarning("code_stale_vector_refs_skipped",
                        "Skipped " + staleCapsuleIds.size()
                                + " code vector refs without a current Postgres capsule"));
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Graph context code vector seed failed for projectKey={} exception={}: {}", projectKey,
                    e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("code_seed_unavailable", "Code vector seed unavailable: "
                    + e.getClass().getSimpleName()));
            return false;
        }
    }

    private List<UUID> hydrateCodeVectorSeeds(List<ScoredCodeCapsuleRef> refs, String projectKey, int limit,
            Map<String, GraphContextSeed> seeds) {
        List<UUID> staleCapsuleIds = new ArrayList<>();
        if (refs == null) {
            return staleCapsuleIds;
        }
        for (ScoredCodeCapsuleRef ref : refs) {
            Optional<GraphContextSeed> resolved = resolveCapsuleSeed(ref.capsuleId(), ref.similarityScore(),
                    LANE_QDRANT_CODE)
                    .filter(seed -> projectKey.equals(seed.projectKey()));
            if (resolved.isEmpty()) {
                staleCapsuleIds.add(ref.capsuleId());
                continue;
            }
            GraphContextSeed seed = resolved.get();
            seeds.putIfAbsent(seed.kind() + ":" + seed.key(), seed);
            if (seeds.values().stream().filter(GraphContextSeed::isCode).count() >= limit) {
                break;
            }
        }
        return staleCapsuleIds;
    }

    private boolean repairStaleCodeVectors(Set<UUID> staleCapsuleIds, List<GraphWarning> warnings) {
        if (staleCapsuleIds.isEmpty()) {
            return true;
        }
        try {
            codeBaselineVectorIndex.deleteAll(List.copyOf(staleCapsuleIds));
            warnings.add(new GraphWarning("code_stale_vector_refs_repaired",
                    "Removed " + staleCapsuleIds.size() + " orphan code vectors from the semantic index"));
            return true;
        } catch (RuntimeException e) {
            log.warn("Graph context stale code vector repair degraded: capsuleCount={} exception={}: {}",
                    staleCapsuleIds.size(), e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("code_stale_vector_cleanup_degraded",
                    "Orphan code vector cleanup failed: " + e.getClass().getSimpleName()));
            return false;
        }
    }

    private void addCodeStructuralSeeds(String query, String projectKey, int limit,
            Map<String, GraphContextSeed> seeds, List<GraphWarning> warnings, boolean fallbackMode) {
        try {
            List<CodeFileRecord> files = codeBaselineRepository.findFilesForProject(projectKey, null,
                    STRUCTURAL_CANDIDATE_CAP);
            Map<UUID, String> filePaths = new LinkedHashMap<>();
            for (CodeFileRecord file : files) {
                if (file != null && projectKey.equals(file.projectKey())) {
                    filePaths.put(file.id(), file.filePath());
                }
            }
            List<StructuralSeed> candidates = new ArrayList<>();
            for (CodeSymbolRecord symbol : codeBaselineRepository.findSymbolsForProject(projectKey, null,
                    STRUCTURAL_CANDIDATE_CAP)) {
                if (symbol == null || !projectKey.equals(symbol.projectKey())) {
                    continue;
                }
                String filePath = filePaths.getOrDefault(symbol.fileId(), "");
                double score = lexicalScore(query, String.join(" ", safe(symbol.name()), safe(symbol.fqn()),
                        safe(symbol.signature()), safe(symbol.symbolKind()), safe(symbol.role()), filePath), true);
                if (score <= 0.0) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("targetKey", symbol.id().toString());
                metadata.put("symbolKind", safe(symbol.symbolKind()));
                metadata.put(GraphContextSeed.METADATA_RETRIEVAL_LANE, GraphContextSeed.LANE_POSTGRES_LEXICAL);
                metadata.put("rawLexicalScore", score);
                putIfPresent(metadata, "filePath", filePath);
                putIfPresent(metadata, "lineStart", symbol.startLine());
                putIfPresent(metadata, "lineEnd", symbol.endLine());
                GraphContextSeed seed = new GraphContextSeed(GraphContextSeedKind.CODE_SYMBOL,
                        symbol.id().toString(), projectKey, score, firstNonBlank(symbol.fqn(), symbol.name()),
                        safe(symbol.signature()), "", null, null, null, symbol.id(), symbol.fileId(), null, metadata);
                candidates.add(new StructuralSeed(seed, score));
            }
            for (CodeFileRecord file : files) {
                if (file == null || !projectKey.equals(file.projectKey())) {
                    continue;
                }
                double score = lexicalScore(query, file.filePath(), false);
                if (score <= 0.0) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("targetKey", file.id().toString());
                metadata.put(GraphContextSeed.METADATA_RETRIEVAL_LANE, GraphContextSeed.LANE_POSTGRES_LEXICAL);
                metadata.put("rawLexicalScore", score);
                putIfPresent(metadata, "filePath", file.filePath());
                GraphContextSeed seed = new GraphContextSeed(GraphContextSeedKind.CODE_FILE, file.id().toString(),
                        projectKey, score, file.filePath(), file.language(), "", null, null, null, null, file.id(),
                        null, metadata);
                candidates.add(new StructuralSeed(seed, score));
            }
            int seedCountBefore = seeds.size();
            candidates.stream()
                    .sorted(Comparator.comparingDouble(StructuralSeed::score).reversed()
                            .thenComparing(candidate -> candidate.seed().kind().name())
                            .thenComparing(candidate -> candidate.seed().key()))
                    .limit(Math.max(1, limit))
                    .forEach(candidate -> seeds.putIfAbsent(candidate.seed().kind() + ":" + candidate.seed().key(),
                            candidate.seed()));
            if (seeds.size() > seedCountBefore) {
                warnings.add(fallbackMode
                        ? new GraphWarning("code_structural_postgres_fallback_used",
                                "Code seeds used bounded Postgres file/symbol lexical fallback")
                        : new GraphWarning("code_structural_postgres_supplement_used",
                                "Code seeds fused bounded Postgres identifier/path lexical evidence with semantic candidates"));
            }
        } catch (RuntimeException e) {
            log.warn("Graph context structural Postgres seed failed for projectKey={} exception={}: {}", projectKey,
                    e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("code_structural_seed_unavailable",
                    "Code structural seed unavailable: " + e.getClass().getSimpleName()));
        }
    }

    private void addCodePostgresSeeds(String query, String projectKey, int limit, Map<String, GraphContextSeed> seeds,
            List<GraphWarning> warnings) {
        try {
            for (CodeSemanticCapsuleRecord capsule : codeBaselineRepository.searchCapsulesText(projectKey, query,
                    limit)) {
                resolveCapsuleSeed(capsule.id(), POSTGRES_FALLBACK_SCORE, LANE_POSTGRES_CAPSULE)
                        .filter(seed -> projectKey.equals(seed.projectKey()))
                        .ifPresent(seed -> seeds.putIfAbsent(seed.kind() + ":" + seed.key(), seed));
            }
            warnings.add(new GraphWarning("code_postgres_fallback_used",
                    "Code seeds used Postgres text fallback"));
        } catch (RuntimeException e) {
            log.warn("Graph context code Postgres seed failed for projectKey={} exception={}: {}", projectKey,
                    e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("code_seed_unavailable", "Code Postgres seed unavailable: "
                    + e.getClass().getSimpleName()));
        }
    }

    private Optional<GraphContextSeed> resolveCapsuleSeed(UUID capsuleId, double score, String retrievalLane) {
        Optional<CodeCapsuleLinkTargetRecord> physical = codeBaselineRepository.findCapsuleLinkTargetById(capsuleId);
        if (physical.isEmpty()) {
            return Optional.empty();
        }
        CodeCapsuleLinkTargetRecord row = physical.get();
        Optional<CodeCapsuleLinkTargetRecord> current = codeBaselineRepository.findCurrentCapsuleLinkTarget(
                row.projectKey(), row.targetKey(), row.capsuleKind());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        CodeCapsuleLinkTargetRecord target = current.get();
        String logicalKey = capsuleLogicalKey(target.projectKey(), target.targetKey(), target.capsuleKind());
        Optional<CodeSemanticCapsuleRecord> capsule = codeBaselineRepository.findCapsuleById(target.id());
        String summary = capsule.map(CodeSemanticCapsuleRecord::summary).orElse(target.targetKey());
        String text = capsule.map(CodeSemanticCapsuleRecord::text).orElse("");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetKey", target.targetKey());
        metadata.put("capsuleKind", target.capsuleKind());
        metadata.put("outputHash", target.outputHash());
        metadata.put(GraphContextSeed.METADATA_RETRIEVAL_LANE, retrievalLane);
        metadata.put("rawRetrievalScore", score);
        capsule.ifPresent(record -> {
            putIfPresent(metadata, "filePath", record.evidence().get("filePath"));
            putIfPresent(metadata, "lineStart", record.evidence().get("lineStart"));
            putIfPresent(metadata, "lineEnd", record.evidence().get("lineEnd"));
        });
        return Optional.of(new GraphContextSeed(GraphContextSeedKind.CODE_CAPSULE, logicalKey, target.projectKey(),
                score, target.targetKey(), summary, text, null, target.id(), logicalKey, target.symbolId(),
                target.fileId(), null, metadata));
    }

    private boolean addMemoryVectorSeeds(String query, String projectKey, int limit, Map<String, GraphContextSeed> seeds,
            List<GraphWarning> warnings) {
        try {
            MemoryEligibilityFilter filter = MemoryEligibilityFilter.forAutomaticInjection(projectKey, null,
                    memoryRanking.minInjectionSimilarity(), ruleMemoryInjectionFilter.enabled());
            int candidateLimit = ruleMemoryInjectionFilter.enabled()
                    ? (int) Math.min(200L, Math.max(1L, (long) limit) * 4L)
                    : Math.max(1, limit);
            MemoryAuthorityCandidates candidates = searchMemoryCandidates(
                    query, Math.max(1, limit), candidateLimit, filter);
            List<ScoredMemoryRef> refs = candidates.refs();
            Set<UUID> excluded = candidates.excludedIds();
            for (ScoredMemoryRef ref : refs) {
                MemoryItem item = ref.item();
                // Enforce the floor here as well: alternate/test index implementations are not required to
                // apply the filter server-side, and a near-zero seed would make graph traversal less relevant.
                if (ref.similarityScore() >= filter.minSimilarity()
                        && filter.accepts(item)
                        && !excluded.contains(item.id())) {
                    GraphContextSeed seed = memorySeed(item, ref.similarityScore(), LANE_QDRANT_MEMORY);
                    seeds.putIfAbsent(seed.kind() + ":" + seed.key(), seed);
                }
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Graph context memory vector seed failed for projectKey={} exception={}: {}", projectKey,
                    e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("memory_seed_unavailable", "Memory vector seed unavailable: "
                    + e.getClass().getSimpleName()));
            return false;
        }
    }

    private MemoryAuthorityCandidates searchMemoryCandidates(String query, int desiredCount, int initialLimit,
            MemoryEligibilityFilter filter) {
        int limit = Math.max(1, initialLimit);
        int maxLimit = Math.max(limit, MAX_MEMORY_AUTHORITY_REFILL_CANDIDATES);
        while (true) {
            List<ScoredMemoryRef> refs = memoryVectorIndex.search(query, limit, filter);
            Set<UUID> excluded = ruleMemoryInjectionFilter.excludedIds(refs.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ScoredMemoryRef::item)
                    .toList());
            long eligible = refs.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(ref -> ref.similarityScore() >= filter.minSimilarity())
                    .filter(ref -> filter.accepts(ref.item()))
                    .filter(ref -> !excluded.contains(ref.item().id()))
                    .count();
            if (excluded.isEmpty() || eligible >= desiredCount || refs.size() < limit || limit >= maxLimit) {
                return new MemoryAuthorityCandidates(List.copyOf(refs), excluded);
            }
            limit = Math.min(maxLimit, Math.max(limit + 1, limit * 2));
        }
    }

    private void addMemoryPostgresSeeds(String query, String projectKey, int limit,
            Map<String, GraphContextSeed> seeds, List<GraphWarning> warnings) {
        try {
            MemoryEligibilityFilter filter = MemoryEligibilityFilter.forAutomaticInjection(projectKey, null,
                    memoryRanking.minInjectionSimilarity(), ruleMemoryInjectionFilter.enabled());
            List<MemoryItem> candidates = memoryRepository.searchForAutomaticInjection(query, limit * 4);
            Set<UUID> excluded = ruleMemoryInjectionFilter.excludedIds(candidates);
            for (MemoryItem item : candidates) {
                if (filter.accepts(item) && !excluded.contains(item.id())) {
                    GraphContextSeed seed = memorySeed(item, POSTGRES_FALLBACK_SCORE, LANE_POSTGRES_MEMORY);
                    seeds.putIfAbsent(seed.kind() + ":" + seed.key(), seed);
                }
                if (seeds.values().stream().filter(GraphContextSeed::isMemory).count() >= limit) {
                    break;
                }
            }
            warnings.add(new GraphWarning("memory_postgres_fallback_used",
                    "Memory seeds used Postgres text fallback"));
        } catch (RuntimeException e) {
            log.warn("Graph context memory Postgres seed failed for projectKey={} exception={}: {}", projectKey,
                    e.getClass().getSimpleName(), e.getMessage());
            warnings.add(new GraphWarning("memory_seed_unavailable", "Memory Postgres seed unavailable: "
                    + e.getClass().getSimpleName()));
        }
    }

    private static GraphContextSeed memorySeed(MemoryItem item, double score, String retrievalLane) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", item.scope() == null ? null : item.scope().value());
        metadata.put("memoryType", item.memoryType() == null ? null : item.memoryType().value());
        metadata.put("status", item.status() == null ? null : item.status().value());
        metadata.put(GraphContextSeed.METADATA_RETRIEVAL_LANE, retrievalLane);
        metadata.put("rawRetrievalScore", score);
        return new GraphContextSeed(GraphContextSeedKind.MEMORY, item.id().toString(), item.projectKey(), score,
                item.summary(), item.summary(), null, item.id(), null, null, null, null, item.sourceRef(), metadata);
    }

    static String capsuleLogicalKey(String projectKey, String targetKey, String capsuleKind) {
        return "%s|%s|%s".formatted(projectKey, targetKey, capsuleKind);
    }

    private static double lexicalScore(String query, String candidate, boolean symbol) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> candidateTerms = terms(candidate);
        long matches = queryTerms.stream().filter(candidateTerms::contains).count();
        if (matches == 0) {
            return 0.0;
        }
        double coverage = matches / (double) queryTerms.size();
        if (coverage < 0.20 && matches < 2) {
            return 0.0;
        }
        String normalizedQuery = normalizedWords(query);
        String normalizedCandidate = normalizedWords(candidate);
        double exactBonus = !normalizedQuery.isBlank() && normalizedCandidate.contains(normalizedQuery) ? 0.18 : 0.0;
        return Math.min(0.99, 0.28 + (coverage * 0.58) + exactBonus + (symbol ? 0.03 : 0.0));
    }

    private static Set<String> terms(String value) {
        String normalized = normalizedWords(value);
        if (normalized.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(normalized.split("\\s+"))
                .filter(term -> term.length() >= 3)
                .filter(term -> !QUERY_STOP_WORDS.contains(term))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String normalizedWords(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String camelSplit = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return java.text.Normalizer.normalize(camelSplit, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? safe(second) : first;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Package-only compatibility for graph-module tests; production DI is strict. */
    private static RuleMemoryInjectionFilter legacyTestFilter() {
        return new RuleMemoryInjectionFilter(new RulesProperties(false, 20, 256, 4096, 200), ignored -> false);
    }

    private record StructuralSeed(GraphContextSeed seed, double score) {
    }

    private record MemoryAuthorityCandidates(List<ScoredMemoryRef> refs, Set<UUID> excludedIds) {
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value);
        }
    }
}
