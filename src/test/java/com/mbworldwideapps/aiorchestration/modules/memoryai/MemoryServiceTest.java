package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.policy.DefaultPolicyEngine;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import org.junit.jupiter.api.Test;

class MemoryServiceTest {

    private final FakeMemoryRepository repository = new FakeMemoryRepository();
    private final MemoryService memoryService = MemoryServiceTestFixture.create(repository, properties());

    @Test
    void createDefaultsToPendingReviewAndWritesContentLeanEventAndReviewQueue() {
        MemoryItem item = memoryService.create(new CreateMemoryRequest(
                MemoryScope.GLOBAL,
                null,
                MemoryType.RULE,
                "Use MoneyTL",
                "Turkish lira amounts use the MoneyTL value object.",
                List.of(" Java ", "money", "java"),
                null,
                null,
                MemorySourceType.MANUAL,
                "session-1",
                "alex",
                Map.of("origin", "test"),
                Instant.parse("2026-05-20T08:00:00Z"),
                null));

        assertThat(item.status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(item.confidence()).isEqualTo(0.42);
        assertThat(item.tags()).containsExactly("java", "money");
        assertThat(item.vectorId()).isEqualTo(MemoryService.deterministicVectorId(
                MemoryScope.GLOBAL,
                null,
                "Use MoneyTL",
                "Turkish lira amounts use the MoneyTL value object."));

        assertThat(repository.events).hasSize(1);
        MemoryEvent event = repository.events.getFirst();
        assertThat(event.eventType()).isEqualTo(MemoryEventType.CREATED);
        assertThat(event.metadata()).containsEntry("scope", "global");
        assertThat(event.metadata()).containsEntry("memoryType", "rule");
        assertThat(event.metadata()).containsEntry("tagCount", 2);
        assertThat(event.metadata()).doesNotContainKeys("text", "summary");

        assertThat(repository.reviewQueue).hasSize(1);
        ReviewQueueItem review = repository.reviewQueue.getFirst();
        assertThat(review.reason()).isEqualTo(ReviewReason.LOW_CONFIDENCE);
        assertThat(review.status()).isEqualTo(ReviewStatus.OPEN);
        assertThat(review.metadata()).doesNotContainKeys("text", "summary");
    }

    @Test
    void projectMemoryRequiresProjectKey() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryScope.PROJECT,
                null,
                MemoryType.DECISION,
                "Repository pattern",
                "Project uses AcmeBaseRepository.",
                List.of(),
                0.9,
                MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL,
                null,
                null,
                Map.of(),
                null,
                null);

        assertThatThrownBy(() -> memoryService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectKey");
    }

