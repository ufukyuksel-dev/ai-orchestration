package com.mbworldwideapps.aiorchestration.modules.rules;

import com.mbworldwideapps.aiorchestration.modules.memoryai.*;

import static org.assertj.core.api.Assertions.*;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Real HTTP/browser + production domain services + disposable PostgreSQL; vector projection is
 * isolated.
 */
@Testcontainers
@ActiveProfiles("local-first")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.ai.model.embedding=hashing",
            "ai-orchestration.graph.neo4j.enabled=false",
            "ai-orchestration.memory.auto-curation.enabled=false",
            "ai-orchestration.memory.relation-judge.enabled=false",
            "ai-orchestration.local-trust.default-project-key=WORKSPACE_TEST"
        })
class WorkspaceEndToEndTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir static Path referenceRoot;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("ai-orchestration.references.root-path", () -> referenceRoot.resolve("ai-references").toString());
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @LocalServerPort int port;
    @Autowired MemoryService memories;
    @Autowired RuleAuthoringService authoring;
    @Autowired WorkspaceRuleRevisionService revisions;
    @Autowired RuleRepository rules;
    @Autowired com.mbworldwideapps.aiorchestration.modules.workspace.WorkspaceReadService reads;
    @Autowired RulePromotionService promotion;
    @Autowired WorkspaceRuleOriginService origins;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean MemoryVectorIndex vectors;
    // job and personal memory talk to Qdrant when they start; these tests use neither
    @MockitoBean com.mbworldwideapps.aiorchestration.modules.mcp.server.JobMemoryMcpTool jobMemory;
    @MockitoBean com.mbworldwideapps.aiorchestration.modules.mcp.server.PersonalMemoryMcpTool personalMemory;

    @Test
    void memoryBackedRevisionUsesFreshPendingOriginAndRejectsTamperedApproval() throws Exception {
        var tx = new TransactionTemplate(transactions);
        var origin =
                origins.draft(
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        "WORKSPACE_TEST",
                        "Original memory-backed rule",
                        "test:isolated-db");
        var candidate =
                new RulePromotionCandidate(
                        origin.id(),
                        origin.contentHash(),
                        "WORKSPACE_TEST",
                        "Original memory-backed rule",
                        null,
                        RuleEnforcement.CONTEXT,
                        true,
                        null,
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        RuleProvenance.HUMAN);
        var initial = promotion.preview(candidate);
        var v1 =
                tx.execute(
                        t ->
                                promotion.promote(
                                        new PromotionRequest(
                                                candidate,
                                                initial.approvalContentHash(),
                                                initial.confirmationCardHash(),
                                                initial.workflowContractVersion(),
                                                new RuleHumanApprovalEvidence(
                                                        "test:isolated-db",
                                                        "test:seed",
                                                        "TEST fixture approval",
                                                        true,
                                                        1))));
        var discarded =
                revisions.preview(v1.ruleId(), "Unused alternative", null, "test:isolated-db");
        var preview =
                revisions.preview(
                        v1.ruleId(),
                        "Revised memory-backed rule",
                        "Fixture revision",
                        "test:isolated-db");
        assertThat(preview.edit().originId()).isNotEqualTo(origin.id());
        assertThat(memories.findById(preview.edit().originId()).status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        var bad =
                new WorkspaceRuleRevisionService.Approval(
                        preview.edit(),
                        "0".repeat(64),
                        preview.confirmation().confirmationCardHash(),
                        preview.confirmation().workflowContractVersion(),
                        "TEST explicit approval",
                        "test:bad");
        assertThatThrownBy(() -> revisions.activate(v1.ruleId(), bad, "test:isolated-db"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rules.findDefinition(v1.ruleId()).orElseThrow().currentVersion()).isEqualTo(1);
        assertThat(memories.findById(discarded.edit().originId()).status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        var good =
                new WorkspaceRuleRevisionService.Approval(
                        preview.edit(),
                        preview.confirmation().approvalContentHash(),
                        preview.confirmation().confirmationCardHash(),
                        preview.confirmation().workflowContractVersion(),
                        "TEST explicit approval",
                        "test:good");
        var v2 = revisions.activate(v1.ruleId(), good, "test:isolated-db");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(memories.findById(discarded.edit().originId()).status())
                .isEqualTo(MemoryStatus.ARCHIVED);
        assertThat(v2.originMemoryId()).isEqualTo(preview.edit().originId());
        assertThat(memories.findById(v2.originMemoryId()).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(rules.findVersion(v1.ruleId(), 1).orElseThrow().originMemoryId())
                .isEqualTo(origin.id());
        assertThat(memories.findById(origin.id()).text()).isEqualTo("Original memory-backed rule");
        var next = revisions.preview(v1.ruleId(), "Next revision draft", null, "test:isolated-db");
        assertThat(revisions.activate(v1.ruleId(), good, "test:isolated-db").version())
                .isEqualTo(2);
        assertThat(memories.findById(next.edit().originId()).status())
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        var history = reads.get("rule-version", v1.ruleId() + "~1");
        assertThat(history.text()).isEqualTo("Original memory-backed rule");
        assertThat(history.editableFields()).isEmpty();
        assertThat(reads.neighbors("rule", v1.ruleId().toString(), null, false, 50).nodes())
                .anyMatch(n -> n.id().equals(history.id()));
        assertThat(reads.neighbors("memory", origin.id().toString(), null, false, 50).nodes())
                .anyMatch(n -> n.id().equals(history.id()));
        assertThat(reads.neighbors("rule-version", v1.ruleId() + "~1", null, false, 50).nodes())
                .hasSize(3);
        var first = reads.neighbors("rule-version", v1.ruleId() + "~1", null, false, 1);
        assertThat(first.links()).hasSize(1);
        assertThat(first.nextCursor()).isNotNull();
        var second =
                reads.neighbors("rule-version", v1.ruleId() + "~1", first.nextCursor(), false, 1);
        assertThat(second.links()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void ruleHierarchySeparatesDirectRulesAndCanonicalTargetGroups() {
        String project = "LAYER_RULE_TEST";
        for (boolean scoped : List.of(false, true)) {
            var draft =
                    authoring.draft(
                            project,
                            new DirectRuleDraftRequest(
                                    scoped ? "Repository rules" : "Project rule",
                                    null,
                                    RuleEnforcement.CONTEXT,
                                    !scoped,
                                    null,
                                    Map.of(),
                                    scoped
                                            ? List.of(
                                                    new RulePromotionCandidate.TargetBindingRequest(
                                                            BindingKind.PATH_GLOB,
                                                            "**/*Repository.java"))
                                            : List.of(),
                                    List.of(),
                                    List.of()),
                            "test");
            var p = authoring.preview(draft.draftId(), project);
            authoring.promote(
                    new DirectRulePromotionRequest(
                            draft.draftId(),
                            project,
                            draft.candidateHash(),
                            p.approvalContentHash(),
                            p.confirmationCardHash(),
                            p.workflowContractVersion(),
                            "TEST fixture approval",
                            "test:hierarchy",
                            true,
                            1),
                    "test");
        }
        var nodes = reads.hierarchy("rule", project, "", "", "", null, 20).items();
        assertThat(nodes).hasSize(2);
        assertThat(nodes)
                .anyMatch(n -> n.kind().equals("rule") && n.title().equals("Project rule"));
        var group =
                nodes.stream()
                        .filter(n -> n.kind().equals("scope-group"))
                        .findFirst()
                        .orElseThrow();
        assertThat(group.title()).contains("**/*Repository.java");
        assertThat(
                        reads.hierarchy(
                                        "rule",
                                        project,
                                        group.info().get("group").toString(),
                                        "",
                                        "",
                                        null,
                                        20)
                                .items())
                .singleElement()
                .satisfies(n -> assertThat(n.title()).isEqualTo("Repository rules"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "workspace.browser.tests",
            matches = "true")
    void browserEditsMemoryAndActivatesHashBoundRuleVersion() throws Exception {
        jdbc.update(
                "INSERT INTO scanner_project_roots(project_key,root_path) VALUES"
                        + " ('WORKSPACE_TEST','/tmp/workspace-test') ON CONFLICT DO NOTHING");
        var m =
                memories.create(
                        new CreateMemoryRequest(
                                MemoryScope.PROJECT,
                                "WORKSPACE_TEST",
                                MemoryType.DECISION,
                                "Atlas browser fixture",
                                "Original browser fixture text",
                                List.of("fixture"),
                                1.0,
                                MemoryStatus.PENDING_REVIEW,
                                MemorySourceType.MANUAL,
                                "test:workspace",
                                "isolated-test",
                                Map.of(),
                                null,
                                null));
        var draft =
                authoring.draft(
                        "WORKSPACE_TEST",
                        new DirectRuleDraftRequest(
                                "Atlas rule fixture",
                                "Browser acceptance test",
                                RuleEnforcement.INSTRUCTION,
                                true,
                                null,
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        "test:isolated-db");
        var preview = authoring.preview(draft.draftId(), "WORKSPACE_TEST");
        var activated =
                authoring.promote(
                        new DirectRulePromotionRequest(
                                draft.draftId(),
                                "WORKSPACE_TEST",
                                draft.candidateHash(),
                                preview.approvalContentHash(),
                                preview.confirmationCardHash(),
                                preview.workflowContractVersion(),
                                "TEST fixture approval in disposable database",
                                "test:fixture",
                                true,
                                1),
                        "test:isolated-db");
        var command = new ProcessBuilder("node", "output/workspace-browser/acceptance.cjs");
        command.environment().put("WORKSPACE_URL", "http://127.0.0.1:" + port);
        command.environment().put("WORKSPACE_MEMORY_ID", m.id().toString());
        command.environment().put("WORKSPACE_RULE_ID", activated.ruleId().toString());
        command.redirectErrorStream(true)
                .redirectOutput(Path.of("output/workspace-browser-acceptance.log").toFile());
        Process process = command.start();
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .withFailMessage(
                        "Browser test failed; read output/workspace-browser-acceptance.log")
                .isZero();
        assertThat(memories.findById(m.id()).text())
                .isEqualTo("Edited through the real workspace browser");
        assertThat(rules.findDefinition(activated.ruleId()).orElseThrow().currentVersion())
                .isEqualTo(2);
        assertThat(rules.findVersion(activated.ruleId(), 1).orElseThrow().statement())
                .isEqualTo("Atlas rule fixture");
        assertThat(rules.findVersion(activated.ruleId(), 2).orElseThrow().statement())
                .isEqualTo("Atlas rule edited through browser");
    }
}
