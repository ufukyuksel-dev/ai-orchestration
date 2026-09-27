package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcMcpAccessLogRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcMcpAccessLogRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcMcpAccessLogRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void insertsSemanticMeaningErrorAuditAgainstRealDecisionConstraint() {
        UUID id = UUID.randomUUID();
        McpAccessLogEntry entry = new McpAccessLogEntry(id, Instant.now(), "AI",
                "semantic-anchor-meaning", "internal", "semantic.anchor.meaning", "input-hash",
                0, 0, "error", "LLMProviderTimeoutException", Map.of("projectKey", "AI"));

        assertThatCode(() -> repository.insert(entry)).doesNotThrowAnyException();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT project_key, client_id, api_key_prefix, tool_name, decision, error_class,
                       metadata->>'projectKey' AS metadata_project_key
                FROM mcp_access_log
                WHERE id = ?
                """, id);
        assertThat(row)
                .containsEntry("project_key", "AI")
                .containsEntry("client_id", "semantic-anchor-meaning")
                .containsEntry("api_key_prefix", "internal")
                .containsEntry("tool_name", "semantic.anchor.meaning")
                .containsEntry("decision", "error")
                .containsEntry("error_class", "LLMProviderTimeoutException")
                .containsEntry("metadata_project_key", "AI");
    }

    @Test
    void everyDecisionTheServerWritesIsAcceptedByTheRealConstraint() {
        // Keep in sync with the decisions passed to McpAuditLogger.log across the server.
        for (String decision : java.util.List.of("success", "denied_auth", "denied_scope", "denied_localhost", "error")) {
            UUID id = UUID.randomUUID();
            assertThatCode(() -> repository.insert(new McpAccessLogEntry(id, Instant.now(), "AI", "client", "local",
                    "memory.write", "hash", 0, 0, decision, null,
                    Map.of("outcome", "not_saved", "gateReason", "duplicate_memory"))))
                    .as(decision).doesNotThrowAnyException();
            assertThat(jdbcTemplate.queryForObject("SELECT metadata->>'outcome' FROM mcp_access_log WHERE id = ?",
                    String.class, id)).isEqualTo("not_saved");
        }
    }

    @Test
    void serverCodeWritesNoDecisionOutsideTheConstraint() throws Exception {
        java.util.regex.Pattern call = java.util.regex.Pattern.compile("\\.log\\([^;]*?\"(denied_[a-z_]+|not_saved|success|error)\"");
        java.util.Set<String> used = new java.util.TreeSet<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = call.matcher(java.nio.file.Files.readString(file));
                while (matcher.find()) used.add(matcher.group(1));
            }
        }
        assertThat(used).contains("success", "denied_scope", "denied_localhost");
        assertThat(used).isSubsetOf("success", "denied_auth", "denied_scope", "denied_localhost", "error");
    }
}
