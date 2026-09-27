package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcRuleRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private JdbcRuleRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcRuleRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void roundTripsDefinitionVersionAndBindings() {
        UUID ruleId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RuleVersion version = version(ruleId, 1, RuleEnforcement.GATE, "diff_regex",
                Map.of("pattern", "@Autowired\\s+private"), now);
        RuleDefinition definition = new RuleDefinition(ruleId, null, "AI_ORCHESTRATION", 1,
                RuleStatus.ACTIVE, now, now);
        RuleTargetBinding fileBinding = new RuleTargetBinding(UUID.randomUUID(), ruleId, 1, BindingKind.FILE,
                "src/main/java/com/acme/A.java", now);
        RuleTargetBinding globBinding = new RuleTargetBinding(UUID.randomUUID(), ruleId, 1, BindingKind.PATH_GLOB,
                "**/*Controller.java", now);
        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertDefinition(definition);
            repository.insertVersion(version);
            repository.insertBindings(List.of(fileBinding, globBinding));
            repository.insertScopeAssignments(List.of(scope(ruleId, 1, "AI_ORCHESTRATION", now)));
        });

        assertThat(repository.findDefinition(ruleId).orElseThrow()).isEqualTo(definition);
        RuleVersion loaded = repository.findVersion(ruleId, 1).orElseThrow();
        assertThat(loaded).isEqualTo(version);
        assertThat(repository.findBindings(ruleId, 1))
                .containsExactlyInAnyOrder(fileBinding, globBinding);
    }

    @Test
    void retainsImmutableOriginMemoryForEveryRuleVersion() {
        UUID firstMemoryId = insertMemoryItem();
        UUID revisionMemoryId = insertMemoryItem();
        UUID ruleId = UUID.randomUUID();
        Instant now = now();
        RuleVersion first = versionWithOrigin(
                ruleId, 1, firstMemoryId, "a".repeat(64), now);
        RuleVersion revision = versionWithOrigin(
                ruleId, 2, revisionMemoryId, "b".repeat(64), now);

        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertDefinition(new RuleDefinition(ruleId, firstMemoryId, "AI_ORCHESTRATION", 1,
                    RuleStatus.ACTIVE, now, now));
            repository.insertVersion(first);
            repository.insertScopeAssignments(List.of(scope(ruleId, 1, "AI_ORCHESTRATION", now)));
            repository.insertVersion(revision);
            repository.insertScopeAssignments(List.of(scope(ruleId, 2, "AI_ORCHESTRATION", now)));
            assertThat(repository.advanceDefinition(ruleId, 1, 2, revisionMemoryId,
                    "AI_ORCHESTRATION", RuleStatus.ACTIVE)).isTrue();
        });

        assertThat(repository.findVersion(ruleId, 1).orElseThrow().originMemoryId())
                .isEqualTo(firstMemoryId);
        assertThat(repository.findVersion(ruleId, 2).orElseThrow().originMemoryId())
                .isEqualTo(revisionMemoryId);
        assertThat(repository.findDefinition(ruleId).orElseThrow().originMemoryId())
                .isEqualTo(revisionMemoryId);
    }

    @Test
    void findVisibleActiveReturnsProjectAndGlobalActiveOnly() {
        UUID projectRule = insertRule("AI_ORCHESTRATION", RuleStatus.ACTIVE);
        UUID globalRule = insertRule(null, RuleStatus.ACTIVE);
        insertRule("AI_ORCHESTRATION", RuleStatus.DEPRECATED);
        insertRule("OTHER_PROJECT", RuleStatus.ACTIVE);

        List<RuleDefinition> visible = repository.findVisibleActive("AI_ORCHESTRATION");

        assertThat(visible).extracting(RuleDefinition::id)
                .containsExactlyInAnyOrder(projectRule, globalRule);
    }

    @Test
    void findsDefinitionByOriginMemory() {
        UUID memoryId = insertMemoryItem();
        UUID ruleId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertDefinition(new RuleDefinition(ruleId, memoryId, "AI_ORCHESTRATION", 1,
                    RuleStatus.ACTIVE, Instant.now(), Instant.now()));
            repository.insertVersion(version(ruleId, 1, RuleEnforcement.CONTEXT, null, null, now()));
            repository.insertScopeAssignments(List.of(scope(ruleId, 1, "AI_ORCHESTRATION", now())));
        });
        assertThat(repository.findByOriginMemoryId(memoryId).orElseThrow().id()).isEqualTo(ruleId);
        assertThat(repository.findByOriginMemoryId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void advancesVersionPointerWithCompareAndSet() {
        UUID ruleId = insertRule("AI_ORCHESTRATION", RuleStatus.ACTIVE);
        RuleVersion next = version(ruleId, 2, RuleEnforcement.CONTEXT, null, null, now());

        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertVersion(next);
            repository.insertScopeAssignments(List.of(scope(ruleId, 2, "AI_ORCHESTRATION", now())));
            assertThat(repository.advanceDefinition(ruleId, 1, 2, null,
                    "AI_ORCHESTRATION", RuleStatus.ACTIVE)).isTrue();
        });

        assertThat(repository.findDefinition(ruleId).orElseThrow().currentVersion()).isEqualTo(2);
        Boolean staleAdvance = transactionTemplate.execute(tx -> repository.advanceDefinition(ruleId, 1, 2, null,
                "AI_ORCHESTRATION", RuleStatus.ACTIVE));
        assertThat(staleAdvance.booleanValue()).isFalse();
    }

    @Test
    void deprecatesOnlyTheExpectedActiveVersion() {
        UUID ruleId = insertRule("AI_ORCHESTRATION", RuleStatus.ACTIVE);

        Boolean firstDeprecation = transactionTemplate.execute(tx -> repository.deprecateDefinition(ruleId, 1));
        assertThat(firstDeprecation.booleanValue()).isTrue();
        assertThat(repository.findDefinition(ruleId).orElseThrow().status()).isEqualTo(RuleStatus.DEPRECATED);
        Boolean duplicateDeprecation = transactionTemplate.execute(tx -> repository.deprecateDefinition(ruleId, 1));
        assertThat(duplicateDeprecation.booleanValue()).isFalse();
    }

    @Test
    void insertsImmutableLifecycleEvidence() {
        UUID ruleId = insertRule("AI_ORCHESTRATION", RuleStatus.ACTIVE);
        RuleLifecycleEvent event = new RuleLifecycleEvent(UUID.randomUUID(), ruleId, 1,
                RuleLifecycleAction.PROMOTED, "alex", "turn-1", "c".repeat(64), null,
                "d".repeat(64), now());

        transactionTemplate.executeWithoutResult(tx -> repository.insertLifecycleEvent(event));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM rule_lifecycle_events WHERE id = ?", String.class, event.id()))
                .isEqualTo("promoted");
        assertThat(repository.findLifecycleEvent(
                RuleLifecycleAction.PROMOTED, event.approvalContentHash())).contains(event);
        RuleLifecycleEvent replay = new RuleLifecycleEvent(UUID.randomUUID(), ruleId, 1,
                RuleLifecycleAction.PROMOTED, "alex", "turn-2", "e".repeat(64), null,
                event.approvalContentHash(), now());
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> repository.insertLifecycleEvent(replay)))
                .hasMessageContaining("rule_lifecycle_events_approval_uq");
    }

    @Test
    void projectsMaximumVisibleGlobRulesForProjectAndGlobalPromotions() {
        UUID global = insertGlobRule(null);
        insertGlobRule("AI_ORCHESTRATION");
        insertSelectorGlobRule("AI_ORCHESTRATION");
        UUID symbolRule = insertSelectorGlobRule("AI_ORCHESTRATION", SelectorField.SYMBOL, "**Controller");
        insertGlobRule("OTHER_PROJECT");

        assertThat(repository.maxProjectedActiveGlobRules("AI_ORCHESTRATION", null)).isEqualTo(5);
        assertThat(repository.maxProjectedActiveGlobRules(null, null)).isEqualTo(5);
        assertThat(repository.maxProjectedActiveGlobRules(null, global)).isEqualTo(4);
        assertThat(repository.maxProjectedActiveGlobRules("AI_ORCHESTRATION", symbolRule)).isEqualTo(4);
        assertThat(repository.findSelectorGroups(symbolRule, 1).getFirst().predicates().getFirst().field())
                .isEqualTo(SelectorField.SYMBOL);
    }

    @Test
    void globalGlobPromotionAtOneProjectsLimitLeavesAuthorityUnchanged() {
        insertGlobRule("PROJECT_AT_LIMIT");
        RuleMemoryPromotionPort unusedPort = (memoryId, ruleId, ruleVersion, originHash,
                approvalHash, confirmationCardHash) -> {
                    throw new AssertionError("direct policy must not activate memory");
                };
        RulePromotionService service = new RulePromotionService(repository, unusedPort,
                new RulesProperties(true, 20, 256, 4096, 1), new ObjectMapper(), List.of());
        RulePromotionCandidate candidate = new RulePromotionCandidate(null, null, null,
                "Controllers must not contain business logic", "Global policy",
                RuleEnforcement.CONTEXT, false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(
                        BindingKind.PATH_GLOB, "**/*Controller.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionPreview preview = service.preview(candidate);
        PromotionRequest request = new PromotionRequest(candidate, preview.approvalContentHash(),
                preview.confirmationCardHash(), preview.workflowContractVersion(),
                new RuleHumanApprovalEvidence("alex", "turn-global", "Global kuralı onaylıyorum", true, 0.99));

        assertThatThrownBy(() -> transactionTemplate.execute(tx -> service.promote(request)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active glob rule limit");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rule_definitions", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rule_lifecycle_events", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class))
                .isZero();
    }

    @Test
    void roundTripsCanonicalSelectorChecksAndProjectScope() {
        UUID ruleId = UUID.randomUUID();
        Instant now = now();
        UUID groupId = UUID.randomUUID();
        RuleSelectorPredicateDefinition include = new RuleSelectorPredicateDefinition(
                UUID.randomUUID(), groupId, SelectorPolarity.INCLUDE, SelectorField.PATH,
                SelectorOperator.GLOB, List.of("**/*Controller.java"), 0, now);
        RuleSelectorPredicateDefinition exclude = new RuleSelectorPredicateDefinition(
                UUID.randomUUID(), groupId, SelectorPolarity.EXCLUDE, SelectorField.SOURCE_SET,
                SelectorOperator.EQUALS, List.of("TEST"), 1, now);
        RuleSelectorGroupDefinition group = new RuleSelectorGroupDefinition(
                groupId, ruleId, 1, "controllers", 0, List.of(include, exclude), now);
        RuleCheckDefinition check = new RuleCheckDefinition(UUID.randomUUID(), ruleId, 1,
                RuleCheckPhase.PLAN, "forbid_intent", "1", "1", RuleCheckSeverity.REQUIRED,
                Map.of("intentKinds", List.of("BUSINESS_DECISION")), "a".repeat(64), 0, now);
        RuleScopeAssignment assignment = scope(ruleId, 1, "AI_ORCHESTRATION", now);

        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertDefinition(new RuleDefinition(ruleId, null, "AI_ORCHESTRATION", 1,
                    RuleStatus.ACTIVE, now, now));
            repository.insertVersion(version(ruleId, 1, RuleEnforcement.CONTEXT, null, null, now));
            repository.insertSelectorGroups(List.of(group));
            repository.insertCheckDefinitions(List.of(check));
            repository.insertScopeAssignments(List.of(assignment));
        });

        assertThat(repository.findSelectorGroups(ruleId, 1)).containsExactly(group);
        assertThat(repository.findCheckDefinitions(ruleId, 1)).containsExactly(check);
        assertThat(repository.findScopeAssignments(ruleId, 1)).containsExactly(assignment);
    }

    @Test
    void projectEffectiveSequenceBumpDoesNotInvalidateOtherProjectsOrGlobalReach() {
        long legacyBefore = repository.currentChangeSeq();
        long globalBefore = repository.currentGlobalEffectiveSeq();

        RuleEffectiveSequence bumped = transactionTemplate.execute(tx -> {
            repository.lockEffectiveSequences(List.of("PROJECT_B", "PROJECT_A", "PROJECT_A"));
            return repository.bumpEffectiveSequences(false, List.of("PROJECT_A"));
        });

        assertThat(bumped.legacySeq()).isEqualTo(legacyBefore + 1);
        assertThat(bumped.globalSeq()).isEqualTo(globalBefore);
        assertThat(repository.currentProjectEffectiveSeq("PROJECT_A")).isEqualTo(legacyBefore + 1);
        assertThat(repository.currentProjectEffectiveSeq("PROJECT_B")).isEqualTo(legacyBefore);
    }

    @Test
    void postMigrationProjectSequenceIsIsolatedBeforeAndAfterItsFirstLocalMutation() {
        jdbcTemplate.update("UPDATE rules_change_seq SET seq = 7");

        assertThat(repository.currentProjectEffectiveSeq("NEW_PROJECT")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rules_project_effective_seq WHERE project_key = 'NEW_PROJECT'
                """, Integer.class)).isZero();

        transactionTemplate.execute(tx -> {
            repository.lockEffectiveSequences(List.of("OTHER_PROJECT"));
            return repository.bumpEffectiveSequences(false, List.of("OTHER_PROJECT"));
        });
        assertThat(repository.currentProjectEffectiveSeq("NEW_PROJECT")).isZero();

        RuleEffectiveSequence bumped = transactionTemplate.execute(tx -> {
            repository.lockEffectiveSequences(List.of("NEW_PROJECT"));
            return repository.bumpEffectiveSequences(false, List.of("NEW_PROJECT"));
        });

        assertThat(bumped.legacySeq()).isEqualTo(9);
        assertThat(repository.currentProjectEffectiveSeq("NEW_PROJECT")).isOne();
    }

    @Test
    void globalEffectiveSequenceBumpLeavesProjectLocalSequenceUntouched() {
        long legacyBefore = repository.currentChangeSeq();
        transactionTemplate.executeWithoutResult(tx -> repository.lockEffectiveSequences(List.of("PROJECT_A")));
        long projectBefore = repository.currentProjectEffectiveSeq("PROJECT_A");
        long globalBefore = repository.currentGlobalEffectiveSeq();

        RuleEffectiveSequence bumped = transactionTemplate.execute(tx -> {
            repository.lockEffectiveSequences(List.of("PROJECT_A"));
            return repository.bumpEffectiveSequences(true, List.of());
        });

        assertThat(bumped.legacySeq()).isEqualTo(legacyBefore + 1);
        assertThat(bumped.globalSeq()).isEqualTo(globalBefore + 1);
        assertThat(repository.currentProjectEffectiveSeq("PROJECT_A")).isEqualTo(projectBefore);
    }

    @Test
    void ruleVersionDefensivelyCopiesNestedDetectorConfiguration() {
        List<String> flags = new ArrayList<>(List.of("A"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("flags", flags);
        RuleVersion version = version(UUID.randomUUID(), 1, RuleEnforcement.CONTEXT, "diff_regex",
                config, now());

        flags.add("B");
        config.put("pattern", "changed");

        assertThat(version.detectorConfig()).containsOnlyKeys("flags");
        assertThat(version.detectorConfig().get("flags")).isEqualTo(List.of("A"));
        assertThatThrownBy(() -> version.detectorConfig().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scopeAndEffectiveSequenceDomainObjectsRejectAmbiguousOrInvalidValues() {
        UUID ruleId = UUID.randomUUID();

        assertThatThrownBy(() -> RuleScopeAssignment.forProjectKey(
                UUID.randomUUID(), ruleId, 1, "   ", now()))
                .hasMessageContaining("null for global scope or non-blank");
        assertThatThrownBy(() -> new RuleEffectiveSequence(
                1, 1, Map.of("AI_ORCHESTRATION", -1L)))
                .hasMessageContaining("non-negative");
    }

    private UUID insertRule(String projectKey, RuleStatus status) {
        UUID ruleId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            repository.insertDefinition(new RuleDefinition(ruleId, null, projectKey, 1, status,
                    Instant.now(), Instant.now()));
            repository.insertVersion(version(ruleId, 1, RuleEnforcement.CONTEXT, null, null, now()));
            repository.insertScopeAssignments(List.of(scope(ruleId, 1, projectKey, now())));
        });
        return ruleId;
    }

    private UUID insertGlobRule(String projectKey) {
        UUID ruleId = insertRule(projectKey, RuleStatus.ACTIVE);
        transactionTemplate.executeWithoutResult(tx -> repository.insertBindings(List.of(
                new RuleTargetBinding(UUID.randomUUID(), ruleId, 1, BindingKind.PATH_GLOB,
                        "**/*Controller.java", now()))));
        return ruleId;
    }

    private UUID insertSelectorGlobRule(String projectKey) {
        return insertSelectorGlobRule(projectKey, SelectorField.PATH, "**/*Controller.java");
    }

    private UUID insertSelectorGlobRule(String projectKey, SelectorField field, String pattern) {
        UUID ruleId = insertRule(projectKey, RuleStatus.ACTIVE);
        UUID groupId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> repository.insertSelectorGroups(List.of(
                new RuleSelectorGroupDefinition(groupId, ruleId, 1, "controller-selector", 0,
                        List.of(new RuleSelectorPredicateDefinition(UUID.randomUUID(), groupId,
                                SelectorPolarity.INCLUDE, field, SelectorOperator.GLOB,
                                List.of(pattern), 0, now())), now()))));
        return ruleId;
    }

    private RuleVersion version(UUID ruleId, int number, RuleEnforcement enforcement, String detectorType,
            Map<String, Object> detectorConfig, Instant now) {
        return new RuleVersion(ruleId, number, "Controllers must not contain business logic.",
                "Keep web adapters thin.", enforcement, false, detectorType, detectorConfig,
                detectorType == null ? null : "d".repeat(64),
                "hash-" + ruleId + "-" + number, null, null, "approval-" + ruleId + "-" + number,
                "card-" + ruleId + "-" + number, "human-" + ruleId + "-" + number,
                RulePromotionService.WORKFLOW_CONTRACT_VERSION, RuleProvenance.HUMAN, "alex", now,
                "turn-1", now);
    }

    private RuleVersion versionWithOrigin(UUID ruleId, int number, UUID originMemoryId,
            String originContentHash, Instant now) {
        return new RuleVersion(ruleId, number, "Controllers must not contain business logic.",
                "Keep web adapters thin.", RuleEnforcement.CONTEXT, false, null, null,
                null, "hash-" + ruleId + "-" + number, originMemoryId, originContentHash,
                "approval-" + ruleId + "-" + number, "card-" + ruleId + "-" + number,
                "human-" + ruleId + "-" + number, RulePromotionService.WORKFLOW_CONTRACT_VERSION,
                RuleProvenance.HUMAN, "alex", now, "turn-1", now);
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private RuleScopeAssignment scope(UUID ruleId, int version, String projectKey, Instant now) {
        return RuleScopeAssignment.forProjectKey(UUID.randomUUID(), ruleId, version, projectKey, now);
    }

    private UUID insertMemoryItem() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO memory_items (id, vector_id, scope, project_key, memory_type, summary, text,
                    confidence, status, source_type)
                VALUES (?, ?, 'project', 'AI_ORCHESTRATION', 'rule', 'summary', 'text', 0.9, 'active', 'manual')
                """, id, UUID.randomUUID());
        return id;
    }
}
