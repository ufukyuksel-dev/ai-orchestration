package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MemoryVectorProjectionStore {

    private static final long CAPACITY_LOCK = 0x4d5650524f4aL;
    private static final long MEMORY_LOCK_NAMESPACE = 0x4d56505253594e43L;
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final JdbcTemplate jdbc;

    MemoryVectorProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long enqueue(UUID memoryId, UUID previousVectorId, int capacity) {
        capacityLock();
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM memory_vector_projection_work WHERE memory_id = ?",
                Integer.class, memoryId);
        if (existing == null || existing == 0) {
            Long depth = jdbc.queryForObject("SELECT count(*) FROM memory_vector_projection_work", Long.class);
            if (depth != null && depth >= capacity) {
                throw new MemoryProjectionQueueFullException(capacity);
            }
        }
        return jdbc.queryForObject("""
                INSERT INTO memory_vector_projection_work
                    (memory_id, revision, previous_vector_id, requested_at, available_at,
                     attempt_count, last_error, dead_lettered_at)
                VALUES (?, COALESCE((SELECT applied_revision + 1
                                     FROM memory_vector_projection_state
                                     WHERE memory_id = ?), 1), ?, now(), now(), 0, NULL, NULL)
                ON CONFLICT (memory_id) DO UPDATE
                SET revision = memory_vector_projection_work.revision + 1,
                    previous_vector_id = COALESCE(memory_vector_projection_work.previous_vector_id,
                                                  EXCLUDED.previous_vector_id),
                    requested_at = now(),
                    available_at = now(),
                    attempt_count = 0,
                    last_error = NULL,
                    dead_lettered_at = NULL
                RETURNING revision
                """, Long.class, memoryId, memoryId, previousVectorId);
    }

    long nextSynchronousRevision(UUID memoryId) {
        memoryLock(memoryId);
        List<Long> revisions = jdbc.query(
                "SELECT applied_revision FROM memory_vector_projection_state WHERE memory_id = ?",
                (rs, rowNum) -> rs.getLong(1), memoryId);
        return revisions.isEmpty() ? 1L : Math.addExact(revisions.getFirst(), 1L);
    }

    Optional<MemoryVectorProjectionWork> lockNextAvailable() {
        return jdbc.query("""
                SELECT memory_id, revision, previous_vector_id, requested_at, available_at,
                       attempt_count, last_error, dead_lettered_at
                FROM memory_vector_projection_work
                WHERE available_at <= now() AND dead_lettered_at IS NULL
                ORDER BY available_at, requested_at, memory_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, MemoryVectorProjectionStore::mapWork).stream().findFirst();
    }

    Optional<MemoryVectorProjectionState> state(UUID memoryId) {
        return jdbc.query("""
                SELECT memory_id, applied_revision, vector_id, retrieval_text_hash,
                       projection_hash, embedding_fingerprint, applied_operation, applied_at
                FROM memory_vector_projection_state
                WHERE memory_id = ?
                """, MemoryVectorProjectionStore::mapState, memoryId).stream().findFirst();
    }

    void record(MemoryVectorProjectionState state) {
        int updated = jdbc.update("""
                INSERT INTO memory_vector_projection_state
                    (memory_id, applied_revision, vector_id, retrieval_text_hash,
                     projection_hash, embedding_fingerprint, applied_operation, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (memory_id) DO UPDATE
                SET applied_revision = EXCLUDED.applied_revision,
                    vector_id = EXCLUDED.vector_id,
                    retrieval_text_hash = EXCLUDED.retrieval_text_hash,
                    projection_hash = EXCLUDED.projection_hash,
                    embedding_fingerprint = EXCLUDED.embedding_fingerprint,
                    applied_operation = EXCLUDED.applied_operation,
                    applied_at = EXCLUDED.applied_at
                WHERE memory_vector_projection_state.applied_revision <= EXCLUDED.applied_revision
                """, state.memoryId(), state.appliedRevision(), state.vectorId(), state.retrievalTextHash(),
                state.projectionHash(), state.embeddingFingerprint(), state.appliedOperation().name(),
                Timestamp.from(state.appliedAt()));
        if (updated != 1) {
            throw new IllegalStateException("memory projection revision moved forward: " + state.memoryId());
        }
    }

    CompletionResult complete(MemoryVectorProjectionWork work) {
        int deleted = jdbc.update("""
                DELETE FROM memory_vector_projection_work
                WHERE memory_id = ? AND revision = ? AND dead_lettered_at IS NULL
                """, work.memoryId(), work.revision());
        return deleted == 1 ? CompletionResult.COMPLETED : CompletionResult.SUPERSEDED;
    }

    RetryResult retry(MemoryVectorProjectionWork work, RuntimeException failure, int maxAttempts) {
        int nextAttempt = Math.addExact(work.attemptCount(), 1);
        String message = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "projection failed" : failure.getMessage());
        String bounded = message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
        boolean deadLetter = nextAttempt >= maxAttempts;
        long backoffSeconds = Math.min(300L, 1L << Math.min(nextAttempt - 1, 8));
        int updated = jdbc.update("""
                UPDATE memory_vector_projection_work
                SET attempt_count = ?, last_error = ?, available_at = ?,
                    dead_lettered_at = CASE WHEN ? THEN now() ELSE NULL END
                WHERE memory_id = ? AND revision = ?
                """, nextAttempt, bounded, Timestamp.from(Instant.now().plusSeconds(backoffSeconds)), deadLetter,
                work.memoryId(), work.revision());
        if (updated != 1) return RetryResult.SUPERSEDED;
        return deadLetter ? RetryResult.DEAD_LETTER : RetryResult.RETRY;
    }

    long queueDepth() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM memory_vector_projection_work WHERE dead_lettered_at IS NULL", Long.class);
        return count == null ? 0L : count;
    }

    long deadLetterCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM memory_vector_projection_work WHERE dead_lettered_at IS NOT NULL", Long.class);
        return count == null ? 0L : count;
    }

    private void capacityLock() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, CAPACITY_LOCK);
    }

    private void memoryLock(UUID memoryId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, synchronousLockKey(memoryId));
    }

    static long synchronousLockKey(UUID memoryId) {
        long mixed = memoryId.getMostSignificantBits()
                ^ Long.rotateLeft(memoryId.getLeastSignificantBits(), 17)
                ^ MEMORY_LOCK_NAMESPACE;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        return mixed ^ mixed >>> 33;
    }

    private static MemoryVectorProjectionWork mapWork(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryVectorProjectionWork(
                rs.getObject("memory_id", UUID.class),
                rs.getLong("revision"),
                rs.getObject("previous_vector_id", UUID.class),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("available_at").toInstant(),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                instant(rs, "dead_lettered_at"));
    }

    private static MemoryVectorProjectionState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryVectorProjectionState(
                rs.getObject("memory_id", UUID.class),
                rs.getLong("applied_revision"),
                rs.getObject("vector_id", UUID.class),
                rs.getString("retrieval_text_hash"),
                rs.getString("projection_hash"),
                rs.getString("embedding_fingerprint"),
                MemoryVectorProjectionState.Operation.valueOf(rs.getString("applied_operation")),
                rs.getTimestamp("applied_at").toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    enum RetryResult {
        RETRY,
        DEAD_LETTER,
        SUPERSEDED
    }

    enum CompletionResult {
        COMPLETED,
        SUPERSEDED
    }
}
