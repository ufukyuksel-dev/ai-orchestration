package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationResponse;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GroundedPromptBuilder;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMGateway;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.MemoryMcpTool;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.MemoryReviewMcpTool;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One production-constructor, PostgreSQL-backed acceptance suite for the rule
 * activation chokepoint. Every legacy memory entry path must remain unable to
 * create executable policy while rule authority is enabled.
 */
@Testcontainers
class RuleMemoryActivationInvariantIntegrationTest {

    private static final String PROJECT = "AI_ORCHESTRATION";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactions;
    private JdbcMemoryRepository repository;
    private RuleMemoryLinkLookup linkLookup;
    private AiOrchestrationProperties properties;
    private MemoryService memoryService;
    private MemoryReviewService reviewService;
    private MemoryController memoryController;
    private MemoryReviewController reviewController;
    private MemoryInlineApprovalProxy inlineApproval;
    private MemoryMcpTool memoryMcpTool;
    private MemoryReviewMcpTool reviewMcpTool;
    private CuratedMemoryLoader curatedLoader;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Path global = Files.createDirectories(tempDir.resolve("global"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        properties = properties(global, project);
        RulesProperties enabledRules = rules(true);
        repository = new JdbcMemoryRepository(jdbcTemplate, new ObjectMapper(), enabledRules);
        linkLookup = new JdbcRuleMemoryLinkLookup(jdbcTemplate);
        memoryService = memoryService(enabledRules);
        reviewService = reviewService(enabledRules);
        curatedLoader = new CuratedMemoryLoader(properties, memoryService, mock(AiMetrics.class));
        memoryController = new MemoryController(memoryService, curatedLoader,
                mock(MemoryCaptureService.class), mock(MemoryRetrievalService.class));
        reviewController = new MemoryReviewController(reviewService);
        inlineApproval = new MemoryInlineApprovalProxy(memoryService, reviewService);
        McpAuditLogger auditLogger = mock(McpAuditLogger.class);
        memoryMcpTool = new MemoryMcpTool(mock(MemoryRetrievalService.class), memoryService, reviewService,
                new PiiScrubber(), auditLogger, properties, mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class), mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
        reviewMcpTool = new MemoryReviewMcpTool(memoryService, reviewService, inlineApproval, auditLogger);
    }

    @AfterEach
    void clearMcpContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void allApprovalAndStatusEntryPointsRejectRuleActivationButLeaveNonRulesUntouched() {
        assertThatThrownBy(() -> memoryController.create(request(MemoryType.RULE, MemoryStatus.ACTIVE,
                MemoryScope.PROJECT, "rest-create-active")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");
        assertThat(repository.list(null, null, null)).isEmpty();

        MemoryItem statusCandidate = pendingRule("rest-status");
        assertThatThrownBy(() -> memoryController.updateStatus(statusCandidate.id(),
                new MemoryStatusUpdateRequest(MemoryStatus.ACTIVE, "rest", "approve")))
                .hasMessageContaining("rules.promote");
        assertPending(statusCandidate.id());

        MemoryItem restApproval = pendingRule("rest-approve");
        assertThatThrownBy(() -> reviewController.approve(restApproval.id(),
                new ReviewDecisionRequest("admin", "approve")))
                .hasMessageContaining("rules.promote");
        assertPending(restApproval.id());

        MemoryItem mcpApproval = pendingRule("mcp-approve");
        useMcpScopes("memory.approve");
        assertThatThrownBy(() -> memoryMcpTool.approve(mcpApproval.id().toString(), "approve", "debug approval"))
                .hasMessageContaining("rules.promote");
        assertPending(mcpApproval.id());

        MemoryItem mcpConfirmation = pendingRule("mcp-confirm");
        useMcpScopes("memory.human_confirmed_approve");
        assertThatThrownBy(() -> reviewMcpTool.confirm(mcpConfirmation.id().toString(), "approve", true,
                "onaylıyorum", 0.99, "turn-rule-1", null, "human confirmed"))
                .hasMessageContaining("rules.promote");
        assertPending(mcpConfirmation.id());

        MemoryItem inlineCandidate = pendingRule("inline-approve");
        assertThatThrownBy(() -> inlineApproval.decide(inlineCandidate.id(),
                new MemoryInlineApprovalDecisionRequest("approve", "human", "approve", null, null,
                        null, null, "turn-inline-1")))
                .hasMessageContaining("rules.promote");
        assertPending(inlineCandidate.id());

        MemoryItem episodicRule = memoryService.create(request(MemoryType.RULE, MemoryStatus.PENDING_REVIEW,
                MemoryScope.EPISODIC, "episodic-promote"));
        assertThatThrownBy(() -> reviewController.promote(episodicRule.id(),
                new PromoteMemoryRequest(MemoryScope.PROJECT, PROJECT, "human", "promote")))
                .hasMessageContaining("rules.promote");
        assertPending(episodicRule.id());

        MemoryItem decision = memoryController.create(request(MemoryType.DECISION, MemoryStatus.ACTIVE,
                MemoryScope.PROJECT, "ordinary-decision"));
        assertThat(decision.status()).isEqualTo(MemoryStatus.ACTIVE);

        MemoryService legacyService = memoryService(rules(false));
        MemoryItem legacyActive = legacyService.create(request(MemoryType.RULE, MemoryStatus.ACTIVE,
                MemoryScope.PROJECT, "legacy-active"));
        assertThat(memoryController.updateStatus(legacyActive.id(),
                new MemoryStatusUpdateRequest(MemoryStatus.ACTIVE, "migration", "no-op")).status())
                .isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void curatedReloadKeepsApprovedOriginAndCreatesPendingRevisionCandidates() throws Exception {
        Path projectDirectory = Path.of(properties.memory().projectPath());
        Path driftFile = projectDirectory.resolve("controller-rule.yml");
        writeCuratedRule(driftFile, "controller-rule", "Controllers stay thin.");

        MemoryService flagOffService = memoryService(rules(false));
        CuratedMemoryLoader flagOffLoader = new CuratedMemoryLoader(properties, flagOffService, mock(AiMetrics.class));
        assertThat(flagOffLoader.loadAll().loaded()).isEqualTo(1);
        MemoryItem activeOrigin = repository.findBySourceRef("curated:project:" + PROJECT + ":controller-rule")
                .orElseThrow();
        assertThat(activeOrigin.status()).isEqualTo(MemoryStatus.ACTIVE);

        writeCuratedRule(driftFile, "controller-rule", "Controllers stay thin and delegate validation.");
        writeCuratedRule(projectDirectory.resolve("tests-required.yml"), "tests-required",
                "Every Java change must have tests.");
        CuratedMemoryLoadResult reload = curatedLoader.loadAll();

        assertThat(reload.rejected()).isZero();
        MemoryItem unchangedOrigin = repository.findById(activeOrigin.id()).orElseThrow();
        assertThat(unchangedOrigin.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(unchangedOrigin.text()).isEqualTo("Controllers stay thin.");
        assertThat(repository.list(MemoryScope.PROJECT, MemoryStatus.PENDING_REVIEW, PROJECT))
                .anySatisfy(candidate -> assertThat(candidate.metadata())
                        .containsEntry("revisionOf", activeOrigin.id().toString())
                        .containsEntry("sourceDrift", true))
                .anySatisfy(candidate -> assertThat(candidate.sourceRef())
                        .isEqualTo("curated:project:" + PROJECT + ":tests-required"));
    }

    @Test
    void mcpUpdateCannotMutateLinkedActiveRuleEvidence() {
        MemoryItem activeOrigin = memoryService(rules(false)).create(request(
                MemoryType.RULE, MemoryStatus.ACTIVE, MemoryScope.PROJECT, "linked-origin"));
        insertRuleEvidence(activeOrigin);
        useMcpScopes("memory.write");

        assertThatThrownBy(() -> memoryMcpTool.update(activeOrigin.id().toString(),
                "Changed authority", null, null, null, null, null, null, "repair"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be edited");

        assertThat(repository.findById(activeOrigin.id()).orElseThrow().summary())
                .isEqualTo(activeOrigin.summary());
    }

    @Test
    void disablingRuleAuthorityRestoresLegacyCreateAndApprovalBehavior() {
        RulesProperties disabledRules = rules(false);
        MemoryService legacyService = memoryService(disabledRules);
        MemoryReviewService legacyReview = reviewService(disabledRules);
        MemoryController legacyController = new MemoryController(legacyService,
                new CuratedMemoryLoader(properties, legacyService, mock(AiMetrics.class)),
                mock(MemoryCaptureService.class), mock(MemoryRetrievalService.class));
        MemoryReviewController legacyReviewController = new MemoryReviewController(legacyReview);

        MemoryItem directActive = legacyController.create(request(MemoryType.RULE, MemoryStatus.ACTIVE,
                MemoryScope.PROJECT, "legacy-direct"));
        MemoryItem pending = legacyController.create(request(MemoryType.RULE, MemoryStatus.PENDING_REVIEW,
                MemoryScope.PROJECT, "legacy-approval"));
        MemoryItem approved = legacyReviewController.approve(pending.id(),
                new ReviewDecisionRequest("legacy", "rollout disabled"));

        assertThat(directActive.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(approved.status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    private MemoryService memoryService(RulesProperties ruleProperties) {
        return new MemoryService(repository, properties, event -> {
        }, PolicyEngine.allowAll(), new PiiScrubber(), noOpVectorIndex(),
                new RuleMemoryActivationPolicy(ruleProperties), linkLookup);
    }

    private MemoryReviewService reviewService(RulesProperties ruleProperties) {
        return new MemoryReviewService(repository, noOpVectorIndex(), event -> {
        }, PolicyEngine.allowAll(), new PiiScrubber(),
                new RuleMemoryActivationPolicy(ruleProperties), linkLookup);
    }

    private MemoryItem pendingRule(String key) {
        return memoryService.create(request(MemoryType.RULE, MemoryStatus.PENDING_REVIEW,
                MemoryScope.PROJECT, key));
    }

    private void assertPending(UUID memoryId) {
        assertThat(repository.findById(memoryId).orElseThrow().status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
    }

    private void useMcpScopes(String... scopes) {
        McpClientContextHolder.set(new McpClientContext(PROJECT, "invariant-test", "test", List.of(scopes)));
    }

    private void insertRuleEvidence(MemoryItem origin) {
        UUID ruleId = UUID.randomUUID();
        String originHash = RuleMemoryContentHash.compute(origin.summary(), origin.text());
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    INSERT INTO rule_definitions
                        (id, origin_memory_id, project_key, current_version, status, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 'active', ?, ?)
                    """, ruleId, origin.id(), PROJECT, Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("""
                    INSERT INTO rule_versions
                        (rule_id, version, statement, enforcement, applies_all, detector_type,
                         detector_config, content_hash, origin_content_hash, origin_memory_id,
                         origin_provenance, approved_by, approved_at, human_turn_ref,
                         approval_content_hash, confirmation_card_hash, human_raw_text_hash,
                         workflow_contract_version, created_at)
                    VALUES (?, 1, ?, 'context', true, NULL, NULL, ?, ?, ?, 'human',
                            'alex', ?, 'turn-linked', ?, ?, ?, 'rules-authoring-v1', ?)
                    """, ruleId, "Linked authority", "d".repeat(64), originHash, origin.id(), Timestamp.from(now),
                    "a".repeat(64), "b".repeat(64), "c".repeat(64), Timestamp.from(now));
            jdbcTemplate.update("""
                    INSERT INTO rule_scope_assignments
                        (id, rule_id, rule_version, scope_type, project_key, ordinal, created_at)
                    VALUES (?, ?, 1, 'project', ?, 0, ?)
                    """, UUID.randomUUID(), ruleId, PROJECT, Timestamp.from(now));
        });
    }

    private static CreateMemoryRequest request(MemoryType type, MemoryStatus status, MemoryScope scope, String key) {
        return new CreateMemoryRequest(scope, PROJECT, type, "Summary " + key, "Text " + key,
                List.of("rule"), 0.95, status, MemorySourceType.MANUAL, "test:" + key, "tester", Map.of(),
                Instant.now(), null);
    }

    private static void writeCuratedRule(Path path, String id, String text) throws Exception {
        Files.writeString(path, """
                id: %s
                type: rule
                title: Rule %s
                text: %s
                tags: [architecture]
                confidence: 1.0
                owner: alex
                """.formatted(id, id, text));
    }

    private static RulesProperties rules(boolean enabled) {
        return new RulesProperties(enabled, 20, 256, 4096, 200);
    }

    private static MemoryVectorIndex noOpVectorIndex() {
        return new MemoryVectorIndex() {
            @Override
            public void upsert(MemoryItem item) {
            }

            @Override
            public void delete(MemoryItem item) {
            }

            @Override
            public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
                return List.of();
            }
        };
    }

    private static AiOrchestrationProperties properties(Path globalPath, Path projectPath) {
        AiOrchestrationProperties.Memory memory = new AiOrchestrationProperties.Memory(
                "memory_episodic_test_384", 2_000, 0.42, globalPath.toString(), projectPath.toString(),
                PROJECT, false, false, 500, 700, 800, 5, 365);
        return new AiOrchestrationProperties(1_200, 160, 5, "logs/rule-invariant-test.jsonl", 384,
                "qwen3:8b", null, null, null, null, null, null, memory,
                new AiOrchestrationProperties.Mcp(true, "sse", "/mcp", false, 5, 50, 10_000L), 365);
    }
}
