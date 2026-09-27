package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LastJobMcpToolTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate jdbc;
    McpAuditLogger audit;
    LastJobMcpTool tool;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS last_job");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V52__last_job.sql")));
        audit = mock(McpAuditLogger.class);
        tool = new LastJobMcpTool(jdbc, audit);
        McpClientContextHolder.set(new McpClientContext("WORDFALL", "codex", "local", List.of("memory.read", "memory.write")));
    }
    @AfterEach void cleanup() { McpClientContextHolder.clear(); }

    @Test void replacesOneRecordAcrossProjectsAndSurvivesNewToolInstance() {
        assertThat(tool.get().found()).isFalse();
        assertThat(tool.save("İlk iş\nçok satır").saved()).isTrue();
        McpClientContextHolder.set(new McpClientContext("OTHER", "claude", "local", List.of("memory.read", "memory.write")));
        assertThat(tool.get().content()).isEqualTo("İlk iş\nçok satır");
        tool.save("Son iş\nartifacts and next steps");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM last_job", Integer.class)).isEqualTo(1);
        assertThat(new LastJobMcpTool(jdbc, audit).get().content()).isEqualTo("Son iş\nartifacts and next steps");
        assertThat(tool.get().updatedAt()).isNotNull();
    }
    @Test void invalidWritesPreserveOldContentAndLimitCountsUtf8Bytes() {
        tool.save("keep");
        for (String value : List.of(" ", "é".repeat(131073))) {
            assertThatThrownBy(() -> tool.save(value)).isInstanceOf(IllegalArgumentException.class);
            assertThat(tool.get().content()).isEqualTo("keep");
        }
        tool.save("é".repeat(131072));
        assertThat(tool.get().content()).hasSize(131072);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO last_job VALUES (2, 'second', now())")).isInstanceOf(RuntimeException.class);
    }
    @Test void deniesAndAuditsBearerAndMissingScopeWithoutDatabaseWrite() {
        for (var context : List.of(
                new McpClientContext("WORDFALL", "remote", "key123", List.of("memory.read", "memory.write")),
                new McpClientContext("WORDFALL", "local", "local", List.of()))) {
            McpClientContextHolder.set(context);
            assertThatThrownBy(() -> tool.get()).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.save("no")).isInstanceOf(McpAccessException.class);
            verify(audit).log(eq(context), eq("last_job.save"), isNull(), eq(0), any(Instant.class), eq("denied_scope"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM last_job", Integer.class)).isZero();
    }
}
