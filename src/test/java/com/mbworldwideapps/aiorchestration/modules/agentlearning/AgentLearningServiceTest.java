package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AgentLearningProperties;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.ReferenceProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Anchor;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Knowledge;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.KnowledgeBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SemanticCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedTarget;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.TaskContextPlan;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextItem;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextResponse;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.JdbcMemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryProjectionQueueFullException;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryActivationPolicy;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspace;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolutionException;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolver;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import com.mbworldwideapps.aiorchestration.modules.scanner.UnresolvedSymbolScanScheduler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AgentLearningServiceTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;

    @TempDir
    Path root;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE memory_items, agent_workspace_bindings CASCADE");
    }

    @Test
    void scenarioBAndEUseOneNavigationBatchWithOneReferenceAndNoContentReplay() throws Exception {
        Files.writeString(root.resolve("Routing.java"), "class Routing { String target = \"primary\"; }\n");
        Fixture fixture = fixture();
        String session = "9".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        fixture.service.resolveContext("principal-a", "codex", session, "P", binding,
                "where is routing decided", "locate", List.of("Routing.java"), List.of(), List.of(),
                512, "never");
        LearningBatch batch = new LearningBatch(List.of(new SemanticCandidate("navigation",
                "Routing changes here", "Routing.java contains the primary target selection.",
                List.of("t1"), List.of("t1"), List.of("routing work"), List.of(),
                List.of("future changes"), null,
                new LearningReference("# Routing investigation\nThe factory selects the primary route."))));

        assertThatThrownBy(() -> fixture.service.openActiveContext("principal-a", "codex", session, "P",
                List.of("t1"), 512)).hasMessage("TARGET_RANGE_REQUIRED");

        var created = fixture.service.learnSemantic("principal-a", "codex", session, "P", batch,
                "codex", "fixture-model");
        var replay = fixture.service.learnSemantic("principal-a", "codex", session, "P", batch,
                "codex", "fixture-model");

        assertThat(created.status()).isEqualTo("ACCEPTED");
        assertThat(created.created()).isEqualTo(1);
        assertThat(created.referencesCreated()).isEqualTo(1);
        assertThat(replay).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE memory_type='discovery'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM research_observations WHERE operation_kind='source_register'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations WHERE reference_id IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void scenarioANoLearningReturnsZeroReceiptWithoutPersistence() throws Exception {
        Fixture fixture = fixture();

        var receipt = fixture.service.learnSemantic("principal-a", "codex", "9".repeat(64), "P",
                new LearningBatch(List.of()), "codex", "fixture-model");

        assertThat(receipt).isEqualTo(new AgentLearningModels.CompactLearnReceipt(
                "ACCEPTED", 0, 0, 0, 0, 0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_operations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_observations", Integer.class)).isZero();
    }

    @Test
    void postTurnCaptureCreatesOnceAndReplaysByTaskRunWithoutRetrieval() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured { boolean enabled = true; }\n");
        Fixture fixture = fixture();
        String session = "4".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);
        List<CaptureCandidate> candidates = List.of(new CaptureCandidate("behavior",
                "Captured is enabled", "Captured.java initializes enabled to true.",
                List.of(fileLocator("Captured.java")), List.of(), List.of(), List.of()));

        var created = fixture.service.captureSemantic("principal-a", "runtime-hook", session, "P", binding,
                "task-run-1", candidates, "codex", "fixture-model");
        var replay = fixture.service.captureSemantic("principal-a", "runtime-hook", session, "P", binding,
                "task-run-1", candidates, "codex", "fixture-model");

        assertThat(created.status()).isEqualTo("ACCEPTED");
        assertThat(created.created()).isEqualTo(1);
        assertThat(replay).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE memory_type='discovery'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_contexts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_operations WHERE operation_key IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_observations WHERE operation_kind='source_register'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void captureReusesSameFactAcrossDifferentTaskRunsAndOperationKeys() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured { boolean enabled = true; }\n");
        Fixture fixture = fixture();
        String session = "4".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "claude-code", fixture.workspace);
        CaptureCandidate fact = new CaptureCandidate("behavior", "Captured is enabled",
                "Captured.java initializes enabled to true.", List.of(fileLocator("Captured.java")),
                List.of(), List.of(), List.of());
        CaptureCandidate reworded = new CaptureCandidate("behavior", "Captured flag defaults on",
                "Captured.java initializes enabled to true.", List.of(fileLocator("Captured.java")),
                List.of(), List.of(), List.of());

        var first = fixture.service.captureSemantic("principal-a", "claude-code", session, "P", binding,
                "task-a", List.of(fact), "claude_code", null);
        var otherTask = fixture.service.captureSemantic("principal-a", "claude-code", "5".repeat(64), "P", binding,
                "task-b", List.of(fact), "claude_code", null);
        var otherSummary = fixture.service.captureSemantic("principal-a", "claude-code", session, "P", binding,
                "task-c", List.of(reworded), "claude_code", null);

        assertThat(first.created()).isEqualTo(1);
        assertThat(otherTask.created()).isZero();
        assertThat(otherTask.reused()).isEqualTo(1);
        assertThat(otherSummary.created()).isZero();
        assertThat(otherSummary.reused()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_operations WHERE operation_key IS NOT NULL",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE memory_type='discovery'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void captureKeepsSameTextOnADifferentAnchorAsDistinctKnowledge() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured { boolean enabled = true; }\n");
        Files.writeString(root.resolve("Other.java"), "class Other { boolean enabled = true; }\n");
        Fixture fixture = fixture();
        String session = "4".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        String content = "The enabled flag is initialized to true.";

        fixture.service.captureSemantic("principal-a", "codex", session, "P", binding, "task-a",
                List.of(new CaptureCandidate("behavior", "Flag default", content,
                        List.of(fileLocator("Captured.java")), List.of(), List.of(), List.of())), "codex", null);
        var other = fixture.service.captureSemantic("principal-a", "codex", session, "P", binding, "task-b",
                List.of(new CaptureCandidate("behavior", "Flag default", content,
                        List.of(fileLocator("Other.java")), List.of(), List.of(), List.of())), "codex", null);

        assertThat(other.created()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE memory_type='discovery'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void postTurnCapturePersistsReferenceAndTranslatesCorrectionRefServerSide() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured { boolean enabled = true; }\n");
        Fixture fixture = fixture();
        String session = "4".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);
        CaptureCandidate original = new CaptureCandidate("behavior", "Captured flag",
                "Captured.enabled starts true.", List.of(fileLocator("Captured.java")),
                List.of(), List.of(), List.of(), null,
                new LearningReference("# Captured flag\nThe initialization is intentionally explicit."));

        var created = fixture.service.captureSemantic("principal-a", "runtime-hook", session, "P", binding,
                "task-run-reference", List.of(original), "codex", "fixture-model");
        UUID memoryId = jdbc.queryForObject("SELECT id FROM memory_items", UUID.class);
        Map<String, Object> profile = jdbc.queryForMap("""
                SELECT learning_revision,canonical_hash FROM memory_learning_profiles WHERE memory_id=?
                """, memoryId);
        var active = fixture.service.resolveContext("principal-a", "runtime-hook", session, "P", binding,
                "correct captured flag", "change", List.of("Captured.java"), List.of(), List.of(),
                512, "never");
        fixture.repository.insertKnowledgeBindings(active.context().id(), List.of(new KnowledgeBinding("k1",
                memoryId, ((Number) profile.get("learning_revision")).longValue(),
                (String) profile.get("canonical_hash"))));
        CaptureCandidate correction = new CaptureCandidate("behavior", "Captured flag",
                "Captured.enabled starts true and is read by the startup path.",
                List.of(fileLocator("Captured.java")), List.of(), List.of(), List.of(), "k1", null);

        var corrected = fixture.service.captureSemantic("principal-a", "runtime-hook", session, "P", binding,
                "task-run-correction", List.of(correction), "codex", "fixture-model");

        assertThat(created.referencesCreated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations WHERE reference_id IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(corrected.updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo(correction.content());
        assertThat(jdbc.queryForObject(
                "SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?", Long.class, memoryId))
                .isEqualTo(2L);
    }

    @Test
    void exactContentDedupReusesRealT1FactWhenSummaryWordingDiffers() throws Exception {
        Files.writeString(root.resolve("StringUtils.java"), "class StringUtils { static boolean isEmpty() { return false; } }\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "StringUtils whitespace behavior", "explain", List.of("StringUtils.java"), List.of(), 512, "never");
        UUID evidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        String content = "Returns true for null and empty input, false for a single space, and does not trim whitespace.";
        Candidate first = candidate(evidence, "StringUtils.isEmpty checks only nullity and length.", content);
        Candidate differentlyWorded = candidate(evidence, "StringUtils.isEmpty behavior", content);

        var created = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(first), "codex", "fixture-model");
        var reused = fixture.service.learn("principal-a", "P", context.context().id(),
                created.nextLearningHandle(), List.of(differentlyWorded), "claude_code", "fixture-model");

        assertThat(created.results()).singleElement().extracting(result -> result.outcome()).isEqualTo("CREATED");
        assertThat(reused.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("EXISTS");
            assertThat(result.memoryId()).isEqualTo(created.results().getFirst().memoryId());
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void changedContentRequiresChangedEvidenceBeforeUpdatingTheMemory() throws Exception {
        Files.writeString(root.resolve("Revision.java"), "class Revision {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "revision behavior", "explain", List.of("Revision.java"), List.of(), 512, "never");
        UUID evidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate first = candidate(evidence, "Stable revision summary", "The old behavior is active.");

        var created = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(first), "codex", "fixture-model");
        UUID memoryId = created.results().getFirst().memoryId();
        String oldContentRevision = jdbc.queryForObject(
                "SELECT content_revision FROM memory_learning_profiles WHERE memory_id=?", String.class, memoryId);
        Candidate changed = new Candidate(first.kind(), first.summary(), "The new behavior is active.",
                first.appliesWhen(), List.of("Only after the new route is enabled."), first.anchors(),
                first.evidenceIds(), first.reusableFor());

        var unchangedEvidence = fixture.service.learn("principal-a", "P", context.context().id(),
                created.nextLearningHandle(), List.of(changed), "codex", "fixture-model");

        assertThat(unchangedEvidence.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("EXISTS");
            assertThat(result.memoryId()).isEqualTo(memoryId);
            assertThat(result.reason()).isEqualTo("CONTENT_DIFFERS_EVIDENCE_UNCHANGED");
        });
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo("The old behavior is active.");
        assertThat(jdbc.queryForObject(
                "SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?", Long.class, memoryId))
                .isEqualTo(1L);

        Files.writeString(root.resolve("Revision.java"), "class Revision { boolean enabled = true; }\n");
        UUID changedEvidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate changedWithNewEvidence = new Candidate(changed.kind(), changed.summary(), changed.content(),
                changed.appliesWhen(), changed.limitations(), changed.anchors(), List.of(changedEvidence),
                changed.reusableFor());

        var updated = fixture.service.learn("principal-a", "P", context.context().id(),
                unchangedEvidence.nextLearningHandle(), List.of(changedWithNewEvidence), "codex", "fixture-model");

        assertThat(updated.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("UPDATED");
            assertThat(result.memoryId()).isEqualTo(memoryId);
        });
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo("The new behavior is active.");
        assertThat(jdbc.queryForObject(
                "SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?", Long.class, memoryId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT content_revision FROM memory_learning_profiles WHERE memory_id=?", String.class, memoryId))
                .isNotEqualTo(oldContentRevision);
    }

    @Test
    void hardDeleteStoragePrimitiveCascadesActiveContextKnowledgeBinding() throws Exception {
        Files.writeString(root.resolve("Bound.java"), "class Bound {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "bound memory", "locate", List.of("Bound.java"), List.of(), 512, "never");
        UUID evidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        UUID memoryId = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(candidate(evidence, "Bound fact", "Bound.java owns the fact.")),
                "codex", "fixture-model").results().getFirst().memoryId();
        Map<String, Object> profile = jdbc.queryForMap("""
                SELECT learning_revision,canonical_hash FROM memory_learning_profiles WHERE memory_id=?
                """, memoryId);
        fixture.repository.insertKnowledgeBindings(context.context().id(), List.of(new KnowledgeBinding("k1",
                memoryId, ((Number) profile.get("learning_revision")).longValue(),
                (String) profile.get("canonical_hash"))));

        new JdbcMemoryRepository(jdbc, new ObjectMapper(),
                new RulesProperties(false, 20, 256, 4096, 200)).deleteById(memoryId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE id=?", Integer.class, memoryId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM research_context_knowledge WHERE memory_id=?", Integer.class, memoryId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM research_contexts WHERE id=?", Integer.class, context.context().id()))
                .isEqualTo(1);
    }

    @Test
    void compactReceiptIsRejectedWhenEveryCapturedCandidateIsRejected() throws Exception {
        Files.writeString(root.resolve("Rejected.java"), "class Rejected {}\n");
        Fixture fixture = fixture(proxiedMemoryService());
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);
        List<CaptureCandidate> candidates = List.of(new CaptureCandidate("behavior", "Rejected detail",
                "Contact person@example.com for the behavior.",
                List.of(fileLocator("Rejected.java")), List.of(), List.of(), List.of()));

        var receipt = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-rejected", candidates, "codex", "fixture-model");

        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.created() + receipt.updated() + receipt.reused()).isZero();
        assertThat(receipt.rejected()).isEqualTo(1);
    }

    @Test
    void postTurnCaptureRejectsMissingSourcePerCandidateWithoutAbortingTheBatch() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);

        List<CaptureCandidate> candidates = List.of(
                new CaptureCandidate("behavior", "Valid capture", "Captured.java is the valid source.",
                        List.of(fileLocator("Captured.java")), List.of(), List.of(), List.of()),
                new CaptureCandidate("behavior", "Missing symbol", "This candidate must also be rejected.",
                        List.of(new CaptureLocator("symbol", "Captured#missing", "missing()",
                                "Missing.java", "supporting")), List.of(), List.of(), List.of()));

        var receipt = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-partial", candidates, "codex", null);

        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(receipt.created()).isEqualTo(1);
        assertThat(receipt.rejected()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_operations WHERE state='COMPLETED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void postTurnCaptureAcceptsUnresolvedSymbolAndQueuesOneBackendScan() throws Exception {
        Files.createDirectories(root.resolve("src/kestrel"));
        Files.writeString(root.resolve("src/kestrel/routing.py"),
                "class TenantRouteResolver:\n    def resolve(self):\n        return 'regional'\n");
        CodeBaselineRepository baseline = mock(CodeBaselineRepository.class);
        UnresolvedSymbolScanScheduler scheduler = mock(UnresolvedSymbolScanScheduler.class);
        Fixture fixture = fixture(proxiedMemoryService(), null, false, baseline, scheduler);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);
        CaptureCandidate candidate = new CaptureCandidate("behavior", "Tenant route fallback",
                "TenantRouteResolver.resolve returns the regional fallback.",
                List.of(new CaptureLocator("symbol", "kestrel.routing.TenantRouteResolver#resolve",
                        "resolve(configured_route: str | None, regional_fallback: str, audit: RouteAudit)",
                        "src/kestrel/routing.py", "primary_change_point")),
                List.of(), List.of(), List.of());

        var created = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-unresolved-symbol", List.of(candidate), "copilot", "gpt-5.4");
        var replay = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-unresolved-symbol", List.of(candidate), "copilot", "gpt-5.4");

        assertThat(created.status()).isEqualTo("ACCEPTED");
        assertThat(created.created()).isEqualTo(1);
        assertThat(created.rejected()).isZero();
        assertThat(replay).isEqualTo(created);
        verify(scheduler, times(1)).schedule("P", fixture.workspace.repositoryRoot(),
                List.of("src/kestrel/routing.py"));
        assertThat(jdbc.queryForMap("""
                SELECT locator_kind,symbol_key,canonical_ref,anchor_role
                FROM memory_navigation_anchors
                """))
                .containsEntry("locator_kind", "symbol")
                .containsEntry("symbol_key", "kestrel.routing.TenantRouteResolver#resolve")
                .containsEntry("canonical_ref", "src/kestrel/routing.py")
                .containsEntry("anchor_role", "primary_change_point");
    }

    @Test
    void postTurnCaptureFallsBackToUniquePathAndFqnWhenOptionalSignatureFormattingDiffers() throws Exception {
        Files.writeString(root.resolve("Captured.py"), "def resolve(value):\n    return value\n");
        CodeBaselineRepository baseline = mock(CodeBaselineRepository.class);
        UUID fileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        CodeSymbolRecord symbol = new CodeSymbolRecord(UUID.randomUUID(), "P", fileId, "function", "resolve",
                "Captured#resolve", "", "function", 1, 2, "a".repeat(64), runId, Map.of());
        when(baseline.findSymbolsByLocator("P", "Captured.py", "Captured#resolve",
                "resolve(value: str)", 2)).thenReturn(List.of());
        when(baseline.findSymbolsByLocator("P", "Captured.py", "Captured#resolve", null, 2))
                .thenReturn(List.of(symbol));
        Fixture fixture = fixture(proxiedMemoryService(), null, false, baseline);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);

        var receipt = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-signature-fallback", List.of(new CaptureCandidate("behavior",
                        "Unique Python symbol", "The unique symbol should resolve despite signature formatting.",
                        List.of(new CaptureLocator("symbol", "Captured#resolve", "resolve(value: str)",
                                "Captured.py", "supporting")), List.of(), List.of(), List.of())),
                "copilot", "gpt-5.4");

        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(receipt.created()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT symbol_key FROM memory_navigation_anchors", String.class))
                .isEqualTo("Captured#resolve");
    }

    @Test
    void postTurnCaptureRejectsAmbiguousSymbolWithoutWritingMemory() throws Exception {
        Files.writeString(root.resolve("Captured.java"), "class Captured { void run() {} void run(int x) {} }\n");
        CodeBaselineRepository baseline = mock(CodeBaselineRepository.class);
        UUID fileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(baseline.findSymbolsByLocator("P", "Captured.java", "Captured#run", null, 2))
                .thenReturn(List.of(
                        new CodeSymbolRecord(UUID.randomUUID(), "P", fileId, "method", "run", "Captured#run",
                                "run()", "method", 1, 1, "a".repeat(64), runId, Map.of()),
                        new CodeSymbolRecord(UUID.randomUUID(), "P", fileId, "method", "run", "Captured#run",
                                "run(int)", "method", 1, 1, "b".repeat(64), runId, Map.of())));
        Fixture fixture = fixture(proxiedMemoryService(), null, false, baseline);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);

        var receipt = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-ambiguous", List.of(new CaptureCandidate("behavior", "Ambiguous run",
                        "Captured.run is overloaded.", List.of(new CaptureLocator("symbol", "Captured#run", null,
                                "Captured.java", "supporting")), List.of(), List.of(), List.of())),
                "codex", null);

        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.rejected()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isZero();
    }

    @Test
    void postTurnCaptureResolvesMultipleSymbolsInOneFileAndReturnsReadableLocatorMetadata() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.writeString(root.resolve("src/main/java/com/acme/Parser.java"),
                "package com.acme; class Parser { String parse(String value) { return value; } }\n");
        Files.createDirectories(root.resolve("modules/parser"));
        CodeBaselineRepository baseline = mock(CodeBaselineRepository.class);
        UUID fileId = UUID.randomUUID();
        UUID scanRunId = UUID.randomUUID();
        when(baseline.findSymbolsByLocator("P", "src/main/java/com/acme/Parser.java",
                "com.acme.Parser", null, 2)).thenReturn(List.of(new CodeSymbolRecord(
                        UUID.randomUUID(), "P", fileId, "class", "Parser", "com.acme.Parser",
                        "class Parser", "domain", 1, 1, "a".repeat(64), scanRunId, Map.of())));
        when(baseline.findSymbolsByLocator("P", "src/main/java/com/acme/Parser.java",
                "com.acme.Parser#parse", "parse(String)", 2)).thenReturn(List.of(new CodeSymbolRecord(
                        UUID.randomUUID(), "P", fileId, "method", "parse", "com.acme.Parser#parse",
                        "parse(String)", "domain", 1, 1, "b".repeat(64), scanRunId, Map.of())));
        Fixture fixture = fixture(proxiedMemoryService(), null, false, baseline);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "runtime-hook", fixture.workspace);
        List<CaptureLocator> locators = List.of(
                new CaptureLocator("symbol", "com.acme.Parser", null,
                        "src/main/java/com/acme/Parser.java", "supporting"),
                new CaptureLocator("symbol", "com.acme.Parser.parse", "parse(String)",
                        "src/main/java/com/acme/Parser.java", "primary_change_point"),
                new CaptureLocator("directory", "modules/parser", null, null, "supporting"));

        var receipt = fixture.service.captureSemantic("principal-a", "runtime-hook", "4".repeat(64),
                "P", binding, "task-run-symbols", List.of(new CaptureCandidate("behavior",
                        "Parser keeps its input", "Parser.parse returns its input unchanged.", locators,
                        List.of(), List.of(), List.of())), "codex", "fixture-model");

        assertThat(receipt.created()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT locator_kind,symbol_key,canonical_ref,anchor_role
                FROM memory_navigation_anchors ORDER BY locator_index
                """))
                .extracting(row -> row.get("locator_kind"), row -> row.get("symbol_key"),
                        row -> row.get("canonical_ref"), row -> row.get("anchor_role"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("symbol", "com.acme.Parser",
                                "src/main/java/com/acme/Parser.java", "supporting"),
                        org.assertj.core.groups.Tuple.tuple("symbol", "com.acme.Parser#parse(String)",
                                "src/main/java/com/acme/Parser.java", "primary_change_point"),
                        org.assertj.core.groups.Tuple.tuple("directory", null,
                                "modules/parser", "supporting"));
        String metadata = jdbc.queryForObject("SELECT metadata::text FROM memory_items", String.class);
        assertThat(metadata).contains("com.acme.Parser#parse(String)",
                "src/main/java/com/acme/Parser.java", "primary_change_point");
        assertThat(metadata).doesNotContain(scanRunId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_observations", Integer.class)).isEqualTo(2);
    }

    @Test
    void semanticContentLimitDoesNotShrinkWhenOptionalMetadataIsPresent() throws Exception {
        Files.writeString(root.resolve("Metadata.java"), "class Metadata {}\n");
        Fixture fixture = fixture();
        String session = "5".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        fixture.service.resolveContext("principal-a", "codex", session, "P", binding,
                "metadata behavior", "explain", List.of("Metadata.java"), List.of(), List.of(), 512, "never");
        LearningBatch batch = new LearningBatch(List.of(new SemanticCandidate("behavior", "Metadata behavior",
                "x".repeat(700), List.of("t1"), List.of("t1"), List.of("applies to metadata work"),
                List.of("not for unrelated work"), List.of("future metadata changes"), null, null)));

        var receipt = fixture.service.learnSemantic("principal-a", "codex", session, "P", batch,
                "codex", "fixture-model");

        assertThat(receipt.created()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT length(text) FROM memory_items", Integer.class)).isEqualTo(700);
    }

    @Test
    void scenarioDExistingSemanticLearningIsReusedAcrossContexts() throws Exception {
        Files.writeString(root.resolve("Routing.java"), "class Routing { String target = \"primary\"; }\n");
        Fixture fixture = fixture();
        String session = "6".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        LearningBatch batch = new LearningBatch(List.of(new SemanticCandidate("navigation",
                "Routing changes here", "Routing.java contains the primary target selection.",
                List.of("t1"), List.of("t1"), List.of("routing work"), List.of(),
                List.of("future changes"), null, null)));
        fixture.service.resolveContext("principal-a", "codex", session, "P", binding,
                "where is routing decided", "locate", List.of("Routing.java"), List.of(), List.of(),
                512, "never");
        var first = fixture.service.learnSemantic("principal-a", "codex", session, "P", batch,
                "codex", "fixture-model");
        fixture.service.resolveContext("principal-a", "codex", session, "P", binding,
                "where is routing changed", "locate", List.of("Routing.java"), List.of(), List.of(),
                512, "never");

        var second = fixture.service.learnSemantic("principal-a", "codex", session, "P", batch,
                "codex", "fixture-model");

        assertThat(first.created()).isEqualTo(1);
        assertThat(second.created()).isZero();
        assertThat(second.reused()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE memory_type='discovery'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void semanticCorrectionRestoresCasFromKRefAndRejectsAnotherSession() throws Exception {
        Files.writeString(root.resolve("Routing.java"), "class Routing { String target = \"primary\"; }\n");
        Fixture fixture = fixture();
        String session = "8".repeat(64);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        fixture.service.resolveContext("principal-a", "codex", session, "P", binding, "routing", "locate",
                List.of("Routing.java"), List.of(), List.of(), 512, "never");
        LearningBatch original = new LearningBatch(List.of(new SemanticCandidate("behavior", "Routing behavior",
                "The primary route is selected here.", List.of("t1"), List.of("t1"), List.of(), List.of(),
                List.of(), null, null)));
        fixture.service.learnSemantic("principal-a", "codex", session, "P", original, "codex", null);
        Map<String, Object> profile = jdbc.queryForMap("""
                SELECT memory_id,learning_revision,canonical_hash FROM memory_learning_profiles
                """);
        var refreshed = fixture.service.resolveContext("principal-a", "codex", session, "P", binding,
                "routing correction", "locate", List.of("Routing.java"), List.of(), List.of(), 512, "never");
        fixture.repository.insertKnowledgeBindings(refreshed.context().id(), List.of(new KnowledgeBinding("k1",
                (UUID) profile.get("memory_id"), ((Number) profile.get("learning_revision")).longValue(),
                (String) profile.get("canonical_hash"))));
        LearningBatch correction = new LearningBatch(List.of(new SemanticCandidate("behavior", "Routing behavior",
                "The primary route is selected only when the fallback is disabled.", List.of("t1"), List.of("t1"),
                List.of(), List.of(), List.of(), "k1", null)));

        var receipt = fixture.service.learnSemantic("principal-a", "codex", session, "P", correction,
                "codex", null);

        assertThat(receipt.updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles", Long.class))
                .isEqualTo(2L);
        assertThatThrownBy(() -> fixture.service.learnSemantic("principal-a", "codex", "7".repeat(64), "P",
                correction, "codex", null)).hasMessageContaining("ACTIVE_CONTEXT_REQUIRED");
    }

    @Test
    void createsReplaysConflictsAndDeduplicatesWithoutSharingPrivateReceipt() throws Exception {
        Path source = Files.writeString(root.resolve("Routing.java"), "class Routing { String target = \"primary\"; }\n");
        Fixture fixture = fixture();
        var binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "where is routing decided", "locate", List.of("Routing.java"), List.of(), 512, "never");
        Map<String, Object> issued = jdbc.queryForMap(
                "SELECT state,payload_hash FROM learning_operations WHERE request_id=?", context.requestId());
        assertThat(issued.get("state")).isEqualTo("ISSUED");
        assertThat(issued.get("payload_hash")).isNull();
        var opened = fixture.service.openContext("principal-a", "P", context.context().id(), List.of("t1"), 512);
        assertThat(jdbc.queryForObject(
                "SELECT provenance FROM research_observations WHERE id=?", String.class,
                opened.items().getFirst().evidenceId())).isEqualTo("server_observed");
        Candidate candidate = candidate(opened.items().getFirst().evidenceId(), "Routing changes here",
                "Routing.java contains the primary target selection.");

        var created = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(candidate), "codex", "fixture-model");
        var replay = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(candidate), "codex", "fixture-model");

        assertThat(created.status()).isEqualTo("COMPLETED");
        assertThat(created.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("CREATED");
            assertThat(result.indexState()).isEqualTo("PENDING");
            assertThat(result.graphState()).isEqualTo("PENDING");
        });
        assertThat(replay).isEqualTo(created);
        assertThat(created.nextLearningHandle()).isNotNull();
        assertThat(fixture.repository.findDiscoveryRoutes("P",
                List.of(created.results().getFirst().memoryId()), 3)).singleElement().satisfies(route -> {
                    assertThat(route.memoryId()).isEqualTo(created.results().getFirst().memoryId());
                    assertThat(route.verification()).isEqualTo("SUPPORTED");
                    assertThat(route.anchors()).singleElement().satisfies(anchor -> {
                        assertThat(anchor.relativePath()).isEqualTo("Routing.java");
                        assertThat(anchor.expectedHash()).hasSize(64);
                    });
                });

        Candidate changedPayload = candidate(opened.items().getFirst().evidenceId(), "Routing changes here",
                "A different claim for the already-bound handle.");
        var conflict = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(changedPayload), "codex", "fixture-model");
        assertThat(conflict.results()).singleElement().extracting(result -> result.outcome())
                .isEqualTo("CONFLICT");

        var duplicate = fixture.service.learn("principal-a", "P", context.context().id(),
                created.nextLearningHandle(), List.of(candidate), "claude_code", "fixture-model");
        assertThat(duplicate.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("EXISTS");
            assertThat(result.memoryId()).isEqualTo(created.results().getFirst().memoryId());
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);

        var invalidEvidence = fixture.service.learn("principal-a", "P", context.context().id(),
                duplicate.nextLearningHandle(),
                List.of(candidate(UUID.randomUUID(), "Unknown evidence", "This evidence ID was never observed.")),
                "copilot", null);
        assertThat(invalidEvidence.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("NOT_SAVED");
            assertThat(result.reason()).isEqualTo("EVIDENCE_SCOPE_DENIED");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);

        assertThat(fixture.service.operationStatus("principal-a", "P", created.requestId(), null))
                .isEqualTo(created);
        assertThatThrownBy(() -> fixture.service.operationStatus("principal-b", "P", created.requestId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OPERATION_SCOPE_DENIED");
        assertThatThrownBy(() -> fixture.service.operationStatus("principal-a", "P", UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REQUEST_ID_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE memory_items SET status='stale' WHERE id=?", created.results().getFirst().memoryId());
        assertThat(fixture.repository.findDiscoveryRoutes("P",
                List.of(created.results().getFirst().memoryId()), 3)).singleElement()
                .extracting(route -> route.stale()).isEqualTo(true);
        assertThat(source).exists();
    }

    @Test
    void operationStatusRecoversCompletedResultAfterCallerTimeoutWithoutCreatingMemory() throws Exception {
        Files.writeString(root.resolve("TimeoutReceipt.java"), "class TimeoutReceipt { int value; }\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "where is the timeout receipt value", "locate", List.of("TimeoutReceipt.java"),
                List.of(), 512, "never");
        var opened = fixture.service.openContext("principal-a", "P", context.context().id(), List.of("t1"), 512);
        Candidate candidate = candidate(opened.items().getFirst().evidenceId(), "Timeout receipt location",
                "TimeoutReceipt.java contains the value used by the timeout receipt path.");

        // Model a client-side timeout/lost response: the durable operation completes,
        // but the caller discards the direct return and recovers solely by requestId.
        fixture.service.learn("principal-a", "P", context.context().id(), context.learningHandle(),
                List.of(candidate), "codex", "fixture-model");
        int countAfterLearn = jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class);

        var recovered = fixture.service.operationStatus("principal-a", "P", context.requestId(), null);

        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("CREATED");
            assertThat(result.memoryId()).isNotNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class))
                .isEqualTo(countAfterLearn).isEqualTo(1);
    }

    @Test
    void projectionQueueBackpressurePersistsRetryableReceiptAndSurfacesNamedError() throws Exception {
        Files.writeString(root.resolve("QueueFull.java"), "class QueueFull { int value; }\n");
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.createDiscovery(any())).thenThrow(new MemoryProjectionQueueFullException(2));
        Fixture fixture = fixture(memoryService);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "where is queue backpressure handled", "locate", List.of("QueueFull.java"),
                List.of(), 512, "never");
        var opened = fixture.service.openContext("principal-a", "P", context.context().id(), List.of("t1"), 512);
        Candidate candidate = candidate(opened.items().getFirst().evidenceId(), "Queue backpressure location",
                "QueueFull.java contains the bounded queue behavior.");

        assertThatThrownBy(() -> fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(candidate), "codex", "fixture-model"))
                .isInstanceOf(MemoryProjectionQueueFullException.class)
                .hasMessage("MEMORY_PROJECTION_QUEUE_FULL: capacity=2");

        var recovered = fixture.service.operationStatus("principal-a", "P", context.requestId(), null);
        assertThat(recovered.status()).isEqualTo("RETRYABLE_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isZero();
    }

    @Test
    void sharesCanonicalDiscoveryAcrossAllRuntimeDirectionsWithoutSharingPrivateHandles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var transfer = mapper.readTree(Files.readString(
                Path.of("contracts/agent-learning/transfer-fixtures.json")));
        var parity = mapper.readTree(Files.readString(
                Path.of("contracts/agent-learning/runtime-parity.json")));
        var scenario = transfer.path("scenario");
        List<String> observableSources = new java.util.ArrayList<>();
        scenario.path("t1").path("observableSources").forEach(node -> observableSources.add(node.asText()));
        List<String> currentChecks = new java.util.ArrayList<>();
        scenario.path("t2").path("requiredCurrentChecks").forEach(node -> currentChecks.add(node.asText()));
        for (String relativePath : observableSources) {
            Path source = root.resolve(relativePath);
            Files.createDirectories(source.getParent());
            Files.writeString(source, relativePath.endsWith("Test.java")
                    ? "class CardResolverTest { void fallbackBoundary() {} }\n"
                    : "class CardResolver { String fallback = \"secondary\"; }\n");
        }
        assertThat(observableSources).containsExactlyElementsOf(currentChecks);
        assertThat(transfer.path("matrix").size()).isEqualTo(9);
        for (var direction : transfer.path("matrix")) {
                String producerRuntime = direction.path("producer").asText();
                String consumerRuntime = direction.path("consumer").asText();
                String producer = parity.path("adapters").path(producerRuntime)
                        .path("producerRuntime").asText();
                String consumer = parity.path("adapters").path(consumerRuntime)
                        .path("producerRuntime").asText();
                jdbc.execute("TRUNCATE memory_items, agent_workspace_bindings CASCADE");
                Fixture fixture = fixture();
                String producerPrincipal = "producer-" + producer;
                UUID producerBinding = fixture.service.issueWorkspaceBinding(
                        producerPrincipal, producer, fixture.workspace);
                var t1 = fixture.service.resolveContext(producerPrincipal, producer, "P", producerBinding,
                        scenario.path("t1").path("prompt").asText(), "locate",
                        observableSources, List.of(), 512, "never");
                var t1Opened = fixture.service.openContext(
                        producerPrincipal, "P", t1.context().id(), List.of("t1"), 512);
                Candidate discovery = candidate(t1Opened.items().getFirst().evidenceId(),
                        "Fallback route location",
                        scenario.path("t1").path("discovery").asText());
                var learned = fixture.service.learn(producerPrincipal, "P", t1.context().id(),
                        t1.learningHandle(), List.of(discovery), producer, "fixture-model");
                assertThat(learned.results()).singleElement().extracting(result -> result.outcome())
                        .isEqualTo("CREATED");
                UUID canonicalMemoryId = learned.results().getFirst().memoryId();

                String consumerPrincipal = "consumer-" + consumer;
                UUID consumerBinding = fixture.service.issueWorkspaceBinding(
                        consumerPrincipal, consumer, fixture.workspace);
                var t2 = fixture.service.resolveContext(consumerPrincipal, consumer, "P", consumerBinding,
                        scenario.path("t2").path("prompt").asText(), "change",
                        currentChecks, List.of(), 512, "never");
                var t2Opened = fixture.service.openContext(
                        consumerPrincipal, "P", t2.context().id(), List.of("t1"), 512);
                Candidate independentlyObserved = candidate(t2Opened.items().getFirst().evidenceId(),
                        "Fallback route location",
                        scenario.path("t1").path("discovery").asText());
                var duplicate = fixture.service.learn(consumerPrincipal, "P", t2.context().id(),
                        t2.learningHandle(), List.of(independentlyObserved), consumer, "fixture-model");

                assertThat(t2.context().id()).isNotEqualTo(t1.context().id());
                assertThat(t2.learningHandle()).isNotEqualTo(t1.learningHandle());
                assertThat(t2.context().targets()).extracting(target -> target.relativePath())
                        .containsExactlyElementsOf(currentChecks);
                assertThat(duplicate.results()).singleElement().satisfies(result -> {
                    assertThat(result.outcome()).isEqualTo("EXISTS");
                    assertThat(result.memoryId()).isEqualTo(canonicalMemoryId);
                });
                assertThatThrownBy(() -> fixture.service.operationStatus(
                        consumerPrincipal, "P", learned.requestId(), null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("OPERATION_SCOPE_DENIED");
        }
    }

    @Test
    void consumerResolveNavigatesToProducerDiscoveryAcrossPrincipalAndRuntime() throws Exception {
        Path source = Files.writeString(root.resolve("SharedRoute.java"),
                "class SharedRoute { String fallback = \"secondary\"; }\n");
        Fixture producer = fixture();
        String producerPrincipal = "producer-claude";
        UUID producerBinding = producer.service.issueWorkspaceBinding(
                producerPrincipal, "claude_code", producer.workspace);
        var t1 = producer.service.resolveContext(producerPrincipal, "claude_code", "P", producerBinding,
                "investigate where the shared fallback route is selected", "locate",
                List.of("SharedRoute.java"), List.of(), 512, "never");
        var opened = producer.service.openContext(
                producerPrincipal, "P", t1.context().id(), List.of("t1"), 512);
        String summary = "Shared fallback route";
        String content = "SharedRoute.java selects the secondary route only when primary is absent.";
        var learned = producer.service.learn(producerPrincipal, "P", t1.context().id(),
                t1.learningHandle(), List.of(candidate(opened.items().getFirst().evidenceId(), summary, content)),
                "claude_code", "fixture-model");
        UUID memoryId = learned.results().getFirst().memoryId();

        LearningContextService contexts = mock(LearningContextService.class);
        MemoryContextItem selected = new MemoryContextItem(memoryId, UUID.randomUUID(), "project:" + memoryId,
                MemoryScope.PROJECT, "P", MemoryType.DISCOVERY, summary, content, 0.9,
                false, 30, 0.95, Instant.now());
        MemoryContextResponse memories = new MemoryContextResponse(List.of(selected), List.of(memoryId),
                1, 30, Map.of("user", 0, "global", 0, "project", 1, "episodic", 0), 0, 0, 0L);
        LearningContextItem selectedItem = new LearningContextItem("memory", memoryId.toString(), summary,
                "project:" + memoryId, content, 0.95, 30, Map.of());
        when(contexts.retrieveExplicit(any())).thenReturn(new LearningContextResponse(
                "P", List.of(selectedItem), memories, null, 30,
                false, false, List.of(), Map.of()));
        TaskContextFacade realFacade = new TaskContextFacade(contexts, producer.repository);
        Fixture consumer = fixture(mock(MemoryService.class), realFacade, true);
        String consumerPrincipal = "consumer-codex";
        UUID consumerBinding = consumer.service.issueWorkspaceBinding(
                consumerPrincipal, "codex", consumer.workspace);

        var t2 = consumer.service.resolveContext(consumerPrincipal, "codex", "P", consumerBinding,
                "add a metric when shared fallback selection occurs", "change",
                List.of(), List.of(), List.of(), 1200, "never");

        assertThat(t2.status()).isEqualTo("FOCUSED");
        assertThat(t2.context().id()).isNotEqualTo(t1.context().id());
        assertThat(t2.learningHandle()).isNotEqualTo(t1.learningHandle());
        assertThat(t2.knowledge()).singleElement().satisfies(knowledge -> {
            assertThat(knowledge.id()).isEqualTo(memoryId);
            assertThat(knowledge.freshness()).isEqualTo("UNCHANGED");
        });
        assertThat(t2.context().targets()).singleElement().satisfies(target ->
                assertThat(target.relativePath()).isEqualTo("SharedRoute.java"));
        assertThat(t2.sourceChecks()).containsEntry("t1", "UNCHANGED");
        assertThat(source).exists();
    }

    @Test
    void persistsAndOpensOnlyIssuedReferenceSectionThenReportsLocalDrift() throws Exception {
        UUID memoryId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory_items
                    (id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','discovery','deploy','detail',0.9,'active','mcp_external')
                """, memoryId, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO reference_items(id,relative_path,parent_path,kind,content_hash,status)
                VALUES (?,'ops.md','','file',?,'current')
                """, referenceId, "a".repeat(64));
        TaskContextFacade taskContexts = mock(TaskContextFacade.class);
        when(taskContexts.resolve(anyString(), eq("P"), eq("change"), eq(1200), eq(true))).thenReturn(
                new TaskContextPlan(
                        List.of(new Knowledge(memoryId, "discovery", "Use the deployment procedure section.",
                                true, "SUPPORTED", "UNKNOWN")),
                        List.of(),
                        List.of(new SuggestedReference(referenceId, "ops.md", "a".repeat(64),
                                "deploy~1", memoryId, "UNCHANGED")),
                        List.of(), 0, 0, false));
        var item = new ReferenceService.Item(referenceId, "ops.md", "file", "a".repeat(64), "current");
        when(taskContexts.openReference(referenceId, "a".repeat(64), "deploy~1", 4800)).thenReturn(
                new ReferenceService.SectionRead(item, "deploy~1", "Deploy", "# Deploy\nstep one\n",
                        true, "b".repeat(64), false));
        Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

        var resolved = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "change deployment flow", "change", List.of(), List.of(), List.of(), 1200, "always");

        assertThat(resolved.status()).isEqualTo("FOCUSED");
        assertThat(resolved.context().references()).singleElement().satisfies(reference -> {
            assertThat(reference.targetId()).isEqualTo("r1");
            assertThat(reference.referenceId()).isEqualTo(referenceId);
            assertThat(reference.sectionKey()).isEqualTo("deploy~1");
        });
        assertThat(resolved.sourceChecks()).containsEntry("r1", "UNCHANGED");
        assertThat(fixture.repository.findContext(resolved.context().id()).orElseThrow().references())
                .isEqualTo(resolved.context().references());
        var opened = fixture.service.openContext("principal-a", "P", resolved.context().id(), List.of("r1"), 1200);
        assertThat(opened.items()).isEmpty();
        assertThat(opened.references()).singleElement().satisfies(reference -> {
            assertThat(reference.content()).contains("step one");
            assertThat(reference.changedSinceResolve()).isFalse();
            assertThat(reference.visibleHash()).isEqualTo("b".repeat(64));
        });

        when(taskContexts.openReference(referenceId, "a".repeat(64), "deploy~1", 4800))
                .thenThrow(new IllegalArgumentException("REFERENCE_CONTENT_CHANGED"));
        assertThat(fixture.service.openContext("principal-a", "P", resolved.context().id(), List.of("r1"), 1200)
                .references()).singleElement().satisfies(reference -> {
                    assertThat(reference.changedSinceResolve()).isTrue();
                    assertThat(reference.content()).isEmpty();
                });
        assertThatThrownBy(() -> fixture.service.openContext("principal-b", "P", resolved.context().id(),
                List.of("r1"), 1200)).hasMessage("CONTEXT_SCOPE_DENIED");
    }

    @Test
    void whenNeededSkipsReferencesForLocateButExpandsThemForChange() throws Exception {
        TaskContextFacade taskContexts = mock(TaskContextFacade.class);
        when(taskContexts.resolve(anyString(), eq("P"), eq("locate"), eq(1200)))
                .thenReturn(TaskContextPlan.empty());
        when(taskContexts.resolve(anyString(), eq("P"), eq("recall"), eq(1200)))
                .thenReturn(TaskContextPlan.empty());
        when(taskContexts.resolve(anyString(), eq("P"), eq("change"), eq(1200), eq(true)))
                .thenReturn(TaskContextPlan.empty());
        Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

        fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "locate deployment flow", "locate", List.of(), List.of(), List.of(), 1200, "when_needed");
        fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "recall deployment flow", "recall", List.of(), List.of(), List.of(), 1200, "when_needed");
        fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "change deployment flow", "change", List.of(), List.of(), List.of(), 1200, "when_needed");

        verify(taskContexts).resolve(anyString(), eq("P"), eq("locate"), eq(1200));
        verify(taskContexts, never()).resolve(anyString(), eq("P"), eq("locate"), eq(1200), eq(true));
        verify(taskContexts).resolve(anyString(), eq("P"), eq("recall"), eq(1200));
        verify(taskContexts, never()).resolve(anyString(), eq("P"), eq("recall"), eq(1200), eq(true));
        verify(taskContexts).resolve(anyString(), eq("P"), eq("change"), eq(1200), eq(true));
    }

    @Test
    void rejectsStaleSourceEvidenceWithoutCreatingMemory() throws Exception {
        Path source = Files.writeString(root.resolve("Config.java"), "class Config { int timeout = 10; }\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "copilot", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "copilot", "P", binding,
                "where is timeout configured", "debug", List.of("Config.java"), List.of(), 512, "never");
        Files.writeString(source, "class Config { int timeout = 20; }\n");
        var opened = fixture.service.openContext("principal-a", "P", context.context().id(), List.of("t1"), 512);
        assertThat(opened.items()).singleElement().extracting(item -> item.changedSinceResolve()).isEqualTo(true);
        Files.writeString(source, "class Config { int timeout = 30; }\n");

        var receipt = fixture.service.learn("principal-a", "P", context.context().id(), context.learningHandle(),
                List.of(candidate(opened.items().getFirst().evidenceId(), "Timeout source", "Config.java defines timeout.")),
                "copilot", null);

        assertThat(receipt.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("NOT_SAVED");
            assertThat(result.reason()).isEqualTo("STALE_EVIDENCE");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isZero();
    }

    @Test
    void learnedNavigationReturnsFocusedThenStaleWithoutChangingMemoryIdentity() throws Exception {
        Path source = Files.writeString(root.resolve("CardResolver.java"), "class CardResolver { int card; }\n");
        UUID memoryId = UUID.randomUUID();
        String learnedHash = sha256(Files.readString(source));
        TaskContextFacade taskContexts = mock(TaskContextFacade.class);
        when(taskContexts.resolve(anyString(), eq("P"), eq("change"), eq(1200))).thenReturn(
                new TaskContextPlan(
                        List.of(new Knowledge(memoryId, "discovery",
                                "Fallback is applied only when the primary card is absent.", true,
                                "SUPPORTED", "UNKNOWN")),
                        List.of(new SuggestedTarget("CardResolver.java", "demo.CardResolver#resolve",
                                "primary_change_point", learnedHash, memoryId, 1, 1)),
                        List.of(), 0, 0, false));
        Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

        var focused = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "change fallback condition", "change", List.of(), List.of(), List.of("fallback"),
                1200, "never");

        assertThat(focused.status()).isEqualTo("FOCUSED");
        assertThat(focused.knowledge()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(memoryId);
            assertThat(item.freshness()).isEqualTo("UNCHANGED");
        });
        assertThat(focused.context().targets()).singleElement().satisfies(target ->
                {
                    assertThat(target.relativePath()).isEqualTo("CardResolver.java");
                    assertThat(target.startLine()).isEqualTo(1);
                    assertThat(target.endLine()).isEqualTo(1);
                });
        assertThat(fixture.service.openActiveContext("principal-a", "codex", sha256("codex:legacy"), "P",
                List.of("t1"), 512).items()).singleElement()
                .extracting(item -> item.content()).asString().contains("CardResolver");
        assertThat(focused.sourceChecks()).containsEntry("t1", "UNCHANGED");

        Files.writeString(source, "class CardResolver { int changed; }\n");
        var stale = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "change fallback condition", "change", List.of(), List.of(), List.of("fallback"),
                1200, "never");

        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.knowledge()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(memoryId);
            assertThat(item.freshness()).isEqualTo("CHANGED");
        });
        assertThat(stale.sourceChecks()).containsEntry("t1", "CHANGED");
    }

    @Test
    void lifecycleStaleKnowledgeCannotBecomeFocusedFromAnUnchangedAnchor() throws Exception {
        Path source = Files.writeString(root.resolve("LifecycleRoute.java"), "class LifecycleRoute {}\n");
        UUID memoryId = UUID.randomUUID();
        String learnedHash = sha256(Files.readString(source));
        TaskContextFacade taskContexts = mock(TaskContextFacade.class);
        when(taskContexts.resolve(anyString(), eq("P"), eq("change"), eq(1200))).thenReturn(
                new TaskContextPlan(
                        List.of(new Knowledge(memoryId, "discovery", "This route was marked stale by lifecycle.",
                                true, "SUPPORTED", "STALE")),
                        List.of(new SuggestedTarget("LifecycleRoute.java", null,
                                "primary_change_point", learnedHash, memoryId)),
                        List.of(), 0, 0, false));
        Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

        var result = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "change lifecycle route", "change", List.of(), List.of(), List.of(), 1200, "never");

        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.sourceChecks()).containsEntry("t1", "UNCHANGED");
        assertThat(result.knowledge()).singleElement().extracting(Knowledge::freshness).isEqualTo("STALE");
    }

    @Test
    void missingLearnedTargetStaysAStaleHintAndIsNeverReboundBySimilarity() throws Exception {
        UUID memoryId = UUID.randomUUID();
        TaskContextFacade taskContexts = mock(TaskContextFacade.class);
        when(taskContexts.resolve(anyString(), eq("P"), eq("debug"), eq(1200))).thenReturn(
                new TaskContextPlan(
                        List.of(new Knowledge(memoryId, "discovery", "The removed handler used to own retries.",
                                true, "SUPPORTED", "UNKNOWN")),
                        List.of(new SuggestedTarget("RemovedHandler.java", "demo.RemovedHandler#retry",
                                "primary_change_point", "a".repeat(64), memoryId)),
                        List.of(), 0, 0, false));
        Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

        var result = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "debug retry handler", "debug", List.of(), List.of(), List.of(), 1200, "never");

        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.context().targets()).isEmpty();
        assertThat(result.knowledge()).singleElement().extracting(Knowledge::freshness).isEqualTo("MISSING");
        assertThat(result.gaps()).contains("learned_target_missing:RemovedHandler.java");
    }

    @Test
    void learnedSymlinkTargetIsReportedMissingInsteadOfEscapingTheBoundWorkspace() throws Exception {
        Path outside = Files.createTempFile("agent-learning-learned-outside", ".java");
        Files.writeString(outside, "class Outside {}\n");
        try {
            Files.createSymbolicLink(root.resolve("LearnedEscape.java"), outside);
            UUID memoryId = UUID.randomUUID();
            TaskContextFacade taskContexts = mock(TaskContextFacade.class);
            when(taskContexts.resolve(anyString(), eq("P"), eq("change"), eq(1200))).thenReturn(
                    new TaskContextPlan(
                            List.of(new Knowledge(memoryId, "discovery", "The old change point was here.",
                                    true, "SUPPORTED", "UNKNOWN")),
                            List.of(new SuggestedTarget("LearnedEscape.java", null,
                                    "primary_change_point", "a".repeat(64), memoryId)),
                            List.of(), 0, 0, false));
            Fixture fixture = fixture(mock(MemoryService.class), taskContexts, true);
            UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);

            var result = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                    "change escaped target", "change", List.of(), List.of(), List.of(), 1200, "never");

            assertThat(result.status()).isEqualTo("STALE");
            assertThat(result.context().targets()).isEmpty();
            assertThat(result.knowledge()).singleElement().extracting(Knowledge::freshness).isEqualTo("MISSING");
            assertThat(result.gaps()).contains("learned_target_missing:LearnedEscape.java");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsTraversalSymlinkAndForeignContextAccess() throws Exception {
        Files.writeString(root.resolve("Safe.java"), "class Safe {}\n");
        Path outside = Files.createTempFile("agent-learning-outside", ".java");
        Files.writeString(outside, "class Outside {}\n");
        try {
            Files.createSymbolicLink(root.resolve("Escape.java"), outside);
            Fixture fixture = fixture();
            UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "claude_code", fixture.workspace);

            assertThatThrownBy(() -> fixture.service.resolveContext("principal-a", "claude_code", "P", binding,
                    "escape", "locate", List.of("../outside.java"), List.of(), 512, "never"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.service.resolveContext("principal-a", "claude_code", "P", binding,
                    "symlink", "locate", List.of("Escape.java"), List.of(), 512, "never"))
                    .isInstanceOf(ProjectWorkspaceResolutionException.class)
                    .hasMessage("root_escape:Escape.java");

            var context = fixture.service.resolveContext("principal-a", "claude_code", "P", binding,
                    "safe", "locate", List.of("Safe.java"), List.of(), 512, "never");
            assertThatThrownBy(() -> fixture.service.openContext("principal-b", "P", context.context().id(),
                    List.of("t1"), 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("CONTEXT_SCOPE_DENIED");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void reclaimsAnExpiredProcessingLeaseWithoutRebindingItsPayload() throws Exception {
        Files.writeString(root.resolve("Lease.java"), "class Lease {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "lease", "locate", List.of("Lease.java"), List.of(), 512, "never");
        Instant now = Instant.now();
        var first = fixture.repository.claim(context.learningHandle(), context.context().id(), "principal-a", "P",
                "a".repeat(64), now.plusSeconds(30), now);
        assertThat(first.kind()).isEqualTo(AgentLearningModels.ClaimKind.CLAIMED);
        jdbc.update("UPDATE learning_operations SET lease_until=? WHERE request_id=?",
                java.sql.Timestamp.from(now.minusSeconds(1)), context.requestId());

        var reclaimed = fixture.repository.claim(context.learningHandle(), context.context().id(),
                "principal-a", "P", "a".repeat(64), now.plusSeconds(60), now.plusSeconds(1));
        var conflict = fixture.repository.claim(context.learningHandle(), context.context().id(),
                "principal-a", "P", "b".repeat(64), now.plusSeconds(60), now.plusSeconds(2));

        assertThat(reclaimed.kind()).isEqualTo(AgentLearningModels.ClaimKind.CLAIMED);
        assertThat(conflict.kind()).isEqualTo(AgentLearningModels.ClaimKind.CONFLICT);
    }

    @Test
    void reclaimsRetryableErrorOnlyForItsBoundPayload() throws Exception {
        Files.writeString(root.resolve("Retry.java"), "class Retry {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "retry", "locate", List.of("Retry.java"), List.of(), 512, "never");
        Instant now = Instant.now();
        String payloadHash = "a".repeat(64);
        var first = fixture.repository.claim(context.learningHandle(), context.context().id(),
                "principal-a", "P", payloadHash, now.plusSeconds(30), now);
        fixture.repository.retryableError(first.operation().requestId(), payloadHash,
                "{\"schemaVersion\":1,\"status\":\"RETRYABLE_ERROR\",\"requestId\":\""
                        + first.operation().requestId() + "\",\"results\":[]}",
                now.plusSeconds(1));

        var reclaimed = fixture.repository.claim(context.learningHandle(), context.context().id(),
                "principal-a", "P", payloadHash, now.plusSeconds(60), now.plusSeconds(2));
        var conflict = fixture.repository.claim(context.learningHandle(), context.context().id(),
                "principal-a", "P", "b".repeat(64), now.plusSeconds(60), now.plusSeconds(3));

        assertThat(reclaimed.kind()).isEqualTo(AgentLearningModels.ClaimKind.CLAIMED);
        assertThat(reclaimed.operation().state()).isEqualTo("PROCESSING");
        assertThat(reclaimed.operation().resultJson()).isNull();
        assertThat(conflict.kind()).isEqualTo(AgentLearningModels.ClaimKind.CONFLICT);
    }

    @Test
    void policyRejectedCandidateDoesNotRollbackValidSiblingOrReceipt() throws Exception {
        Files.writeString(root.resolve("Policy.java"), "class Policy {}\n");
        Fixture fixture = fixture(proxiedMemoryService());
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "policy", "locate", List.of("Policy.java"), List.of(), 512, "never");
        var opened = fixture.service.openContext("principal-a", "P", context.context().id(), List.of("t1"), 512);
        UUID evidenceId = opened.items().getFirst().evidenceId();

        var receipt = fixture.service.learn("principal-a", "P", context.context().id(), context.learningHandle(),
                List.of(
                        candidate(evidenceId, "Reusable policy location", "Policy.java contains the branch."),
                        candidate(evidenceId, "Rejected personal detail", "Contact person@example.com for access.")),
                "codex", "fixture-model");

        assertThat(receipt.status()).isEqualTo("COMPLETED");
        assertThat(receipt.results()).extracting(result -> result.outcome())
                .containsExactly("CREATED", "NOT_SAVED");
        assertThat(receipt.results().get(1).reason()).isEqualTo("pii-email");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM learning_operations WHERE request_id=?", String.class,
                receipt.requestId())).isEqualTo("COMPLETED");
    }

    @Test
    void correctionUsesRevisionAndCanonicalHashCasWithoutChangingMemoryIdentity() throws Exception {
        Path source = Files.writeString(root.resolve("Correction.java"), "class Correction { int oldValue; }\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "claude_code", fixture.workspace);
        var firstContext = fixture.service.resolveContext("principal-a", "claude_code", "P", binding,
                "locate correction", "locate", List.of("Correction.java"), List.of(), 512, "never");
        UUID firstEvidence = fixture.service.openContext("principal-a", "P", firstContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        var created = fixture.service.learn("principal-a", "P", firstContext.context().id(),
                firstContext.learningHandle(), List.of(candidate(firstEvidence, "Original correction route",
                        "The old value is handled here.")), "claude_code", "fixture-model");
        UUID memoryId = created.results().getFirst().memoryId();
        Map<String, Object> original = jdbc.queryForMap("""
                SELECT learning_revision,canonical_hash FROM memory_learning_profiles WHERE memory_id=?
                """, memoryId);

        Files.writeString(source, "class Correction { int newValue; }\n");
        var secondContext = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "update correction", "change", List.of("Correction.java"), List.of(), 512, "never");
        UUID secondEvidence = fixture.service.openContext("principal-a", "P", secondContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate correction = correctionCandidate(secondEvidence, "Revised correction route",
                "The new value is handled here.", memoryId,
                ((Number) original.get("learning_revision")).longValue(), (String) original.get("canonical_hash"));

        var corrected = fixture.service.learn("principal-a", "P", secondContext.context().id(),
                secondContext.learningHandle(), List.of(correction), "codex", "fixture-model");

        assertThat(corrected.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("UPDATED");
            assertThat(result.memoryId()).isEqualTo(memoryId);
            assertThat(result.indexState()).isEqualTo("PENDING");
            assertThat(result.graphState()).isEqualTo("PENDING");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?",
                Long.class, memoryId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT usefulness_state FROM memory_learning_profiles WHERE memory_id=?",
                String.class, memoryId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .contains("new value");
        assertThat(jdbc.queryForObject("SELECT observed_hash FROM memory_learning_evidence WHERE memory_id=?",
                String.class, memoryId)).isEqualTo(sha256(Files.readString(source)));

        var thirdContext = fixture.service.resolveContext("principal-a", "copilot", "P", binding,
                "retry stale correction", "change", List.of("Correction.java"), List.of(), 512, "never");
        UUID thirdEvidence = fixture.service.openContext("principal-a", "P", thirdContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate stale = correctionCandidate(thirdEvidence, "Stale writer", "Must not overwrite.", memoryId,
                ((Number) original.get("learning_revision")).longValue(), (String) original.get("canonical_hash"));
        var conflict = fixture.service.learn("principal-a", "P", thirdContext.context().id(),
                thirdContext.learningHandle(), List.of(stale), "copilot", "fixture-model");

        assertThat(conflict.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("CONFLICT");
            assertThat(result.reason()).isEqualTo("CORRECTION_EXPECTATION_MISMATCH");
            assertThat(result.memoryId()).isEqualTo(memoryId);
        });
        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?",
                Long.class, memoryId)).isEqualTo(2L);
    }

    @Test
    void externalEditInvalidatesOldCorrectionTupleAndFreshResolveCanUpdate() throws Exception {
        Path source = Files.writeString(root.resolve("HumanEdit.java"),
                "class HumanEdit { String route = \"initial\"; }\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var initialContext = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "locate the human edit route", "locate", List.of("HumanEdit.java"), List.of(), 512, "never");
        UUID initialEvidence = fixture.service.openContext("principal-a", "P", initialContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        UUID memoryId = fixture.service.learn("principal-a", "P", initialContext.context().id(),
                initialContext.learningHandle(), List.of(candidate(initialEvidence, "Initial human edit route",
                        "HumanEdit.java owns the initial route.")), "codex", "fixture-model")
                .results().getFirst().memoryId();
        Map<String, Object> issued = jdbc.queryForMap("""
                SELECT learning_revision,canonical_hash FROM memory_learning_profiles WHERE memory_id=?
                """, memoryId);
        long issuedRevision = ((Number) issued.get("learning_revision")).longValue();
        String issuedHash = (String) issued.get("canonical_hash");

        jdbc.update("""
                UPDATE memory_items
                SET summary='Human-edited route',text='A human changed this route.',status='stale',updated_at=now()
                WHERE id=?
                """, memoryId);
        fixture.repository.markLearningProfileStale(memoryId);

        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?",
                Long.class, memoryId)).isEqualTo(issuedRevision + 1);
        var staleContext = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "apply an old correction", "change", List.of("HumanEdit.java"), List.of(), 512, "never");
        UUID staleEvidence = fixture.service.openContext("principal-a", "P", staleContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        var conflict = fixture.service.learn("principal-a", "P", staleContext.context().id(),
                staleContext.learningHandle(), List.of(correctionCandidate(staleEvidence, "Old agent correction",
                        "This must not replace the human edit.", memoryId, issuedRevision, issuedHash)),
                "codex", "fixture-model");

        assertThat(conflict.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("CONFLICT");
            assertThat(result.reason()).isEqualTo("CORRECTION_EXPECTATION_MISMATCH");
        });
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo("A human changed this route.");
        assertThat(jdbc.queryForObject("SELECT status FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo("stale");

        LearningContextService contexts = mock(LearningContextService.class);
        MemoryContextItem selected = new MemoryContextItem(memoryId, UUID.randomUUID(), "project:" + memoryId,
                MemoryScope.PROJECT, "P", MemoryType.DISCOVERY, "Human-edited route",
                "A human changed this route.", 0.9, true, 30, 0.95, Instant.now());
        MemoryContextResponse memories = new MemoryContextResponse(List.of(selected), List.of(memoryId),
                1, 30, Map.of("user", 0, "global", 0, "project", 1, "episodic", 0), 0, 0, 0L);
        when(contexts.retrieveExplicit(any())).thenReturn(new LearningContextResponse(
                "P", List.of(new LearningContextItem("memory", memoryId.toString(), "Human-edited route",
                        "project:" + memoryId, selected.text(), 0.95, 30, Map.of())),
                memories, null, 30, false, false, List.of(), Map.of()));
        Fixture resolver = fixture(mock(MemoryService.class), new TaskContextFacade(contexts, fixture.repository), true);
        var freshContext = resolver.service.resolveContext("principal-a", "codex", "P", binding,
                "update the human-edited route with fresh evidence", "change",
                List.of(), List.of(), List.of(), 1200, "never");
        Knowledge fresh = freshContext.knowledge().getFirst();

        assertThat(fresh.learningRevision()).isEqualTo(issuedRevision + 1);
        assertThat(fresh.canonicalHash()).isEqualTo(issuedHash);
        assertThat(fresh.freshness()).isEqualTo("STALE");
        UUID freshEvidence = fixture.service.openContext("principal-a", "P", freshContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        var updated = fixture.service.learn("principal-a", "P", freshContext.context().id(),
                freshContext.learningHandle(), List.of(correctionCandidate(freshEvidence, "Fresh agent correction",
                        "Fresh evidence now supports the updated route.", memoryId,
                        fresh.learningRevision(), fresh.canonicalHash())), "codex", "fixture-model");

        assertThat(updated.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("UPDATED");
            assertThat(result.memoryId()).isEqualTo(memoryId);
        });
        assertThat(jdbc.queryForObject("SELECT text FROM memory_items WHERE id=?", String.class, memoryId))
                .contains("Fresh evidence");
        assertThat(jdbc.queryForObject("SELECT status FROM memory_items WHERE id=?", String.class, memoryId))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?",
                Long.class, memoryId)).isEqualTo(issuedRevision + 2);
    }

    @Test
    void correctionCannotTakeCanonicalClaimOwnedByAnotherDiscovery() throws Exception {
        Files.writeString(root.resolve("OwnerA.java"), "class OwnerA {}\n");
        Files.writeString(root.resolve("OwnerB.java"), "class OwnerB {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var contextA = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "owner a", "locate", List.of("OwnerA.java"), List.of(), 512, "never");
        UUID evidenceA = fixture.service.openContext("principal-a", "P", contextA.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate candidateA = candidate(evidenceA, "Canonical owner A", "Owner A handles this route.");
        UUID memoryA = fixture.service.learn("principal-a", "P", contextA.context().id(),
                contextA.learningHandle(), List.of(candidateA), "codex", "fixture-model")
                .results().getFirst().memoryId();
        var contextB = fixture.service.resolveContext("principal-a", "claude_code", "P", binding,
                "owner b", "locate", List.of("OwnerB.java"), List.of(), 512, "never");
        UUID evidenceB = fixture.service.openContext("principal-a", "P", contextB.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        UUID memoryB = fixture.service.learn("principal-a", "P", contextB.context().id(),
                contextB.learningHandle(), List.of(candidate(evidenceB, "Canonical owner B",
                        "Owner B handles another route.")), "claude_code", "fixture-model")
                .results().getFirst().memoryId();
        Map<String, Object> profileB = jdbc.queryForMap("""
                SELECT learning_revision,canonical_hash FROM memory_learning_profiles WHERE memory_id=?
                """, memoryB);
        var correctionContext = fixture.service.resolveContext("principal-a", "copilot", "P", binding,
                "move b to owner a", "change", List.of("OwnerA.java"), List.of(), 512, "never");
        UUID correctionEvidence = fixture.service.openContext("principal-a", "P", correctionContext.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        Candidate collision = correctionCandidate(correctionEvidence, "Canonical owner A",
                "Owner A handles this route.", memoryB,
                ((Number) profileB.get("learning_revision")).longValue(),
                (String) profileB.get("canonical_hash"));

        var receipt = fixture.service.learn("principal-a", "P", correctionContext.context().id(),
                correctionContext.learningHandle(), List.of(collision), "copilot", "fixture-model");

        assertThat(receipt.results()).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo("CONFLICT");
            assertThat(result.reason()).isEqualTo("CORRECTION_CANONICAL_CONFLICT");
            assertThat(result.memoryId()).isEqualTo(memoryB);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT learning_revision FROM memory_learning_profiles WHERE memory_id=?",
                Long.class, memoryB)).isEqualTo(1L);
        assertThat(memoryA).isNotEqualTo(memoryB);
    }

    @Test
    void staleLearningProfileRemainsRetrievableButIsNeverReportedFresh() throws Exception {
        Files.writeString(root.resolve("StaleProfile.java"), "class StaleProfile {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "stale profile", "locate", List.of("StaleProfile.java"), List.of(), 512, "never");
        UUID evidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        UUID memoryId = fixture.service.learn("principal-a", "P", context.context().id(),
                context.learningHandle(), List.of(candidate(evidence, "Stale profile route",
                        "The route remains a bounded stale hint.")), "codex", "fixture-model")
                .results().getFirst().memoryId();

        fixture.repository.markLearningProfileStale(memoryId);
        var routes = fixture.repository.findDiscoveryRoutes("P", List.of(memoryId), 3);

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.memoryId()).isEqualTo(memoryId);
            assertThat(route.stale()).isTrue();
        });
    }

    @Test
    void purgeRemovesOnlyExpiredPrivateStateAndKeepsDurableLearningEvidence() throws Exception {
        Files.writeString(root.resolve("Purge.java"), "class Purge {}\n");
        Fixture fixture = fixture();
        UUID binding = fixture.service.issueWorkspaceBinding("principal-a", "codex", fixture.workspace);
        var context = fixture.service.resolveContext("principal-a", "codex", "P", binding,
                "purge", "locate", List.of("Purge.java"), List.of(), 512, "never");
        UUID evidence = fixture.service.openContext("principal-a", "P", context.context().id(),
                List.of("t1"), 512).items().getFirst().evidenceId();
        UUID memoryId = fixture.service.learn("principal-a", "P", context.context().id(), context.learningHandle(),
                List.of(candidate(evidence, "Durable purge finding", "Purge.java owns cleanup.")),
                "codex", "fixture-model").results().getFirst().memoryId();
        UUID activeBinding = fixture.service.issueWorkspaceBinding("principal-b", "codex", fixture.workspace);
        var activeContext = fixture.service.resolveContext("principal-b", "codex", "P", activeBinding,
                "active", "locate", List.of("Purge.java"), List.of(), 512, "never");
        Instant oldCreated = Instant.now().minusSeconds(7200);
        Instant expired = Instant.now().minusSeconds(3600);
        jdbc.update("UPDATE learning_operations SET created_at=?,updated_at=?,expires_at=? WHERE context_id=?",
                java.sql.Timestamp.from(oldCreated), java.sql.Timestamp.from(oldCreated),
                java.sql.Timestamp.from(expired), context.context().id());
        jdbc.update("UPDATE research_contexts SET created_at=?,expires_at=? WHERE id=?",
                java.sql.Timestamp.from(oldCreated), java.sql.Timestamp.from(expired), context.context().id());
        jdbc.update("UPDATE agent_workspace_bindings SET created_at=?,expires_at=? WHERE id=?",
                java.sql.Timestamp.from(oldCreated), java.sql.Timestamp.from(expired), binding);

        var purged = fixture.repository.purgeExpired(Instant.now(), 500);

        assertThat(purged.operations()).isGreaterThanOrEqualTo(1);
        assertThat(purged.contexts()).isEqualTo(1);
        assertThat(purged.bindings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_contexts WHERE id=?", Integer.class,
                context.context().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_observations WHERE context_id=?",
                Integer.class, context.context().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_contexts WHERE id=?", Integer.class,
                activeContext.context().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_workspace_bindings WHERE id=?", Integer.class,
                activeBinding)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_items WHERE id=?", Integer.class,
                memoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_learning_profiles WHERE memory_id=?",
                Integer.class, memoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_learning_evidence WHERE memory_id=?",
                Integer.class, memoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_navigation_anchors WHERE memory_id=?",
                Integer.class, memoryId)).isEqualTo(1);
    }

    private Fixture fixture() throws Exception {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.createDiscovery(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest.class);
            UUID id = UUID.randomUUID();
            UUID vectorId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO memory_items
                        (id,vector_id,scope,project_key,memory_type,summary,text,tags,confidence,status,
                         source_type,source_ref,owner,metadata,last_verified_at)
                    VALUES (?,?,'project',?,'discovery',?,?,?::jsonb,0.9,'active','mcp_external',?,?,?::jsonb,?)
                    """, id, vectorId, request.projectKey(), request.summary(), request.text(), "[]",
                    request.sourceRef(), request.owner(), "{}", java.sql.Timestamp.from(Instant.now()));
            Instant now = Instant.now();
            return new MemoryItem(id, vectorId, request.scope(), request.projectKey(), request.memoryType(),
                    request.summary(), request.text(), List.of(), 0.9, request.status(), request.sourceType(),
                    request.sourceRef(), request.owner(), Map.of(), now, now, null, now, null);
        });
        when(memoryService.correctDiscovery(any(UUID.class), anyString(), any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0, UUID.class);
            var request = invocation.getArgument(2,
                    com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest.class);
            UUID vectorId = UUID.randomUUID();
            jdbc.update("""
                    UPDATE memory_items SET vector_id=?,summary=?,text=?,tags=?::jsonb,confidence=0.9,
                        status='active',source_ref=?,owner=?,metadata=?::jsonb,updated_at=now(),last_verified_at=now()
                    WHERE id=? AND project_key=? AND memory_type='discovery'
                    """, vectorId, request.summary(), request.text(), "[]", request.sourceRef(), request.owner(),
                    "{}", id, request.projectKey());
            Instant created = jdbc.queryForObject("SELECT created_at FROM memory_items WHERE id=?",
                    java.sql.Timestamp.class, id).toInstant();
            Instant now = Instant.now();
            return new MemoryItem(id, vectorId, request.scope(), request.projectKey(), request.memoryType(),
                    request.summary(), request.text(), List.of(), 0.9, request.status(), request.sourceType(),
                    request.sourceRef(), request.owner(), Map.of(), created, now, null, now, null);
        });
        return fixture(memoryService);
    }

    private Fixture fixture(MemoryService memoryService) throws Exception {
        return fixture(memoryService, null, false);
    }

    private Fixture fixture(MemoryService memoryService, TaskContextFacade taskContexts,
            boolean navigationEnabled) throws Exception {
        return fixture(memoryService, taskContexts, navigationEnabled, mock(CodeBaselineRepository.class));
    }

    private Fixture fixture(MemoryService memoryService, TaskContextFacade taskContexts,
            boolean navigationEnabled, CodeBaselineRepository codeBaselineRepository) throws Exception {
        return fixture(memoryService, taskContexts, navigationEnabled, codeBaselineRepository,
                UnresolvedSymbolScanScheduler.noop());
    }

    private Fixture fixture(MemoryService memoryService, TaskContextFacade taskContexts,
            boolean navigationEnabled, CodeBaselineRepository codeBaselineRepository,
            UnresolvedSymbolScanScheduler unresolvedSymbolScanScheduler) throws Exception {
        Path canonicalRoot = root.toRealPath();
        ProjectWorkspace workspace = new ProjectWorkspace("P", canonicalRoot, "f".repeat(64));
        ProjectWorkspaceResolver resolver = mock(ProjectWorkspaceResolver.class);
        when(resolver.resolve("P", workspace.repositoryFingerprint())).thenReturn(workspace);
        ScannerPayloadRedactor redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        var properties = new AgentLearningProperties(true, true, navigationEnabled, true,
                3, 12, 1200, 4000, 3, 24, 7, 300, 1_048_576, 500, 3_600_000);
        var repository = new JdbcAgentLearningRepository(jdbc);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var manager = new DataSourceTransactionManager(dataSource);
        var references = new ReferenceService(new ReferenceProperties(root.resolve("references").toString()),
                jdbc, redactor, manager, event -> {});
        var relations = new MemoryRelationService(jdbc, redactor, manager, event -> {}, references);
        var service = new AgentLearningService(repository, resolver,
                codeBaselineRepository, redactor, memoryService, taskContexts, properties,
                mapper, manager, references, relations, new AgentLearningContract(mapper),
                unresolvedSymbolScanScheduler);
        return new Fixture(service, workspace, repository);
    }

    private MemoryService proxiedMemoryService() {
        MemoryRepository repository = mock(MemoryRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            MemoryItem item = invocation.getArgument(0, MemoryItem.class);
            jdbc.update("""
                    INSERT INTO memory_items
                        (id,vector_id,scope,project_key,memory_type,summary,text,tags,confidence,status,
                         source_type,source_ref,owner,metadata,created_at,updated_at,last_verified_at)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?,?,?)
                    """, item.id(), item.vectorId(), item.scope().value(), item.projectKey(),
                    item.memoryType().value(), item.summary(), item.text(), "[]", item.confidence(),
                    item.status().value(), item.sourceType().value(), item.sourceRef(), item.owner(),
                    new ObjectMapper().writeValueAsString(item.metadata()),
                    java.sql.Timestamp.from(item.createdAt()), java.sql.Timestamp.from(item.updatedAt()),
                    java.sql.Timestamp.from(item.lastVerifiedAt()));
            return item;
        });
        AiOrchestrationProperties properties = mock(AiOrchestrationProperties.class);
        AiOrchestrationProperties.Memory memory = mock(AiOrchestrationProperties.Memory.class);
        when(properties.memory()).thenReturn(memory);
        when(memory.defaultConfidence()).thenReturn(0.9);
        PolicyEngine policyEngine = mock(PolicyEngine.class);
        when(policyEngine.evaluateMemoryWrite(any(), any())).thenReturn(PolicyDecision.allow());
        when(policyEngine.evaluateMemoryContent(anyString())).thenReturn(PolicyDecision.allow());
        MemoryService target = new MemoryService(repository, properties, mock(ApplicationEventPublisher.class),
                policyEngine, new PiiScrubber(), mock(MemoryVectorIndex.class),
                RuleMemoryActivationPolicy.authorityEnabled(), mock(RuleMemoryLinkLookup.class));
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (MemoryService) proxyFactory.getProxy();
    }

    private static Candidate candidate(UUID evidenceId, String summary, String content) {
        return new Candidate("change_point", summary, content, List.of("routing work"), List.of(),
                List.of(new Anchor("t1", "primary_change_point")), List.of(evidenceId), List.of("future changes"));
    }

    private static CaptureLocator fileLocator(String path) {
        return new CaptureLocator("file", path, null, null, "supporting");
    }

    private static Candidate correctionCandidate(UUID evidenceId, String summary, String content,
            UUID targetId, long revision, String canonicalHash) {
        return new Candidate("change_point", summary, content, List.of("routing work"), List.of(),
                List.of(new Anchor("t1", "primary_change_point")), List.of(evidenceId),
                List.of("future changes"), targetId, revision, canonicalHash);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(AgentLearningService service, ProjectWorkspace workspace,
            JdbcAgentLearningRepository repository) {
    }
}
