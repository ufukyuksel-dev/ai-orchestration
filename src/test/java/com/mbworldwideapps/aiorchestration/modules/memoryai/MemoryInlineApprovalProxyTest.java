package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.MemoryConfirmProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class MemoryInlineApprovalProxyTest {

    private final FakeMemoryRepository repository = new FakeMemoryRepository();
    private final MemoryService memoryService = MemoryServiceTestFixture.create(repository, properties());
    private final MemoryReviewService reviewService = MemoryReviewServiceTestFixture.create(
            repository, new FakeMemoryVectorIndex(), event -> {
            });
    private final MemoryInlineApprovalProxy proxy = new MemoryInlineApprovalProxy(memoryService, reviewService);
    private final MemoryConfirmService confirmService = new MemoryConfirmService(memoryService, reviewService,
            new PiiScrubber(), new MemoryConfirmProperties(0.75));
    private final MemoryInlineApprovalTurnService turnService =
            new MemoryInlineApprovalTurnService(proxy, confirmService);

    @Test
    void pendingCardsIncludeConfidenceReasonAndProposedContent() {
        MemoryItem pending = createPending("Use ports for outbound dependencies.", Map.of(
                "gateReason", "LOW_CONFIDENCE",
                "confidence", 0.73,
                "scopeDecisionMode", "server_classified",
                "scopeDecisionReason", "general engineering rule signal"));

        MemoryInlineApprovalPendingResponse response = proxy.pending("PROJECT_A", 10, 0);

        assertThat(response.count()).isEqualTo(1);
        MemoryInlineApprovalCard card = response.items().getFirst();
        assertThat(card.memoryId()).isEqualTo(pending.id());
        assertThat(card.scope()).isEqualTo("project");
        assertThat(card.scopeDecisionMode()).isEqualTo("server_classified");
        assertThat(card.scopeDecisionReason()).isEqualTo("general engineering rule signal");
        assertThat(card.proposedContent()).isEqualTo("Use ports for outbound dependencies.");
        assertThat(card.confidence()).isEqualTo(0.73);
        assertThat(card.reason()).isEqualTo("LOW_CONFIDENCE");
        assertThat(card.metadata()).containsEntry("confidence", 0.73);
    }

    @Test
    void pendingCountReportsTotalNotPageSize() {
        for (int i = 0; i < 14; i++) {
            createPending("Pending memory " + i + ".", Map.of());
        }

        MemoryInlineApprovalPendingResponse response = proxy.pending("PROJECT_A", 10, 0);

        assertThat(response.items()).hasSize(10);
        assertThat(response.count()).isEqualTo(14);
    }

    @Test
    void approveUsesHumanConfirmationAndDoesNotRequireAgentAdminScope() {
        MemoryItem pending = createPending("Controller should call use case ports.", Map.of("gateReason", "SHADOW"));

        MemoryInlineApprovalDecisionResponse response = proxy.decide(pending.id(),
                new MemoryInlineApprovalDecisionRequest("onayla", "alex", "stable project rule",
                        null, null, null, null, "turn-42"));

        assertThat(response.decision()).isEqualTo("approve");
        assertThat(response.item().status()).isEqualTo(MemoryStatus.ACTIVE);
        MemoryEvent event = repository.eventsForMemory(pending.id()).stream()
                .filter(memoryEvent -> memoryEvent.eventType() == MemoryEventType.STATUS_CHANGED)
                .findFirst()
                .orElseThrow();
        assertThat(event.metadata()).containsEntry("humanConfirmed", true);
        assertThat(event.metadata()).containsEntry("humanTurnRef", "turn-42");
        assertThat(event.metadata()).containsEntry("actor", "alex");
        assertThat(event.metadata()).doesNotContainKeys("reason", "text", "summary");
        assertThat(repository.reviewQueueForMemory(pending.id()).getFirst().status()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void ruleAuthorityBlocksInlineAndConfirmApprovalPaths() {
        MemoryItem pending = createPending("Controllers must stay thin.", Map.of());
        MemoryReviewService guardedReview = MemoryReviewServiceTestFixture.create(
                repository, new FakeMemoryVectorIndex(), event -> {
                }, MemoryServiceTestFixture.rules(true));
        MemoryInlineApprovalProxy guardedProxy = new MemoryInlineApprovalProxy(memoryService, guardedReview);
        MemoryConfirmService guardedConfirm = new MemoryConfirmService(memoryService, guardedReview,
                new PiiScrubber(), new MemoryConfirmProperties(0.75));

        assertThatThrownBy(() -> guardedProxy.decide(pending.id(),
                new MemoryInlineApprovalDecisionRequest("onayla", "alex", "stable rule",
                        null, null, null, null, "turn-guarded-inline")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThatThrownBy(() -> guardedConfirm.confirm(new MemoryConfirmService.ConfirmCommand(
                pending.id(), "approve", true, "onaylıyorum", 0.99, "turn-guarded-confirm",
                null, "stable rule", "alex", "PROJECT_A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThat(repository.findById(pending.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
    }

    @Test
    void rejectUsesHumanConfirmation() {
        MemoryItem pending = createPending("Too vague rule.", Map.of());

        MemoryInlineApprovalDecisionResponse response = proxy.decide(pending.id(),
                new MemoryInlineApprovalDecisionRequest("reddet", "alex", "too vague",
                        null, null, null, null, "turn-43"));

        assertThat(response.item().status()).isEqualTo(MemoryStatus.REJECTED);
        MemoryEvent event = repository.eventsForMemory(pending.id()).stream()
                .filter(memoryEvent -> memoryEvent.eventType() == MemoryEventType.REJECTED)
                .findFirst()
                .orElseThrow();
        assertThat(event.metadata()).containsEntry("humanConfirmed", true);
        assertThat(event.metadata()).containsEntry("humanTurnRef", "turn-43");
        assertThat(repository.reviewQueueForMemory(pending.id()).getFirst().status()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void editCreatesAuditedReplacementAndSupersedesOriginal() {
        MemoryItem pending = createPending("Controller can call repositories directly.", Map.of(
                "gateReason", "LOW_CONFIDENCE"));

        MemoryInlineApprovalDecisionResponse response = proxy.decide(pending.id(),
                new MemoryInlineApprovalDecisionRequest("düzelt",
                        "alex",
                        "human edited wording",
                        "Hexagonal boundary rule",
                        "Controllers call application use cases; repositories are only adapter implementations.",
                        List.of("architecture", "hexagonal"),
                        0.91,
                        "turn-44"));

        MemoryItem original = response.item();
        MemoryItem replacement = response.replacement();
        assertThat(original.id()).isEqualTo(pending.id());
        assertThat(original.status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(original.text()).isEqualTo("Controller can call repositories directly.");
        assertThat(replacement.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(replacement.text()).isEqualTo(
                "Controllers call application use cases; repositories are only adapter implementations.");
        assertThat(replacement.metadata()).containsEntry("editedFrom", pending.id().toString());
        assertThat(replacement.metadata()).containsEntry("humanConfirmed", true);
        assertThat(replacement.metadata()).containsEntry("humanTurnRef", "turn-44");

        MemoryEvent originalEvent = repository.eventsForMemory(pending.id()).stream()
                .filter(event -> event.eventType() == MemoryEventType.REJECTED)
                .findFirst()
                .orElseThrow();
        assertThat(originalEvent.metadata()).containsEntry("action", "reject_for_edit");
        assertThat(originalEvent.metadata()).containsEntry("supersededBy", replacement.id().toString());

        MemoryEvent replacementEvent = repository.eventsForMemory(replacement.id()).stream()
                .filter(event -> event.eventType() == MemoryEventType.STATUS_CHANGED)
                .findFirst()
                .orElseThrow();
        assertThat(replacementEvent.metadata()).containsEntry("action", "approve_after_human_edit");
        assertThat(replacementEvent.metadata()).containsEntry("editedFrom", pending.id().toString());
    }

    @Test
    void pollAfterTranscriptSubmitRendersPendingCard() {
        MemoryItem pending = createPending("Use ports for outbound dependencies.", Map.of(
                "gateReason", "LOW_CONFIDENCE",
                "confidence", 0.74));

        MemoryInlineApprovalRenderResponse response =
                turnService.pollAndRender("PROJECT_A", "session-1", "turn-1");

        assertThat(response.status()).isEqualTo("pending");
        assertThat(response.display()).isTrue();
        assertThat(response.activeItem().memoryId()).isEqualTo(pending.id());
        assertThat(response.markdown()).contains("Pending memory review");
        assertThat(response.markdown()).contains("Scope: project");
        assertThat(response.markdown()).contains(pending.id().toString().substring(0, 8));
        assertThat(response.markdown()).contains("memory.confirm");
    }

    @Test
    void humanApproveTurnAppliesDecisionAndWritesAudit() {
        MemoryItem pending = createPending("Use application use cases from controllers.", Map.of());

        MemoryInlineApprovalTurnResponse response = turnService.handleHumanTurn(
                new AgentInterpretedDecisionRequest("PROJECT_A", "session-1", pending.id(),
                        "approve", true, "onayla, çünkü doğru", 0.91, "turn-2",
                        null, "doğru"));

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.decisionApplied()).isTrue();
        assertThat(repository.findById(pending.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        MemoryEvent event = repository.eventsForMemory(pending.id()).stream()
                .filter(memoryEvent -> memoryEvent.eventType() == MemoryEventType.STATUS_CHANGED)
                .findFirst()
                .orElseThrow();
        assertThat(event.metadata()).containsEntry("humanConfirmed", true);
        assertThat(event.metadata()).containsEntry("humanTurnRef", "turn-2");
        assertThat(event.metadata()).containsEntry("actor", "human:inline:session-1");
    }

    @Test
    void humanEditTurnAppliesReplacementFlow() {
        MemoryItem pending = createPending("Controller directly calls repository.", Map.of());

        MemoryInlineApprovalTurnResponse response = turnService.handleHumanTurn(
                new AgentInterpretedDecisionRequest("PROJECT_A", "session-1", pending.id(),
                        "edit", true, "düzelt: Controllers call application use cases.", 0.92,
                        "turn-3", "Controllers call application use cases.", "human edited"));

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.decision().decision()).isEqualTo("edit");
        assertThat(repository.findById(pending.id()).orElseThrow().status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(response.decision().replacement().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(response.decision().replacement().metadata()).containsEntry("editedFrom", pending.id().toString());
    }

    @Test
    void passThroughDecisionUsesExplicitMemoryIdWhenMultiplePendingExist() {
        MemoryItem first = createPending("First pending memory.", Map.of());
        createPending("First pending memory.", Map.of());
        createPending("Second pending memory.", Map.of());

        MemoryInlineApprovalTurnResponse response = turnService.handleHumanTurn(
                new AgentInterpretedDecisionRequest("PROJECT_A", "session-1", first.id(),
                        "approve", true, "approve first", 0.9, "turn-4", null, "selected"));

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.decisionApplied()).isTrue();
        assertThat(repository.findById(first.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void pollingFailureSilentlyDegrades() {
        MemoryInlineApprovalTurnService failingService = new MemoryInlineApprovalTurnService(
                new FailingInlineApprovalProxy(), confirmService);

        MemoryInlineApprovalRenderResponse response =
                failingService.pollAndRender("PROJECT_A", "session-1", "turn-5");

        assertThat(response.status()).isEqualTo("degraded");
        assertThat(response.display()).isFalse();
        assertThat(response.markdown()).isBlank();
    }

    private MemoryItem createPending(String text, Map<String, Object> metadata) {
        return memoryService.create(new CreateMemoryRequest(
                MemoryScope.PROJECT,
                "PROJECT_A",
                MemoryType.RULE,
                "Rule",
                text,
                List.of("architecture"),
                metadata.get("confidence") instanceof Number confidence ? confidence.doubleValue() : 0.73,
                MemoryStatus.PENDING_REVIEW,
                MemorySourceType.AUTO_CURATED,
                "auto-curated:test:" + UUID.randomUUID(),
                "memory-auto-curator",
                metadata,
                Instant.parse("2026-05-25T08:00:00Z"),
                null));
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-inline-approval.jsonl",
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

    private static final class FakeMemoryVectorIndex implements MemoryVectorIndex {
        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
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
                    .filter(item -> sourceRef != null && sourceRef.equals(item.sourceRef()))
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
            items.put(id, new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(),
                    item.memoryType(), item.summary(), item.text(), item.tags(), item.confidence(), status,
                    item.sourceType(), item.sourceRef(), item.owner(), item.metadata(), item.createdAt(),
                    Instant.now(), item.lastUsedAt(), item.lastVerifiedAt(), item.expiresAt()));
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
            int updated = 0;
            for (int i = 0; i < reviewQueue.size(); i++) {
                ReviewQueueItem item = reviewQueue.get(i);
                if (memoryId.equals(item.candidateMemoryId()) && item.status() == expectedStatus) {
                    Map<String, Object> mergedMetadata = new HashMap<>(item.metadata());
                    mergedMetadata.putAll(metadata);
                    reviewQueue.set(i, new ReviewQueueItem(item.id(), item.candidateMemoryId(), item.reason(),
                            status, mergedMetadata, item.createdAt(), reviewedAt));
                    updated++;
                }
            }
            return updated;
        }
    }

    private static final class FailingInlineApprovalProxy extends MemoryInlineApprovalProxy {
        private FailingInlineApprovalProxy() {
            super(null, null);
        }

        @Override
        public MemoryInlineApprovalPendingResponse pending(String projectKey, Integer limit, Integer offset) {
            throw new IllegalStateException("polling failed");
        }
    }
}
