package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcScannerFileStateRepository implements ScannerFileStateRepository {

    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcScannerFileStateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ScannerFileState> find(String filePath) {
        return find("AI_ORCHESTRATION", filePath);
    }

    @Override
    public Optional<ScannerFileState> find(String projectKey, String filePath) {
        return jdbcTemplate.query("""
                SELECT *
                FROM scanner_file_state
                WHERE project_key = ?
                  AND file_path = ?
                """, mapper(), normalizedProjectKey(projectKey), filePath).stream().findFirst();
    }

    @Override
    public void upsert(ScannerFileState state) {
        jdbcTemplate.update("""
                INSERT INTO scanner_file_state (
                    project_key, file_path, content_hash, extracted_memory_ids, last_scanned_at
                )
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (project_key, file_path) DO UPDATE
                SET content_hash = EXCLUDED.content_hash,
                    extracted_memory_ids = EXCLUDED.extracted_memory_ids,
                    last_scanned_at = EXCLUDED.last_scanned_at
                """,
                normalizedProjectKey(state.projectKey()),
                state.filePath(),
                state.contentHash(),
                json(state.extractedMemoryIds()),
                Timestamp.from(state.lastScannedAt()));
    }

    private RowMapper<ScannerFileState> mapper() {
        return (rs, rowNum) -> new ScannerFileState(
                rs.getString("project_key"),
                rs.getString("file_path"),
                rs.getString("content_hash"),
                readUuidList(rs.getString("extracted_memory_ids")),
                instant(rs, "last_scanned_at"));
    }

    private static String normalizedProjectKey(String projectKey) {
        return projectKey == null || projectKey.isBlank() ? "AI_ORCHESTRATION" : projectKey.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize scanner state", e);
        }
    }

    private List<UUID> readUuidList(String json) {
        try {
            return objectMapper.readValue(json, UUID_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize scanner state", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
