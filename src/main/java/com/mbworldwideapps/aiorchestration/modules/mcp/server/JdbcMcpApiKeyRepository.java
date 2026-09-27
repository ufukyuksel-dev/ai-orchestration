package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMcpApiKeyRepository implements McpApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMcpApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<McpApiKey> findActiveByHash(String apiKeyHash) {
        List<McpApiKey> matches = jdbcTemplate.query("""
                SELECT *
                FROM mcp_api_keys
                WHERE api_key_hash = ?
                  AND revoked_at IS NULL
                LIMIT 1
                """, mapper(), apiKeyHash);
        return matches.stream().findFirst();
    }

    @Override
    public void updateLastUsed(String apiKeyHash) {
        jdbcTemplate.update("""
                UPDATE mcp_api_keys
                SET last_used_at = now()
                WHERE api_key_hash = ?
                """, apiKeyHash);
    }

    @Override
    public McpApiKey save(McpApiKey apiKey) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO mcp_api_keys (
                        id, project_key, client_id, api_key_hash, key_prefix, scopes, created_at, revoked_at, last_used_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, apiKey.id());
            statement.setString(2, apiKey.projectKey());
            statement.setString(3, apiKey.clientId());
            statement.setString(4, apiKey.apiKeyHash());
            statement.setString(5, apiKey.keyPrefix());
            statement.setArray(6, connection.createArrayOf("text", apiKey.scopes().toArray(String[]::new)));
            statement.setTimestamp(7, timestamp(apiKey.createdAt()));
            statement.setTimestamp(8, timestamp(apiKey.revokedAt()));
            statement.setTimestamp(9, timestamp(apiKey.lastUsedAt()));
            return statement;
        });
        return apiKey;
    }

    @Override
    public List<McpApiKey> listActive(String projectKey) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM mcp_api_keys
                WHERE revoked_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" AND project_key = ?");
            args.add(projectKey.trim());
        }
        sql.append(" ORDER BY created_at DESC LIMIT 100");
        return jdbcTemplate.query(sql.toString(), mapper(), args.toArray());
    }

    @Override
    public Optional<McpApiKey> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT *
                FROM mcp_api_keys
                WHERE id = ?
                """, mapper(), id).stream().findFirst();
    }

    @Override
    public boolean revoke(UUID id, Instant revokedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE mcp_api_keys
                SET revoked_at = ?
                WHERE id = ?
                  AND revoked_at IS NULL
                """, timestamp(revokedAt == null ? Instant.now() : revokedAt), id);
        return updated == 1;
    }

    @Override
    public Optional<McpApiKey> findByPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM mcp_api_keys
                WHERE key_prefix = ?
                  AND revoked_at IS NULL
                LIMIT 1
                """, mapper(), keyPrefix.trim()).stream().findFirst();
    }

    private static RowMapper<McpApiKey> mapper() {
        return (rs, rowNum) -> new McpApiKey(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("project_key"),
                rs.getString("client_id"),
                rs.getString("api_key_hash"),
                rs.getString("key_prefix"),
                scopes(rs),
                instant(rs, "created_at"),
                instant(rs, "revoked_at"),
                instant(rs, "last_used_at"));
    }

    private static List<String> scopes(ResultSet rs) throws SQLException {
        Array array = rs.getArray("scopes");
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] strings) {
            return List.of(strings);
        }
        if (value instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
