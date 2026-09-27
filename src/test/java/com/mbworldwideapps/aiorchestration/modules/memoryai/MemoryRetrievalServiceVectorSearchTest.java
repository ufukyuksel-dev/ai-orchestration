package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.junit.jupiter.api.Test;

class MemoryRetrievalServiceVectorSearchTest {

    @Test
    void topResultDiffersByQueryUsingSemanticScore() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem moneyTl = item(MemoryScope.PROJECT, "Turkish lira value object is MoneyTL.", 0.9);
        MemoryItem repositoryRule = item(MemoryScope.PROJECT, "Project repositories extend AcmeBaseRepository.", 0.9);
        MemoryItem pii = item(MemoryScope.PROJECT, "Do not log TCKN, IBAN, payment card.", 0.9);
        repository.put(moneyTl, repositoryRule, pii);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);

        vectorIndex.bindQuery("TRY money", moneyTl, 0.92).bindQuery("TRY money", repositoryRule, 0.10)
                .bindQuery("TRY money", pii, 0.05);
        vectorIndex.bindQuery("PII logging", moneyTl, 0.05).bindQuery("PII logging", repositoryRule, 0.15)
                .bindQuery("PII logging", pii, 0.91);
        vectorIndex.bindQuery("repository base", moneyTl, 0.08).bindQuery("repository base", repositoryRule, 0.88)
                .bindQuery("repository base", pii, 0.05);

        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());

        assertThat(service.retrieve("TRY money", "AI_ORCHESTRATION").injectedMemoryIds().get(0))
                .isEqualTo(moneyTl.id());
        assertThat(service.retrieve("PII logging", "AI_ORCHESTRATION").injectedMemoryIds().get(0))
                .isEqualTo(pii.id());
        assertThat(service.retrieve("repository base", "AI_ORCHESTRATION").injectedMemoryIds().get(0))
                .isEqualTo(repositoryRule.id());
    }

    @Test
    void semanticScoreIsPropagatedToContextItemsNotZero() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem alpha = item(MemoryScope.PROJECT, "Alpha rule.", 0.8);
        MemoryItem beta = item(MemoryScope.PROJECT, "Beta rule.", 0.8);
        repository.put(alpha, beta);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);
        vectorIndex.bindQuery("alpha", alpha, 0.81).bindQuery("alpha", beta, 0.12);

        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());
        MemoryContextResponse response = service.retrieve("alpha", "AI_ORCHESTRATION");

        assertThat(response.items()).isNotEmpty();
        MemoryContextItem top = response.items().get(0);
        assertThat(top.memoryId()).isEqualTo(alpha.id());
        assertThat(top.semanticScore()).isCloseTo(0.81, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(response.items()).allSatisfy(itm -> assertThat(itm.semanticScore()).isGreaterThan(0.0));
    }

    @Test
    void compositeRankScoreOrderingMatchesWeights() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem highSemanticLowConfidence = item(MemoryScope.PROJECT, "High semantic, low confidence.", 0.10);
        MemoryItem lowSemanticHighConfidence = item(MemoryScope.PROJECT, "Low semantic, high confidence.", 0.99);
        repository.put(highSemanticLowConfidence, lowSemanticHighConfidence);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);
        vectorIndex.bindQuery("topic", highSemanticLowConfidence, 0.95)
                .bindQuery("topic", lowSemanticHighConfidence, 0.10);

        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());
        MemoryContextResponse response = service.retrieve("topic", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).startsWith(highSemanticLowConfidence.id());
    }

    @Test
    void stalePenaltyDemotesStaleItemWhenSemanticScoresAreClose() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem fresh = item(MemoryScope.PROJECT, "Fresh rule.", 0.8,
                MemoryStatus.ACTIVE, Instant.parse("2026-05-20T00:00:00Z"));
        MemoryItem stale = item(MemoryScope.PROJECT, "Stale rule.", 0.8,
                MemoryStatus.STALE, Instant.parse("2026-05-20T00:00:00Z"));
        repository.put(fresh, stale);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);
        vectorIndex.bindQuery("any", fresh, 0.80).bindQuery("any", stale, 0.80);

        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());
        MemoryContextResponse response = service.retrieve("any", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).startsWith(fresh.id());
        assertThat(response.items().stream().anyMatch(MemoryContextItem::stale)).isTrue();
    }

    @Test
    void scopeBoostFavorsProjectOverGlobalAtEqualSemantic() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem globalRule = item(MemoryScope.GLOBAL, null, "Global rule.", 0.7,
                MemoryStatus.ACTIVE, Instant.parse("2026-05-20T00:00:00Z"));
        MemoryItem projectRule = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", "Project rule.", 0.7,
                MemoryStatus.ACTIVE, Instant.parse("2026-05-20T00:00:00Z"));
        repository.put(globalRule, projectRule);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);
        vectorIndex.bindQuery("rule", globalRule, 0.70).bindQuery("rule", projectRule, 0.70);

        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());
        MemoryContextResponse response = service.retrieve("rule", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).startsWith(projectRule.id());
    }

    @Test
    void hybridEvaluationRescuesExactSymbolAndKeepsForeignRowsOut() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem denseFirst = item(MemoryScope.PROJECT, "General payment routing", 0.8);
        MemoryItem denseSecond = item(MemoryScope.PROJECT, "Retry policy", 0.8);
        MemoryItem exact = item(MemoryScope.PROJECT, "PaymentService#mapError ERR_PAY_042", 0.8);
        MemoryItem foreign = item(MemoryScope.PROJECT, "FOREIGN", "PaymentService#mapError ERR_PAY_042", 0.99,
                MemoryStatus.ACTIVE, Instant.parse("2026-05-20T00:00:00Z"));
        repository.put(denseFirst, denseSecond, exact, foreign);
        QueryScoredMemoryVectorIndex vectorIndex = new QueryScoredMemoryVectorIndex(repository);
        String query = "PaymentService#mapError ERR_PAY_042";
        vectorIndex.bindQuery(query, denseFirst, 0.92)
                .bindQuery(query, denseSecond, 0.81)
                .bindQuery(query, exact, 0.20)
                .bindQuery(query, foreign, 0.99);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());

        List<MemorySearchHit> dense = service.searchPreviewsForEvaluation(
                query, "AI_ORCHESTRATION", null, 3, MemoryRetrievalArm.DENSE);
        List<MemorySearchHit> hybrid = service.searchPreviewsForEvaluation(
                query, "AI_ORCHESTRATION", null, 3, MemoryRetrievalArm.HYBRID_RRF);

        assertThat(dense.get(0).memoryId()).isEqualTo(denseFirst.id());
        assertThat(hybrid.get(0).memoryId()).isEqualTo(exact.id());
        assertThat(hybrid).extracting(MemorySearchHit::memoryId)
                .doesNotContain(foreign.id())
                .doesNotHaveDuplicates();
    }

    @Test
    void fusedSingleLaneHitIsNotComparedToCosineThresholdAgain() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem singleLaneHit = item(MemoryScope.PROJECT, "Single shadow lane hit", 0.8);
        repository.put(singleLaneHit);
        MemoryVectorIndex fusedIndex = new MemoryVectorIndex() {
            @Override
            public void upsert(MemoryItem item) {
            }

            @Override
            public void delete(MemoryItem item) {
            }

            @Override
            public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
                return List.of(new ScoredMemoryRef(singleLaneHit, 0.5,
                        ScoredMemoryRef.ScoreKind.FUSED_RANK));
            }
        };
        MemoryRankingProperties strictCosineFloor = new MemoryRankingProperties(
                null, null, null, null, null, 0.6, 0.6);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, fusedIndex, properties(2000),
                strictCosineFloor);

        assertThat(service.searchPreviews("shadow-only", "AI_ORCHESTRATION", null, 3))
                .extracting(MemorySearchHit::memoryId)
                .containsExactly(singleLaneHit.id());
        assertThat(service.retrieve("shadow-only", "AI_ORCHESTRATION").injectedMemoryIds())
                .containsExactly(singleLaneHit.id());
    }

    private static MemoryItem item(MemoryScope scope, String text, double confidence) {
        return item(scope, scope == MemoryScope.GLOBAL ? null : "AI_ORCHESTRATION", text, confidence,
                MemoryStatus.ACTIVE, Instant.parse("2026-05-20T00:00:00Z"));
    }

    private static MemoryItem item(MemoryScope scope, String projectKey, String text, double confidence,
            MemoryStatus status, Instant lastVerifiedAt) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), scope, projectKey,
                MemoryType.RULE, "Summary", text, List.of("test"), confidence, status, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, lastVerifiedAt, null);
    }

    private static MemoryItem item(MemoryScope scope, String text, double confidence, MemoryStatus status,
            Instant lastVerifiedAt) {
        return item(scope, scope == MemoryScope.GLOBAL ? null : "AI_ORCHESTRATION", text, confidence, status,
                lastVerifiedAt);
    }

    private static AiOrchestrationProperties properties(int hardCap) {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-retrieval-vector.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", hardCap, 0.3,
                        "config/memory/global", ".ai_orch/memory", "AI_ORCHESTRATION", false,
                        false, hardCap, hardCap, hardCap, 5, 365),
                365);
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new LinkedHashMap<>();

        void put(MemoryItem... values) {
            for (MemoryItem value : values) {
                items.put(value.id(), value);
            }
        }

        @Override
        public MemoryItem save(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public Optional<MemoryItem> findById(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<MemoryItem> findBySourceRef(String sourceRef) {
            return Optional.empty();
        }

        @Override
        public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
            return items.values().stream()
                    .filter(item -> scope == null || item.scope() == scope)
                    .filter(item -> status == null || item.status() == status)
                    .filter(item -> projectKey == null || projectKey.equals(item.projectKey()))
                    .toList();
        }

        @Override
        public List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
            return items.values().stream().filter(item -> statuses.contains(item.status())).toList();
        }

        @Override
        public List<MemoryItem> searchEligible(String query, int limit, MemoryEligibilityFilter filter) {
            String normalized = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
            return items.values().stream()
                    .filter(filter::accepts)
                    .filter(item -> (item.summary() + " " + item.text() + " " + item.tags())
                            .toLowerCase(java.util.Locale.ROOT).contains(normalized))
                    .limit(limit)
                    .toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            return false;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return List.of();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return List.of();
        }
    }

    private static final class QueryScoredMemoryVectorIndex implements MemoryVectorIndex {
        private final MemoryRepository repository;
        private final Map<String, Map<UUID, Double>> scoresByQuery = new HashMap<>();

        QueryScoredMemoryVectorIndex(MemoryRepository repository) {
            this.repository = repository;
        }

        QueryScoredMemoryVectorIndex bindQuery(String query, MemoryItem item, double score) {
            scoresByQuery.computeIfAbsent(query, k -> new HashMap<>()).put(item.id(), score);
            return this;
        }

        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            Map<UUID, Double> scoresForQuery = scoresByQuery.getOrDefault(query, Map.of());
            List<ScoredMemoryRef> matched = new ArrayList<>();
            for (MemoryItem item : repository.listByStatuses(filter.statuses())) {
                if (!filter.accepts(item)) {
                    continue;
                }
                matched.add(new ScoredMemoryRef(item, scoresForQuery.getOrDefault(item.id(), 0.0)));
            }
            matched.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
            return matched.size() > topK ? matched.subList(0, topK) : matched;
        }
    }
}
