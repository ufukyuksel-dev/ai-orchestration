package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemoryRepository implements MemoryRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean ruleAuthorityEnabled;

    @Autowired
    public JdbcMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            RulesProperties rulesProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ruleAuthorityEnabled = rulesProperties.enabled();
    }

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ruleAuthorityEnabled = false;
    }

    @Override
    public MemoryItem save(MemoryItem item) {
        jdbcTemplate.update("""
                INSERT INTO memory_items (
                    id, vector_id, scope, project_key, memory_type, summary, text, tags, confidence,
                    status, source_type, source_ref, owner, metadata, created_at, updated_at,
                    last_used_at, last_verified_at, expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """,
                item.id(),
                item.vectorId(),
                item.scope().value(),
                item.projectKey(),
                item.memoryType().value(),
                item.summary(),
                item.text(),
                json(item.tags()),
                item.confidence(),
                item.status().value(),
                item.sourceType().value(),
                item.sourceRef(),
                item.owner(),
                json(item.metadata()),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()),
                timestamp(item.lastUsedAt()),
                timestamp(item.lastVerifiedAt()),
                timestamp(item.expiresAt()));
        return item;
    }

    @Override
    public Optional<MemoryItem> findById(UUID id) {
        List<MemoryItem> items = jdbcTemplate.query("""
                SELECT *
                FROM memory_items
                WHERE id = ?
                """, memoryItemMapper(), id);
        return items.stream().findFirst();
    }

    @Override
    public Optional<MemoryItem> findByIdForUpdate(UUID id) {
        List<MemoryItem> items = jdbcTemplate.query("""
                SELECT *
                FROM memory_items
                WHERE id = ?
                FOR UPDATE
                """, memoryItemMapper(), id);
        return items.stream().findFirst();
    }

    @Override
    public List<MemoryItem> findAllByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> uniqueIds = ids.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(uniqueIds.size(), "?"));
        return jdbcTemplate.query("SELECT * FROM memory_items WHERE id IN (" + placeholders + ")",
                memoryItemMapper(), uniqueIds.toArray());
    }

    @Override
    public Optional<MemoryItem> findBySourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return Optional.empty();
        }
        List<MemoryItem> items = jdbcTemplate.query("""
                SELECT *
                FROM memory_items
                WHERE source_ref = ?
                ORDER BY updated_at DESC, created_at DESC
                LIMIT 1
                """, memoryItemMapper(), sourceRef);
        return items.stream().findFirst();
    }

    @Override
    public Optional<MemoryItem> findBySourceRefForUpdate(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return Optional.empty();
        }
        List<MemoryItem> items = jdbcTemplate.query("""
                SELECT *
                FROM memory_items
                WHERE source_ref = ?
                ORDER BY updated_at DESC, created_at DESC
                LIMIT 1
                FOR UPDATE
                """, memoryItemMapper(), sourceRef);
        return items.stream().findFirst();
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM memory_items
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (scope != null) {
            sql.append(" AND scope = ?");
            args.add(scope.value());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.value());
        }
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" AND project_key = ?");
            args.add(projectKey.trim());
        }
        sql.append(" ORDER BY updated_at DESC, created_at DESC");
        return jdbcTemplate.query(sql.toString(), memoryItemMapper(), args.toArray());
    }

    @Override
    public List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = statuses.stream().map(s -> "?").collect(Collectors.joining(", "));
        String sql = """
                SELECT *
                FROM memory_items
                WHERE status IN (%s)
                ORDER BY id ASC
                """.formatted(placeholders);
        Object[] args = statuses.stream().map(MemoryStatus::value).toArray();
        return jdbcTemplate.query(sql, memoryItemMapper(), args);
    }

    @Override
    public List<MemoryItem> findEligibleForGraphProjection(String projectKey, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM memory_items
                WHERE status IN (?, ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(MemoryStatus.ACTIVE.value());
        args.add(MemoryStatus.STALE.value());
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" AND scope = ? AND project_key = ?");
            args.add(MemoryScope.PROJECT.value());
            args.add(projectKey.trim());
        }
        if (afterId != null) {
            sql.append(" AND id > ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id ASC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), memoryItemMapper(), args.toArray());
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM memory_items
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (scope != null) {
            sql.append(" AND scope = ?");
            args.add(scope.value());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.value());
        }
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" AND project_key = ?");
            args.add(projectKey.trim());
        }
        sql.append(" ORDER BY updated_at DESC, created_at DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, limit));
        args.add(Math.max(0, offset));
        return jdbcTemplate.query(sql.toString(), memoryItemMapper(), args.toArray());
    }

    @Override
    public MemoryItem update(MemoryItem item) {
        jdbcTemplate.update("""
                UPDATE memory_items
                SET vector_id = ?,
                    scope = ?,
                    project_key = ?,
                    memory_type = ?,
                    summary = ?,
                    text = ?,
                    tags = ?::jsonb,
                    confidence = ?,
                    status = ?,
                    source_type = ?,
                    source_ref = ?,
                    owner = ?,
                    metadata = ?::jsonb,
                    updated_at = ?,
                    last_used_at = ?,
                    last_verified_at = ?,
                    expires_at = ?
                WHERE id = ?
                """,
                item.vectorId(),
                item.scope().value(),
                item.projectKey(),
                item.memoryType().value(),
                item.summary(),
                item.text(),
                json(item.tags()),
                item.confidence(),
                item.status().value(),
                item.sourceType().value(),
                item.sourceRef(),
                item.owner(),
                json(item.metadata()),
                Timestamp.from(item.updatedAt()),
                timestamp(item.lastUsedAt()),
                timestamp(item.lastVerifiedAt()),
                timestamp(item.expiresAt()),
                item.id());
        return item;
    }

    @Override
    public boolean updateStatus(UUID id, MemoryStatus status) {
        int updated = jdbcTemplate.update("""
                UPDATE memory_items
                SET status = ?, updated_at = now()
                WHERE id = ?
                """, status.value(), id);
        return updated > 0;
    }

    @Override
    public void insertEvent(MemoryEvent event) {
        jdbcTemplate.update("""
                INSERT INTO memory_events (id, memory_id, event_type, metadata, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """,
                event.id(),
                event.memoryId(),
                event.eventType().value(),
                json(event.metadata()),
                Timestamp.from(event.createdAt()));
    }

    @Override
    public List<MemoryEvent> eventsForMemory(UUID memoryId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM memory_events
                WHERE memory_id = ?
                ORDER BY created_at
                """, memoryEventMapper(), memoryId);
    }

    @Override
    public void insertReviewQueue(ReviewQueueItem item) {
        jdbcTemplate.update("""
                INSERT INTO memory_review_queue (
                    id, candidate_memory_id, reason, status, metadata, created_at, reviewed_at
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                item.id(),
                item.candidateMemoryId(),
                item.reason().value(),
                item.status().value(),
                json(item.metadata()),
                Timestamp.from(item.createdAt()),
                timestamp(item.reviewedAt()));
    }

    @Override
    public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM memory_review_queue
                WHERE candidate_memory_id = ?
                ORDER BY created_at
                """, reviewQueueMapper(), memoryId);
    }

    @Override
    public int updateReviewQueueStatus(UUID memoryId, ReviewStatus expectedStatus, ReviewStatus status,
            Instant reviewedAt, Map<String, Object> metadata) {
        return jdbcTemplate.update("""
                UPDATE memory_review_queue
                SET status = ?,
                    reviewed_at = ?,
                    metadata = metadata || ?::jsonb
                WHERE candidate_memory_id = ?
                  AND status = ?
                """,
                status.value(),
                timestamp(reviewedAt),
                json(metadata),
                memoryId,
                expectedStatus.value());
    }

    @Override
    public List<ReviewQueueItem> listReviewQueue(ReviewStatus status) {
        if (status == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM memory_review_queue
                    ORDER BY created_at
                    """, reviewQueueMapper());
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM memory_review_queue
                WHERE status = ?
                ORDER BY created_at
                """, reviewQueueMapper(), status.value());
    }

    @Override
    public List<MemoryItem> search(String query, int limit) {
        return search(query, limit, false);
    }

    @Override
    public List<MemoryItem> searchForAutomaticInjection(String query, int limit) {
        return search(query, limit, true);
    }

    private List<MemoryItem> search(String query, int limit, boolean automaticInjection) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        if (normalized.isBlank()) {
            return List.of();
        }
        String pattern = "%" + normalized + "%";
        String authorityClause = automaticInjection ? automaticInjectionAuthorityClause() : "";
        return jdbcTemplate.query(("""
                SELECT *
                FROM memory_items
                WHERE (lower(summary) LIKE ?
                   OR lower(text) LIKE ?
                   OR lower(tags::text) LIKE ?)
                %s
                ORDER BY confidence DESC, updated_at DESC
                LIMIT ?
                """).formatted(authorityClause), memoryItemMapper(), pattern, pattern, pattern, Math.max(1, limit));
    }

    @Override
    public List<MemoryItem> searchEligible(String query, int limit, MemoryEligibilityFilter filter) {
        String normalized = query == null ? "" : java.text.Normalizer
                .normalize(query.trim(), java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank() || filter == null) {
            return List.of();
        }
        String pattern = "%" + escapeLike(normalized) + "%";
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM memory_items
                WHERE status IN (?, ?)
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (lower(summary) LIKE ? ESCAPE '\\'
                       OR lower(text) LIKE ? ESCAPE '\\'
                       OR lower(tags::text) LIKE ? ESCAPE '\\')
                """);
        List<Object> args = new ArrayList<>();
        args.add(MemoryStatus.ACTIVE.value());
        args.add(MemoryStatus.STALE.value());
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);

        List<String> scopeClauses = new ArrayList<>();
        if (filter.projectKey() != null && !filter.projectKey().isBlank()) {
            scopeClauses.add("(scope = ? AND project_key = ?)");
            args.add(MemoryScope.PROJECT.value());
            args.add(filter.projectKey());
        }
        if (filter.userProjectKey() != null && !filter.userProjectKey().isBlank()) {
            scopeClauses.add("(scope = ? AND project_key = ?)");
            args.add(MemoryScope.USER.value());
            args.add(filter.userProjectKey());
        }
        if (filter.includeGlobal()) {
            scopeClauses.add("(scope = ? AND project_key IS NULL)");
            args.add(MemoryScope.GLOBAL.value());
        }
        if (filter.includeEpisodic()) {
            scopeClauses.add("scope = ?");
            args.add(MemoryScope.EPISODIC.value());
        }
        if (scopeClauses.isEmpty()) {
            return List.of();
        }
        sql.append(" AND (").append(String.join(" OR ", scopeClauses)).append(")");
        if (filter.excludePromotedRuleOrigins() && ruleAuthorityEnabled) {
            sql.append(automaticInjectionAuthorityClause());
        }
        sql.append("""
                 ORDER BY CASE
                    WHEN lower(summary) = ? OR lower(text) = ? THEN 0
                    WHEN lower(summary) LIKE ? ESCAPE '\\' OR lower(text) LIKE ? ESCAPE '\\' THEN 1
                    ELSE 2
                 END,
                 confidence DESC,
                 updated_at DESC
                 LIMIT ?
                """);
        args.add(normalized);
        args.add(normalized);
        args.add(pattern);
        args.add(pattern);
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), memoryItemMapper(), args.toArray()).stream()
                .filter(filter::accepts)
                .toList();
    }

    @Override
    public long countIndexable() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM memory_items
                WHERE status IN (?, ?)
                  AND (expires_at IS NULL OR expires_at > now())
                """, Long.class, MemoryStatus.ACTIVE.value(), MemoryStatus.STALE.value());
        return count == null ? 0L : count;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String automaticInjectionAuthorityClause() {
        if (ruleAuthorityEnabled) {
            return """
                    AND NOT (
                        memory_type = 'rule'
                        AND EXISTS (
                            SELECT 1 FROM rule_versions version
                            WHERE version.origin_memory_id = memory_items.id
                        )
                    )
                    """;
        }
        return "";
    }

    @Override
    public int deleteByScopeAndProjectKey(MemoryScope scope, String projectKey) {
        if (scope == null || projectKey == null || projectKey.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update("""
                DELETE FROM memory_items
                WHERE scope = ?
                  AND project_key = ?
                """, scope.value(), projectKey.trim());
    }

    @Override
    public void deleteById(UUID id) {
        jdbcTemplate.update("""
                DELETE FROM memory_items
                WHERE id = ?
                """, id);
    }

    @Override
    public boolean existsByTextHashSince(String textHashPrefix, String projectKey, Instant since) {
        if (textHashPrefix == null || textHashPrefix.isBlank()) {
            return false;
        }
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM memory_items
                WHERE metadata ->> 'textHashPrefix' = ?
                  AND created_at >= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(textHashPrefix.trim());
        args.add(Timestamp.from(since == null ? Instant.EPOCH : since));
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" AND project_key = ?");
            args.add(projectKey.trim());
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count != null && count > 0;
    }

    private RowMapper<MemoryItem> memoryItemMapper() {
        return (rs, rowNum) -> new MemoryItem(
                rs.getObject("id", UUID.class),
                rs.getObject("vector_id", UUID.class),
                MemoryScope.from(rs.getString("scope")),
                rs.getString("project_key"),
                MemoryType.from(rs.getString("memory_type")),
                rs.getString("summary"),
                rs.getString("text"),
                readJson(rs.getString("tags"), STRING_LIST),
                rs.getDouble("confidence"),
                MemoryStatus.from(rs.getString("status")),
                MemorySourceType.from(rs.getString("source_type")),
                rs.getString("source_ref"),
                rs.getString("owner"),
                readJson(rs.getString("metadata"), STRING_OBJECT_MAP),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "last_used_at"),
                instant(rs, "last_verified_at"),
                instant(rs, "expires_at"));
    }

    private RowMapper<MemoryEvent> memoryEventMapper() {
        return (rs, rowNum) -> new MemoryEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("memory_id", UUID.class),
                MemoryEventType.from(rs.getString("event_type")),
                readJson(rs.getString("metadata"), STRING_OBJECT_MAP),
                instant(rs, "created_at"));
    }

    private RowMapper<ReviewQueueItem> reviewQueueMapper() {
        return (rs, rowNum) -> new ReviewQueueItem(
                rs.getObject("id", UUID.class),
                rs.getObject("candidate_memory_id", UUID.class),
                ReviewReason.from(rs.getString("reason")),
                ReviewStatus.from(rs.getString("status")),
                readJson(rs.getString("metadata"), STRING_OBJECT_MAP),
                instant(rs, "created_at"),
                instant(rs, "reviewed_at"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize memory JSON field", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize memory JSON field", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
