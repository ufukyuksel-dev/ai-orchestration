package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class InstructionReadServiceTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    RulePromotionService promotion;
    RuleAuthoringService authoring;
    InstructionReadService reader;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        promotion = new RulePromotionService(new JdbcRuleRepository(jdbc, mapper), mock(RuleMemoryPromotionPort.class),
                new RulesProperties(true, 20, 256, 4096, 200), mapper, List.of());
        authoring = new RuleAuthoringService(new JdbcRuleAuthoringDraftRepository(jdbc), promotion, mapper);
        reader = new InstructionReadService(jdbc, mapper);
        jdbc.update("INSERT INTO scanner_project_roots(project_key,root_path) VALUES ('PROJECT_A','/a'),('PROJECT_B','/b')");
    }

    @Test
    void realDraftPreviewApprovalIsRequiredAndLongTextSurvives() {
        String text = "Ürün kapsamı. ".repeat(900);
        var draft = authoring.draft(null, request(text, List.of()), "test:isolated-db");
        assertThat(reader.read("PROJECT_A", null, null).instructions()).isEmpty();
        var preview = authoring.preview(draft.draftId(), null);
        assertThat(preview.confirmationCard()).contains(text.trim());
        var wrongHash = new DirectRulePromotionRequest(draft.draftId(), null, "0".repeat(64),
                preview.approvalContentHash(), preview.confirmationCardHash(), preview.workflowContractVersion(),
                "TEST approval in disposable PostgreSQL only", "test:1", true, 1);
        assertThatThrownBy(() -> tx.execute(s -> authoring.promote(wrongHash, "test:isolated-db")))
                .isInstanceOf(IllegalStateException.class);
        approve(draft, preview);
        var result = reader.read("PROJECT_A", null, null);
        assertThat(result.instructions()).singleElement().satisfies(r -> {
            assertThat(r.statement()).isEqualTo(text.trim());
            assertThat(r.scope()).isEqualTo("GLOBAL_STRICT");
        });
        assertThat(result.globalEffectiveSeq()).isEqualTo(1);
        assertThat(result.projectEffectiveSeq()).isZero();
        // global rules belong to no project, so reading only them needs no projectKey
        add("PROJECT_A", "project only", List.of());
        assertThat(reader.read(null, "global_strict", null).instructions()).singleElement()
                .satisfies(r -> assertThat(r.scope()).isEqualTo("GLOBAL_STRICT"));
    }

    @Test
    void filtersOldKindsAndDeprecatedVersionsAndReadsCurrentVersion() {
        var active = add("PROJECT_A", "old", List.of());
        var repo = new JdbcRuleRepository(jdbc, mapper);
        var old = repo.findVersion(active.ruleId(), 1).orElseThrow();
        var candidate = request("new", List.of()).toCandidate("PROJECT_A");
        var preview = promotion.previewVersion(old.ruleId(), 1, old.contentHash(), candidate);
        tx.execute(s -> promotion.promoteVersion(old.ruleId(), 1, old.contentHash(),
                new PromotionRequest(candidate, preview.approvalContentHash(), preview.confirmationCardHash(),
                        preview.workflowContractVersion(), new RuleHumanApprovalEvidence("test", "test:2", "TEST approve", true, 1))));
        var ordinary = new DirectRuleDraftRequest("ordinary", null, RuleEnforcement.CONTEXT, true, null, null,
                List.of(), List.of(), List.of());
        var draft = authoring.draft("PROJECT_A", ordinary, "test");
        approve(draft, authoring.preview(draft.draftId(), "PROJECT_A"));
        assertThat(reader.read("PROJECT_A", null, null).instructions()).singleElement().satisfies(i -> {
            assertThat(i.statement()).isEqualTo("new"); assertThat(i.version()).isEqualTo(2);
        });
        jdbc.update("UPDATE rule_definitions SET status='deprecated' WHERE id=?", active.ruleId());
        assertThat(reader.read("PROJECT_A", null, null).instructions()).isEmpty();
    }

    @Test
    void countAndByteOverflowAreErrorsNotPartialResults() {
        for (int n = 0; n < 101; n++) add("PROJECT_A", "r" + n, List.of());
        assertThatThrownBy(() -> reader.read("PROJECT_A", null, null))
                .isInstanceOf(InstructionReadService.CapacityExceeded.class)
                .hasMessageContaining("reason=count", "observedCount=101", "countIsLowerBound=true", "observedRuleIds=");
        for (int n = 0; n < 5; n++) add("PROJECT_B", "x".repeat(16_383) + n, List.of());
        assertThatThrownBy(() -> reader.read("PROJECT_B", null, null))
                .isInstanceOf(InstructionReadService.CapacityExceeded.class).hasMessageContaining("reason=bytes");
    }

    @Test
    void pagedReadReturnsEveryRuleExactlyOnceAndDetectsConcurrentChanges() {
        for (int n = 0; n < 150; n++) add("PROJECT_A", "rule " + n + " " + "y".repeat(400), List.of());
        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        InstructionReadService.Page page;
        do {
            page = reader.readPage("PROJECT_A", cursor);
            assertThat(page.result().instructions()).hasSizeLessThanOrEqualTo(InstructionReadService.PAGE_RULES);
            page.result().instructions().forEach(i -> seen.add(i.statement()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(pages).isGreaterThan(1);
        assertThat(page.result().complete()).isTrue();
        assertThat(seen).hasSize(150).doesNotHaveDuplicates();
        assertThat(page.total()).isEqualTo(150);

        String restart = reader.readPage("PROJECT_A", null).nextCursor();
        add("PROJECT_A", "added while paging", List.of());
        assertThatThrownBy(() -> reader.readPage("PROJECT_A", restart))
                .isInstanceOf(InstructionReadService.RulesChanged.class);
        assertThatThrownBy(() -> reader.readPage("PROJECT_A", "not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pagedCursorIsBoundToItsProjectEvenWhenSequenceValuesMatch() {
        for (int n = 0; n < 70; n++) add("PROJECT_A", "a rule " + n, List.of());
        String cursorA = reader.readPage("PROJECT_A", null).nextCursor();
        assertThat(cursorA).isNotNull();
        assertThatThrownBy(() -> reader.readPage("PROJECT_B", cursorA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different project");

        // Same offset and exactly project B's own sequence values, but minted for project A: still rejected.
        var firstB = reader.readPage("PROJECT_B", null).result();
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("60:" + firstB.globalEffectiveSeq() + ":" + firstB.projectEffectiveSeq() + ":PROJECT_A")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> reader.readPage("PROJECT_B", forged))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different project");
        String legacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("60:" + firstB.globalEffectiveSeq() + ":" + firstB.projectEffectiveSeq())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> reader.readPage("PROJECT_B", legacy)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directoryRulesMatchByPrefixAndFileRulesOnlyTheirOwnFile() {
        add("PROJECT_A", "dir rule", List.of("a/b/**"));
        var file = new DirectRuleDraftRequest("file rule", null, RuleEnforcement.INSTRUCTION, false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(BindingKind.FILE, "a/b/C.java"),
                        new RulePromotionCandidate.TargetBindingRequest(BindingKind.SYMBOL, "p.C#run(String)")),
                List.of(), List.of());
        var draft = authoring.draft("PROJECT_A", file, "test");
        approve(draft, authoring.preview(draft.draftId(), "PROJECT_A"));

        assertThat(statements("a/b/C.java")).containsExactlyInAnyOrder("dir rule", "file rule");
        assertThat(statements("a/b/D.java")).containsExactly("dir rule");
        assertThat(statements("a/bc/D.java")).isEmpty();
        assertThat(statements("a/b/C.javax")).containsExactly("dir rule");
        assertThat(reader.boundPaths("PROJECT_A")).containsExactly("a/b", "a/b/C.java");
        assertThat(reader.boundPaths("PROJECT_B")).isEmpty();
        assertThat(reader.read("PROJECT_A", null, null).instructions())
                .extracting(InstructionReadService.Instruction::modulePaths)
                .containsExactlyInAnyOrder(List.of("a/b"), List.of("a/b/C.java"));

        var symbolOnly = new DirectRuleDraftRequest("symbol only", null, RuleEnforcement.INSTRUCTION, false, null,
                null, List.of(new RulePromotionCandidate.TargetBindingRequest(BindingKind.SYMBOL, "p.C#run")),
                List.of(), List.of());
        assertThatThrownBy(() -> authoring.draft("PROJECT_A", symbolOnly, "test"))
                .hasMessageContaining("file binding");
    }

    private List<String> statements(String path) {
        return reader.read("PROJECT_A", "module", List.of(path)).instructions().stream()
                .map(InstructionReadService.Instruction::statement).toList();
    }

    @Test
    void rejectsMalformedPathsScopesAndUnknownProjects() {
        for (String path : List.of("../core", "/core", "core/", "core//a", "core*", "core\\a", "C:core", "core?", "core/[a]", "core/{a}")) {
            assertThatThrownBy(() -> reader.read("PROJECT_A", "module", List.of(path))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> authoring.draft("PROJECT_A", request("bad", List.of(path + "/**")), "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reader.read("UNKNOWN", null, null)).hasMessageContaining("unknown projectKey");
        assertThatThrownBy(() -> reader.read(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("PROJECT_A", "module", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("PROJECT_A", "wrong", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("PROJECT_A", "project", List.of("core"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationRunsAtDraftAndPromotionAndRejectsOversizeOrGlobalModule() {
        var oversized = request("ü".repeat(8193), List.of());
        assertThatThrownBy(() -> authoring.draft("PROJECT_A", oversized, "test"))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> promotion.promote(new PromotionRequest(oversized.toCandidate("PROJECT_A"),
                "a".repeat(64), "b".repeat(64), RulePromotionService.WORKFLOW_CONTRACT_VERSION,
                new RuleHumanApprovalEvidence("test", "test", "TEST approve", true, 1))))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> authoring.draft(null, request("global module", List.of("core/**")), "test"))
                .hasMessageContaining("module instructions require a project");
        var bad = new DirectRuleDraftRequest("bad", null, RuleEnforcement.INSTRUCTION, true, "diff_regex", Map.of(),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> authoring.draft("PROJECT_A", bad, "test")).hasMessageContaining("without detectors");
    }

    @Test
    void actualStreamableHttpRegistersAndInvokesInstructions() throws Exception {
        add(null, "HTTP global policy", List.of());
        var audit = mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger.class);
        var tool = new com.mbworldwideapps.aiorchestration.modules.mcp.server.RulesMcpTool(authoring, audit, mapper, reader);
        var auth = new com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuthenticationFilter(
                mock(com.mbworldwideapps.aiorchestration.modules.mcp.server.McpApiKeyRepository.class), audit,
                new com.mbworldwideapps.aiorchestration.config.LocalTrustProperties(true, "PROJECT_A", "test-http", List.of("rules.read")));
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(HttpFixture.class)
                .properties("spring.config.name=instruction-http-test", "server.address=127.0.0.1", "server.port=0",
                        "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.type=SYNC")
                .initializers(c -> {
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) c.getBeanFactory())
                            .registerBeanDefinition("rulesTool", new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.mbworldwideapps.aiorchestration.modules.mcp.server.RulesMcpTool.class, () -> tool));
                    var filter = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(auth);
                    filter.addUrlPatterns("/mcp", "/mcp/*");
                    c.getBeanFactory().registerSingleton("authFilter", filter);
                }).run()) {
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) context)
                    .getWebServer().getPort();
            var client = java.net.http.HttpClient.newHttpClient();
            var endpoint = java.net.URI.create("http://127.0.0.1:" + port + "/mcp");
            var init = post(client, endpoint, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
                     "capabilities":{},"clientInfo":{"name":"instruction-http-test","version":"1"}}}
                    """);
            assertThat(init.statusCode()).isEqualTo(200);
            String session = init.headers().firstValue("mcp-session-id").orElseThrow();
            post(client, endpoint, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            var listing = post(client, endpoint, session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            assertThat(listing.body()).contains("rules.instructions", "modulePaths");
            var call = post(client, endpoint, session, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rules.instructions",
                     "arguments":{"projectKey":"PROJECT_A","scope":"effective"}}}
                    """);
            assertThat(call.statusCode()).isEqualTo(200);
            assertThat(call.body()).contains("HTTP global policy", "globalEffectiveSeq", "GLOBAL_STRICT");
            assertThat(call.body()).doesNotContain("\"isError\":true");
        }
    }

    private java.net.http.HttpResponse<String> post(java.net.http.HttpClient client, java.net.URI endpoint,
            String session, String payload) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (session != null) request.header("Mcp-Session-Id", session);
        return client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
        org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.class,
        org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class
    })
    static class HttpFixture { }

    private DirectRuleDraftRequest request(String text, List<String> targets) {
        return new DirectRuleDraftRequest(text, null, RuleEnforcement.INSTRUCTION, targets.isEmpty(), null, null,
                targets.stream().map(t -> new RulePromotionCandidate.TargetBindingRequest(BindingKind.PATH_GLOB, t)).toList(),
                List.of(), List.of());
    }

    private RuleAuthoringPromotionResult add(String project, String text, List<String> targets) {
        var draft = authoring.draft(project, request(text, targets), "test:isolated-db");
        return approve(draft, authoring.preview(draft.draftId(), project));
    }

    private RuleAuthoringPromotionResult approve(RuleAuthoringDraftResult draft, RuleAuthoringPreviewResult preview) {
        return tx.execute(s -> authoring.promote(new DirectRulePromotionRequest(draft.draftId(), draft.projectKey(),
                draft.candidateHash(), preview.approvalContentHash(), preview.confirmationCardHash(),
                preview.workflowContractVersion(), "TEST approval in disposable PostgreSQL only", "test:1", true, 1),
                "test:isolated-db"));
    }
}
