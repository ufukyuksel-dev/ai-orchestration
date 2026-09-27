package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
class RulesSchemaMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        flyway(null, true).clean();
        flyway(null, false).migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void createsRuleAuthorityTables() {
        for (String table : new String[] {"rule_definitions", "rule_versions", "rule_target_bindings",
                "rules_change_seq", "rule_lifecycle_events", "rule_memory_projection_work",
                "rule_selector_groups", "rule_selector_predicates", "rule_check_definitions",
                "rule_scope_assignments", "rules_global_effective_seq", "rules_project_effective_seq"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
            assertThat(count).as("table %s", table).isEqualTo(1);
        }
        Long seq = jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class);
        assertThat(seq).isZero();
    }

    @Test
    void upgradesV46RulesIntoImmutableScopesAndSafeEffectiveSequenceBaselines() {
        flyway("46", true).clean();
        flyway("46", false).migrate();
        jdbcTemplate.update("UPDATE rules_change_seq SET seq = 17");
        UUID globalRuleId = insertRuleDefinitionForProject(null);
        UUID projectRuleId = insertRuleDefinitionForProject("AI_ORCHESTRATION");
        insertAdditionalRuleVersion(projectRuleId, 2);
        jdbcTemplate.update(
                "UPDATE rule_definitions SET current_version = 2 WHERE id = ?",
                projectRuleId);
        jdbcTemplate.update("""
                INSERT INTO scanner_project_roots (project_key, root_path)
                VALUES ('HCE_CMS', '/workspace/card-cms')
                """);

        flyway(null, false).migrate();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT scope_type, project_key
                FROM rule_scope_assignments
                WHERE rule_id = ? AND rule_version = 1
                """, globalRuleId))
                .containsEntry("scope_type", "global")
                .containsEntry("project_key", null);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT scope_type, project_key
                FROM rule_scope_assignments
                WHERE rule_id = ? AND rule_version = 1
                """, projectRuleId))
                .containsEntry("scope_type", "project")
                .containsEntry("project_key", "AI_ORCHESTRATION");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT scope_type, project_key
                FROM rule_scope_assignments
                WHERE rule_id = ? AND rule_version = 2
                """, projectRuleId))
                .containsEntry("scope_type", "project")
                .containsEntry("project_key", "AI_ORCHESTRATION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM rules_global_effective_seq", Long.class)).isEqualTo(17L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT project_key, seq FROM rules_project_effective_seq ORDER BY project_key
                """))
                .extracting(row -> row.get("project_key"), row -> row.get("seq"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AI_ORCHESTRATION", 17L),
                        org.assertj.core.groups.Tuple.tuple("HCE_CMS", 17L));
    }

    @Test
    void planSelectorAndCheckerRowsAreVersionBoundConstrainedAndAppendOnly() {
        UUID ruleId = insertRuleDefinition();
        UUID groupId = UUID.randomUUID();
        UUID predicateId = UUID.randomUUID();
        UUID checkId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_groups
                        (id, rule_id, rule_version, group_key, ordinal)
                    VALUES (?, ?, 1, 'controller-main', 0)
                    """, groupId, ruleId);
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_predicates
                        (id, group_id, polarity, field, operator, values, ordinal)
                    VALUES (?, ?, 'include', 'role', 'in', '["web.http-controller"]'::jsonb, 0)
                    """, predicateId, groupId);
        });
        jdbcTemplate.update("""
                INSERT INTO rule_check_definitions
                    (id, rule_id, rule_version, phase, checker_type, implementation_version,
                     config_schema_version, severity, config, checker_contract_hash, ordinal)
                VALUES (?, ?, 1, 'plan', 'forbid_intent', '1', '1', 'required',
                    '{"intentKinds":["BUSINESS_DECISION"]}'::jsonb, ?, 0)
                """, checkId, ruleId, "c".repeat(64));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("""
                        INSERT INTO rule_selector_groups
                            (id, rule_id, rule_version, group_key, ordinal)
                        VALUES (?, ?, 1, 'empty', 1)
                        """, UUID.randomUUID(), ruleId)))
                .satisfies(failure -> assertThat(rootCause(failure))
                        .hasMessageContaining("selector group requires at least one include predicate"));
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            UUID excludeOnlyGroup = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_groups
                        (id, rule_id, rule_version, group_key, ordinal)
                    VALUES (?, ?, 1, 'exclude-only', 1)
                    """, excludeOnlyGroup, ruleId);
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_predicates
                        (id, group_id, polarity, field, operator, values, ordinal)
                    VALUES (?, ?, 'exclude', 'source_set', 'in', '["test"]'::jsonb, 0)
                    """, UUID.randomUUID(), excludeOnlyGroup);
        })).satisfies(failure -> assertThat(rootCause(failure))
                .hasMessageContaining("selector group requires at least one include predicate"));
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_selector_predicates
                    (id, group_id, polarity, field, operator, values, ordinal)
                VALUES (?, ?, 'include', 'role', 'in', '{}'::jsonb, 1)
                """, UUID.randomUUID(), groupId))
                .hasMessageContaining("rule_selector_predicates_values_array_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_check_definitions
                    (id, rule_id, rule_version, phase, checker_type, implementation_version,
                     config_schema_version, severity, config, checker_contract_hash, ordinal)
                VALUES (?, ?, 1, 'plan', 'unknown', '1', '1', 'required', '[]'::jsonb, ?, 1)
                """, UUID.randomUUID(), ruleId, "d".repeat(64)))
                .hasMessageContaining("rule_check_definitions_config_object_check");
        jdbcTemplate.update("""
                INSERT INTO rule_check_definitions
                    (id, rule_id, rule_version, phase, checker_type, implementation_version,
                     config_schema_version, severity, config, checker_contract_hash, ordinal)
                VALUES (?, ?, 1, 'plan', 'future_checker', '1', '1', 'required', '{}'::jsonb, ?, 1)
                """, UUID.randomUUID(), ruleId, "d".repeat(64));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_selector_groups SET group_key = 'changed' WHERE id = ?", groupId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM rule_selector_predicates WHERE id = ?", predicateId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_check_definitions SET severity = 'advisory' WHERE id = ?", checkId))
                .hasMessageContaining("immutable");
    }

    @Test
    void effectiveSequencesAreNonNegativeAndIndependent() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM rules_global_effective_seq", Long.class)).isZero();
        jdbcTemplate.update("UPDATE rules_global_effective_seq SET seq = seq + 1");
        jdbcTemplate.update("""
                INSERT INTO rules_project_effective_seq (project_key, seq)
                VALUES ('PROJECT_A', 1), ('PROJECT_B', 0)
                """);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT seq FROM rules_project_effective_seq WHERE project_key = 'PROJECT_A'
                """, Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT seq FROM rules_project_effective_seq WHERE project_key = 'PROJECT_B'
                """, Long.class)).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rules_global_effective_seq SET seq = -1"))
                .hasMessageContaining("rules_global_effective_seq_nonnegative_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE rules_project_effective_seq SET seq = -1 WHERE project_key = 'PROJECT_A'
                """))
                .hasMessageContaining("rules_project_effective_seq_nonnegative_check");
    }

    @Test
    void postV47RuleVersionsRequireScopeInTheSameTransaction() {
        UUID missingScopeRuleId = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                insertRuleDefinitionRows(missingScopeRuleId, "AI_ORCHESTRATION")))
                .satisfies(failure -> assertThat(rootCause(failure))
                        .hasMessageContaining("rule version requires at least one scope assignment"));

        UUID scopedRuleId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            insertRuleDefinitionRows(scopedRuleId, "AI_ORCHESTRATION");
            insertDefaultScopeIfSupported(scopedRuleId, 1, "AI_ORCHESTRATION");
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_scope_assignments
                WHERE rule_id = ? AND rule_version = 1
                """, Integer.class, scopedRuleId)).isOne();
    }

    @Test
    void versionScopeMustAgreeWithTheLegacyDefinitionProjection() {
        UUID projectRuleId = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertRuleDefinitionRows(projectRuleId, "PROJECT_A");
            insertDefaultScopeIfSupported(projectRuleId, 1, "PROJECT_B");
        })).satisfies(failure -> assertThat(rootCause(failure))
                .hasMessageContaining("project scope must match rule definition project"));

        UUID globalRuleId = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertRuleDefinitionRows(globalRuleId, null);
            insertDefaultScopeIfSupported(globalRuleId, 1, "PROJECT_A");
        })).satisfies(failure -> assertThat(rootCause(failure))
                .hasMessageContaining("project scope must match rule definition project"));

        UUID projectAsGlobalRuleId = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertRuleDefinitionRows(projectAsGlobalRuleId, "PROJECT_A");
            insertDefaultScopeIfSupported(projectAsGlobalRuleId, 1, null);
        })).satisfies(failure -> assertThat(rootCause(failure))
                .hasMessageContaining("non-project scope requires a global rule definition projection"));
    }

    @Test
    void gateRuleMayUseAValidatedSelectorGroupInsteadOfAnExactBinding() {
        UUID ruleId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions (id, project_key, current_version, status)
                    VALUES (?, 'AI_ORCHESTRATION', 1, 'active')
                    """, ruleId);
            jdbcTemplate.update("""
                    INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all,
                        detector_type, detector_config, detector_contract_hash, content_hash,
                        origin_provenance, approved_by, approved_at, human_turn_ref,
                        approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                        workflow_contract_version)
                    VALUES (?, 1, 'Controller gate', 'gate', false, 'diff_regex',
                        '{"pattern":"Controller"}'::jsonb, ?, 'selector-gate-hash',
                        'direct_human_policy', 'alex', now(), 'turn-selector-gate',
                        'approval-selector-gate', 'card-selector-gate', 'raw-selector-gate',
                        'rule-authority/v2')
                    """, ruleId, "e".repeat(64));
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_groups
                        (id, rule_id, rule_version, group_key, ordinal)
                    VALUES (?, ?, 1, 'controller', 0)
                    """, groupId, ruleId);
            jdbcTemplate.update("""
                    INSERT INTO rule_selector_predicates
                        (id, group_id, polarity, field, operator, values, ordinal)
                    VALUES (?, ?, 'include', 'path', 'glob', '["**/*Controller.java"]'::jsonb, 0)
                    """, UUID.randomUUID(), groupId);
            insertDefaultScopeIfSupported(ruleId, 1, "AI_ORCHESTRATION");
        });

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_target_bindings
                WHERE rule_id = ? AND rule_version = 1
                """, Integer.class, ruleId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_selector_groups
                WHERE rule_id = ? AND rule_version = 1
                """, Integer.class, ruleId)).isOne();
    }

    @Test
    void publishedV42MigrationChecksumRemainsStable() throws Exception {
        try (InputStream migration = getClass().getResourceAsStream(
                "/db/migration/V42__rule_definitions.sql")) {
            assertThat(migration).isNotNull();
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes()));
            assertThat(digest).isEqualTo(
                    "47254024d88d5b4e8dc9e2aecfcda060574ec0322a328409fc673a84647400d9");
        }
    }

    @Test
    void upgradesExistingV42AuthorityRowsAndMarksLegacyApprovalEvidence() {
        flyway("42", true).clean();
        flyway("42", false).migrate();

        UUID memoryId = insertMemoryItem();
        UUID ruleId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions
                        (id, origin_memory_id, project_key, current_version, status)
                    VALUES (?, ?, 'AI_ORCHESTRATION', 1, 'active')
                    """, ruleId, memoryId);
            jdbcTemplate.update("""
                    INSERT INTO rule_versions
                        (rule_id, version, statement, enforcement, applies_all, detector_type,
                         detector_config, content_hash, origin_content_hash, origin_provenance,
                         approved_by, approved_at, human_turn_ref)
                    VALUES (?, 1, 'Legacy V42 rule', 'context', false, 'diff_regex',
                        '{"pattern":"legacy"}'::jsonb, 'legacy-content-hash', ?, 'human',
                        'alex', now(), 'legacy-turn')
                    """, ruleId, "a".repeat(64));
        });

        flyway(null, false).migrate();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT origin_memory_id FROM rule_versions WHERE rule_id = ? AND version = 1
                """, UUID.class, ruleId)).isEqualTo(memoryId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT detector_contract_hash FROM rule_versions WHERE rule_id = ? AND version = 1
                """, String.class, ruleId)).isEqualTo("0".repeat(64));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT workflow_contract_version FROM rule_versions WHERE rule_id = ? AND version = 1
                """, String.class, ruleId)).isEqualTo("rules-v42-legacy");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_versions SET statement = 'changed' WHERE rule_id = ?", ruleId))
                .hasMessageContaining("immutable");
    }

    @Test
    void upgradesEveryV42VersionFromItsOwnPromotedMemoryEvidenceAndBackfillsCleanupWork() {
        flyway("42", true).clean();
        flyway("42", false).migrate();

        UUID ruleId = UUID.randomUUID();
        UUID firstOrigin = insertMemoryItem();
        UUID secondOrigin = insertMemoryItem();
        jdbcTemplate.update("""
                UPDATE memory_items
                SET metadata = jsonb_build_object(
                    'promotedRuleId', ?::text, 'promotedRuleVersion', 1)
                WHERE id = ?
                """, ruleId.toString(), firstOrigin);
        jdbcTemplate.update("""
                UPDATE memory_items
                SET metadata = jsonb_build_object(
                    'promotedRuleId', ?::text, 'promotedRuleVersion', 2)
                WHERE id = ?
                """, ruleId.toString(), secondOrigin);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions
                        (id, origin_memory_id, project_key, current_version, status)
                    VALUES (?, ?, 'AI_ORCHESTRATION', 1, 'active')
                    """, ruleId, firstOrigin);
            insertLegacyV42Version(ruleId, 1, "a".repeat(64));
            insertLegacyV42Version(ruleId, 2, "b".repeat(64));
            jdbcTemplate.update("""
                    UPDATE rule_definitions
                    SET origin_memory_id = ?, current_version = 2
                    WHERE id = ?
                    """, secondOrigin, ruleId);
        });

        flyway(null, false).migrate();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT origin_memory_id FROM rule_versions
                WHERE rule_id = ? AND version = 1
                """, UUID.class, ruleId)).isEqualTo(firstOrigin);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT origin_memory_id FROM rule_versions
                WHERE rule_id = ? AND version = 2
                """, UUID.class, ruleId)).isEqualTo(secondOrigin);
        assertThat(jdbcTemplate.queryForList("""
                SELECT memory_id, rule_version
                FROM rule_memory_projection_work
                WHERE rule_id = ?
                ORDER BY rule_version
                """, ruleId))
                .extracting(row -> row.get("memory_id"), row -> row.get("rule_version"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(firstOrigin, 1),
                        org.assertj.core.groups.Tuple.tuple(secondOrigin, 2));
    }

    @Test
    void ruleVersionsRejectUpdateAndDeleteAtDatabaseLevel() {
        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_versions SET statement = 'changed' WHERE rule_id = ?", ruleId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM rule_versions WHERE rule_id = ?", ruleId))
                .hasMessageContaining("immutable");
    }

    @Test
    void ruleTargetBindingsRejectUpdateAndDeleteAtDatabaseLevel() {
        UUID ruleId = insertRuleWithVersion();
        jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'file', 'src/main/java/Example.java')
                """, UUID.randomUUID(), ruleId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_target_bindings SET target_key = 'x' WHERE rule_id = ?", ruleId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM rule_target_bindings WHERE rule_id = ?", ruleId))
                .hasMessageContaining("immutable");
    }

    @Test
    void lifecycleEventsEnforceEvidenceForeignKeyActionsAndImmutability() {
        UUID ruleId = insertRuleWithVersion();
        UUID promotedEventId = insertLifecycleEvent(ruleId, 1, "promoted", "alex", "turn-1",
                "raw-text-hash", "approval-hash");
        insertLifecycleEvent(ruleId, 1, "deprecated", "alex", "turn-2", "raw-text-hash-2", "approval-hash-2");

        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 99, "promoted", "alex", "turn-1", "raw-text-hash", "approval-hash-fk"))
                .hasMessageContaining("rule_lifecycle_events_rule_version_fk");
        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 1, "updated", "alex", "turn-1", "raw-text-hash", "approval-hash"))
                .hasMessageContaining("rule_lifecycle_events_action_check");
        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 1, "promoted", "   ", "turn-1", "raw-text-hash", "approval-hash-actor"))
                .hasMessageContaining("rule_lifecycle_events_actor_nonblank_check");
        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 1, "promoted", "alex", "   ", "raw-text-hash", "approval-hash-turn"))
                .hasMessageContaining("rule_lifecycle_events_human_turn_ref_nonblank_check");
        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 1, "promoted", "alex", "turn-1", "   ", "approval-hash-raw"))
                .hasMessageContaining("rule_lifecycle_events_human_raw_text_hash_nonblank_check");
        assertThatThrownBy(() -> insertLifecycleEvent(
                ruleId, 1, "promoted", "alex", "turn-1", "raw-text-hash", "   "))
                .hasMessageContaining("rule_lifecycle_events_approval_content_hash_nonblank_check");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE rule_lifecycle_events SET actor = 'other' WHERE id = ?", promotedEventId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM rule_lifecycle_events WHERE id = ?", promotedEventId))
                .hasMessageContaining("immutable");
    }

    @Test
    void gateEnforcementRequiresNonBlankDetectorAndForbidsAppliesAll() {
        UUID ruleId = insertRuleDefinition();
        assertThatThrownBy(() -> insertVersion(ruleId, "gate", null, false))
                .hasMessageContaining("rule_versions_gate_detector_check");
        assertThatThrownBy(() -> insertVersion(ruleId, "gate", "   ", false))
                .hasMessageContaining("rule_versions_gate_detector_check");
        assertThatThrownBy(() -> insertVersion(ruleId, "gate", "diff_regex", true))
                .hasMessageContaining("rule_versions_gate_targeted_check");
    }

    @Test
    void gateVersionRequiresAtLeastOneBindingAtCommit() {
        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                insertVersion(ruleId, "gate", "diff_regex", false)))
                .satisfies(failure -> assertThat(rootCause(failure))
                        .hasMessageContaining("gate rule version requires at least one target binding"));

        transactionTemplate.executeWithoutResult(status -> {
            insertVersion(ruleId, "gate", "diff_regex", false);
            jdbcTemplate.update("""
                    INSERT INTO rule_target_bindings
                        (id, rule_id, rule_version, binding_kind, target_key)
                    VALUES (?, ?, 2, 'path_glob', '**/*Controller.java')
                    """, UUID.randomUUID(), ruleId);
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_target_bindings
                WHERE rule_id = ? AND rule_version = 2
                """, Integer.class, ruleId)).isOne();
    }

    @Test
    void requiresPositiveVersionsAndNonBlankRuleAuthorityText() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_definitions (id, project_key, current_version, status)
                VALUES (?, 'AI_ORCHESTRATION', 0, 'active')
                """, UUID.randomUUID()))
                .hasMessageContaining("rule_definitions_current_version_positive_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_definitions (id, project_key, current_version, status)
                VALUES (?, '   ', 1, 'active')
                """, UUID.randomUUID()))
                .hasMessageContaining("rule_definitions_project_key_nonblank_check");

        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> insertRawVersion(ruleId, 0, "statement", null, "hash", "alex", "turn-1"))
                .hasMessageContaining("rule_versions_version_positive_check");
        assertThatThrownBy(() -> insertRawVersion(ruleId, 2, "   ", null, "hash", "alex", "turn-1"))
                .hasMessageContaining("rule_versions_statement_nonblank_check");
        assertThatThrownBy(() -> insertRawVersion(ruleId, 2, "statement", null, "   ", "alex", "turn-1"))
                .hasMessageContaining("rule_versions_content_hash_nonblank_check");
        assertThatThrownBy(() -> insertRawVersion(ruleId, 2, "statement", null, "hash", "   ", "turn-1"))
                .hasMessageContaining("rule_versions_approved_by_nonblank_check");
        assertThatThrownBy(() -> insertRawVersion(ruleId, 2, "statement", null, "hash", "alex", "   "))
                .hasMessageContaining("rule_versions_human_turn_ref_nonblank_check");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE rules_change_seq SET seq = -1"))
                .hasMessageContaining("rules_change_seq_nonnegative_check");
    }

    @Test
    void detectorConfigMustBeAJsonObject() {
        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> insertRawVersion(ruleId, 2, "statement", "[]", "hash", "alex", "turn-1"))
                .hasMessageContaining("rule_versions_detector_config_object_check");
    }

    @Test
    void detectorContractHashIsPairedWithDetectorTypeAndConfigMayRemainNull() {
        UUID ruleId = insertRuleWithVersion();

        assertThatThrownBy(() -> insertVersionWithDetectorContract(ruleId, 2, "diff_regex", null))
                .hasMessageContaining("rule_versions_detector_contract_pair_check");
        assertThatThrownBy(() -> insertVersionWithDetectorContract(ruleId, 2, null, "c".repeat(64)))
                .hasMessageContaining("rule_versions_detector_contract_pair_check");
        assertThatThrownBy(() -> insertVersionWithDetectorContract(ruleId, 2, "diff_regex", "   "))
                .hasMessageContaining("rule_versions_detector_contract_hash_nonblank_check");

        String contractHash = "b".repeat(64);
        insertVersionWithDetectorContract(ruleId, 2, "diff_regex", contractHash);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT detector_contract_hash FROM rule_versions WHERE rule_id = ? AND version = 2
                """, String.class, ruleId)).isEqualTo(contractHash);
    }

    @Test
    void ruleVersionApprovalAuthorityEvidenceIsRequiredAndNonBlank() {
        for (String column : new String[] {"approval_content_hash", "confirmation_card_hash",
                "human_raw_text_hash", "workflow_contract_version"}) {
            String nullable = jdbcTemplate.queryForObject("""
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'rule_versions' AND column_name = ?
                    """, String.class, column);
            assertThat(nullable).as("column %s", column).isEqualTo("NO");
        }

        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> insertVersionWithApprovalEvidence(
                ruleId, 2, "   ", "card-hash", "raw-text-hash", "rules-v1"))
                .hasMessageContaining("rule_versions_approval_content_hash_nonblank_check");
        assertThatThrownBy(() -> insertVersionWithApprovalEvidence(
                ruleId, 2, "approval-hash", "   ", "raw-text-hash", "rules-v1"))
                .hasMessageContaining("rule_versions_confirmation_card_hash_nonblank_check");
        assertThatThrownBy(() -> insertVersionWithApprovalEvidence(
                ruleId, 2, "approval-hash", "card-hash", "   ", "rules-v1"))
                .hasMessageContaining("rule_versions_human_raw_text_hash_nonblank_check");
        assertThatThrownBy(() -> insertVersionWithApprovalEvidence(
                ruleId, 2, "approval-hash", "card-hash", "raw-text-hash", "   "))
                .hasMessageContaining("rule_versions_workflow_contract_version_nonblank_check");
    }

    @Test
    void ruleVersionsRetainPairedOriginMemoryEvidenceAndDirectPoliciesCannotClaimIt() {
        UUID ruleId = insertRuleWithVersion();
        UUID memoryId = insertMemoryItem();

        assertThatThrownBy(() -> insertVersionWithOrigin(
                ruleId, 2, memoryId, null, "human"))
                .hasMessageContaining("rule_versions_origin_evidence_pair_check");
        assertThatThrownBy(() -> insertVersionWithOrigin(
                ruleId, 2, memoryId, "a".repeat(64), "direct_human_policy"))
                .hasMessageContaining("rule_versions_direct_origin_check");
        assertThatThrownBy(() -> insertVersionWithOrigin(
                ruleId, 2, UUID.randomUUID(), "a".repeat(64), "human"))
                .hasMessageContaining("rule_versions_origin_memory_fk");

        insertVersionWithOrigin(ruleId, 2, memoryId, "a".repeat(64), "human");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT origin_memory_id FROM rule_versions WHERE rule_id = ? AND version = 2
                """, UUID.class, ruleId)).isEqualTo(memoryId);
    }

    @Test
    void linkedRuleOriginMemoryAuthorityIsImmutableButPromotionStateRemainsMutable() {
        UUID firstMemoryId = insertMemoryItem();
        UUID revisionMemoryId = insertMemoryItem();
        UUID ruleId = insertRuleDefinition(firstMemoryId);
        transactionTemplate.executeWithoutResult(status -> {
            insertVersionWithOrigin(ruleId, 2, revisionMemoryId, "b".repeat(64), "human");
            jdbcTemplate.update("""
                    UPDATE rule_definitions
                    SET current_version = 2, origin_memory_id = ?
                    WHERE id = ?
                    """, revisionMemoryId, ruleId);
        });

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE memory_items SET summary = 'mutated evidence' WHERE id = ?", firstMemoryId))
                .hasMessageContaining("linked rule origin memory authority is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE memory_items SET project_key = 'OTHER_PROJECT' WHERE id = ?", revisionMemoryId))
                .hasMessageContaining("linked rule origin memory authority is immutable");

        jdbcTemplate.update("""
                UPDATE memory_items
                SET status = 'stale', metadata = '{"promotedRuleVersion": 2}'::jsonb, updated_at = now()
                WHERE id = ?
                """, firstMemoryId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM memory_items WHERE id = ?", String.class, firstMemoryId))
                .isEqualTo("stale");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT metadata ->> 'promotedRuleVersion' FROM memory_items WHERE id = ?
                """, String.class, firstMemoryId)).isEqualTo("2");
    }

    @Test
    void ruleMemoryProjectionWorkIsForeignKeyBoundRetryableAndMutable() {
        UUID memoryId = insertMemoryItem();
        UUID ruleId = insertRuleDefinition(memoryId);
        UUID unrelatedMemoryId = insertMemoryItem();

        assertThatThrownBy(() -> insertProjectionWork(UUID.randomUUID(), ruleId, 1))
                .hasMessageContaining("rule_memory_projection_work_memory_fk");
        assertThatThrownBy(() -> insertProjectionWork(memoryId, ruleId, 99))
                .hasMessageContaining("rule_memory_projection_work_rule_version_fk");
        assertThatThrownBy(() -> insertProjectionWork(unrelatedMemoryId, ruleId, 1))
                .hasMessageContaining("rule_memory_projection_work_rule_version_fk");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_memory_projection_work
                    (memory_id, rule_id, rule_version, attempt_count)
                VALUES (?, ?, 1, -1)
                """, memoryId, ruleId))
                .hasMessageContaining("rule_memory_projection_work_attempt_nonnegative_check");

        insertProjectionWork(memoryId, ruleId, 1);
        jdbcTemplate.update("""
                INSERT INTO rule_memory_projection_work
                    (memory_id, rule_id, rule_version, requested_at, available_at)
                VALUES (?, ?, 1, now(), now() + interval '1 minute')
                ON CONFLICT (memory_id) DO UPDATE
                SET rule_id = EXCLUDED.rule_id,
                    rule_version = EXCLUDED.rule_version,
                    requested_at = EXCLUDED.requested_at,
                    available_at = EXCLUDED.available_at,
                    attempt_count = 0,
                    last_error = NULL
                """, memoryId, ruleId);
        jdbcTemplate.update("""
                UPDATE rule_memory_projection_work
                SET attempt_count = attempt_count + 1,
                    available_at = now() + interval '5 minutes',
                    last_error = 'temporary failure'
                WHERE memory_id = ?
                """, memoryId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM rule_memory_projection_work WHERE memory_id = ?
                """, Integer.class, memoryId)).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM rule_memory_projection_work WHERE memory_id = ?", memoryId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rule_memory_projection_work", Integer.class)).isZero();
    }

    @Test
    void currentVersionMustReferenceExistingVersionAtCommit() {
        UUID ruleId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_definitions (id, project_key, current_version, status)
                VALUES (?, 'AI_ORCHESTRATION', 1, 'active')
                """, ruleId))
                .hasMessageContaining("rule_definitions_current_version_fk");
    }

    @Test
    void bindingConstraintsRejectInvalidOrDuplicateTargets() {
        UUID ruleId = insertRuleWithVersion();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 99, 'file', 'src/Unknown.java')
                """, UUID.randomUUID(), ruleId))
                .hasMessageContaining("rule_target_bindings_rule_id_rule_version_fkey");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'module', 'core')
                """, UUID.randomUUID(), ruleId))
                .hasMessageContaining("rule_target_bindings_kind_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'file', '   ')
                """, UUID.randomUUID(), ruleId))
                .hasMessageContaining("rule_target_bindings_target_key_nonblank_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'path_glob', ?)
                """, UUID.randomUUID(), ruleId, "x".repeat(257)))
                .hasMessageContaining("rule_target_bindings_glob_len_check");

        jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'file', 'src/Example.java')
                """, UUID.randomUUID(), ruleId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO rule_target_bindings (id, rule_id, rule_version, binding_kind, target_key)
                VALUES (?, ?, 1, 'file', 'src/Example.java')
                """, UUID.randomUUID(), ruleId))
                .hasMessageContaining("rule_target_bindings_unique");
    }

    @Test
    void rulesScopeMigrationGrantsCapabilitiesToExistingActiveKeysWithoutDuplicates() {
        flyway("44", true).clean();
        flyway("44", false).migrate();

        UUID reader = insertApiKey(false, "memory.read");
        UUID writer = insertApiKey(false, "memory.read", "memory.write");
        UUID confirmer = insertApiKey(false,
                "memory.read", "memory.write", "memory.human_confirmed_approve");
        UUID admin = insertApiKey(false, "memory.read", "memory.write", "memory.admin");
        UUID revoked = insertApiKey(true,
                "memory.read", "memory.write", "memory.human_confirmed_approve");
        UUID preGranted = insertApiKey(false, "memory.read", "memory.write", "memory.human_confirmed_approve",
                "rules.read", "rules.write", "rules.confirm", "rules.global.confirm");

        flyway("45", false).migrate();

        assertScopes(reader, true, false, false, false);
        assertScopes(writer, true, true, false, false);
        assertScopes(confirmer, true, true, true, true);
        assertScopes(admin, true, true, true, true);
        assertScopes(revoked, false, false, false, false);
        assertScopes(preGranted, true, true, true, true);
        for (String scope : new String[] {"rules.read", "rules.write", "rules.confirm", "rules.global.confirm"}) {
            assertThat(scopeCount(preGranted, scope)).as("scope %s", scope).isEqualTo(1);
        }
    }

    @Test
    void deletingOriginMemoryClearsCurrentPointerButRetainsVersionHistory() {
        UUID memoryId = insertMemoryItem();
        UUID ruleId = insertRuleDefinition(memoryId);
        insertVersion(ruleId, "context", null, false);
        assertThatThrownBy(() -> insertRuleDefinition(memoryId))
                .hasMessageContaining("rule_definitions_origin_memory_uq");
        jdbcTemplate.update("DELETE FROM memory_items WHERE id = ?", memoryId);
        UUID origin = jdbcTemplate.queryForObject(
                "SELECT origin_memory_id FROM rule_definitions WHERE id = ?", UUID.class, ruleId);
        assertThat(origin).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT origin_memory_id FROM rule_versions WHERE rule_id = ? AND version = 1
                """, UUID.class, ruleId)).isEqualTo(memoryId);
    }

    private Flyway flyway(String targetVersion, boolean cleanEnabled) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        if (cleanEnabled) {
            configuration.cleanDisabled(false);
        }
        return configuration.load();
    }

    private UUID insertContextTrace(UUID taskId) {
        UUID traceId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO context_traces (id, task_id, project_key, role, query_hash, context_mode,
                    provider, token_estimate, graph_available, semantic_available, fallback_used)
                VALUES (?, ?, 'AI_ORCHESTRATION', 'software_engineer', 'query-hash', 'graph_context',
                    'codex', 128, true, false, false)
                """, traceId, taskId);
        return traceId;
    }

    private UUID insertLearningContextEvent(UUID traceId, String idempotencyKey, String ruleIdsJson) {
        UUID eventId = UUID.randomUUID();
        if (ruleIdsJson == null) {
            jdbcTemplate.update("""
                    INSERT INTO learning_context_events (id, project_key, role, query_hash, context_mode,
                        token_estimate, graph_available, semantic_available, fallback_used, outcome,
                        context_trace_id, outcome_subject, source_event_id, idempotency_key)
                    VALUES (?, 'AI_ORCHESTRATION', 'software_engineer', 'query-hash', 'graph_context',
                        128, true, false, false, 'retrieved', ?, 'context', 'source-event', ?)
                    """, eventId, traceId, idempotencyKey);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO learning_context_events (id, project_key, role, query_hash, context_mode,
                        token_estimate, graph_available, semantic_available, fallback_used, outcome,
                        context_trace_id, rule_ids, outcome_subject, source_event_id, idempotency_key)
                    VALUES (?, 'AI_ORCHESTRATION', 'software_engineer', 'query-hash', 'graph_context',
                        128, true, false, false, 'retrieved', ?, ?::jsonb, 'context', 'source-event', ?)
                    """, eventId, traceId, ruleIdsJson, idempotencyKey);
        }
        return eventId;
    }

    private UUID insertApiKey(boolean revoked, String... scopes) {
        UUID keyId = UUID.randomUUID();
        String suffix = keyId.toString();
        String scopesLiteral = "{" + String.join(",", scopes) + "}";
        jdbcTemplate.update("""
                INSERT INTO mcp_api_keys
                    (id, project_key, client_id, api_key_hash, key_prefix, scopes, revoked_at)
                VALUES (?, 'AI_ORCHESTRATION', ?, ?, ?, ?::text[], CASE WHEN ? THEN now() ELSE NULL END)
                """, keyId, "client-" + suffix, "hash-" + suffix, "prefix-" + suffix, scopesLiteral, revoked);
        return keyId;
    }

    private void assertScopes(UUID keyId, boolean read, boolean write, boolean confirm, boolean globalConfirm) {
        assertThat(hasScope(keyId, "rules.read")).as("rules.read").isEqualTo(read);
        assertThat(hasScope(keyId, "rules.write")).as("rules.write").isEqualTo(write);
        assertThat(hasScope(keyId, "rules.confirm")).as("rules.confirm").isEqualTo(confirm);
        assertThat(hasScope(keyId, "rules.global.confirm")).as("rules.global.confirm").isEqualTo(globalConfirm);
    }

    private boolean hasScope(UUID keyId, String scope) {
        return scopeCount(keyId, scope) > 0;
    }

    private long scopeCount(UUID keyId, String scope) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM mcp_api_keys k
                CROSS JOIN LATERAL unnest(k.scopes) AS granted(scope)
                WHERE k.id = ? AND granted.scope = ?
                """, Long.class, keyId, scope);
        return count == null ? 0 : count;
    }

    private UUID insertRuleWithVersion() {
        return insertRuleDefinition();
    }

    private UUID insertRuleDefinitionForProject(String projectKey) {
        UUID ruleId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            insertRuleDefinitionRows(ruleId, projectKey);
            insertDefaultScopeIfSupported(ruleId, 1, projectKey);
        });
        return ruleId;
    }

    private void insertRuleDefinitionRows(UUID ruleId, String projectKey) {
        jdbcTemplate.update("""
                INSERT INTO rule_definitions (id, project_key, current_version, status)
                VALUES (?, ?, 1, 'active')
                """, ruleId, projectKey);
        jdbcTemplate.update("""
                INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all,
                    content_hash, origin_provenance, approved_by, approved_at, human_turn_ref,
                    approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                    workflow_contract_version)
                VALUES (?, 1, 'V46 upgrade rule', 'context', false, 'upgrade-hash',
                    'direct_human_policy', 'alex', now(), 'turn-upgrade', 'approval-upgrade',
                    'card-upgrade', 'raw-upgrade', 'rules-v1')
                """, ruleId);
    }

    private void insertAdditionalRuleVersion(UUID ruleId, int version) {
        jdbcTemplate.update("""
                INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all,
                    content_hash, origin_provenance, approved_by, approved_at, human_turn_ref,
                    approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                    workflow_contract_version)
                VALUES (?, ?, 'Historical V46 rule version', 'context', false, ?,
                    'direct_human_policy', 'alex', now(), ?, ?, ?, ?, 'rules-v1')
                """, ruleId, version, "upgrade-hash-" + version, "turn-upgrade-" + version,
                "approval-upgrade-" + version, "card-upgrade-" + version,
                "raw-upgrade-" + version);
    }

    private UUID insertRuleDefinition() {
        return insertRuleDefinition(null);
    }

    private UUID insertRuleDefinition(UUID originMemoryId) {
        UUID ruleId = UUID.randomUUID();
        String originContentHash = originMemoryId == null ? null : "a".repeat(64);
        String provenance = originMemoryId == null ? "direct_human_policy" : "human";
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions (id, origin_memory_id, project_key, current_version, status)
                    VALUES (?, ?, 'AI_ORCHESTRATION', 1, 'active')
                    """, ruleId, originMemoryId);
            jdbcTemplate.update("""
                    INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all,
                        content_hash, origin_memory_id, origin_content_hash, origin_provenance,
                        approved_by, approved_at, human_turn_ref,
                        approval_content_hash, confirmation_card_hash, human_raw_text_hash, workflow_contract_version)
                    VALUES (?, 1, 'Test rule statement', 'context', false, 'hash-1', ?, ?, ?, 'alex', now(), 'turn-1',
                        'approval-hash-1', 'card-hash-1', 'raw-text-hash-1', 'rules-v1')
                    """, ruleId, originMemoryId, originContentHash, provenance);
            insertDefaultScopeIfSupported(ruleId, 1, "AI_ORCHESTRATION");
        });
        return ruleId;
    }

    private void insertVersion(UUID ruleId, String enforcement, String detectorType, boolean appliesAll) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM rule_versions WHERE rule_id = ?", Integer.class, ruleId);
        String detectorContractHash = detectorType == null ? null : "b".repeat(64);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all, detector_type,
                        detector_contract_hash, content_hash, origin_provenance, approved_by, approved_at, human_turn_ref,
                        approval_content_hash, confirmation_card_hash, human_raw_text_hash, workflow_contract_version)
                    VALUES (?, ?, 'Test rule statement', ?, ?, ?, ?, 'hash-' || ?, 'human', 'alex', now(), 'turn-1',
                        'approval-hash-' || ?, 'card-hash-' || ?, 'raw-text-hash-' || ?, 'rules-v1')
                    """, ruleId, next, enforcement, appliesAll, detectorType, detectorContractHash,
                    next, next, next, next);
            insertDefaultScopeIfSupported(ruleId, next, projectKeyFor(ruleId));
        });
    }

    private void insertRawVersion(UUID ruleId, int version, String statement, String detectorConfig,
            String contentHash, String approvedBy, String humanTurnRef) {
        jdbcTemplate.update("""
                INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all, detector_config,
                    content_hash, origin_provenance, approved_by, approved_at, human_turn_ref,
                    approval_content_hash, confirmation_card_hash, human_raw_text_hash, workflow_contract_version)
                VALUES (?, ?, ?, 'context', false, ?::jsonb, ?, 'human', ?, now(), ?,
                    'approval-hash', 'card-hash', 'raw-text-hash', 'rules-v1')
                """, ruleId, version, statement, detectorConfig, contentHash, approvedBy, humanTurnRef);
    }

    private void insertVersionWithApprovalEvidence(UUID ruleId, int version, String approvalContentHash,
            String confirmationCardHash, String humanRawTextHash, String workflowContractVersion) {
        jdbcTemplate.update("""
                INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all, content_hash,
                    origin_provenance, approved_by, approved_at, human_turn_ref, approval_content_hash,
                    confirmation_card_hash, human_raw_text_hash, workflow_contract_version)
                VALUES (?, ?, 'Test rule statement', 'context', false, 'hash-2', 'human', 'alex', now(), 'turn-2',
                    ?, ?, ?, ?)
                """, ruleId, version, approvalContentHash, confirmationCardHash, humanRawTextHash,
                workflowContractVersion);
    }

    private void insertVersionWithDetectorContract(UUID ruleId, int version,
            String detectorType, String detectorContractHash) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all,
                        detector_type, detector_contract_hash, content_hash, origin_provenance,
                        approved_by, approved_at, human_turn_ref, approval_content_hash,
                        confirmation_card_hash, human_raw_text_hash, workflow_contract_version)
                    VALUES (?, ?, 'Test detector contract', 'context', false, ?, ?, 'detector-version-hash',
                        'direct_human_policy', 'alex', now(), 'turn-detector', 'approval-detector-hash',
                        'card-detector-hash', 'raw-detector-hash', 'rules-v1')
                    """, ruleId, version, detectorType, detectorContractHash);
            insertDefaultScopeIfSupported(ruleId, version, projectKeyFor(ruleId));
        });
    }

    private void insertDefaultScopeIfSupported(UUID ruleId, int version, String projectKey) {
        if (!tableExists("rule_scope_assignments")) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO rule_scope_assignments
                    (id, rule_id, rule_version, scope_type, project_key, ordinal)
                VALUES (?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), ruleId, version,
                projectKey == null ? "global" : "project", projectKey);
    }

    private String projectKeyFor(UUID ruleId) {
        return jdbcTemplate.queryForObject(
                "SELECT project_key FROM rule_definitions WHERE id = ?", String.class, ruleId);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count == 1;
    }

    private void insertVersionWithOrigin(UUID ruleId, int version, UUID originMemoryId,
            String originContentHash, String provenance) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO rule_versions (rule_id, version, statement, enforcement, applies_all, content_hash,
                        origin_memory_id, origin_content_hash, origin_provenance, approved_by, approved_at,
                        human_turn_ref, approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                        workflow_contract_version)
                    VALUES (?, ?, 'Test rule statement', 'context', false, 'origin-version-hash', ?, ?, ?,
                        'alex', now(), 'turn-origin', 'approval-origin-hash', 'card-origin-hash',
                        'raw-origin-hash', 'rules-v1')
                    """, ruleId, version, originMemoryId, originContentHash, provenance);
            insertDefaultScopeIfSupported(ruleId, version, projectKeyFor(ruleId));
        });
    }

    private void insertLegacyV42Version(UUID ruleId, int version, String originContentHash) {
        jdbcTemplate.update("""
                INSERT INTO rule_versions
                    (rule_id, version, statement, enforcement, applies_all, detector_type,
                     detector_config, content_hash, origin_content_hash, origin_provenance,
                     approved_by, approved_at, human_turn_ref)
                VALUES (?, ?, ?, 'context', false, 'diff_regex',
                    '{"pattern":"legacy"}'::jsonb, ?, ?, 'human',
                    'alex', now(), ?)
                """, ruleId, version, "Legacy V42 rule " + version,
                "legacy-content-hash-" + version, originContentHash, "legacy-turn-" + version);
    }

    private void insertProjectionWork(UUID memoryId, UUID ruleId, int ruleVersion) {
        jdbcTemplate.update("""
                INSERT INTO rule_memory_projection_work (memory_id, rule_id, rule_version)
                VALUES (?, ?, ?)
                """, memoryId, ruleId, ruleVersion);
    }

    private UUID insertLifecycleEvent(UUID ruleId, int ruleVersion, String action, String actor,
            String humanTurnRef, String humanRawTextHash, String approvalContentHash) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rule_lifecycle_events (id, rule_id, rule_version, action, actor, human_turn_ref,
                    human_raw_text_hash, approval_content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, ruleId, ruleVersion, action, actor, humanTurnRef, humanRawTextHash,
                approvalContentHash);
        return eventId;
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

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
