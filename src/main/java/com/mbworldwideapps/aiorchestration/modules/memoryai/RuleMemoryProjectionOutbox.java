package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC outbox used to close the database-to-vector/graph commit gap. */
@Repository
class RuleMemoryProjectionOutbox {

    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration UNAVAILABLE_BACKEND_RECHECK = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;

    RuleMemoryProjectionOutbox(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void enqueue(UUID memoryId, UUID ruleId, int ruleVersion) {
        jdbcTemplate.update("""
                INSERT INTO rule_memory_projection_work
                    (memory_id, rule_id, rule_version, requested_at, available_at, attempt_count, last_error)
                VALUES (?, ?, ?, now(), now(), 0, NULL)
                ON CONFLICT (memory_id) DO UPDATE
                SET rule_id = EXCLUDED.rule_id,
                    rule_version = EXCLUDED.rule_version,
                    requested_at = now(),
                    available_at = now(),
                    vector_projected_at = CASE
                        WHEN rule_memory_projection_work.rule_id = EXCLUDED.rule_id
                         AND rule_memory_projection_work.rule_version = EXCLUDED.rule_version
                        THEN rule_memory_projection_work.vector_projected_at ELSE NULL END,
                    graph_marked_at = CASE
                        WHEN rule_memory_projection_work.rule_id = EXCLUDED.rule_id
                         AND rule_memory_projection_work.rule_version = EXCLUDED.rule_version
                        THEN rule_memory_projection_work.graph_marked_at ELSE NULL END,
                    semantic_marked_at = CASE
                        WHEN rule_memory_projection_work.rule_id = EXCLUDED.rule_id
                         AND rule_memory_projection_work.rule_version = EXCLUDED.rule_version
                        THEN rule_memory_projection_work.semantic_marked_at ELSE NULL END,
                    attempt_count = 0,
                    last_error = NULL
                """, memoryId, ruleId, ruleVersion);
    }

    Optional<RuleMemoryProjectionWork> lockNextAvailable() {
        List<RuleMemoryProjectionWork> work = jdbcTemplate.query("""
                SELECT memory_id, rule_id, rule_version, requested_at, available_at,
                       vector_projected_at, graph_marked_at, semantic_marked_at,
                       attempt_count, last_error
                FROM rule_memory_projection_work
                WHERE available_at <= now()
                ORDER BY available_at, requested_at, memory_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, RuleMemoryProjectionOutbox::map);
        return work.stream().findFirst();
    }

    void complete(RuleMemoryProjectionWork work) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM rule_memory_projection_work
                WHERE memory_id = ? AND rule_id = ? AND rule_version = ?
                  AND vector_projected_at IS NOT NULL
                  AND graph_marked_at IS NOT NULL
                  AND semantic_marked_at IS NOT NULL
                """, work.memoryId(), work.ruleId(), work.ruleVersion());
        if (deleted != 1) {
            throw new IllegalStateException("projection work was not fully completed: " + work.memoryId());
        }
    }

    void markVectorProjected(RuleMemoryProjectionWork work) {
        markSink(work, "vector_projected_at");
    }

    void markGraphMarked(RuleMemoryProjectionWork work) {
        markSink(work, "graph_marked_at");
    }

    void markSemanticMarked(RuleMemoryProjectionWork work) {
        markSink(work, "semantic_marked_at");
    }

    void deferUnavailable(RuleMemoryProjectionWork work) {
        jdbcTemplate.update("""
                UPDATE rule_memory_projection_work
                SET available_at = ?, last_error = NULL
                WHERE memory_id = ? AND rule_id = ? AND rule_version = ?
                """, Timestamp.from(Instant.now().plus(UNAVAILABLE_BACKEND_RECHECK)),
                work.memoryId(), work.ruleId(), work.ruleVersion());
    }

    void retryLater(RuleMemoryProjectionWork work, RuntimeException failure) {
        int nextAttempt = Math.addExact(work.attemptCount(), 1);
        long backoffSeconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(nextAttempt - 1, 8));
        String message = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "projection failed" : failure.getMessage());
        String bounded = message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
        jdbcTemplate.update("""
                UPDATE rule_memory_projection_work
                SET attempt_count = ?, available_at = ?, last_error = ?
                WHERE memory_id = ? AND rule_id = ? AND rule_version = ?
                """, nextAttempt, Timestamp.from(Instant.now().plusSeconds(backoffSeconds)), bounded,
                work.memoryId(), work.ruleId(), work.ruleVersion());
    }

    private static RuleMemoryProjectionWork map(ResultSet rs, int rowNum) throws SQLException {
        return new RuleMemoryProjectionWork(
                rs.getObject("memory_id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                rs.getInt("rule_version"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("available_at").toInstant(),
                instant(rs, "vector_projected_at"),
                instant(rs, "graph_marked_at"),
                instant(rs, "semantic_marked_at"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"));
    }

    private void markSink(RuleMemoryProjectionWork work, String column) {
        if (!Set.of("vector_projected_at", "graph_marked_at", "semantic_marked_at").contains(column)) {
            throw new IllegalArgumentException("unsupported projection sink column");
        }
        int updated = jdbcTemplate.update(("""
                UPDATE rule_memory_projection_work
                SET %s = COALESCE(%s, now())
                WHERE memory_id = ? AND rule_id = ? AND rule_version = ?
                """).formatted(column, column), work.memoryId(), work.ruleId(), work.ruleVersion());
        if (updated != 1) {
            throw new IllegalStateException("projection work changed while locked: " + work.memoryId());
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
