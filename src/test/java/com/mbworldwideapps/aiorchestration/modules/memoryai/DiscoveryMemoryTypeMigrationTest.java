package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DiscoveryMemoryTypeMigrationTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradesFromV55AndAcceptsLegacyAndDiscoveryTypes() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("55").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, "decision");

        var result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(10);
        insert(jdbc, "discovery");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(2);
    }

    private static void insert(JdbcTemplate jdbc, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory_items (
                    id, vector_id, scope, project_key, memory_type, summary, text, tags,
                    confidence, status, source_type, source_ref, owner, metadata
                ) VALUES (?, ?, 'project', 'AI_ORCHESTRATION', ?, ?, ?, '[]'::jsonb,
                          0.9, 'active', 'manual', ?, 'test', '{}'::jsonb)
                """, id, UUID.randomUUID(), type, type + " summary", type + " text", "test:" + id);
    }
}
