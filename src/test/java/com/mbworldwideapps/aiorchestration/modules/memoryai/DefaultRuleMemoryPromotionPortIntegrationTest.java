package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DefaultRuleMemoryPromotionPortIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcMemoryRepository memoryRepository;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        memoryRepository = new JdbcMemoryRepository(jdbcTemplate, new ObjectMapper());
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void activatesPendingMemoryWhenTheCommittedSchemaContainsTheExactPromotionTuple() {
        UUID memoryId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        MemoryItem pending = pendingRule(memoryId);
        memoryRepository.save(pending);
        String originHash = RuleMemoryContentHash.compute(pending.summary(), pending.text());
        String approvalHash = "a".repeat(64);
        String confirmationCardHash = "b".repeat(64);
        DefaultRuleMemoryPromotionPort port = new DefaultRuleMemoryPromotionPort(
                memoryRepository, jdbcTemplate, new RuleMemoryProjectionOutbox(jdbcTemplate));

        MemoryItem activated = transactionTemplate.execute(status -> {
            port.preparePromotionOrigin(memoryId, originHash);
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions
                        (id, origin_memory_id, project_key, current_version, status, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 'active', ?, ?)
                    """, ruleId, memoryId, "AI_ORCHESTRATION", Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("""
                    INSERT INTO rule_versions
                        (rule_id, version, statement, rationale, enforcement, applies_all,
                         detector_type, detector_config, content_hash, origin_content_hash,
                         origin_memory_id, origin_provenance, approved_by, approved_at, human_turn_ref,
                         approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                         workflow_contract_version, created_at)
                    VALUES (?, 1, ?, ?, 'context', false, NULL, NULL, ?, ?, ?, 'human', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ruleId, "Controllers must stay thin", "Boundary rule", "version-hash",
                    originHash, memoryId, "alex", Timestamp.from(now), "turn-1", approvalHash,
                    confirmationCardHash, "c".repeat(64), "rules-authoring-v1", Timestamp.from(now));
            insertProjectScope(ruleId, "AI_ORCHESTRATION", now);
            return port.activatePromotedRule(memoryId, ruleId, 1,
                    originHash, approvalHash, confirmationCardHash);
        });

        assertThat(activated).isNotNull();
        assertThat(activated.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(memoryRepository.findById(memoryId).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(memoryRepository.eventsForMemory(memoryId)).extracting(MemoryEvent::eventType)
                .containsExactly(MemoryEventType.STATUS_CHANGED);
        assertThat(new JdbcRuleMemoryLinkLookup(jdbcTemplate).hasLinkedDefinition(memoryId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rule_memory_projection_work WHERE memory_id = ?",
                Integer.class, memoryId)).isEqualTo(1);

        MemoryVectorIndex vectorIndex = mock(MemoryVectorIndex.class);
        doThrow(new IllegalStateException("qdrant unavailable"))
                .doNothing()
                .when(vectorIndex).upsert(any(MemoryItem.class));
        RuleMemoryProjectionWorker worker = new RuleMemoryProjectionWorker(
                new RuleMemoryProjectionOutbox(jdbcTemplate), memoryRepository, vectorIndex, transactionTemplate);

        worker.poll();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM rule_memory_projection_work WHERE memory_id = ?",
                Integer.class, memoryId)).isEqualTo(1);
        assertProjectionProgress(memoryId, false, false, false);

        makeProjectionWorkAvailable(memoryId);
        worker.poll();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rule_memory_projection_work WHERE memory_id = ?",
                Integer.class, memoryId)).isZero();
        ArgumentCaptor<MemoryItem> projected = ArgumentCaptor.forClass(MemoryItem.class);
        verify(vectorIndex, times(2)).upsert(projected.capture());
        assertThat(projected.getAllValues()).allSatisfy(item -> assertThat(item.metadata())
                .containsEntry("promotedRuleId", ruleId.toString())
                .containsEntry("promotedRuleVersion", 1));
    }

    @Test
    void rollbackModeStillReconcilesPromotedOriginAfterRestart() {
        UUID memoryId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        MemoryItem pending = pendingRule(memoryId);
        memoryRepository.save(pending);
        String originHash = RuleMemoryContentHash.compute(pending.summary(), pending.text());
        String approvalHash = "d".repeat(64);
        String confirmationCardHash = "e".repeat(64);
        DefaultRuleMemoryPromotionPort port = new DefaultRuleMemoryPromotionPort(
                memoryRepository, jdbcTemplate, new RuleMemoryProjectionOutbox(jdbcTemplate));

        transactionTemplate.executeWithoutResult(status -> {
            port.preparePromotionOrigin(memoryId, originHash);
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions
                        (id, origin_memory_id, project_key, current_version, status, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 'active', ?, ?)
                    """, ruleId, memoryId, "AI_ORCHESTRATION", Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("""
                    INSERT INTO rule_versions
                        (rule_id, version, statement, rationale, enforcement, applies_all,
                         detector_type, detector_config, content_hash, origin_content_hash,
                         origin_memory_id, origin_provenance, approved_by, approved_at, human_turn_ref,
                         approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                         workflow_contract_version, created_at)
                    VALUES (?, 1, ?, ?, 'context', false, NULL, NULL, ?, ?, ?, 'human', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ruleId, "Controllers must stay thin", "Boundary rule", "version-hash",
                    originHash, memoryId, "alex", Timestamp.from(now), "turn-rollback", approvalHash,
                    confirmationCardHash, "f".repeat(64), "rules-authoring-v1", Timestamp.from(now));
            insertProjectScope(ruleId, "AI_ORCHESTRATION", now);
            port.activatePromotedRule(memoryId, ruleId, 1, originHash, approvalHash, confirmationCardHash);
        });

        RuleMemoryInjectionFilter rollbackFilter = new RuleMemoryInjectionFilter(
                rules(false), new JdbcRuleMemoryLinkLookup(jdbcTemplate));
        MemoryItem active = memoryRepository.findById(memoryId).orElseThrow();
        assertThat(rollbackFilter.eligibleForAutomaticInjection(List.of(active))).containsExactly(active);

        MemoryVectorIndex vectorIndex = mock(MemoryVectorIndex.class);

        RuleMemoryProjectionWorker restartedWorker = new RuleMemoryProjectionWorker(
                new RuleMemoryProjectionOutbox(jdbcTemplate), memoryRepository, vectorIndex, transactionTemplate);
        restartedWorker.poll();

        ArgumentCaptor<MemoryItem> indexed = ArgumentCaptor.forClass(MemoryItem.class);
        verify(vectorIndex).upsert(indexed.capture());
        assertThat(indexed.getValue().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(indexed.getValue().metadata()).containsEntry("promotedRuleId", ruleId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rule_memory_projection_work WHERE memory_id = ?",
                Integer.class, memoryId)).isZero();
    }

    @Test
    void rollbackClearsPreparedOriginSoTheSameThreadCanRetry() {
        UUID memoryId = UUID.randomUUID();
        MemoryItem pending = pendingRule(memoryId);
        memoryRepository.save(pending);
        String originHash = RuleMemoryContentHash.compute(pending.summary(), pending.text());
        DefaultRuleMemoryPromotionPort port = new DefaultRuleMemoryPromotionPort(
                memoryRepository, jdbcTemplate, new RuleMemoryProjectionOutbox(jdbcTemplate));

        transactionTemplate.executeWithoutResult(status -> {
            port.preparePromotionOrigin(memoryId, originHash);
            assertThat(TransactionSynchronizationManager.hasResource(port)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(TransactionSynchronizationManager.hasResource(port)).isFalse();

        transactionTemplate.executeWithoutResult(status -> {
            port.preparePromotionOrigin(memoryId, originHash);
            assertThat(TransactionSynchronizationManager.hasResource(port)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(TransactionSynchronizationManager.hasResource(port)).isFalse();
    }

    private void insertProjectScope(UUID ruleId, String projectKey, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO rule_scope_assignments
                    (id, rule_id, rule_version, scope_type, project_key, ordinal, created_at)
                VALUES (?, ?, 1, 'project', ?, 0, ?)
                """, UUID.randomUUID(), ruleId, projectKey, Timestamp.from(now));
    }

    private static RulesProperties rules(boolean enabled) {
        return new RulesProperties(enabled, 20, 256, 4096, 200);
    }

    private void assertProjectionProgress(UUID memoryId, boolean vector, boolean graph, boolean semantic) {
        Map<String, Object> progress = jdbcTemplate.queryForMap("""
                SELECT vector_projected_at, graph_marked_at, semantic_marked_at
                FROM rule_memory_projection_work
                WHERE memory_id = ?
                """, memoryId);
        assertThat(progress.get("vector_projected_at") != null).isEqualTo(vector);
        assertThat(progress.get("graph_marked_at") != null).isEqualTo(graph);
        assertThat(progress.get("semantic_marked_at") != null).isEqualTo(semantic);
    }

    private void makeProjectionWorkAvailable(UUID memoryId) {
        jdbcTemplate.update(
                "UPDATE rule_memory_projection_work SET available_at = now() WHERE memory_id = ?", memoryId);
    }

    private static MemoryItem pendingRule(UUID id) {
        Instant now = Instant.parse("2026-08-04T08:00:00Z");
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryType.RULE,
                " Controllers must stay thin ", " Business logic belongs in services. ", List.of("controller"),
                1.0, MemoryStatus.PENDING_REVIEW, MemorySourceType.MANUAL, "manual:" + id, "alex", Map.of(),
                now, now, null, now, null);
    }
}