    @Test
    void genericCreateKeepsDiscoveryBehindLearningAdmission() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryScope.PROJECT,
                "PROJECT_A",
                MemoryType.DISCOVERY,
                "Resolver behavior",
                "A reusable code behavior discovered during research.",
                List.of("learning"),
                0.9,
                MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL,
                "fixture:discovery",
                "test",
                Map.of(),
                Instant.now(),
                null);

        assertThatThrownBy(() -> memoryService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory.learn");
        assertThat(repository.items).isEmpty();
    }

    @Test
    void evidenceBackedDiscoveryCorrectionPreservesIdentityAndReactivatesItem() {
        List<MemoryItemChangedEvent> published = new ArrayList<>();
        TrackingVectorIndex vectors = new TrackingVectorIndex();
        MemoryService service = MemoryServiceTestFixture.create(repository, properties(),
                event -> published.add((MemoryItemChangedEvent) event), PolicyEngine.allowAll(),
                new com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber(), vectors);
        MemoryItem original = service.createDiscovery(discoveryRequest(
                "Original route", "The original route owns fallback.", "learning:first:0"));
        repository.update(new MemoryItem(original.id(), original.vectorId(), original.scope(), original.projectKey(),
                original.memoryType(), original.summary(), original.text(), original.tags(), original.confidence(),
                MemoryStatus.STALE, original.sourceType(), original.sourceRef(), original.owner(),
                original.metadata(), original.createdAt(), original.updatedAt(), original.lastUsedAt(),
                original.lastVerifiedAt(), original.expiresAt()));

        MemoryItem corrected = service.correctDiscovery(original.id(), "PROJECT_A", discoveryRequest(
                "Corrected route", "The corrected route owns fallback.", "learning:second:0"));

        assertThat(corrected.id()).isEqualTo(original.id());
        assertThat(corrected.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(corrected.vectorId()).isNotEqualTo(original.vectorId());
        assertThat(vectors.deletedVectorIds).isEmpty();
        assertThat(repository.items).hasSize(1);
        assertThat(repository.events.getLast().metadata()).containsEntry("learningCorrection", true);
        assertThat(published.getLast().origin()).isEqualTo(MemoryItemChangedEvent.Origin.LEARNING_CORRECTION);
        assertThat(published.getLast().previousItem().vectorId()).isEqualTo(original.vectorId());
        assertThat(published.getLast().previousItem().status()).isEqualTo(MemoryStatus.STALE);
    }

    @Test
    void updateStatusWritesContentLeanStatusChangedEvent() {
        MemoryItem item = memoryService.create(new CreateMemoryRequest(
                MemoryScope.GLOBAL,
                null,
                MemoryType.RULE,
                "Logger rule",
                "Use acme-logger.",
                List.of("logging"),
                0.95,
                MemoryStatus.PENDING_REVIEW,
                MemorySourceType.MANUAL,
                null,
                "platform",
                Map.of(),
                null,
                null));

        MemoryItem updated = memoryService.updateStatus(item.id(), MemoryStatus.ACTIVE, "reviewer",
                "Approved after review: Use acme-logger.");

        assertThat(updated.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(repository.events).hasSize(2);
        MemoryEvent event = repository.events.get(1);
        assertThat(event.eventType()).isEqualTo(MemoryEventType.STATUS_CHANGED);
        assertThat(event.metadata()).containsEntry("oldStatus", "pending_review");
        assertThat(event.metadata()).containsEntry("newStatus", "active");
        assertThat(event.metadata()).containsEntry("actor", "reviewer");
        assertThat(event.metadata()).containsEntry("reasonProvided", true);
        assertThat(event.metadata()).containsEntry("reasonLength", 39);
        assertThat(event.metadata()).doesNotContainKeys("text", "summary", "reason");
    }

    @Test
    void ruleAuthorityBlocksDirectCreateAndStatusActivationButNotNonRuleMemory() {
        MemoryService guarded = rulesEnabledService(repository, new TrackingVectorIndex());
        CreateMemoryRequest activeRule = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Controller rule.", "active-rule-hash");

        assertThatThrownBy(() -> guarded.create(activeRule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");
        assertThat(repository.items).isEmpty();

        CreateMemoryRequest staleRule = new CreateMemoryRequest(
                activeRule.scope(), activeRule.projectKey(), activeRule.memoryType(), activeRule.summary(),
                activeRule.text(), activeRule.tags(), activeRule.confidence(), MemoryStatus.STALE,
                activeRule.sourceType(), "manual:stale-rule", activeRule.owner(), activeRule.metadata(),
                activeRule.lastVerifiedAt(), activeRule.expiresAt());
        assertThatThrownBy(() -> guarded.create(staleRule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        MemoryItem pendingRule = guarded.create(new CreateMemoryRequest(
                activeRule.scope(), activeRule.projectKey(), activeRule.memoryType(), activeRule.summary(),
                activeRule.text(), activeRule.tags(), activeRule.confidence(), MemoryStatus.PENDING_REVIEW,
                activeRule.sourceType(), "manual:pending-rule", activeRule.owner(), activeRule.metadata(),
                activeRule.lastVerifiedAt(), activeRule.expiresAt()));
        assertThatThrownBy(() -> guarded.updateStatus(
                pendingRule.id(), MemoryStatus.ACTIVE, "reviewer", "approve"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");
        assertThat(repository.findById(pendingRule.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThatThrownBy(() -> guarded.updateStatus(
                pendingRule.id(), MemoryStatus.STALE, "reviewer", "bypass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        MemoryItem decision = guarded.create(new CreateMemoryRequest(
                MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryType.DECISION, "Architecture decision",
                "Use constructor injection.", List.of("architecture"), 1.0, MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL, "manual:decision", "alex", Map.of(), Instant.now(), null));
        assertThat(decision.status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void restControllerCreateAndStatusRoutesCannotBypassRuleAuthority() {
        MemoryService guarded = rulesEnabledService(repository, new TrackingVectorIndex());
        MemoryController controller = new MemoryController(guarded, mock(CuratedMemoryLoader.class),
                mock(MemoryCaptureService.class), mock(MemoryRetrievalService.class));
        CreateMemoryRequest activeRule = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Controller rule.", "rest-active-rule-hash");

        assertThatThrownBy(() -> controller.create(activeRule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        MemoryItem pending = controller.create(new CreateMemoryRequest(
                activeRule.scope(), activeRule.projectKey(), activeRule.memoryType(), activeRule.summary(),
                activeRule.text(), activeRule.tags(), activeRule.confidence(), MemoryStatus.PENDING_REVIEW,
                activeRule.sourceType(), "manual:rest-pending-rule", activeRule.owner(), activeRule.metadata(),
                activeRule.lastVerifiedAt(), activeRule.expiresAt()));
        assertThatThrownBy(() -> controller.updateStatus(pending.id(),
                new MemoryStatusUpdateRequest(MemoryStatus.ACTIVE, "reviewer", "approve")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");
        assertThat(repository.findById(pending.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
    }

    @Test
    void ruleAuthorityDowngradesNewCuratedRuleWhileFlagOffPreservesLegacyBehavior() {
        MemoryService guarded = rulesEnabledService(repository, new TrackingVectorIndex());
        CuratedMemoryUpsertResult guardedResult = guarded.upsertCurated(
                curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                        "Controller rule.", "guarded-curated-hash"),
                "guarded-curated-hash");
        assertThat(guardedResult.item().status()).isEqualTo(MemoryStatus.PENDING_REVIEW);

        FakeMemoryRepository legacyRepository = new FakeMemoryRepository();
        MemoryService legacy = MemoryServiceTestFixture.create(legacyRepository, properties());
        MemoryItem legacyRule = legacy.create(curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Legacy active rule.", "legacy-rule-hash"));
        assertThat(legacyRule.status()).isEqualTo(MemoryStatus.ACTIVE);

        MemoryService enabled = rulesEnabledService(legacyRepository, new TrackingVectorIndex());
        MemoryItem unchanged = enabled.updateStatus(legacyRule.id(), MemoryStatus.ACTIVE, "migration", "no-op");
        assertThat(unchanged.status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void exposesMemoryConfig() {
        AiOrchestrationProperties.Memory config = memoryService.memoryConfig();

        assertThat(config.episodicCollectionName()).isEqualTo("memory_episodic_test_384");
        assertThat(config.contextTokenCap()).isEqualTo(1234);
        assertThat(config.defaultConfidence()).isEqualTo(0.42);
    }

    @Test
    void curatedUpdateChangingVectorIdPublishesPreviousItemAcrossAllScopes() {
        for (MemoryScope scope : List.of(MemoryScope.PROJECT, MemoryScope.GLOBAL, MemoryScope.USER)) {
            FakeMemoryRepository localRepo = new FakeMemoryRepository();
            List<MemoryItemChangedEvent> published = new ArrayList<>();
            TrackingVectorIndex tracker = new TrackingVectorIndex();
            MemoryService service = MemoryServiceTestFixture.create(localRepo, properties(),
                    event -> published.add((MemoryItemChangedEvent) event), PolicyEngine.allowAll(),
                    new com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber(), tracker);

            CreateMemoryRequest original = curatedRequest(scope, "Original wording for " + scope.value() + ".",
                    "hash-1");
            CuratedMemoryUpsertResult loaded = service.upsertCurated(original, "hash-1");
            assertThat(loaded.action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
            UUID originalVectorId = loaded.item().vectorId();

            CreateMemoryRequest changed = curatedRequest(scope, "Updated wording for " + scope.value()
                    + " with more detail.", "hash-2");
            CuratedMemoryUpsertResult updated = service.upsertCurated(changed, "hash-2");

            assertThat(updated.action()).isEqualTo(CuratedMemoryUpsertAction.UPDATED);
            assertThat(updated.item().vectorId()).isNotEqualTo(originalVectorId);
            assertThat(tracker.deletedVectorIds).isEmpty();
            assertThat(published.getLast().previousItem().vectorId())
                    .as("projection event must carry the old vector for scope=%s", scope)
                    .isEqualTo(originalVectorId);
        }
    }

    @Test
    void upsertCuratedSkipsUnchangedAndUpdatesChangedContentWithContentLeanEvent() {
        CreateMemoryRequest original = curatedRequest("Use acme-logger.", "hash-1");

        CuratedMemoryUpsertResult loaded = memoryService.upsertCurated(original, "hash-1");
        CuratedMemoryUpsertResult skipped = memoryService.upsertCurated(original, "hash-1");
        CreateMemoryRequest changed = curatedRequest("Use acme-logger for audit/info/error logging.", "hash-2");
        CuratedMemoryUpsertResult updated = memoryService.upsertCurated(changed, "hash-2");

        assertThat(loaded.action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
        assertThat(skipped.action()).isEqualTo(CuratedMemoryUpsertAction.SKIPPED);
        assertThat(updated.action()).isEqualTo(CuratedMemoryUpsertAction.UPDATED);
        assertThat(updated.item().id()).isEqualTo(loaded.item().id());
        assertThat(updated.item().metadata()).containsEntry("contentHash", "hash-2");

        assertThat(repository.events).hasSize(2);
        MemoryEvent event = repository.events.get(1);
        assertThat(event.eventType()).isEqualTo(MemoryEventType.UPDATED);
        assertThat(event.metadata()).containsEntry("contentHashChanged", true);
        assertThat(event.metadata()).containsEntry("oldContentHashPrefix", "hash-1");
        assertThat(event.metadata()).containsEntry("newContentHashPrefix", "hash-2");
        assertThat(event.metadata()).doesNotContainKeys("text", "summary", "reason");
    }

    @Test
    void curatedUpsertCannotChangeRuleTypeInEitherDirectionWhenAuthorityIsEnabled() {
        CreateMemoryRequest rule = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Controller rule.", "hash-rule");
        MemoryItem activeRule = memoryService.upsertCurated(rule, "hash-rule").item();
        MemoryService guarded = rulesEnabledService(repository, new TrackingVectorIndex());

        CreateMemoryRequest asDecision = curatedRequest(MemoryScope.PROJECT, MemoryType.DECISION,
                "Controller decision.", "hash-decision");
        assertThatThrownBy(() -> guarded.upsertCurated(asDecision, "hash-decision"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RULE memory type")
                .hasMessageContaining("cannot be changed in place");
        assertThat(repository.findById(activeRule.id()).orElseThrow().memoryType()).isEqualTo(MemoryType.RULE);

        FakeMemoryRepository reverseRepository = new FakeMemoryRepository();
        MemoryService legacy = MemoryServiceTestFixture.create(reverseRepository, properties());
        CreateMemoryRequest decision = curatedRequest(MemoryScope.PROJECT, MemoryType.DECISION,
                "Controller decision.", "hash-decision");
        MemoryItem activeDecision = legacy.upsertCurated(decision, "hash-decision").item();
        MemoryService reverseGuarded = rulesEnabledService(reverseRepository, new TrackingVectorIndex());
        CreateMemoryRequest asRule = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Controller rule.", "hash-rule");

        assertThatThrownBy(() -> reverseGuarded.upsertCurated(asRule, "hash-rule"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RULE memory type")
                .hasMessageContaining("cannot be changed in place");
        assertThat(reverseRepository.findById(activeDecision.id()).orElseThrow().memoryType())
                .isEqualTo(MemoryType.DECISION);
    }

    @Test
    void activeCuratedRuleSourceDriftCreatesCollisionSafeIdempotentRevisionCandidates() {
        CreateMemoryRequest original = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Original controller rule.", "original-hash");
        MemoryItem active = memoryService.upsertCurated(original, "original-hash").item();
        MemoryService guarded = rulesEnabledService(repository, new TrackingVectorIndex());
        CreateMemoryRequest firstDrift = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "First revised controller rule.", "123456789012-first");
        CreateMemoryRequest secondDrift = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Second revised controller rule.", "123456789012-second");

        CuratedMemoryUpsertResult first = guarded.upsertCurated(firstDrift, "123456789012-first");
        CuratedMemoryUpsertResult firstRetry = guarded.upsertCurated(firstDrift, "123456789012-first");
        CuratedMemoryUpsertResult second = guarded.upsertCurated(secondDrift, "123456789012-second");

        assertThat(first.action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
        assertThat(firstRetry.action()).isEqualTo(CuratedMemoryUpsertAction.SKIPPED);
        assertThat(firstRetry.item().id()).isEqualTo(first.item().id());
        assertThat(second.action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
        assertThat(second.item().id()).isNotEqualTo(first.item().id());
        assertThat(first.item().sourceRef()).matches(".+#revision-[0-9a-f]{64}");
        assertThat(second.item().sourceRef()).matches(".+#revision-[0-9a-f]{64}");
        assertThat(first.item().sourceRef()).isNotEqualTo(second.item().sourceRef());
        assertThat(first.item().status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(first.item().metadata())
                .containsEntry("revisionOf", active.id().toString())
                .containsEntry("sourceDrift", true)
                .containsEntry("contentHash", "123456789012-first");
        assertThat(repository.findById(active.id()).orElseThrow().text())
                .isEqualTo("Original controller rule.");
    }

    @Test
    void linkedCuratedRuleRemainsImmutableWhenRolloutFlagIsDisabled() {
        CreateMemoryRequest original = curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                "Original linked controller rule.", "original-linked-hash");
        MemoryItem active = memoryService.upsertCurated(original, "original-linked-hash").item();
        MemoryService linkedProtection = MemoryServiceTestFixture.create(repository, properties(), event -> {
        }, PolicyEngine.allowAll(), new com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber(),
                new TrackingVectorIndex(), MemoryServiceTestFixture.rules(false), active.id()::equals);

        CuratedMemoryUpsertResult drift = linkedProtection.upsertCurated(
                curatedRequest(MemoryScope.PROJECT, MemoryType.RULE,
                        "Revised linked controller rule.", "revised-linked-hash"),
                "revised-linked-hash");

        assertThat(drift.action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
        assertThat(drift.item().status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(drift.item().metadata()).containsEntry("revisionOf", active.id().toString());
        assertThat(repository.findById(active.id()).orElseThrow().text())
                .isEqualTo("Original linked controller rule.");

        assertThatThrownBy(() -> linkedProtection.updateStatus(
                active.id(), MemoryStatus.ARCHIVED, "reviewer", "must use rules lifecycle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle-managed");
        assertThat(repository.findById(active.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);

        assertThatThrownBy(() -> linkedProtection.upsertCurated(
                curatedRequest(MemoryScope.PROJECT, MemoryType.DECISION,
                        "Changed type.", "changed-type-hash"), "changed-type-hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be changed in place");
    }

    @Test
    void writePolicyRejectsCorrectionSignalGlobalWrites() {
        PolicyEngine policyEngine = new DefaultPolicyEngine(
                new PolicyProperties(true, false, "", List.of("ACME-LOAN-001")));
        MemoryService guardedService = MemoryServiceTestFixture.create(repository, properties(), event -> {
        }, policyEngine);

        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryScope.GLOBAL,
                null,
                MemoryType.RULE,
                "Wrong global capture",
                "Correction signals cannot directly become global memory.",
                List.of("policy"),
                0.7,
                MemoryStatus.PENDING_REVIEW,
                MemorySourceType.CORRECTION_SIGNAL,
                null,
                "alex",
                Map.of(),
                null,
                null);

        assertThatThrownBy(() -> guardedService.create(request))
                .isInstanceOf(MemoryPolicyViolationException.class)
                .hasMessageContaining("write-source-scope-denied");
        assertThat(repository.items).isEmpty();
    }

    @Test
    void contentPolicyRejectsSensitiveTermsBeforePersistence() {
        PolicyEngine policyEngine = new DefaultPolicyEngine(
                new PolicyProperties(true, false, "", List.of("ACME-LOAN-001")));
        MemoryService guardedService = MemoryServiceTestFixture.create(repository, properties(), event -> {
        }, policyEngine);

        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryScope.PROJECT,
                "AI_ORCHESTRATION",
                MemoryType.RULE,
                "Sensitive product",
                "ACME-LOAN-001 must not be captured as memory.",
                List.of("policy"),
                0.9,
                MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL,
                null,
                "platform",
                Map.of(),
                null,
                null);

        assertThatThrownBy(() -> guardedService.create(request))
                .isInstanceOf(MemoryPolicyViolationException.class)
                .hasMessageContaining("sensitive-term");
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void directCreateRejectsPiiBeforePersistence() {
        CreateMemoryRequest request = new CreateMemoryRequest(
                MemoryScope.PROJECT,
                "AI_ORCHESTRATION",
                MemoryType.RULE,
                "PII rule",
                "Bu memory 12345678901 TCKN bilgisini iceriyor.",
                List.of("pii"),
                0.9,
                MemoryStatus.PENDING_REVIEW,
                MemorySourceType.MANUAL,
                null,
                "platform",
                Map.of(),
                null,
                null);

        assertThatThrownBy(() -> memoryService.create(request))
                .isInstanceOf(MemoryPolicyViolationException.class)
                .hasMessageContaining("pii-tckn");
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", 1234, 0.42),
                365);
    }

    private static CreateMemoryRequest curatedRequest(String text, String contentHash) {
        return curatedRequest(MemoryScope.GLOBAL, text, contentHash);
    }

    private static CreateMemoryRequest discoveryRequest(String summary, String text, String sourceRef) {
        return new CreateMemoryRequest(MemoryScope.PROJECT, "PROJECT_A", MemoryType.DISCOVERY,
                summary, text, List.of("discovery"), 0.9, MemoryStatus.ACTIVE,
                MemorySourceType.MCP_EXTERNAL, sourceRef, "runtime", Map.of("learningSchemaVersion", 1),
                Instant.now(), null);
    }

    private static CreateMemoryRequest curatedRequest(MemoryScope scope, String text, String contentHash) {
        return curatedRequest(scope, MemoryType.RULE, text, contentHash);
    }

    private static CreateMemoryRequest curatedRequest(
            MemoryScope scope, MemoryType type, String text, String contentHash) {
        String projectKey = switch (scope) {
            case PROJECT -> "AI_ORCHESTRATION";
            case USER -> "user:alex";
            case GLOBAL, EPISODIC -> null;
        };
        return new CreateMemoryRequest(
                scope,
                projectKey,
                type,
                "Logger rule",
                text,
                List.of("logging"),
                1.0,
                MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL,
                "curated:%s:%s:logger-rule-001".formatted(
                        scope.value(), projectKey == null ? "global" : projectKey),
                "platform",
                Map.of("contentHash", contentHash),
                Instant.parse("2026-05-20T00:00:00Z"),
                null);
    }

    private static MemoryService rulesEnabledService(
            MemoryRepository repository, MemoryVectorIndex vectorIndex) {
        return MemoryServiceTestFixture.create(repository, properties(), event -> {
        }, PolicyEngine.allowAll(), new com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber(), vectorIndex,
                MemoryServiceTestFixture.rules(true));
    }

    private static final class TrackingVectorIndex implements MemoryVectorIndex {
        private final List<UUID> deletedVectorIds = new ArrayList<>();

        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
            deletedVectorIds.add(item.vectorId());
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
    }
}
