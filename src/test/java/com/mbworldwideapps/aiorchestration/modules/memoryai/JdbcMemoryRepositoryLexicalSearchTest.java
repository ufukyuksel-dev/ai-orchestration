package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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
class JdbcMemoryRepositoryLexicalSearchTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcMemoryRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE memory_items CASCADE");
        repository = new JdbcMemoryRepository(jdbc, new ObjectMapper());
    }

    @Test
    void eligibilityIsAppliedBeforeLimitForExactErrorCode() {
        MemoryItem eligible = item("P", MemoryStatus.ACTIVE, null, 0.20, "ERR_PAY_042 PaymentService#mapError");
        repository.save(item("FOREIGN", MemoryStatus.ACTIVE, null, 0.99,
                "ERR_PAY_042 PaymentService#mapError"));
        repository.save(item("P", MemoryStatus.ARCHIVED, null, 0.98,
                "ERR_PAY_042 PaymentService#mapError"));
        repository.save(item("P", MemoryStatus.ACTIVE, Instant.now().minusSeconds(60), 0.97,
                "ERR_PAY_042 PaymentService#mapError"));
        repository.save(eligible);

        List<MemoryItem> results = repository.searchEligible("ERR_PAY_042 PaymentService#mapError", 1,
                MemoryEligibilityFilter.forRetrieve("P", null));

        assertThat(results).extracting(MemoryItem::id).containsExactly(eligible.id());
    }

    @Test
    void countIndexableExcludesArchivedAndExpiredRows() {
        repository.save(item("P", MemoryStatus.ACTIVE, null, 0.8, "active"));
        repository.save(item("P", MemoryStatus.STALE, null, 0.8, "stale"));
        repository.save(item("P", MemoryStatus.ARCHIVED, null, 0.8, "archived"));
        repository.save(item("P", MemoryStatus.ACTIVE, Instant.now().minusSeconds(1), 0.8, "expired"));

        assertThat(repository.countIndexable()).isEqualTo(2);
    }

    private static MemoryItem item(String projectKey, MemoryStatus status, Instant expiresAt,
            double confidence, String text) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, projectKey, MemoryType.DISCOVERY,
                text, text, List.of("payment"), confidence, status, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, Instant.now(), expiresAt);
    }
}
