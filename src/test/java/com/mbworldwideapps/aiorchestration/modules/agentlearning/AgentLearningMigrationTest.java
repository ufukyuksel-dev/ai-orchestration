package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AgentLearningMigrationTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradesFromV56AndCreatesScopedLearningFoundation() {
        var dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("56").load().migrate();

        var result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(result.migrationsExecuted).isEqualTo(9);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN
                    ('learning_operations','memory_learning_evidence','memory_learning_profiles')
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "learning_operations", "memory_learning_evidence", "memory_learning_profiles");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN
                    ('agent_workspace_bindings','research_contexts','research_context_targets','research_observations',
                     'research_context_references')
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "agent_workspace_bindings", "research_context_references", "research_context_targets", "research_contexts",
                        "research_observations");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO learning_operations
                    (request_id,learning_handle,principal_key,project_key,state,created_at,updated_at,expires_at)
                VALUES (?,?,'principal','P','COMPLETED',now(),now(),now()+interval '1 day')
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT delete_rule FROM information_schema.referential_constraints
                WHERE constraint_name='research_context_knowledge_memory_id_fkey'
                """, String.class)).isEqualTo("CASCADE");
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name='research_context_targets' AND column_name='locator_kind'
                """, String.class)).isEqualTo("text");
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name='memory_navigation_anchors' AND column_name='locator_kind'
                """, String.class)).isEqualTo("text");
    }
}
