package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemoryRetrievalService {

    private static final int MAX_AUTHORITY_REFILL_CANDIDATES = 200;

    private final MemoryRepository repository;
    private final MemoryVectorIndex memoryVectorIndex;
    private final AiOrchestrationProperties properties;
    private final MemoryRankingProperties ranking;
    private final RuleMemoryInjectionFilter ruleMemoryInjectionFilter;

    MemoryRetrievalService(MemoryRepository repository, MemoryVectorIndex memoryVectorIndex,
            AiOrchestrationProperties properties) {
        this(repository, memoryVectorIndex, properties, MemoryRankingProperties.defaults(), legacyTestFilter());
    }

    MemoryRetrievalService(MemoryRepository repository, MemoryVectorIndex memoryVectorIndex,
            AiOrchestrationProperties properties, MemoryRankingProperties ranking) {
        this(repository, memoryVectorIndex, properties, ranking, legacyTestFilter());
    }

    @Autowired
    public MemoryRetrievalService(MemoryRepository repository, MemoryVectorIndex memoryVectorIndex,
            AiOrchestrationProperties properties, MemoryRankingProperties ranking,
            RuleMemoryInjectionFilter ruleMemoryInjectionFilter) {
        this.repository = repository;
        this.memoryVectorIndex = memoryVectorIndex;
        this.properties = properties;
        this.ranking = ranking == null ? MemoryRankingProperties.defaults() : ranking;
        this.ruleMemoryInjectionFilter = java.util.Objects.requireNonNull(
                ruleMemoryInjectionFilter, "ruleMemoryInjectionFilter");
    }

    public MemoryContextResponse retrieve(String query, String projectKey) {
        return retrieve(query, projectKey, null);
    }

    public MemoryContextResponse retrieve(String query, String projectKey, String userId) {
        return retrieve(query, projectKey, userId, ranking.minInjectionSimilarity());
    }

    /** Automatic prompt injection path. Explicit search/evaluation APIs remain available when injection is off. */
    public MemoryContextResponse retrieveForInjection(String query, String projectKey, String userId) {
        // Preserve lightweight test/custom subclasses that override the legacy retrieval API without wiring
        // Spring configuration. Production instances always have non-null properties.
        if (properties == null) {
            return retrieve(query, projectKey, userId);
        }
        if (!properties.memory().injectionEnabled() || query == null || query.isBlank()) {
            return MemoryContextResponse.empty();
        }
        return retrieve(query, projectKey, userId, ranking.minInjectionSimilarity());
    }

    public MemoryContextResponse retrieveForInjection(String query, String projectKey) {
        if (properties == null) {
            return retrieve(query, projectKey);
        }
        return retrieveForInjection(query, projectKey, null);
    }

    public boolean injectionEnabled() {
        return properties == null || properties.memory().injectionEnabled();
    }

    private MemoryContextResponse retrieve(String query, String projectKey, String userId, double minSimilarity) {
        if (query == null || query.isBlank()) {
            return MemoryContextResponse.empty();
        }
        Instant started = Instant.now();
        List<RankedItem> ranked = rankedItems(
                query, projectKey, userId, injectionOversample(), minSimilarity, true, MemoryRetrievalArm.DENSE);

        // Injection-only relevance gate: drop weakly-related memories on raw semantic similarity, before
        // budget packing, so confidence/scope alone cannot carry an irrelevant memory into the prompt.
        // Explicit search has its own independently configurable relevance floor.
        List<RankedItem> relevant = new ArrayList<>();
        for (RankedItem r : ranked) {
            if (r.passedLaneRelevanceGate() || r.similarityScore() >= minSimilarity) {
                relevant.add(r);
            }
        }
        int relevanceFiltered = ranked.size() - relevant.size();

        List<MemoryContextItem> packed = pack(relevant);

        Map<String, Integer> scopes = new LinkedHashMap<>();
        scopes.put("user", countScope(packed, MemoryScope.USER));
        scopes.put("global", countScope(packed, MemoryScope.GLOBAL));
        scopes.put("project", countScope(packed, MemoryScope.PROJECT));
        scopes.put("episodic", countScope(packed, MemoryScope.EPISODIC));

        return new MemoryContextResponse(
                List.copyOf(packed),
                packed.stream().map(MemoryContextItem::memoryId).toList(),
                packed.size(),
                tokens(packed),
                scopes,
                (int) packed.stream().filter(MemoryContextItem::stale).count(),
                0,
                relevanceFiltered,
                Duration.between(started, Instant.now()).toMillis());
    }

    public List<MemorySearchHit> searchPreviews(String query, String projectKey, String userId, int topK) {
        return searchPreviews(query, projectKey, userId, topK, null);
    }

    public List<MemorySearchHit> searchPreviews(String query, String projectKey, String userId, int topK,
            Integer excerptMaxChars) {
        if (excerptMaxChars != null && (excerptMaxChars < 128 || excerptMaxChars > 1024)) {
            throw new IllegalArgumentException("excerptMaxChars must be between 128 and 1024");
        }
        int effectiveTopK = Math.max(0, topK);
        if (effectiveTopK == 0) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int oversample = Math.max(effectiveTopK * ranking.oversampleFactor(), effectiveTopK);
        return rankedItems(query, projectKey, userId, oversample, ranking.minSearchSimilarity(), false,
                MemoryRetrievalArm.DENSE).stream()
                .filter(item -> item.passedLaneRelevanceGate()
                        || item.similarityScore() >= ranking.minSearchSimilarity())
                .limit(effectiveTopK)
                .map(item -> toSearchHit(item, excerptMaxChars))
                .toList();
    }

    /** Evaluation-only arm selection; ordinary search and injection remain dense-active. */
    List<MemorySearchHit> searchPreviewsForEvaluation(String query, String projectKey, String userId, int topK,
            MemoryRetrievalArm arm) {
        int effectiveTopK = Math.max(0, topK);
        if (effectiveTopK == 0 || query == null || query.isBlank()) {
            return List.of();
        }
        int oversample = Math.max(effectiveTopK * ranking.oversampleFactor(), effectiveTopK);
        return rankedItems(query, projectKey, userId, oversample, 0.0, false,
                arm == null ? MemoryRetrievalArm.DENSE : arm).stream()
                .limit(effectiveTopK)
                .map(item -> toSearchHit(item, null))
                .toList();
    }

    private List<RankedItem> rankedItems(String query, String projectKey, String userId, int oversample,
            double minSimilarity, boolean excludePromotedRuleOrigins, MemoryRetrievalArm arm) {
        String normalizedProjectKey = projectKey == null || projectKey.isBlank()
                ? properties.memory().projectKey()
                : projectKey.trim();
        String userProjectKey = userProjectKey(userId);
        MemoryEligibilityFilter filter = excludePromotedRuleOrigins
                ? MemoryEligibilityFilter.forAutomaticInjection(normalizedProjectKey, userProjectKey,
                        minSimilarity, ruleMemoryInjectionFilter.enabled())
                : MemoryEligibilityFilter.forRetrieve(normalizedProjectKey, userProjectKey, minSimilarity);
        AuthorityCandidates authorityCandidates = excludePromotedRuleOrigins
                ? searchInjectionCandidates(query, Math.max(1, oversample), filter)
                : new AuthorityCandidates(searchCandidates(query, Math.max(1, oversample), filter, arm), Set.of());
        List<ScoredMemoryRef> scored = authorityCandidates.refs();
        Set<UUID> promotedRuleOrigins = authorityCandidates.excludedIds();

        List<RankedItem> ranked = new ArrayList<>();
        for (ScoredMemoryRef ref : scored) {
            MemoryItem item = ref.item();
            if (!filter.accepts(item) || promotedRuleOrigins.contains(item.id())) {
                continue;
            }
            boolean stale = item.status() == MemoryStatus.STALE || staleByVerificationDate(item);
            double rankScore = ranking.scoreFor(ref.similarityScore(), item.confidence(), stale, item.scope());
            ranked.add(new RankedItem(item, ref.similarityScore(), stale, rankScore,
                    ref.passedLaneRelevanceGate()));
        }

        ranked.sort(Comparator.<RankedItem>comparingDouble(r -> r.rankScore).reversed()
                .thenComparing(r -> r.item.lastVerifiedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked;
    }

    private List<ScoredMemoryRef> searchCandidates(String query, int limit, MemoryEligibilityFilter filter,
            MemoryRetrievalArm arm) {
        if (arm == MemoryRetrievalArm.DENSE) {
            return memoryVectorIndex.search(query, limit, filter);
        }
        List<ScoredMemoryRef> lexical = lexicalLane(query, limit, filter);
        if (arm == MemoryRetrievalArm.LEXICAL) {
            return MemoryRankFusion.fuse(List.of(lexical), limit);
        }
        List<ScoredMemoryRef> dense = memoryVectorIndex.search(query, limit, filter);
        return MemoryRankFusion.fuse(List.of(dense, lexical), limit);
    }

    private List<ScoredMemoryRef> lexicalLane(String query, int limit, MemoryEligibilityFilter filter) {
        List<MemoryItem> items = repository.searchEligible(query, limit, filter);
        List<ScoredMemoryRef> refs = new ArrayList<>(items.size());
        int rank = 0;
        for (MemoryItem item : items) {
            if (filter.accepts(item)) {
                refs.add(new ScoredMemoryRef(item, 1.0 / ++rank));
            }
        }
        return List.copyOf(refs);
    }

    private AuthorityCandidates searchInjectionCandidates(String query, int desiredCount,
            MemoryEligibilityFilter filter) {
        int limit = Math.max(1, desiredCount);
        int maxLimit = Math.max(limit, MAX_AUTHORITY_REFILL_CANDIDATES);
        while (true) {
            List<ScoredMemoryRef> refs = memoryVectorIndex.search(query, limit, filter);
            Set<UUID> excluded = ruleMemoryInjectionFilter.excludedIds(refs.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ScoredMemoryRef::item)
                    .toList());
            long eligible = refs.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(ref -> filter.accepts(ref.item()))
                    .filter(ref -> !excluded.contains(ref.item().id()))
                    .count();
            if (excluded.isEmpty() || eligible >= desiredCount || refs.size() < limit || limit >= maxLimit) {
                return new AuthorityCandidates(List.copyOf(refs), excluded);
            }
            limit = Math.min(maxLimit, Math.max(limit + 1, limit * 2));
        }
    }

    private int injectionOversample() {
        return Math.max(properties.memory().episodicTopK() * ranking.oversampleFactor(),
                properties.memory().episodicTopK());
    }

    private List<MemoryContextItem> pack(List<RankedItem> ranked) {
        int globalCap = properties.memory().contextTokenCap();
        Map<MemoryScope, Integer> scopeCaps = new EnumMap<>(MemoryScope.class);
        scopeCaps.put(MemoryScope.USER, properties.memory().projectTokenCap());
        scopeCaps.put(MemoryScope.PROJECT, properties.memory().projectTokenCap());
        scopeCaps.put(MemoryScope.GLOBAL, properties.memory().globalTokenCap());
        scopeCaps.put(MemoryScope.EPISODIC, properties.memory().episodicTokenCap());

        Map<MemoryScope, Integer> scopeUsed = new EnumMap<>(MemoryScope.class);
        for (MemoryScope scope : MemoryScope.values()) {
            scopeUsed.put(scope, 0);
        }
        int globalUsed = 0;

        List<MemoryContextItem> packed = new ArrayList<>();
        for (RankedItem r : ranked) {
            String promptText = MemoryTextPreview.promptText(r.item.summary(), r.item.text());
            int tokenEstimate = MemoryTextPreview.estimateTokens(promptText);
            int scopeCap = scopeCaps.getOrDefault(r.item.scope(), 0);
            int scopeBudget = Math.min(scopeCap - scopeUsed.get(r.item.scope()), globalCap - globalUsed);
            if (scopeBudget < tokenEstimate) {
                continue;
            }
            packed.add(new MemoryContextItem(
                    r.item.id(),
                    r.item.vectorId(),
                    citationId(r.item),
                    r.item.scope(),
                    r.item.projectKey(),
                    r.item.memoryType(),
                    r.item.summary(),
                    r.item.text(),
                    promptText,
                    r.item.confidence(),
                    r.stale,
                    tokenEstimate,
                    r.similarityScore,
                    r.item.lastVerifiedAt(),
                    r.item.sourceRef()));
            scopeUsed.merge(r.item.scope(), tokenEstimate, Integer::sum);
            globalUsed += tokenEstimate;
        }
        return packed;
    }

    private record AuthorityCandidates(List<ScoredMemoryRef> refs, Set<UUID> excludedIds) {
    }

    private boolean staleByVerificationDate(MemoryItem item) {
        if (item.lastVerifiedAt() == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(properties.memory().staleAfterDays(), ChronoUnit.DAYS);
        return item.lastVerifiedAt().isBefore(cutoff);
    }

    public static String userProjectKey(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return "user:" + userId.trim();
    }

    private static int countScope(List<MemoryContextItem> items, MemoryScope scope) {
        return (int) items.stream().filter(item -> item.scope() == scope).count();
    }

    private static int tokens(List<MemoryContextItem> items) {
        return items.stream().mapToInt(MemoryContextItem::tokenEstimate).sum();
    }

    private MemorySearchHit toSearchHit(RankedItem ranked, Integer excerptMaxChars) {
        MemoryItem item = ranked.item();
        String excerpt = excerptMaxChars == null ? MemoryTextPreview.excerpt(item.text())
                : MemoryTextPreview.truncate(item.text(), excerptMaxChars);
        List<MemoryCodeLocator> locators = List.of();
        List<String> warnings = List.of();
        int omittedLocators = 0;
        try {
            // Validate scope as well as the envelope; never expose invalid global code hints.
            List<MemoryCodeLocator> declared = MemoryCodeLocatorMetadata.read(item.metadata()).items();
            if (!declared.isEmpty() && (item.scope() != MemoryScope.PROJECT
                    || item.projectKey() == null || item.projectKey().isBlank())) {
                throw new IllegalArgumentException("Project code locators require project scope");
            }
            locators = declared.stream().limit(4).toList();
            omittedLocators = declared.size() - locators.size();
        } catch (IllegalArgumentException invalidMetadata) {
            warnings = List.of("invalid_code_locators_omitted");
        }
        String locatorText = locators.stream().map(locator -> " " + locator.kind().value() + " " + locator.ref()
                + (locator.capsuleKind() == null ? "" : " " + locator.capsuleKind())
                + (locator.relationship() == null ? "" : " " + locator.relationship().value()))
                .collect(java.util.stream.Collectors.joining());
        int tokenEstimate = MemoryTextPreview.estimateTokens(item.summary() + " " + excerpt + locatorText);
        return new MemorySearchHit(
                item.id(),
                item.vectorId(),
                citationId(item),
                item.scope().value(),
                item.projectKey(),
                item.memoryType().value(),
                item.summary(),
                excerpt,
                item.tags(),
                item.confidence(),
                ranked.stale(),
                tokenEstimate,
                ranked.similarityScore(),
                item.lastVerifiedAt(),
                item.status().value(), locators, omittedLocators, warnings);
    }

    private static String citationId(MemoryItem item) {
        return item.scope().value() + ":" + item.id();
    }

    private record RankedItem(MemoryItem item, double similarityScore, boolean stale, double rankScore,
            boolean passedLaneRelevanceGate) {
    }

    /** Package-only compatibility for existing memory-module tests; production DI is strict. */
    private static RuleMemoryInjectionFilter legacyTestFilter() {
        return new RuleMemoryInjectionFilter(new RulesProperties(false, 20, 256, 4096, 200), ignored -> false);
    }
}
