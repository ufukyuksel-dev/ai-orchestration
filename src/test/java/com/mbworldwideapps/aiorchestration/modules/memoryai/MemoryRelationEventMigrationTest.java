package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MemoryRelationEventMigrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradesFullFlywayChainWithoutLosingOldEventsAndRoundTripsEveryEventType() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("54").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var repository = new JdbcMemoryRepository(jdbc, new ObjectMapper());
        UUID memory = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','decision','summary','detail',1,'active','manual')
                """, memory, UUID.randomUUID());
        var historical = new MemoryEvent(UUID.randomUUID(), memory, MemoryEventType.CREATED,
                Map.of("preserve", true), Instant.parse("2025-01-01T00:00:00Z"));
        repository.insertEvent(historical);
        assertThatThrownBy(() -> repository.insertEvent(new MemoryEvent(UUID.randomUUID(), memory,
                MemoryEventType.RELATION_JUDGED, Map.of(), historical.createdAt())))
                .isInstanceOf(DataIntegrityViolationException.class);

        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
        flyway.validate();
        assertThat(repository.eventsForMemory(memory)).containsExactly(historical);
        int offset = 1;
        for (var type : MemoryEventType.values()) {
            var event = new MemoryEvent(UUID.randomUUID(), memory, type,
                    Map.of("candidateId", UUID.randomUUID().toString(), "action", "UNRELATED"),
                    historical.createdAt().plusSeconds(offset++));
            repository.insertEvent(event);
            assertThat(repository.eventsForMemory(memory)).contains(event);
        }
        assertThat(repository.eventsForMemory(memory)).hasSize(MemoryEventType.values().length + 1);
        assertThat(repository.eventsForMemory(memory)).extracting(MemoryEvent::eventType)
                .containsAll(List.of(MemoryEventType.values()));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO memory_events(id,memory_id,event_type) VALUES (?,?,'unknown')",
                UUID.randomUUID(), memory)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
