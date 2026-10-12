package com.mbworldwideapps.aiorchestration.modules.trails;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Work trails per project: idempotent upsert per request, newest-first listing, retention by count. */
@Repository
public class WorkTrailRepository {

    public static final int KEEP_PER_PROJECT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkTrailRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Insert or (same project and trail key: a later Stop of the same request) replace; returns the row id. */
    @Transactional
    public UUID upsert(WorkTrail trail) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO work_trails (id, project_key, trail_key, client, code_fingerprint, request_text, edited,
                    cited, supporting_reads, checks, fact_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                ON CONFLICT (project_key, trail_key) DO UPDATE SET client = EXCLUDED.client,
                    code_fingerprint = EXCLUDED.code_fingerprint, request_text = EXCLUDED.request_text,
                    edited = EXCLUDED.edited, cited = EXCLUDED.cited, supporting_reads = EXCLUDED.supporting_reads,
                    checks = EXCLUDED.checks, fact_ids = EXCLUDED.fact_ids, updated_at = now()
                RETURNING id
                """, UUID.class, UUID.randomUUID(), trail.projectKey(), trail.trailKey(), trail.client(),
                trail.codeFingerprint(), trail.requestText(), write(trail.edited()), write(trail.cited()),
                write(trail.supportingReads()), write(trail.checks()), write(trail.factIds()));
        prune(trail.projectKey(), KEEP_PER_PROJECT);
        return id;
    }

    /** Deletes all but the newest `keep` trails of a project; returns the deleted ids (for a later vector outbox). */
    public List<UUID> prune(String projectKey, int keep) {
        return jdbc.queryForList("""
                DELETE FROM work_trails WHERE id IN (
                    SELECT id FROM work_trails WHERE project_key = ? ORDER BY updated_at DESC, id OFFSET ?)
                RETURNING id
                """, UUID.class, projectKey, keep);
    }

    public List<WorkTrail> recent(String projectKey, int limit) {
        return jdbc.query("SELECT * FROM work_trails WHERE project_key = ? ORDER BY updated_at DESC, id LIMIT ?",
                mapper, projectKey, limit);
    }

    public int deleteProject(String projectKey) {
        return jdbc.update("DELETE FROM work_trails WHERE project_key = ?", projectKey);
    }

    private final RowMapper<WorkTrail> mapper = (rs, row) -> new WorkTrail(
            rs.getObject("id", UUID.class), rs.getString("project_key"), rs.getString("trail_key"),
            rs.getString("client"), rs.getString("code_fingerprint"), rs.getString("request_text"),
            read(rs.getString("edited"), new TypeReference<>() { }),
            read(rs.getString("cited"), new TypeReference<>() { }),
            read(rs.getString("supporting_reads"), new TypeReference<>() { }),
            read(rs.getString("checks"), new TypeReference<>() { }),
            read(rs.getString("fact_ids"), new TypeReference<>() { }),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("WORK_TRAIL_NOT_SERIALIZABLE", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WORK_TRAIL_CORRUPT", e);
        }
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
