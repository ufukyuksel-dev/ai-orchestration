package com.mbworldwideapps.aiorchestration.modules.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

/**
 * Rule scope read contract (Atlas Universe Faz 6).
 *
 * The visual layer must not invent rule applicability from prose or regex, so the backend
 * resolves scope from the recorded {@code rule_target_bindings} using exactly the same
 * prefix semantics as {@code InstructionReadService.read}. Scope match is never compliance.
 */
@Testcontainers
class WorkspaceRuleScopeTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    WorkspaceReadService service;

    @BeforeAll
    static void migrate() {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        // rule_definitions_current_version_fk DEFERRABLE INITIALLY DEFERRED: tanım ve versiyon
        // aynı transaction içinde yazılmalı (promotion akışının yaptığının aynısı).
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new WorkspaceReadService(
                jdbc,
                new ObjectMapper(),
                mock(RuleMemoryLinkLookup.class),
                mock(ObjectProvider.class),
                new JobMemoryProperties(null, null, null));
    }

    private UUID rule(String projectKey, boolean appliesAll, String... modulePaths) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
        jdbc.update(
                "INSERT INTO rule_definitions(id,project_key,current_version,status) VALUES (?,?,1,'active')",
                id, projectKey);
        jdbc.update(
                "INSERT INTO rule_versions(rule_id,version,statement,enforcement,applies_all,content_hash,"
                    + "origin_provenance,approved_by,approved_at,human_turn_ref,approval_content_hash,"
                    + "confirmation_card_hash,human_raw_text_hash,workflow_contract_version)"
                    + " VALUES (?,1,?,'instruction',?,?,'human','test',?,'turn',?,?,?,'v1')",
                id, "statement for " + id, appliesAll, "hash-" + id,
                java.sql.Timestamp.from(Instant.now()), "approval-" + id, "card-" + id, "raw-" + id);
        // rule_versions trigger'ı en az bir scope assignment ister (gerçek promotion akışı da yazar).
        jdbc.update(
                "INSERT INTO rule_scope_assignments(id,rule_id,rule_version,scope_type,project_key,ordinal)"
                    + " VALUES (?,?,1,?,?,0)",
                UUID.randomUUID(), id, projectKey == null ? "global" : "project", projectKey);
        for (String path : modulePaths)
            jdbc.update(
                    "INSERT INTO rule_target_bindings(id,rule_id,rule_version,binding_kind,target_key)"
                        + " VALUES (?,?,1,'path_glob',?)",
                    UUID.randomUUID(), id, path + "/**");
        });
        return id;
    }

    private void file(String project, String path) {
        jdbc.update(
                "INSERT INTO code_files(id,project_key,file_path,content_hash,language)"
                    + " VALUES (?,?,?,'hash','java')",
                UUID.randomUUID(), project, path);
    }

    @Test
    void moduleScopeMatchesDeclaredPrefixesAndNothingElse() {
        String project = "SCOPE_MODULE";
        file(project, "core/Service.java");
        file(project, "core/nested/Deep.java");
        file(project, "core"); // dizinin kendisi de eşleşmeli
        file(project, "corex/NotMatched.java"); // prefix tuzağı: eşleşMEmeli
        file(project, "web/Other.java");
        UUID id = rule(project, false, "core");

        var result = service.ruleScope(id.toString(), project, 50);

        assertThat(result.get("scopeType")).isEqualTo("MODULE");
        assertThat(result.get("coverage")).isEqualTo("module-paths");
        assertThat((List<String>) result.get("modulePaths")).containsExactly("core");
        assertThat((List<String>) result.get("matchedFiles"))
                .containsExactlyInAnyOrder("core", "core/Service.java", "core/nested/Deep.java");
        assertThat((List<String>) result.get("matchedFiles")).doesNotContain("corex/NotMatched.java", "web/Other.java");
        assertThat(result.get("matchedTotal")).isEqualTo(3);
        assertThat(result.get("truncated")).isEqualTo(false);
        assertThat(result.get("matcherContract")).isEqualTo("instruction-module-prefix/v1");
    }

    @Test
    void scopeMatchIsNeverPresentedAsCompliance() {
        String project = "SCOPE_COMPLIANCE";
        file(project, "core/A.java");
        UUID id = rule(project, false, "core");

        var result = service.ruleScope(id.toString(), project, 50);

        assertThat(result.get("complianceEvaluated")).isEqualTo(false);
        assertThat((String) result.get("complianceNote")).contains("is not evidence that the code complies");
    }

    @Test
    void projectWideAndGlobalScopeDoNotEnumeratePerFileEdges() {
        String project = "SCOPE_WIDE";
        file(project, "a/A.java");
        file(project, "b/B.java");

        var projectRule = service.ruleScope(rule(project, true).toString(), project, 50);
        assertThat(projectRule.get("scopeType")).isEqualTo("PROJECT");
        assertThat(projectRule.get("coverage")).isEqualTo("project-wide");
        // T41: proje geneli kapsam dosya başına kenar üretmez.
        assertThat((List<String>) projectRule.get("matchedFiles")).isEmpty();
        assertThat(projectRule.get("matchedTotal")).isEqualTo(2);

        var globalRule = service.ruleScope(rule(null, true).toString(), project, 50);
        assertThat(globalRule.get("scopeType")).isEqualTo("GLOBAL_STRICT");
        assertThat(globalRule.get("appliesToProject")).isEqualTo(true);
        assertThat((List<String>) globalRule.get("matchedFiles")).isEmpty();
    }

    @Test
    void otherProjectRuleIsReportedOutOfScope() {
        String project = "SCOPE_OWNER";
        file(project, "core/A.java");
        UUID foreign = rule("SCOPE_FOREIGN", false, "core");

        var result = service.ruleScope(foreign.toString(), project, 50);

        assertThat(result.get("appliesToProject")).isEqualTo(false);
        assertThat(result.get("coverage")).isEqualTo("out-of-scope");
        assertThat(result.get("matchedTotal")).isEqualTo(0);
    }

    @Test
    void moduleRuleWithoutBindingsIsUnresolvedNotEmptyMatch() {
        String project = "SCOPE_UNRESOLVED";
        file(project, "core/A.java");
        UUID id = rule(project, false); // MODULE ama hiç path bağlaması yok

        var result = service.ruleScope(id.toString(), project, 50);

        assertThat(result.get("coverage")).isEqualTo("unresolved");
        assertThat((String) result.get("warning")).contains("the scope could not be resolved");
    }

    @Test
    void matchedFileListIsBoundedAndReportsTruncation() {
        String project = "SCOPE_BOUNDED";
        for (int i = 0; i < 12; i++) file(project, "core/F" + i + ".java");
        UUID id = rule(project, false, "core");

        var result = service.ruleScope(id.toString(), project, 5);

        assertThat((List<String>) result.get("matchedFiles")).hasSize(5);
        assertThat(result.get("matchedTotal")).isEqualTo(12);
        assertThat(result.get("truncated")).isEqualTo(true);
    }

    @Test
    void unknownRuleIsReportedAsMissing() {
        assertThatThrownBy(() -> service.ruleScope(UUID.randomUUID().toString(), "ANY", 10))
                .isInstanceOf(NoSuchElementException.class);
    }
}
