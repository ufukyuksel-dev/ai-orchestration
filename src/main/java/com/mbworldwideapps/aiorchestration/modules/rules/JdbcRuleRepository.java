package com.mbworldwideapps.aiorchestration.modules.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRuleRepository implements RuleRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<RuleDefinition> definitionMapper = (rs, rowNum) -> new RuleDefinition(
            rs.getObject("id", UUID.class),
            rs.getObject("origin_memory_id", UUID.class),
            rs.getString("project_key"),
            rs.getInt("current_version"),
            RuleStatus.from(rs.getString("status")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final RowMapper<RuleTargetBinding> bindingMapper = (rs, rowNum) -> new RuleTargetBinding(
            rs.getObject("id", UUID.class),
            rs.getObject("rule_id", UUID.class),
            rs.getInt("rule_version"),
            BindingKind.from(rs.getString("binding_kind")),
            rs.getString("target_key"),
            instant(rs, "created_at"));

    private final RowMapper<RuleLifecycleEvent> lifecycleMapper = (rs, rowNum) -> new RuleLifecycleEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("rule_id", UUID.class),
            rs.getInt("rule_version"),
            RuleLifecycleAction.from(rs.getString("action")),
            rs.getString("actor"),
            rs.getString("human_turn_ref"),
            rs.getString("human_raw_text_hash"),
            rs.getString("reason"),
            rs.getString("approval_content_hash"),
            instant(rs, "created_at"));

    private final RowMapper<RuleSelectorPredicateDefinition> selectorPredicateMapper = (rs, rowNum) ->
            new RuleSelectorPredicateDefinition(
                    rs.getObject("id", UUID.class),
                    rs.getObject("group_id", UUID.class),
                    SelectorPolarity.from(rs.getString("polarity")),
                    SelectorField.from(rs.getString("field")),
                    SelectorOperator.from(rs.getString("operator")),
                    readStringList(rs.getString("values")),
                    rs.getInt("ordinal"),
                    instant(rs, "created_at"));

    private final RowMapper<RuleCheckDefinition> checkMapper = (rs, rowNum) -> new RuleCheckDefinition(
            rs.getObject("id", UUID.class),
            rs.getObject("rule_id", UUID.class),
            rs.getInt("rule_version"),
            RuleCheckPhase.from(rs.getString("phase")),
            rs.getString("checker_type"),
            rs.getString("implementation_version"),
            rs.getString("config_schema_version"),
            RuleCheckSeverity.from(rs.getString("severity")),
            readJsonMap(rs.getString("config")),
            rs.getString("checker_contract_hash"),
            rs.getInt("ordinal"),
            instant(rs, "created_at"));

    private final RowMapper<RuleScopeAssignment> scopeMapper = (rs, rowNum) -> new RuleScopeAssignment(
            rs.getObject("id", UUID.class),
            rs.getObject("rule_id", UUID.class),
            rs.getInt("rule_version"),
            RuleScopeType.from(rs.getString("scope_type")),
            rs.getString("project_key"),
            rs.getObject("policy_set_id", UUID.class),
            rs.getObject("policy_set_version", Integer.class),
            rs.getString("relation_type"),
            rs.getInt("ordinal"),
            instant(rs, "created_at"));

    private final RowMapper<RuleVersion> versionMapper = (rs, rowNum) -> new RuleVersion(
            rs.getObject("rule_id", UUID.class),
            rs.getInt("version"),
            rs.getString("statement"),
            rs.getString("rationale"),
            RuleEnforcement.from(rs.getString("enforcement")),
            rs.getBoolean("applies_all"),
            rs.getString("detector_type"),
            readJsonMap(rs.getString("detector_config")),
            rs.getString("detector_contract_hash"),
            rs.getString("content_hash"),
            rs.getObject("origin_memory_id", UUID.class),
            rs.getString("origin_content_hash"),
            rs.getString("approval_content_hash"),
            rs.getString("confirmation_card_hash"),
            rs.getString("human_raw_text_hash"),
            rs.getString("workflow_contract_version"),
            RuleProvenance.from(rs.getString("origin_provenance")),
            rs.getString("approved_by"),
            instant(rs, "approved_at"),
            rs.getString("human_turn_ref"),
            instant(rs, "created_at"));

    public JdbcRuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insertDefinition(RuleDefinition definition) {
        jdbcTemplate.update("""
                INSERT INTO rule_definitions
                    (id, origin_memory_id, project_key, current_version, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                definition.id(), definition.originMemoryId(), definition.projectKey(),
                definition.currentVersion(), definition.status().value(),
                Timestamp.from(definition.createdAt()), Timestamp.from(definition.updatedAt()));
    }

    @Override
    public void insertVersion(RuleVersion version) {
        jdbcTemplate.update("""
                INSERT INTO rule_versions (rule_id, version, statement, rationale, enforcement, applies_all,
                    detector_type, detector_config, detector_contract_hash, content_hash,
                    origin_memory_id, origin_content_hash,
                    origin_provenance,
                    approved_by, approved_at, human_turn_ref, approval_content_hash,
                    confirmation_card_hash, human_raw_text_hash, workflow_contract_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.ruleId(), version.version(), version.statement(), version.rationale(),
                version.enforcement().value(), version.appliesAll(), version.detectorType(),
                writeJson(version.detectorConfig()), version.detectorContractHash(), version.contentHash(),
                version.originMemoryId(),
                version.originContentHash(), version.originProvenance().value(), version.approvedBy(),
                Timestamp.from(version.approvedAt()), version.humanTurnRef(), version.approvalContentHash(),
                version.confirmationCardHash(), version.humanRawTextHash(), version.workflowContractVersion(),
                Timestamp.from(version.createdAt()));
    }

    @Override
    public void insertBindings(List<RuleTargetBinding> bindings) {
        for (RuleTargetBinding binding : bindings) {
            jdbcTemplate.update("""
                INSERT INTO rule_target_bindings
                    (id, rule_id, rule_version, binding_kind, target_key, created_at)
                VALUES (?, ?, ?, ?, ?, COALESCE(?, now()))
                """,
                    binding.id(), binding.ruleId(), binding.ruleVersion(), binding.kind().value(),
                    binding.targetKey(), timestamp(binding.createdAt()));
        }
    }

    @Override
    public void insertSelectorGroups(List<RuleSelectorGroupDefinition> groups) {
        List<Object[]> groupArguments = new ArrayList<>(groups.size());
        List<Object[]> predicateArguments = new ArrayList<>();
        for (RuleSelectorGroupDefinition group : groups) {
            groupArguments.add(new Object[] {group.id(), group.ruleId(), group.ruleVersion(), group.groupKey(),
                    group.ordinal(), timestamp(group.createdAt())});
            for (RuleSelectorPredicateDefinition predicate : group.predicates()) {
                predicateArguments.add(new Object[] {predicate.id(), predicate.groupId(),
                        predicate.polarity().value(), predicate.field().value(), predicate.operator().value(),
                        writeJson(predicate.values()), predicate.ordinal(), timestamp(predicate.createdAt())});
            }
        }
        if (!groupArguments.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO rule_selector_groups
                        (id, rule_id, rule_version, group_key, ordinal, created_at)
                    VALUES (?, ?, ?, ?, ?, COALESCE(?, now()))
                    """, groupArguments);
        }
        if (!predicateArguments.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO rule_selector_predicates
                        (id, group_id, polarity, field, operator, values, ordinal, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, COALESCE(?, now()))
                    """, predicateArguments);
        }
    }

    @Override
    public void insertCheckDefinitions(List<RuleCheckDefinition> checks) {
        for (RuleCheckDefinition check : checks) {
            jdbcTemplate.update("""
                    INSERT INTO rule_check_definitions
                        (id, rule_id, rule_version, phase, checker_type, implementation_version,
                         config_schema_version, severity, config, checker_contract_hash, ordinal, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, COALESCE(?, now()))
                    """, check.id(), check.ruleId(), check.ruleVersion(), check.phase().value(),
                    check.checkerType(), check.implementationVersion(), check.configSchemaVersion(),
                    check.severity().value(), writeJson(check.config()), check.checkerContractHash(),
                    check.ordinal(), timestamp(check.createdAt()));
        }
    }

    @Override
    public void insertScopeAssignments(List<RuleScopeAssignment> assignments) {
        for (RuleScopeAssignment assignment : assignments) {
            jdbcTemplate.update("""
                    INSERT INTO rule_scope_assignments
                        (id, rule_id, rule_version, scope_type, project_key, policy_set_id,
                         policy_set_version, relation_type, ordinal, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()))
                    """, assignment.id(), assignment.ruleId(), assignment.ruleVersion(),
                    assignment.scopeType().value(), assignment.projectKey(), assignment.policySetId(),
                    assignment.policySetVersion(), assignment.relationType(), assignment.ordinal(),
                    timestamp(assignment.createdAt()));
        }
    }

    @Override
    public void insertLifecycleEvent(RuleLifecycleEvent event) {
        jdbcTemplate.update("""
                INSERT INTO rule_lifecycle_events
                    (id, rule_id, rule_version, action, actor, human_turn_ref, human_raw_text_hash,
                     reason, approval_content_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.ruleId(), event.ruleVersion(), event.action().value(), event.actor(),
                event.humanTurnRef(), event.humanRawTextHash(), event.reason(), event.approvalContentHash(),
                Timestamp.from(event.createdAt()));
    }

    @Override
    public Optional<RuleLifecycleEvent> findLifecycleEvent(
            RuleLifecycleAction action, String approvalContentHash) {
        List<RuleLifecycleEvent> results = jdbcTemplate.query("""
                SELECT * FROM rule_lifecycle_events
                WHERE action = ? AND approval_content_hash = ?
                """, lifecycleMapper, action.value(), approvalContentHash);
        return results.stream().findFirst();
    }

    @Override
    public Optional<RuleDefinition> findDefinition(UUID ruleId) {
        List<RuleDefinition> results = jdbcTemplate.query(
                "SELECT * FROM rule_definitions WHERE id = ?", definitionMapper, ruleId);
        return results.stream().findFirst();
    }

    @Override
    public Optional<RuleDefinition> findByOriginMemoryId(UUID memoryId) {
        List<RuleDefinition> results = jdbcTemplate.query(
                "SELECT * FROM rule_definitions WHERE origin_memory_id = ?", definitionMapper, memoryId);
        return results.stream().findFirst();
    }

    @Override
    public Optional<RuleVersion> findVersion(UUID ruleId, int version) {
        List<RuleVersion> results = jdbcTemplate.query(
                "SELECT * FROM rule_versions WHERE rule_id = ? AND version = ?", versionMapper, ruleId, version);
        return results.stream().findFirst();
    }

    @Override
    public List<RuleTargetBinding> findBindings(UUID ruleId, int version) {
        return jdbcTemplate.query(
                "SELECT * FROM rule_target_bindings WHERE rule_id = ? AND rule_version = ?",
                bindingMapper, ruleId, version);
    }

    @Override
    public List<RuleSelectorGroupDefinition> findSelectorGroups(UUID ruleId, int version) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, group_key, ordinal, created_at
                FROM rule_selector_groups
                WHERE rule_id = ? AND rule_version = ?
                ORDER BY ordinal
                """, ruleId, version);
        List<RuleSelectorPredicateDefinition> allPredicates = jdbcTemplate.query("""
                SELECT predicate.*
                FROM rule_selector_predicates predicate
                JOIN rule_selector_groups selector_group ON selector_group.id = predicate.group_id
                WHERE selector_group.rule_id = ? AND selector_group.rule_version = ?
                ORDER BY selector_group.ordinal, predicate.ordinal
                """, selectorPredicateMapper, ruleId, version);
        Map<UUID, List<RuleSelectorPredicateDefinition>> predicatesByGroup = new LinkedHashMap<>();
        for (RuleSelectorPredicateDefinition predicate : allPredicates) {
            predicatesByGroup.computeIfAbsent(predicate.groupId(), ignored -> new ArrayList<>()).add(predicate);
        }
        List<RuleSelectorGroupDefinition> groups = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            UUID groupId = (UUID) row.get("id");
            Timestamp createdAt = (Timestamp) row.get("created_at");
            groups.add(new RuleSelectorGroupDefinition(groupId, ruleId, version,
                    (String) row.get("group_key"), ((Number) row.get("ordinal")).intValue(),
                    predicatesByGroup.getOrDefault(groupId, List.of()),
                    createdAt == null ? null : createdAt.toInstant()));
        }
        return List.copyOf(groups);
    }

    @Override
    public List<RuleCheckDefinition> findCheckDefinitions(UUID ruleId, int version) {
        return jdbcTemplate.query("""
                SELECT * FROM rule_check_definitions
                WHERE rule_id = ? AND rule_version = ? ORDER BY ordinal
                """, checkMapper, ruleId, version);
    }

    @Override
    public List<RuleScopeAssignment> findScopeAssignments(UUID ruleId, int version) {
        return jdbcTemplate.query("""
                SELECT * FROM rule_scope_assignments
                WHERE rule_id = ? AND rule_version = ? ORDER BY ordinal
                """, scopeMapper, ruleId, version);
    }

    @Override
    public List<RuleDefinition> findVisibleActive(String projectKey) {
        return jdbcTemplate.query("""
                SELECT * FROM rule_definitions
                WHERE (project_key IS NULL OR project_key = ?) AND status = 'active'
                """, definitionMapper, projectKey);
    }

    @Override
    public int maxProjectedActiveGlobRules(String projectKey, UUID excludedRuleId) {
        if (projectKey != null) {
            Long current = jdbcTemplate.queryForObject("""
                    SELECT count(DISTINCT d.id)
                    FROM rule_definitions d
                    JOIN rule_versions v
                      ON v.rule_id = d.id AND v.version = d.current_version
                    WHERE d.status = 'active'
                      AND (d.project_key IS NULL OR d.project_key = ?)
                      AND d.id IS DISTINCT FROM CAST(? AS UUID)
                      AND (
                          EXISTS (
                              SELECT 1 FROM rule_target_bindings b
                              WHERE b.rule_id = v.rule_id AND b.rule_version = v.version
                                AND b.binding_kind = 'path_glob'
                          )
                          OR EXISTS (
                              SELECT 1
                              FROM rule_selector_groups g
                              JOIN rule_selector_predicates p ON p.group_id = g.id
                              WHERE g.rule_id = v.rule_id AND g.rule_version = v.version
                                AND p.field IN ('path', 'symbol') AND p.operator = 'glob'
                          )
                      )
                    """, Long.class, projectKey, excludedRuleId);
            return Math.addExact(Math.toIntExact(current == null ? 0L : current), 1);
        }

        Long projectedMaximum = jdbcTemplate.queryForObject("""
                WITH active_glob_rules AS (
                    SELECT DISTINCT d.id, d.project_key
                    FROM rule_definitions d
                    JOIN rule_versions v
                      ON v.rule_id = d.id AND v.version = d.current_version
                    WHERE d.status = 'active'
                      AND d.id IS DISTINCT FROM CAST(? AS UUID)
                      AND (
                          EXISTS (
                              SELECT 1 FROM rule_target_bindings b
                              WHERE b.rule_id = v.rule_id AND b.rule_version = v.version
                                AND b.binding_kind = 'path_glob'
                          )
                          OR EXISTS (
                              SELECT 1
                              FROM rule_selector_groups g
                              JOIN rule_selector_predicates p ON p.group_id = g.id
                              WHERE g.rule_id = v.rule_id AND g.rule_version = v.version
                                AND p.field IN ('path', 'symbol') AND p.operator = 'glob'
                          )
                      )
                ),
                known_projects AS (
                    SELECT project_key FROM scanner_project_roots
                    UNION
                    SELECT project_key FROM rule_definitions WHERE project_key IS NOT NULL
                ),
                global_count AS (
                    SELECT count(*) AS value
                    FROM active_glob_rules
                    WHERE project_key IS NULL
                ),
                project_visible_counts AS (
                    SELECT projects.project_key,
                           globals.value + count(local_rules.id) AS value
                    FROM known_projects projects
                    CROSS JOIN global_count globals
                    LEFT JOIN active_glob_rules local_rules
                      ON local_rules.project_key = projects.project_key
                    GROUP BY projects.project_key, globals.value
                )
                SELECT GREATEST(
                    (SELECT value FROM global_count),
                    COALESCE((SELECT max(value) FROM project_visible_counts), 0)
                ) + 1
                """, Long.class, excludedRuleId);
        return Math.toIntExact(projectedMaximum == null ? 1L : projectedMaximum);
    }

    @Override
    public boolean advanceDefinition(UUID ruleId, int expectedCurrentVersion, int newVersion,
            UUID newOriginMemoryId, String expectedProjectKey, RuleStatus expectedStatus) {
        return jdbcTemplate.update("""
                UPDATE rule_definitions
                SET current_version = ?, origin_memory_id = ?, updated_at = now()
                WHERE id = ? AND current_version = ? AND status = ?
                  AND project_key IS NOT DISTINCT FROM ?
                """, newVersion, newOriginMemoryId, ruleId, expectedCurrentVersion,
                expectedStatus.value(), expectedProjectKey) == 1;
    }

    @Override
    public boolean deprecateDefinition(UUID ruleId, int expectedCurrentVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_definitions
                SET status = 'deprecated', updated_at = now()
                WHERE id = ? AND current_version = ? AND status = 'active'
                """, ruleId, expectedCurrentVersion) == 1;
    }

    @Override
    public long currentChangeSeq() {
        Long seq = jdbcTemplate.queryForObject("SELECT seq FROM rules_change_seq", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public long currentGlobalEffectiveSeq() {
        Long seq = jdbcTemplate.queryForObject(
                "SELECT seq FROM rules_global_effective_seq WHERE singleton = true", Long.class);
        return seq == null ? 0L : seq;
    }

    @Override
    public long currentProjectEffectiveSeq(String projectKey) {
        Long seq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(
                    (SELECT project.seq FROM rules_project_effective_seq project WHERE project.project_key = ?),
                    0
                )
                """, Long.class, canonicalProjectKey(projectKey));
        return seq == null ? 0L : seq;
    }

    @Override
    public void lockEffectiveSequences(List<String> projectKeys) {
        List<String> canonicalProjects = canonicalProjectKeys(projectKeys);
        jdbcTemplate.queryForObject("""
                SELECT seq FROM rules_global_effective_seq
                WHERE singleton = true FOR UPDATE
                """, Long.class);
        for (String projectKey : canonicalProjects) {
            jdbcTemplate.update("""
                    INSERT INTO rules_project_effective_seq (project_key, seq) VALUES (?, 0)
                    ON CONFLICT (project_key) DO NOTHING
                    """, projectKey);
            jdbcTemplate.queryForObject("""
                    SELECT seq FROM rules_project_effective_seq
                    WHERE project_key = ? FOR UPDATE
                    """, Long.class, projectKey);
        }
        jdbcTemplate.queryForObject("""
                SELECT seq FROM rules_change_seq
                WHERE singleton = true FOR UPDATE
                """, Long.class);
    }

    @Override
    public RuleEffectiveSequence bumpEffectiveSequences(boolean globalReachChanged, List<String> projectKeys) {
        List<String> canonicalProjects = canonicalProjectKeys(projectKeys);
        if (globalReachChanged && !canonicalProjects.isEmpty()) {
            throw new IllegalArgumentException("global mutation must not also bump project reach");
        }
        if (!globalReachChanged && canonicalProjects.isEmpty()) {
            throw new IllegalArgumentException("project mutation requires at least one project key");
        }
        Long legacy = jdbcTemplate.queryForObject(
                "UPDATE rules_change_seq SET seq = seq + 1 RETURNING seq", Long.class);
        long global;
        if (globalReachChanged) {
            Long updated = jdbcTemplate.queryForObject("""
                    UPDATE rules_global_effective_seq SET seq = seq + 1
                    WHERE singleton = true RETURNING seq
                    """, Long.class);
            global = updated == null ? 0L : updated;
        } else {
            global = currentGlobalEffectiveSeq();
        }
        Map<String, Long> projects = new LinkedHashMap<>();
        for (String projectKey : canonicalProjects) {
            Long updated = jdbcTemplate.queryForObject("""
                    UPDATE rules_project_effective_seq SET seq = seq + 1
                    WHERE project_key = ? RETURNING seq
                    """, Long.class, projectKey);
            if (updated == null) {
                throw new IllegalStateException("locked project sequence disappeared: " + projectKey);
            }
            projects.put(projectKey, updated);
        }
        return new RuleEffectiveSequence(legacy == null ? 0L : legacy, global, projects);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rule configuration is not serializable", e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored rule configuration is not valid JSON", e);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored selector values are not valid JSON", e);
        }
    }

    private static List<String> canonicalProjectKeys(List<String> projectKeys) {
        if (projectKeys == null) {
            throw new IllegalArgumentException("project keys are required");
        }
        return projectKeys.stream().map(JdbcRuleRepository::canonicalProjectKey)
                .distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private static String canonicalProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank() || projectKey.length() > 256) {
            throw new IllegalArgumentException("canonical project key is required");
        }
        return projectKey.trim();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
