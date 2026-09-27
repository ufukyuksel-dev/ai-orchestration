package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.sql.Timestamp;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMcpAccessLogRepository implements McpAccessLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMcpAccessLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(McpAccessLogEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO mcp_access_log (
                    id, timestamp, project_key, client_id, api_key_prefix, tool_name, query_hash,
                    result_count, latency_ms, decision, error_class, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                entry.id(),
                Timestamp.from(entry.timestamp()),
                entry.projectKey(),
                entry.clientId(),
                entry.apiKeyPrefix(),
                entry.toolName(),
                entry.queryHash(),
                entry.resultCount(),
                entry.latencyMs(),
                entry.decision(),
                entry.errorClass(),
                json(entry.metadata()));
    }

    private String json(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize MCP access metadata", e);
        }
    }
}
