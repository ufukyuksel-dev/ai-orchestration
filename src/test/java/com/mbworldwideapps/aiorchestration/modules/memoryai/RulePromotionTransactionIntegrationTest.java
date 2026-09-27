package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionServiceTestAccess.deprecate;
import static com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionServiceTestAccess.promote;
import static com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionServiceTestAccess.promoteVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.MemoryConfirmProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.core.diff.UnifiedDiffParser;
import com.mbworldwideapps.aiorchestration.modules.rules.BindingKind;
import com.mbworldwideapps.aiorchestration.modules.rules.DiffRegexRuleDetector;
import com.mbworldwideapps.aiorchestration.modules.rules.JdbcRuleRepository;
import com.mbworldwideapps.aiorchestration.modules.rules.PromotionRequest;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleEnforcement;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleDeprecationPreview;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleDeprecationRequest;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleDefinition;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckPhase;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSeverity;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSpec;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleHumanApprovalEvidence;
import com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionCandidate;
import com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionPreview;
import com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionService;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleProvenance;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleSelectorGroup;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleSelectorPredicate;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleStatus;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleVersion;
import com.mbworldwideapps.aiorchestration.modules.rules.SelectorField;
import com.mbworldwideapps.aiorchestration.modules.rules.SelectorOperator;
import com.mbworldwideapps.aiorchestration.modules.rules.SelectorPolarity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RulePromotionTransactionIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcMemoryRepository memoryRepository;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private RulePromotionService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        memoryRepository = new JdbcMemoryRepository(jdbcTemplate, new ObjectMapper());
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        DefaultRuleMemoryPromotionPort port = new DefaultRuleMemoryPromotionPort(
                memoryRepository, jdbcTemplate, new RuleMemoryProjectionOutbox(jdbcTemplate));
        service = new RulePromotionService(new JdbcRuleRepository(jdbcTemplate, new ObjectMapper()), port,
                new RulesProperties(true, 20, 256, 4096, 200), new ObjectMapper(),
                List.of(new DiffRegexRuleDetector(new UnifiedDiffParser())));
    }

    @Test
    void memoryBackedPromotionCommitsRuleEvidenceAndActivatesMemoryAtomically() {
        MemoryItem pending = savePendingRule();
        RulePromotionCandidate candidate = memoryCandidate(pending,
                RuleMemoryContentHash.compute(pending.summary(), pending.text()));
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = transactionTemplate.execute(status -> promote(service, request(candidate, preview)));

        assertThat(promoted).isNotNull();
        assertThat(memoryRepository.findById(pending.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(count("rule_definitions")).isEqualTo(1);
        assertThat(count("rule_versions")).isEqualTo(1);
        assertThat(count("rule_target_bindings")).isEqualTo(1);
        assertThat(count("rule_lifecycle_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin_memory_id FROM rule_definitions WHERE id = ?", UUID.class, promoted.ruleId()))
                .isEqualTo(pending.id());
        assertThat(memoryRepository.eventsForMemory(pending.id())).extracting(MemoryEvent::eventType)
                .containsExactly(MemoryEventType.STATUS_CHANGED);
        assertThat(count("rule_memory_projection_work")).isEqualTo(1);
        JdbcMemoryRepository ruleAwareGraphRepository = new JdbcMemoryRepository(
                jdbcTemplate, new ObjectMapper(), new RulesProperties(true, 20, 256, 4096, 200));
        assertThat(ruleAwareGraphRepository.findEligibleForGraphProjection(
                pending.projectKey(), null, 20)).extracting(MemoryItem::id).containsExactly(pending.id());
        assertThat(memoryRepository.findEligibleForGraphProjection(pending.projectKey(), null, 20))
                .extracting(MemoryItem::id)
                .containsExactly(pending.id());

        RuleVersion replayed = transactionTemplate.execute(status -> promote(service, request(candidate, preview)));
        assertThat(replayed).isEqualTo(promoted);
        assertThat(count("rule_definitions")).isEqualTo(1);
        assertThat(count("rule_versions")).isEqualTo(1);
        assertThat(count("rule_lifecycle_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(1L);
        assertThat(count("rule_memory_projection_work")).isEqualTo(1);
    }

    @Test
    void derivedActiveUnlinkedLegacyRulePromotesAndLeavesNoDuplicateInjectionCandidate() {
        MemoryItem template = pendingRule(MemoryStatus.ACTIVE, "manual:legacy-controller-rule");
        memoryRepository.save(template);
        RulePromotionCandidate candidate = memoryCandidate(template,
                RuleMemoryContentHash.compute(template.summary(), template.text()));
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = transactionTemplate.execute(status -> promote(service, request(candidate, preview)));

        assertThat(promoted).isNotNull();
        assertThat(memoryRepository.findById(template.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(memoryRepository.eventsForMemory(template.id())).extracting(MemoryEvent::eventType)
                .containsExactly(MemoryEventType.PROMOTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_target_bindings
                WHERE rule_id = ? AND rule_version = 1
                """, Integer.class, promoted.ruleId())).isOne();
        RuleMemoryInjectionFilter filter = new RuleMemoryInjectionFilter(
                new RulesProperties(true, 20, 256, 4096, 200),
                new JdbcRuleMemoryLinkLookup(jdbcTemplate));
        assertThat(filter.eligibleForAutomaticInjection(List.of(
                memoryRepository.findById(template.id()).orElseThrow()))).isEmpty();
    }

    @Test
    void staleOriginHashRollsBackEveryRuleTableAndLeavesMemoryPending() {
        MemoryItem pending = savePendingRule();
        RulePromotionCandidate staleCandidate = memoryCandidate(pending, "0".repeat(64));
        RulePromotionPreview preview = service.preview(staleCandidate);

        assertThatThrownBy(() -> transactionTemplate.execute(
                status -> promote(service, request(staleCandidate, preview))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale origin content hash");

        assertThat(memoryRepository.findById(pending.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(count("rule_definitions")).isZero();
        assertThat(count("rule_versions")).isZero();
        assertThat(count("rule_target_bindings")).isZero();
        assertThat(count("rule_selector_groups")).isZero();
        assertThat(count("rule_check_definitions")).isZero();
        assertThat(count("rule_scope_assignments")).isZero();
        assertThat(count("rule_lifecycle_events")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM rules_global_effective_seq", Long.class)).isZero();
        assertThat(count("rules_project_effective_seq")).isZero();
        assertThat(memoryRepository.eventsForMemory(pending.id())).isEmpty();
        assertThat(count("rule_memory_projection_work")).isZero();
    }

    @Test
    void directHumanPromotionCreatesNoMemoryAndUsesNoMemoryActivationEvent() {
        RulePromotionCandidate candidate = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Controllers must stay thin", "Keep adapters at the boundary", RuleEnforcement.CONTEXT,
                false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(
                        BindingKind.PATH_GLOB, "**/*Controller.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = transactionTemplate.execute(status -> promote(service, request(candidate, preview)));

        assertThat(promoted).isNotNull();
        assertThat(count("memory_items")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin_memory_id FROM rule_definitions WHERE id = ?", UUID.class, promoted.ruleId()))
                .isNull();
        assertThat(count("rule_memory_projection_work")).isZero();
    }

    @Test
    void directPlanSemanticPromotionCommitsSelectorsChecksScopeAndProjectSequenceAtomically() {
        RuleSelectorGroup selector = new RuleSelectorGroup("controllers", List.of(
                new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                        SelectorOperator.GLOB, List.of("**/*Controller.java"))));
        RuleCheckSpec check = new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("BUSINESS_DECISION")));
        RulePromotionCandidate candidate = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Controllers must stay thin", "Keep adapters at the boundary", RuleEnforcement.CONTEXT,
                false, null, null, List.of(), List.of(selector), List.of(check),
                RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = transactionTemplate.execute(status -> promote(service, request(candidate, preview)));

        assertThat(promoted).isNotNull();
        assertThat(count("rule_selector_groups")).isOne();
        assertThat(count("rule_selector_predicates")).isOne();
        assertThat(count("rule_check_definitions")).isOne();
        assertThat(count("rule_scope_assignments")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT checker_contract_hash FROM rule_check_definitions
                WHERE rule_id = ? AND rule_version = 1
                """, String.class, promoted.ruleId())).matches("[0-9a-f]{64}");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT seq FROM rules_project_effective_seq WHERE project_key = 'AI_ORCHESTRATION'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM rules_global_effective_seq", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(1L);
    }

    @Test
    void concurrentExactPromotionsConvergeOnOneCanonicalRule() throws Exception {
        MemoryItem pending = savePendingRule();
        RulePromotionCandidate candidate = memoryCandidate(pending,
                RuleMemoryContentHash.compute(pending.summary(), pending.text()));
        RulePromotionPreview preview = service.preview(candidate);
        PromotionRequest request = request(candidate, preview);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> promote(service, request));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> promote(service, request));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RuleVersion firstResult = first.get(20, TimeUnit.SECONDS);
            RuleVersion secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("rule_definitions")).isEqualTo(1);
        assertThat(count("rule_versions")).isEqualTo(1);
        assertThat(count("rule_lifecycle_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(1L);
        assertThat(memoryRepository.findById(pending.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(count("rule_memory_projection_work")).isEqualTo(1);
    }

    @Test
    void concurrentDifferentVersionUpdatesAllowExactlyOneV2Winner() throws Exception {
        RulePromotionCandidate initial = directCandidate("Controllers must stay thin");
        RulePromotionPreview initialPreview = service.preview(initial);
        RuleVersion current = transactionTemplate.execute(status -> promote(service,
                request(initial, initialPreview)));
        assertThat(current).isNotNull();

        RulePromotionCandidate firstCandidate = directCandidate("Controllers must delegate business logic");
        RulePromotionCandidate secondCandidate = directCandidate("Controllers must only map transport data");
        RulePromotionPreview firstPreview = service.previewVersion(
                current.ruleId(), 1, current.contentHash(), firstCandidate);
        RulePromotionPreview secondPreview = service.previewVersion(
                current.ruleId(), 1, current.contentHash(), secondCandidate);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> promoteVersion(service, current.ruleId(), 1,
                        current.contentHash(), request(firstCandidate, firstPreview)));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> promoteVersion(service, current.ruleId(), 1,
                        current.contentHash(), request(secondCandidate, secondPreview)));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(awaitOutcome(first), awaitOutcome(second));
            assertThat(outcomes).filteredOn(RuleVersion.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(IllegalStateException.class::isInstance).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(count("rule_versions")).isEqualTo(2);
        assertThat(count("rule_lifecycle_events")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM rule_definitions WHERE id = ?", Integer.class, current.ruleId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(2L);
    }

    @Test
    void concurrentExactDeprecationsConvergeOnOneLifecycleEvent() throws Exception {
        RulePromotionCandidate initial = directCandidate("Controllers must stay thin");
        RulePromotionPreview initialPreview = service.preview(initial);
        RuleVersion current = transactionTemplate.execute(status -> promote(service,
                request(initial, initialPreview)));
        assertThat(current).isNotNull();
        String reason = "Superseded by application-boundary policy";
        RuleDeprecationPreview preview = service.previewDeprecation(
                current.ruleId(), 1, current.contentHash(), reason);
        RuleDeprecationRequest request = new RuleDeprecationRequest(
                current.ruleId(), 1, current.contentHash(), reason,
                preview.approvalContentHash(), preview.confirmationCardHash(),
                preview.workflowContractVersion(),
                new RuleHumanApprovalEvidence("alex", "turn-deprecate", "Bu kuralı kaldırıyorum", true, 0.99));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> deprecate(service, request));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> deprecate(service, request));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RuleDefinition firstResult = first.get(20, TimeUnit.SECONDS);
            RuleDefinition secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.status()).isEqualTo(RuleStatus.DEPRECATED);
            assertThat(secondResult.status()).isEqualTo(RuleStatus.DEPRECATED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(count("rule_versions")).isEqualTo(1);
        assertThat(count("rule_lifecycle_events")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM rule_definitions WHERE id = ?", String.class, current.ruleId()))
                .isEqualTo("deprecated");
    }

    @Test
    void editRacingPromotionCannotMutateTheLinkedRuleOrigin() throws Exception {
        MemoryItem pending = savePendingRule();

        RaceOutcome<MemoryItem> outcome = runPromotionFirstRace(pending, LookupKind.ID,
                repository -> reviewService(repository).edit(pending.id(), new EditMemoryRequest(
                        "Controllers may contain business logic",
                        "Business logic may now live in controllers.",
                        List.of("controller"), 1.0, "reviewer", "concurrent edit")));

        assertThat(outcome.promotion()).isNotNull();
        assertThat(outcome.result()).isNull();
        assertThat(outcome.failure())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable rule history");
        assertPromotedOriginUnchanged(pending);
    }

    @Test
    void curatedReloadRacingPromotionCreatesARevisionWithoutMutatingTheOrigin() throws Exception {
        MemoryItem pending = savePendingRule();
        CreateMemoryRequest drift = new CreateMemoryRequest(
                pending.scope(), pending.projectKey(), pending.memoryType(), pending.summary(),
                "Business logic belongs in application services and never in controllers.",
                pending.tags(), pending.confidence(), MemoryStatus.ACTIVE, pending.sourceType(),
                pending.sourceRef(), pending.owner(), Map.of("contentHash", "curated-drift-v2"),
                Instant.parse("2026-08-04T09:00:00Z"), pending.expiresAt());

        RaceOutcome<CuratedMemoryUpsertResult> outcome = runPromotionFirstRace(pending, LookupKind.SOURCE_REF,
                repository -> memoryService(repository).upsertCurated(drift, "curated-drift-v2"));

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.result()).isNotNull();
        assertThat(outcome.result().action()).isEqualTo(CuratedMemoryUpsertAction.LOADED);
        assertThat(outcome.result().item().id()).isNotEqualTo(pending.id());
        assertThat(outcome.result().item().status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(outcome.result().item().metadata())
                .containsEntry("revisionOf", pending.id().toString())
                .containsEntry("sourceDrift", true);
        assertPromotedOriginUnchanged(pending);
        assertThat(count("memory_items")).isEqualTo(2);
    }

    @Test
    void statusChangeRacingPromotionCannotDemoteTheLinkedRuleOrigin() throws Exception {
        MemoryItem pending = savePendingRule();

        RaceOutcome<MemoryItem> outcome = runPromotionFirstRace(pending, LookupKind.ID,
                repository -> memoryService(repository).updateStatus(
                        pending.id(), MemoryStatus.STALE, "reviewer", "concurrent stale transition"));

        assertThat(outcome.promotion()).isNotNull();
        assertThat(outcome.result()).isNull();
        assertThat(outcome.failure())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle-managed by rules tools");
        assertPromotedOriginUnchanged(pending);
    }

    @Test
    void confirmEditRollsBackReplacementAndOriginalRejectionWhenRuleApprovalIsBlocked() throws Exception {
        MemoryItem pending = savePendingRule();
        memoryRepository.insertReviewQueue(new ReviewQueueItem(
                UUID.randomUUID(), pending.id(), ReviewReason.PROMOTION_CANDIDATE, ReviewStatus.OPEN,
                Map.of("source", "transaction-regression"), Instant.now(), null));
        MemoryService memoryService = memoryService(memoryRepository);
        MemoryReviewService reviewService = reviewService(memoryRepository);
        MemoryConfirmService target = new MemoryConfirmService(
                memoryService, reviewService, new PiiScrubber(), new MemoryConfirmProperties(0.75));
        MemoryConfirmService confirmService = transactionalProxy(target);

        Transactional contract = MemoryConfirmService.class
                .getMethod("confirm", MemoryConfirmService.ConfirmCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(contract).isNotNull();
        assertThat(contract.noRollbackFor()).contains(MemoryConfirmService.ConfirmValidationException.class);

        assertThatThrownBy(() -> confirmService.confirm(new MemoryConfirmService.ConfirmCommand(
                pending.id(), "edit", true, "Bu duzeltilmis kurali onayliyorum", 0.99,
                "turn-confirm-edit-race", "Controllers must remain thin; business logic belongs in services.",
                "human edited rule", "alex", pending.projectKey())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThat(count("memory_items")).isEqualTo(1);
        assertThat(memoryRepository.findById(pending.id()).orElseThrow().status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(memoryRepository.eventsForMemory(pending.id())).isEmpty();
        assertThat(memoryRepository.reviewQueueForMemory(pending.id()))
                .singleElement()
                .extracting(ReviewQueueItem::status)
                .isEqualTo(ReviewStatus.OPEN);
        assertThat(count("memory_review_queue")).isEqualTo(1);
    }

    private <T> RaceOutcome<T> runPromotionFirstRace(MemoryItem pending, LookupKind lookupKind,
            MemoryMutation<T> mutation) throws Exception {
        CountDownLatch promotionLocked = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        CountDownLatch mutationAttempted = new CountDownLatch(1);
        PausingPromotionMemoryRepository promotionRepository = new PausingPromotionMemoryRepository(
                jdbcTemplate, promotionLocked, releasePromotion);
        RulePromotionService promotionService = promotionService(promotionRepository);
        RulePromotionCandidate candidate = memoryCandidate(pending,
                RuleMemoryContentHash.compute(pending.summary(), pending.text()));
        RulePromotionPreview preview = promotionService.preview(candidate);
        PromotionRequest request = request(candidate, preview);
        AttemptSignallingMemoryRepository mutationRepository = new AttemptSignallingMemoryRepository(
                jdbcTemplate, lookupKind, mutationAttempted);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var promotionFuture = executor.submit(() -> transactionTemplate.execute(
                    status -> promote(promotionService, request)));
            assertThat(promotionLocked.await(10, TimeUnit.SECONDS))
                    .as("promotion acquired the memory row lock")
                    .isTrue();

            var mutationFuture = executor.submit(() -> transactionTemplate.execute(
                    status -> mutation.run(mutationRepository)));
            assertThat(mutationAttempted.await(10, TimeUnit.SECONDS))
                    .as("competing mutation reached its locking lookup")
                    .isTrue();
            assertThat(mutationFuture.isDone())
                    .as("competing mutation must wait while promotion owns the row lock")
                    .isFalse();

            releasePromotion.countDown();
            RuleVersion promoted = promotionFuture.get(20, TimeUnit.SECONDS);
            try {
                return new RaceOutcome<>(promoted, mutationFuture.get(20, TimeUnit.SECONDS), null);
            } catch (java.util.concurrent.ExecutionException failure) {
                return new RaceOutcome<>(promoted, null, failure.getCause());
            }
        } finally {
            releasePromotion.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private RulePromotionService promotionService(MemoryRepository promotionMemoryRepository) {
        DefaultRuleMemoryPromotionPort port = new DefaultRuleMemoryPromotionPort(
                promotionMemoryRepository, jdbcTemplate, new RuleMemoryProjectionOutbox(jdbcTemplate));
        return new RulePromotionService(new JdbcRuleRepository(jdbcTemplate, new ObjectMapper()), port,
                new RulesProperties(true, 20, 256, 4096, 200), new ObjectMapper(),
                List.of(new DiffRegexRuleDetector(new UnifiedDiffParser())));
    }

    private MemoryService memoryService(MemoryRepository repository) {
        RuleMemoryActivationPolicy rulePolicy = MemoryServiceTestFixture.rules(true);
        RuleMemoryLinkLookup linkLookup = new JdbcRuleMemoryLinkLookup(jdbcTemplate);
        return MemoryServiceTestFixture.create(repository, memoryProperties(), event -> {
        }, PolicyEngine.allowAll(), new PiiScrubber(), MemoryServiceTestFixture.noOpVectorIndex(),
                rulePolicy, linkLookup);
    }

    private MemoryReviewService reviewService(MemoryRepository repository) {
        return new MemoryReviewService(repository, MemoryServiceTestFixture.noOpVectorIndex(), event -> {
        }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.rules(true),
                new JdbcRuleMemoryLinkLookup(jdbcTemplate));
    }

    private MemoryConfirmService transactionalProxy(MemoryConfirmService target) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return (MemoryConfirmService) proxyFactory.getProxy();
    }

    private void assertPromotedOriginUnchanged(MemoryItem original) {
        MemoryItem stored = memoryRepository.findById(original.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(stored.summary()).isEqualTo(original.summary());
        assertThat(stored.text()).isEqualTo(original.text());
        assertThat(stored.scope()).isEqualTo(original.scope());
        assertThat(stored.projectKey()).isEqualTo(original.projectKey());
        assertThat(stored.sourceRef()).isEqualTo(original.sourceRef());
        assertThat(new JdbcRuleMemoryLinkLookup(jdbcTemplate).hasLinkedDefinition(original.id())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin_memory_id FROM rule_versions WHERE rule_id = ? AND version = 1",
                UUID.class, promotedRuleId(original.id())))
                .isEqualTo(original.id());
    }

    private UUID promotedRuleId(UUID memoryId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM rule_definitions WHERE origin_memory_id = ?", UUID.class, memoryId);
    }

    private static AiOrchestrationProperties memoryProperties() {
        return new AiOrchestrationProperties(
                1200, 160, 5, "logs/test-memory.jsonl", 384, "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true), new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null, new AiOrchestrationProperties.Memory("memory_episodic_test_384", 1234, 0.42), 365);
    }

    private MemoryItem savePendingRule() {
        MemoryItem pending = pendingRule(MemoryStatus.PENDING_REVIEW, "manual:controller-rule");
        memoryRepository.save(pending);
        return pending;
    }

    private static MemoryItem pendingRule(MemoryStatus status, String sourceRef) {
        Instant now = Instant.parse("2026-08-04T08:00:00Z");
        return new MemoryItem(UUID.randomUUID(), UUID.randomUUID(), MemoryScope.PROJECT,
                "AI_ORCHESTRATION", MemoryType.RULE, "Controllers must stay thin",
                "Business logic belongs in services.", List.of("controller"), 1.0,
                status, MemorySourceType.MANUAL, sourceRef, "alex",
                Map.of(), now, now, null, now, null);
    }

    private RulePromotionCandidate memoryCandidate(MemoryItem pending, String originHash) {
        return new RulePromotionCandidate(pending.id(), originHash, pending.projectKey(),
                "Controllers must stay thin", "Keep adapters at the boundary", RuleEnforcement.CONTEXT,
                false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(
                        BindingKind.PATH_GLOB, "**/*Controller.java")),
                RuleProvenance.HUMAN);
    }

    private PromotionRequest request(RulePromotionCandidate candidate, RulePromotionPreview preview) {
        return new PromotionRequest(candidate, preview.approvalContentHash(), preview.confirmationCardHash(),
                preview.workflowContractVersion(),
                new RuleHumanApprovalEvidence("alex", "turn-42", "Bu exact kuralı onaylıyorum", true, 0.99));
    }

    private RulePromotionCandidate directCandidate(String statement) {
        return new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                statement, "Transport boundary", RuleEnforcement.CONTEXT,
                false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(
                        BindingKind.PATH_GLOB, "**/*Controller.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);
    }

    private static Object awaitOutcome(java.util.concurrent.Future<RuleVersion> future) throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private enum LookupKind {
        ID,
        SOURCE_REF
    }

    @FunctionalInterface
    private interface MemoryMutation<T> {
        T run(MemoryRepository repository);
    }

    private record RaceOutcome<T>(RuleVersion promotion, T result, Throwable failure) {
    }

    private static final class PausingPromotionMemoryRepository extends JdbcMemoryRepository {

        private final CountDownLatch locked;
        private final CountDownLatch release;

        private PausingPromotionMemoryRepository(JdbcTemplate jdbcTemplate, CountDownLatch locked,
                CountDownLatch release) {
            super(jdbcTemplate, new ObjectMapper());
            this.locked = locked;
            this.release = release;
        }

        @Override
        public Optional<MemoryItem> findByIdForUpdate(UUID id) {
            Optional<MemoryItem> item = super.findByIdForUpdate(id);
            locked.countDown();
            await(release, "release promotion memory lock");
            return item;
        }
    }

    private static final class AttemptSignallingMemoryRepository extends JdbcMemoryRepository {

        private final LookupKind lookupKind;
        private final CountDownLatch attempted;

        private AttemptSignallingMemoryRepository(JdbcTemplate jdbcTemplate, LookupKind lookupKind,
                CountDownLatch attempted) {
            super(jdbcTemplate, new ObjectMapper());
            this.lookupKind = lookupKind;
            this.attempted = attempted;
        }

        @Override
        public Optional<MemoryItem> findByIdForUpdate(UUID id) {
            if (lookupKind == LookupKind.ID) {
                attempted.countDown();
            }
            return super.findByIdForUpdate(id);
        }

        @Override
        public Optional<MemoryItem> findBySourceRefForUpdate(String sourceRef) {
            if (lookupKind == LookupKind.SOURCE_REF) {
                attempted.countDown();
            }
            return super.findBySourceRefForUpdate(sourceRef);
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to " + description, e);
        }
    }
}
