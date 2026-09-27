package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MemoryCaptureServiceTest {

    @Test
    void rejectsKnownPiiSetWithZeroFalseNegativesAndContentLeanMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryCaptureService service = service(repository, registry);

        List<CaptureMemoryRequest> requests = List.of(
                request("Bunu hatırla: TCKN 10000000146 loglanmaz."),
                request("Bunu hatırla: IBAN TR330006100519786457841326 yazma."),
                request("Bunu hatırla: email dev@example.com kullanılmaz."),
                request("Bunu hatırla: telefon 05551234567 kaydetme."),
                request("Bunu hatırla: kart 4111 1111 1111 1111 saklanmaz."));

        List<CaptureMemoryResponse> responses = requests.stream().map(service::capture).toList();

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.accepted()).isFalse();
            assertThat(response.rejectedReason()).isEqualTo("pii_detected");
            assertThat(response.piiPattern()).isNotBlank();
            assertThat(response.textHashPrefix()).hasSize(12);
        });
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "tckn").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "iban").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "email").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "phone").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "payment-card").count())
                .isEqualTo(1.0);
    }

    @Test
    void cleanSignalsCreateActiveMemoryWithoutAReviewQueueEntry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryCaptureService service = service(repository, registry);

        List<CaptureMemoryResponse> responses = List.of(
                service.capture(new CaptureMemoryRequest(
                        "Hayır, biz bu projede repository sınıflarında AcmeBaseRepository kullanıyoruz.",
                        null, "s-1", "AI_ORCHESTRATION", "alex")),
                service.capture(new CaptureMemoryRequest(
                        "Doğru, bu projede servis isimlerinde acme-svc-domain-service formatını zorunlu kullanıyoruz.",
                        null, "s-1", "AI_ORCHESTRATION", "alex")),
                service.capture(new CaptureMemoryRequest(
                        "Bunu hatırla: Java servislerinde MoneyTL kullanmak zorunlu, float yasak.",
                        null, "s-1", "AI_ORCHESTRATION", "alex")));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.accepted()).isTrue();
            assertThat(response.status()).isEqualTo("active");
            assertThat(response.confidence()).isLessThan(0.8);
            assertThat(response.textHashPrefix()).hasSize(12);
        });
        assertThat(repository.items).hasSize(3);
        assertThat(repository.reviewQueue).isEmpty();
        assertThat(repository.events).hasSize(3);
        assertThat(repository.items.values()).allSatisfy(item -> {
            assertThat(item.scope()).isEqualTo(MemoryScope.PROJECT);
            assertThat(item.projectKey()).isEqualTo("AI_ORCHESTRATION");
            assertThat(item.status()).isEqualTo(MemoryStatus.ACTIVE);
            assertThat(item.metadata()).doesNotContainKeys("text", "summary", "reason");
            assertThat(item.metadata()).containsKeys("textHashPrefix", "tokenEstimate", "piiChecked");
        });
        assertThat(repository.items.values()).extracting(MemoryItem::sourceType)
                .contains(MemorySourceType.CORRECTION_SIGNAL, MemorySourceType.APPROVAL_SIGNAL,
                        MemorySourceType.SESSION_SUMMARY);
    }

    @Test
    void borderlineSignalsAreRejectedWithoutWritingMemory() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryCaptureService service = service(repository, new SimpleMeterRegistry());

        CaptureMemoryResponse shortCorrection = service.capture(request("hayır"));
        CaptureMemoryResponse shortApproval = service.capture(request("doğru"));

        assertThat(shortCorrection.accepted()).isFalse();
        assertThat(shortCorrection.rejectedReason()).isEqualTo("insufficient-context");
        assertThat(shortApproval.accepted()).isFalse();
        assertThat(shortApproval.rejectedReason()).isEqualTo("insufficient-context");
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void sensitiveTermsAreRejectedBeforeMemoryWriteAndOnlyHashIsReturned() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeMemoryRepository repository = new FakeMemoryRepository();
        PolicyEngine policyEngine = new DefaultPolicyEngine(
                new PolicyProperties(true, false, "", List.of("ACME-LOAN-001", "internal-prod-svc")));
        MemoryCaptureService service = service(repository, registry, policyEngine);

        CaptureMemoryResponse response = service.capture(request(
                "Bunu hatırla: ACME-LOAN-001 ürünü için internal-prod-svc kullanılmaz."));

        assertThat(response.accepted()).isFalse();
        assertThat(response.rejectedReason()).isEqualTo("sensitive_policy");
        assertThat(response.textHashPrefix()).hasSize(12);
        assertThat(response.toString()).doesNotContain("ACME-LOAN-001", "internal-prod-svc");
        assertThat(repository.items).isEmpty();
        assertThat(registry.counter("ai_orchestration_memory_policy_rejects_total", "reason", "sensitive-term")
                .count()).isEqualTo(1.0);
    }

    private static CaptureMemoryRequest request(String text) {
        return new CaptureMemoryRequest(text, null, "session-1", "AI_ORCHESTRATION", "alex");
    }

    private static MemoryCaptureService service(FakeMemoryRepository repository, SimpleMeterRegistry registry) {
        return service(repository, registry, PolicyEngine.allowAll());
    }

    private static MemoryCaptureService service(FakeMemoryRepository repository, SimpleMeterRegistry registry,
            PolicyEngine policyEngine) {
        return service(repository, registry, policyEngine, false);
    }

    private static MemoryCaptureService service(FakeMemoryRepository repository, SimpleMeterRegistry registry,
            PolicyEngine policyEngine, boolean ruleAuthorityEnabled) {
        RuleMemoryActivationPolicy rulePolicy = MemoryServiceTestFixture.rules(ruleAuthorityEnabled);
        MemoryService memoryService = MemoryServiceTestFixture.create(repository, properties(), event -> {
        }, policyEngine, new PiiScrubber(), MemoryServiceTestFixture.noOpVectorIndex(), rulePolicy);
        return new MemoryCaptureService(new PiiScrubber(), new MemorySignalDetector(),
                new EpisodicCandidateClassifier(), memoryService, new AiMetrics(registry), policyEngine,
                rulePolicy);
    }

    /**
     * Rule authority is enabled by default in production. A captured RULE candidate must stay a
     * rules.promote candidate instead of being written ACTIVE, which the activation policy rejects.
     */
    @Test
    void ruleCandidateStaysAPromotionCandidateWhenRuleAuthorityIsEnabled() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryCaptureService service = service(repository, new SimpleMeterRegistry(),
                PolicyEngine.allowAll(), true);

        CaptureMemoryResponse response = service.capture(new CaptureMemoryRequest(
                "Bunu hatırla: Java servislerinde MoneyTL kullanmak zorunlu, float yasak.",
                null, "s-rule", "AI_ORCHESTRATION", "alex"));

        assertThat(response.accepted()).isTrue();
        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.memoryType()).isEqualTo(MemoryType.RULE);
        assertThat(stored.status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(response.status()).isEqualTo("pending_review");
        assertThat(response.decision()).isEqualTo("created_pending_review");
        assertThat(repository.reviewQueue).hasSize(1);
    }

    /**
     * With rule authority disabled a non-RULE capture is stored active: there is no pending state
     * for ordinary memory any more.
     */
    @Test
    void nonRuleCaptureIsStoredActive() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryCaptureService service = service(repository, new SimpleMeterRegistry(),
                PolicyEngine.allowAll(), true);

        CaptureMemoryResponse response = service.capture(new CaptureMemoryRequest(
                "Hayır, biz bu projede repository sınıflarında AcmeBaseRepository kullanıyoruz.",
                null, "s-fact", "AI_ORCHESTRATION", "alex"));

        assertThat(response.accepted()).isTrue();
        MemoryItem stored = repository.items.get(response.memoryId());
        if (stored.memoryType() == MemoryType.RULE) {
            assertThat(stored.status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        } else {
            assertThat(stored.status()).isEqualTo(MemoryStatus.ACTIVE);
            assertThat(response.status()).isEqualTo("active");
            assertThat(repository.reviewQueue).isEmpty();
        }
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-capture.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", 2000, 0.3),
                365);
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
    }
}
