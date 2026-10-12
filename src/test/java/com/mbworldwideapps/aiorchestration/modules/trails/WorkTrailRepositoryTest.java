package com.mbworldwideapps.aiorchestration.modules.trails;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrail.Check;
import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrail.Locator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class WorkTrailRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    private WorkTrailRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE work_trails");
        repository = new WorkTrailRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void sameRequestUpsertsOneRowWithTheLatestContent() {
        UUID first = repository.upsert(trail("P", "s1:1", "fix follower count", List.of()));
        UUID second = repository.upsert(trail("P", "s1:1", "fix follower count",
                List.of(new Check("python -m unittest", "pytest", "/repo", "tree1", "tests", "passed", "ran"))));

        List<WorkTrail> rows = repository.recent("P", 10);
        assertThat(second).isEqualTo(first);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).checks()).containsExactly(new Check("python -m unittest", "pytest", "/repo", "tree1", "tests", "passed", "ran"));
        assertThat(rows.get(0).edited()).containsExactly(new Locator("app/models.py", "User.followers_count", "def"));
    }

    @Test
    void capturedTextReadRangesAndReferencesSurviveTheRoundTrip() {
        Locator edited = new Locator("app/api.py", "send_message", "def", null, 12,
                "def send_message():\n    return 1", "tree9", "h1");
        Locator read = new Locator("app/models.py", "User", "read", "40-80", null, null, null, null);
        Locator ref = new Locator("app/auth.py", "token_auth", "ref");
        repository.upsert(new WorkTrail(null, "P", "s2:1", "codex", "abc", "send messages", List.of(edited),
                List.of(), List.of(read, ref), List.of(), List.of(), null, null));

        WorkTrail row = repository.recent("P", 1).get(0);
        assertThat(row.edited()).containsExactly(edited);
        assertThat(row.supportingReads()).containsExactly(read, ref);
    }

    @Test
    void projectsAreSeparateAndRetentionKeepsTheNewest() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(trail("P", "s:" + i, "task " + i, List.of()));
        }
        repository.upsert(trail("OTHER", "s:0", "other", List.of()));

        assertThat(repository.prune("P", 3)).hasSize(2);
        assertThat(repository.recent("P", 10)).extracting(WorkTrail::requestText)
                .containsExactly("task 4", "task 3", "task 2");
        assertThat(repository.recent("OTHER", 10)).hasSize(1);
    }

    private static WorkTrail trail(String project, String key, String request, List<Check> checks) {
        return new WorkTrail(null, project, key, "claude-code", "abc", request,
                List.of(new Locator("app/models.py", "User.followers_count", "def")), List.of(), List.of(), checks,
                List.of(), null, null);
    }
}
