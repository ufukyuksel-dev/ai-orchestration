package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.DefaultPolicyEngine;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class MemoryReviewServiceTest {

    private final FakeMemoryRepository repository = new FakeMemoryRepository();
    private final FakeMemoryVectorIndex vectorIndex = new FakeMemoryVectorIndex();
    private final List<MemoryItemChangedEvent> publishedEvents = new ArrayList<>();
    private final MemoryReviewService reviewService = MemoryReviewServiceTestFixture.create(
            repository, vectorIndex, event -> publishedEvents.add((MemoryItemChangedEvent) event),
            PolicyEngine.allowAll(), new PiiScrubber());

    @Test
    void approvePendingReviewClosesQueueAndWritesContentLeanStatusEvent() {
        MemoryItem item = item(MemoryScope.EPISODIC, null, MemoryStatus.PENDING_REVIEW, "Use acme-logger.");
        repository.save(item);
        repository.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), item.id(), ReviewReason.LOW_CONFIDENCE,
                ReviewStatus.OPEN, Map.of(), Instant.now(), null));

        MemoryItem approved = reviewService.approve(item.id(), "reviewer",
                "Approved because this is a stable logging rule.");

        assertThat(approved.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(repository.reviewQueueForMemory(item.id()).getFirst().status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(repository.events).hasSize(1);
        MemoryEvent event = repository.events.getFirst();
        assertThat(event.eventType()).isEqualTo(MemoryEventType.STATUS_CHANGED);
        assertThat(event.metadata()).containsEntry("oldStatus", "pending_review");
        assertThat(event.metadata()).containsEntry("newStatus", "active");
        assertThat(event.metadata()).containsEntry("reasonProvided", true);
        assertThat(event.metadata()).containsKey("reasonHashPrefix");
        assertThat(event.metadata()).doesNotContainKeys("text", "summary", "reason");
    }

    @Test
    void ruleAuthorityBlocksReviewApprovalAndLeavesCandidatePending() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.PENDING_REVIEW,
                "Controllers must stay thin.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true), ignored -> false);

        assertThatThrownBy(() -> guarded.approve(item.id(), "reviewer", "approve"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThat(repository.findById(item.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(repository.events).isEmpty();
    }

    @Test
    void rejectMemoryIsExcludedFromRetrieval() {
        MemoryItem item = item(MemoryScope.GLOBAL, null, MemoryStatus.PENDING_REVIEW, "Use acme-logger.");
        repository.save(item);
        repository.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), item.id(), ReviewReason.LOW_CONFIDENCE,
                ReviewStatus.OPEN, Map.of(), Instant.now(), null));

        reviewService.reject(item.id(), "reviewer", "Too specific for global memory.");
        MemoryRetrievalService retrievalService = new MemoryRetrievalService(repository, vectorIndex, properties());

        MemoryContextResponse response = retrievalService.retrieve("acme logger", null);

        assertThat(repository.findById(item.id()).orElseThrow().status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(repository.reviewQueueForMemory(item.id()).getFirst().status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(response.injectedMemoryIds()).doesNotContain(item.id());
        assertThat(repository.events.getFirst().eventType()).isEqualTo(MemoryEventType.REJECTED);
    }

    @Test
    void editActiveEpisodicPublishesPreviousVectorAndWritesContentLeanUpdatedEvent() {
        MemoryItem item = item(MemoryScope.EPISODIC, null, MemoryStatus.ACTIVE, "Use acme-logger.");
        repository.save(item);

        MemoryItem edited = reviewService.edit(item.id(), new EditMemoryRequest(
                "Logger rule",
                "Use acme-logger for audit info and error logging.",
                List.of("logging", "audit"),
                0.91,
                "reviewer",
                "Expanded approved wording."));

        assertThat(edited.vectorId()).isNotEqualTo(item.vectorId());
        assertThat(vectorIndex.deletedIds).isEmpty();
        assertThat(publishedEvents).singleElement()
                .extracting(event -> event.previousItem().vectorId()).isEqualTo(item.vectorId());
        assertThat(repository.events.getFirst().eventType()).isEqualTo(MemoryEventType.UPDATED);
        assertThat(repository.events.getFirst().metadata()).containsEntry("textHashChanged", true);
        assertThat(repository.events.getFirst().metadata()).doesNotContainKeys("text", "summary", "reason");
    }

    @Test
    void editActiveProjectMemoryPublishesPreviousVector() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Repositories extend AcmeBaseRepository.");
        repository.save(item);

        MemoryItem edited = reviewService.edit(item.id(), new EditMemoryRequest(
                "Repository rule",
                "Repositories extend AcmeBaseRepository and use typed query methods.",
                List.of("repository", "convention"),
                0.95,
                "reviewer",
                "Refined wording for project standard."));

        assertThat(edited.vectorId()).isNotEqualTo(item.vectorId());
        assertThat(vectorIndex.deletedIds).isEmpty();
        assertThat(publishedEvents).singleElement()
                .extracting(event -> event.previousItem().vectorId()).isEqualTo(item.vectorId());
    }

    @Test
    void generalDiscoveryEditMarksItemStaleAndPublishesExternalEditOrigin() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                MemoryType.DISCOVERY, "The old route is here.");
        repository.save(item);
        List<MemoryItemChangedEvent> events = new ArrayList<>();
        MemoryReviewService service = MemoryReviewServiceTestFixture.create(repository, vectorIndex,
                event -> events.add((MemoryItemChangedEvent) event), PolicyEngine.allowAll(), new PiiScrubber());

        MemoryItem edited = service.edit(item.id(), new EditMemoryRequest(
                "Edited discovery", "A human edited this without fresh learning evidence.",
                List.of("discovery"), 0.9, "reviewer", "Legacy edit."));

        assertThat(edited.status()).isEqualTo(MemoryStatus.STALE);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.item().id()).isEqualTo(item.id());
            assertThat(event.origin()).isEqualTo(MemoryItemChangedEvent.Origin.EXTERNAL_EDIT);
            assertThat(event.previousItem()).isEqualTo(item);
        });
    }

    @Test
    void editActiveGlobalMemoryPublishesPreviousVector() {
        MemoryItem item = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Service names follow acme-svc-domain-service.");
        repository.save(item);

        MemoryItem edited = reviewService.edit(item.id(), new EditMemoryRequest(
                "Naming rule",
                "Internal services use the prefix acme-svc-domain-service across the org.",
                List.of("naming", "service"),
                0.95,
                "reviewer",
                "Clarified scope of the naming rule."));

        assertThat(edited.vectorId()).isNotEqualTo(item.vectorId());
        assertThat(vectorIndex.deletedIds).isEmpty();
        assertThat(publishedEvents).singleElement()
                .extracting(event -> event.previousItem().vectorId()).isEqualTo(item.vectorId());
    }

    @Test
    void editRejectsActiveRuleThatAlreadyBacksAnImmutableRuleDefinition() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Controllers must not contain business logic.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true), item.id()::equals);

        assertThatThrownBy(() -> guarded.edit(item.id(), new EditMemoryRequest(
                "Controller rule", "Controllers may contain small business logic.", List.of("controller"), 1.0,
                "reviewer", "Changed after promotion.")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable")
                .hasMessageContaining("rules.promote");

        assertThat(repository.findById(item.id()).orElseThrow()).isEqualTo(item);
        assertThat(repository.events).isEmpty();
    }

    @Test
    void linkedRuleHistoryCannotBeEditedArchivedOrHardDeletedRegardlessOfStatus() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ARCHIVED,
                "Historical approved controller rule.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true), item.id()::equals);

        assertThatThrownBy(() -> guarded.edit(item.id(), new EditMemoryRequest(
                "Controller rule", "Mutated history.", List.of("controller"), 1.0,
                "reviewer", "must fail")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable rule history");
        assertThatThrownBy(() -> guarded.archive(item.id(), "reviewer", "must fail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule lifecycle");
        assertThatThrownBy(() -> guarded.hardDelete(item.id(), "reviewer", "must fail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule lifecycle");

        assertThat(repository.findById(item.id())).contains(item);
        assertThat(repository.events).isEmpty();
    }

    @Test
    void linkedPendingRuleCannotBeApprovedOrRejectedThroughGenericMemoryReview() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.PENDING_REVIEW,
                "Historical approved controller rule.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(false), item.id()::equals);

        assertThatThrownBy(() -> guarded.approve(item.id(), "reviewer", "must fail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule lifecycle");
        assertThatThrownBy(() -> guarded.reject(item.id(), "reviewer", "must fail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule lifecycle");

        assertThat(repository.findById(item.id())).contains(item);
        assertThat(repository.events).isEmpty();
    }

    @Test
    void editAllowsActiveLegacyRuleWithoutLinkedDefinition() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Legacy controller guidance.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true), ignored -> false);

        MemoryItem edited = guarded.edit(item.id(), new EditMemoryRequest(
                "Controller rule", "Legacy controller guidance clarified.", List.of("controller"), 1.0,
                "reviewer", "Legacy rule is not linked."));

        assertThat(edited.text()).isEqualTo("Legacy controller guidance clarified.");
    }

    @Test
    void editRejectsSensitiveContentWhenPolicyIsEnabled() {
        MemoryReviewService strictReviewService = reviewServiceWithPolicy();
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Use service naming convention.");
        repository.save(item);

        assertThatThrownBy(() -> strictReviewService.edit(item.id(), new EditMemoryRequest(
                "Sensitive rule",
                "Do not log internal-prod-svc in memory.",
                List.of("security"),
                0.9,
                "reviewer",
                "Attempted unsafe wording.")))
                .isInstanceOf(MemoryPolicyViolationException.class)
                .hasMessageContaining("sensitive-term");
    }

    @Test
    void editRejectsDisallowedSourceScopeMoveWhenPolicyIsEnabled() {
        MemoryReviewService strictReviewService = reviewServiceWithPolicy();
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Project correction signal.");
        repository.save(item);

        assertThatThrownBy(() -> strictReviewService.edit(item.id(), new EditMemoryRequest(
                "Correction signal",
                "Project correction signal.",
                List.of("correction"),
                0.9,
                "global",
                null,
                null,
                "reviewer",
                "Correction signals cannot become global memory.")))
                .isInstanceOf(MemoryPolicyViolationException.class)
                .hasMessageContaining("write-source-scope-denied");
    }

    @Test
    void promoteEpisodicToProjectPublishesPreviousVectorAndKeepsHumanApprovalExplicit() {
        MemoryItem item = item(MemoryScope.EPISODIC, null, MemoryStatus.ACTIVE, "Use AcmeBaseRepository.");
        repository.save(item);
        repository.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), item.id(), ReviewReason.PROMOTION_CANDIDATE,
                ReviewStatus.OPEN, Map.of(), Instant.now(), null));

        MemoryItem promoted = reviewService.promote(item.id(), new PromoteMemoryRequest(
                MemoryScope.PROJECT,
                "AI_ORCHESTRATION",
                "reviewer",
                "Project-wide repository convention."));

        assertThat(promoted.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(promoted.projectKey()).isEqualTo("AI_ORCHESTRATION");
        assertThat(promoted.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(promoted.vectorId()).isNotEqualTo(item.vectorId());
        assertThat(vectorIndex.deletedIds).isEmpty();
        assertThat(publishedEvents).singleElement()
                .extracting(event -> event.previousItem().vectorId()).isEqualTo(item.vectorId());
        assertThat(repository.reviewQueueForMemory(item.id()).getFirst().status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(repository.events.getFirst().eventType()).isEqualTo(MemoryEventType.PROMOTED);
        assertThat(repository.events.getFirst().metadata()).containsEntry("oldScope", "episodic");
        assertThat(repository.events.getFirst().metadata()).containsEntry("newScope", "project");
    }

    @Test
    void ruleAuthorityBlocksEpisodicRulePromotion() {
        MemoryItem item = item(MemoryScope.EPISODIC, null, MemoryStatus.ACTIVE,
                "Controllers must stay thin.");
        repository.save(item);
        MemoryReviewService guarded = MemoryReviewServiceTestFixture.create(
                repository, vectorIndex, event -> {
                }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true), ignored -> false);

        assertThatThrownBy(() -> guarded.promote(item.id(), new PromoteMemoryRequest(
                MemoryScope.PROJECT, "AI_ORCHESTRATION", "reviewer", "promote")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThat(repository.findById(item.id()).orElseThrow()).isEqualTo(item);
    }

    @Test
    void hardDeleteActiveMemoryDeletesVectorAndRowWithoutEvent() {
        MemoryItem item = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Old service naming rule.");
        repository.save(item);

        reviewService.hardDelete(item.id(), "mcp:self-pipeline", "duplicate");

        assertThat(repository.findById(item.id())).isEmpty();
        assertThat(vectorIndex.deletedIds).containsExactly(item.vectorId());
        assertThat(repository.eventsForMemory(item.id())).isEmpty();
    }

    @Test
    void hardDeletePendingMemoryDeletesRowWithoutVectorDelete() {
        MemoryItem item = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.PENDING_REVIEW,
                "Candidate rule.");
        repository.save(item);
        repository.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), item.id(), ReviewReason.LOW_CONFIDENCE,
                ReviewStatus.OPEN, Map.of(), Instant.now(), null));

        reviewService.hardDelete(item.id(), "mcp:self-pipeline", "not needed");

        assertThat(repository.findById(item.id())).isEmpty();
        assertThat(vectorIndex.deletedIds).isEmpty();
        assertThat(repository.reviewQueueForMemory(item.id())).isEmpty();
    }

    private static MemoryItem item(MemoryScope scope, String projectKey, MemoryStatus status, String text) {
        return item(scope, projectKey, status, MemoryType.RULE, text);
    }

    private static MemoryItem item(MemoryScope scope, String projectKey, MemoryStatus status,
            MemoryType memoryType, String text) {
        UUID id = UUID.randomUUID();
        UUID vectorId = MemoryService.deterministicVectorId(scope, projectKey, "Rule", text);
        Instant now = Instant.parse("2026-05-20T08:00:00Z");
        return new MemoryItem(
                id,
                vectorId,
                scope,
                projectKey,
                memoryType,
                "Rule",
                text,
                List.of("logging"),
                0.7,
                status,
                MemorySourceType.CORRECTION_SIGNAL,
                "test:" + id,
                "tester",
                Map.of(),
                now,
                now,
                null,
                now,
                null);
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-review.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", 2000, 0.42),
                365);
    }

    private MemoryReviewService reviewServiceWithPolicy() {
        return MemoryReviewServiceTestFixture.create(repository, vectorIndex, event -> {
        }, new DefaultPolicyEngine(new PolicyProperties(true, true, "admin-token", List.of())),
                new PiiScrubber());
    }

    private static final class FakeMemoryVectorIndex implements MemoryVectorIndex {
        private final List<UUID> deletedIds = new ArrayList<>();

        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
            deletedIds.add(item.vectorId());
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            return List.of();
        }
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new HashMap<>();
        private final List<MemoryEvent> events = new ArrayList<>();
        private final List<ReviewQueueItem> reviewQueue = new ArrayList<>();

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
            return items.values().stream()
                    .filter(item -> sourceRef.equals(item.sourceRef()))
                    .findFirst();
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
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            MemoryItem item = items.get(id);
            if (item == null) {
                return false;
            }
            items.put(id, new MemoryItem(
                    item.id(),
                    item.vectorId(),
                    item.scope(),
                    item.projectKey(),
                    item.memoryType(),
                    item.summary(),
                    item.text(),
                    item.tags(),
                    item.confidence(),
                    status,
                    item.sourceType(),
                    item.sourceRef(),
                    item.owner(),
                    item.metadata(),
                    item.createdAt(),
                    Instant.now(),
                    item.lastUsedAt(),
                    item.lastVerifiedAt(),
                    item.expiresAt()));
            return true;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
            events.add(event);
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return events.stream()
                    .filter(event -> memoryId.equals(event.memoryId()))
                    .toList();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
            reviewQueue.add(item);
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return reviewQueue.stream()
                    .filter(item -> memoryId.equals(item.candidateMemoryId()))
                    .toList();
        }

        @Override
        public int updateReviewQueueStatus(UUID memoryId, ReviewStatus expectedStatus, ReviewStatus status,
                Instant reviewedAt, Map<String, Object> metadata) {
            int count = 0;
            for (int i = 0; i < reviewQueue.size(); i++) {
                ReviewQueueItem item = reviewQueue.get(i);
                if (memoryId.equals(item.candidateMemoryId()) && item.status() == expectedStatus) {
                    Map<String, Object> mergedMetadata = new HashMap<>(item.metadata());
                    mergedMetadata.putAll(metadata);
                    reviewQueue.set(i, new ReviewQueueItem(item.id(), item.candidateMemoryId(), item.reason(), status,
                            mergedMetadata, item.createdAt(), reviewedAt));
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<ReviewQueueItem> listReviewQueue(ReviewStatus status) {
            return reviewQueue.stream()
                    .filter(item -> status == null || item.status() == status)
                    .toList();
        }

        @Override
        public List<MemoryItem> search(String query, int limit) {
            return list(null, null, null).stream()
                    .filter(item -> item.summary().contains(query) || item.text().contains(query))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void deleteById(UUID id) {
            items.remove(id);
            events.removeIf(event -> id.equals(event.memoryId()));
            reviewQueue.removeIf(item -> id.equals(item.candidateMemoryId()));
        }
    }
}
