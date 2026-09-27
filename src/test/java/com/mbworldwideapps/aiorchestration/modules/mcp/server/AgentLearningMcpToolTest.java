package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ContextReservation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningCaptureCommand;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SemanticCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Knowledge;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedSource;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ResearchContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningService;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryProjectionQueueFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

class AgentLearningMcpToolTest {

    @Test
    void nativeLearnSchemaKeepsSemanticOptionalFieldsOptional() throws Exception {
        var method = AgentLearningMcpTool.class.getMethod("learnV2", LearningBatch.class);
        var schema = new ObjectMapper().readTree(
                org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator.generateForMethodInput(method));
        var learning = schema.path("properties").path("batch").path("properties")
                .path("learnings").path("items");
        List<String> required = new java.util.ArrayList<>();
        learning.path("required").forEach(value -> required.add(value.asText()));

        assertThat(required).containsExactlyInAnyOrder("anchors", "content", "evidence", "kind", "summary");
        assertThat(required).doesNotContain(
                "appliesWhen", "limitations", "reusableFor", "correctionRef", "reference");
    }

    private final AgentLearningService service = mock(AgentLearningService.class);
    private final McpAuditLogger audit = mock(McpAuditLogger.class);
    private final AgentLearningMcpTool tool = new AgentLearningMcpTool(service, audit);

    @AfterEach
    void clear() {
        McpClientContextHolder.clear();
    }

    @Test
    void claudeAndCodexLearnThroughTheSingleSessionToolNotTheInternalAdapter() throws Exception {
        var parity = new ObjectMapper().readTree(Files.readString(
                Path.of("contracts/agent-learning/runtime-parity.json")));
        Set<String> expected = new java.util.HashSet<>();
        parity.path("domainOperations").forEach(node -> expected.add(node.asText()));
        assertThat(expected).containsExactlyInAnyOrder(
                "runtime.prefetch", "runtime.capture");
        for (String runtime : List.of("CLAUDE_CODE", "CODEX")) {
            var adapter = parity.path("adapters").path(runtime);
            assertThat(adapter.path("transport").asText()).isEqualTo("direct_mcp_tools");
            assertThat(adapter.path("requestFiles").isEmpty()).isTrue();
            assertThat(adapter.path("forbidden").toString())
                    .contains("runtime_finalization_hook", "retry_after_connection_failure");
            Set<String> aliases = new java.util.HashSet<>();
            adapter.path("operations").fieldNames().forEachRemaining(aliases::add);
            assertThat(aliases).isEqualTo(expected);
        }
        assertThat(Arrays.stream(AgentLearningMcpTool.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name))
                .isEmpty();
        assertThat(Arrays.stream(AgentSessionMcpTool.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name))
                .containsExactlyInAnyOrder("session.bootstrap", "memory.learn");
    }

    @Test
    void resolveRequiresEveryServerScopeAndReturnsServerIssuedIdentifiers() {
        UUID binding = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID handle = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Target target = new Target("t1", "src/A.java", "a.A", "primary_change_point", "a".repeat(64), 3, 7);
        ResearchContext context = new ResearchContext(contextId, binding, "principal", "codex", "P",
                UUID.randomUUID(), "locate", "b".repeat(64), "server_observed", Instant.now(),
                Instant.now().plusSeconds(60), List.of(target));
        when(service.resolveContext(anyString(), eq("codex"), eq("P"), eq(binding), eq("find A"),
                eq("locate"), eq(List.of("src/A.java")), eq(List.of()), eq(List.of()), eq(512), eq("never")))
                .thenReturn(new ContextReservation(context, handle, requestId, "STALE",
                        List.of(new Knowledge(UUID.randomUUID(), "discovery", "bounded knowledge", true,
                                "SUPPORTED", "CHANGED", 4L, "c".repeat(64))),
                        java.util.Map.of("t1", "CHANGED"), List.of("revalidate_source"), 0, 1));
        McpClientContextHolder.set(context("memory.read", "codebase.read", "rules.read"));

        var response = tool.resolve(1, "P", binding.toString(), "find A", "locate",
                new AgentLearningMcpTool.ContextHints(List.of(), List.of("src/A.java"), List.of()), 512, "never");

        assertThat(response.contextId()).isEqualTo(contextId);
        assertThat(response.learningHandle()).isEqualTo(handle);
        assertThat(response.status()).isEqualTo("STALE");
        assertThat(response.knowledge()).singleElement().satisfies(item -> {
            assertThat(item.text()).isEqualTo("bounded knowledge");
            assertThat(item.freshness()).isEqualTo("CHANGED");
            assertThat(item.learningRevision()).isEqualTo(4L);
            assertThat(item.canonicalHash()).isEqualTo("c".repeat(64));
        });
        assertThat(response.targets()).singleElement().satisfies(item -> {
            assertThat(item.path()).isEqualTo("src/A.java");
            assertThat(item.lines()).containsExactly(3, 7);
            assertThat(item.sourceCheck()).isEqualTo("CHANGED");
        });
        assertThat(response.gaps()).containsExactly("revalidate_source");
        assertThat(response.omitted().targets()).isEqualTo(1);
        verify(audit).log(any(), eq("context.resolve"), any(), eq(1), any(Long.class), eq("success"), any(), any());

        // A bearer client cannot turn the shared local-user reference root into
        // project context even when it asks for eager expansion.
        assertThat(tool.resolve(1, "P", binding.toString(), "find A", "locate",
                new AgentLearningMcpTool.ContextHints(List.of(), List.of("src/A.java"), List.of()),
                512, "always").references()).isEmpty();
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2)).resolveContext(anyString(), eq("codex"),
                eq("P"), eq(binding), eq("find A"), eq("locate"), eq(List.of("src/A.java")), eq(List.of()),
                eq(List.of()), eq(512), eq("never"));

        McpClientContextHolder.set(context("memory.read", "codebase.read"));
        assertThatThrownBy(() -> tool.resolve(1, "P", binding.toString(), "find A", "locate", null, 512, "never"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("rules.read");
    }

    @Test
    void openLearnAndStatusDelegateOnlyAfterProjectAndPrincipalResolution() {
        UUID contextId = UUID.randomUUID();
        UUID handle = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(service.projectForContext(anyString(), eq(contextId))).thenReturn("P");
        UUID evidenceId = UUID.randomUUID();
        when(service.openContext(anyString(), eq("P"), eq(contextId), eq(List.of("t1")), eq(256)))
                .thenReturn(new OpenedContext(contextId, List.of(new OpenedSource(
                        "t1", evidenceId, "src/A.java", 4, 9, "bounded content", true, false))));
        LearnReceipt receipt = new LearnReceipt(1, "COMPLETED", requestId, List.of(), null);
        when(service.learn(anyString(), eq("P"), eq(contextId), eq(handle), eq(List.of()), eq("codex"), eq(null)))
                .thenReturn(receipt);
        when(service.projectForOperation(anyString(), eq(requestId), eq(null))).thenReturn("P");
        when(service.operationStatus(anyString(), eq("P"), eq(requestId), eq(null))).thenReturn(receipt);

        McpClientContextHolder.set(context("codebase.read", "memory.write"));
        var opened = tool.open(contextId.toString(), List.of("t1"), 256);
        assertThat(opened.status()).isEqualTo("READ");
        assertThat(opened.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceId()).isEqualTo(evidenceId);
            assertThat(item.lines()).containsExactly(4, 9);
            assertThat(item.content()).isEqualTo("bounded content");
        });
        assertThat(tool.learn(1, contextId.toString(), handle.toString(), List.of(), "codex", null))
                .isEqualTo(receipt);
        assertThat(tool.status(requestId.toString(), null)).isEqualTo(receipt);
    }

    @Test
    void compactContractNeverReturnsPersistenceIdentifiers() throws Exception {
        UUID binding = UUID.randomUUID();
        UUID hiddenContext = UUID.randomUUID();
        UUID hiddenMemory = UUID.randomUUID();
        Target target = new Target("t1", "src/A.java", "a.A#run", "supporting", "a".repeat(64), 3, 7);
        ResearchContext research = new ResearchContext(hiddenContext, binding, "principal", "codex",
                context().sessionScopeHash(), "P", UUID.randomUUID(), "locate", "b".repeat(64),
                "server_observed", Instant.now(), Instant.now().plusSeconds(60), List.of(target), List.of());
        when(service.resolveContext(anyString(), eq("codex"), eq(context().sessionScopeHash()), eq("P"),
                eq(binding), eq("find A"), eq("locate"), eq(List.of()), eq(List.of()), eq(List.of()),
                eq(512), eq("never"))).thenReturn(new ContextReservation(research, UUID.randomUUID(),
                        UUID.randomUUID(), "FOCUSED", List.of(new Knowledge(hiddenMemory, "discovery",
                        "A compact durable fact", true, "SUPPORTED", "FRESH", 2L, "c".repeat(64))),
                        java.util.Map.of("t1", "UNCHANGED"), List.of(), 0, 0));
        McpClientContextHolder.set(context("memory.read", "codebase.read", "rules.read"));

        var response = tool.resolveV2("P", binding.toString(), "find A", "locate", null, 512, "never");
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"ref\":\"k1\"", "\"ref\":\"t1\"");
        assertThat(json).doesNotContain(hiddenContext.toString(), hiddenMemory.toString(), "learningHandle",
                "canonicalHash", "learningRevision");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).learnSemantic(anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void compactResolveBudgetAppliesToTheFinalSerializedPayload() throws Exception {
        UUID binding = UUID.randomUUID();
        String large = "durable routing detail ".repeat(80);
        Target target = new Target("t1", "src/A.java", "a.A#run", "supporting", "a".repeat(64), 3, 7);
        ResearchContext research = new ResearchContext(UUID.randomUUID(), binding, "principal", "codex",
                context().sessionScopeHash(), "P", UUID.randomUUID(), "locate", "b".repeat(64),
                "server_observed", Instant.now(), Instant.now().plusSeconds(60), List.of(target), List.of());
        when(service.resolveContext(anyString(), eq("codex"), eq(context().sessionScopeHash()), eq("P"),
                eq(binding), eq("find A"), eq("locate"), eq(List.of()), eq(List.of()), eq(List.of()),
                eq(128), eq("never"))).thenReturn(new ContextReservation(research, UUID.randomUUID(),
                        UUID.randomUUID(), "FOCUSED", List.of(new Knowledge(UUID.randomUUID(), "discovery",
                        large, true, "SUPPORTED", "FRESH", 2L, "c".repeat(64))),
                        java.util.Map.of("t1", "UNCHANGED"), List.of(), 0, 0));
        McpClientContextHolder.set(context("memory.read", "codebase.read", "rules.read"));

        var response = tool.resolveV2("P", binding.toString(), "find A", "locate", null, 128, "never");
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(TokenEstimator.estimate(json)).isLessThanOrEqualTo(128);
        assertThat(response.gaps()).contains("knowledge_content_omitted_by_budget");
    }

    @Test
    void compactLearnDelegatesOnlySemanticBatchAndReturnsEightCounters() {
        LearningBatch batch = new LearningBatch(List.of(new SemanticCandidate("behavior", "summary", "content",
                List.of("t1"), List.of("t1"), List.of(), List.of(), List.of(), null, null)));
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 0, 0, 0, 0);
        McpClientContext context = context("memory.write", "codebase.read");
        when(service.projectForActiveContext(anyString(), eq("codex"), eq(context.sessionScopeHash())))
                .thenReturn("P");
        when(service.learnSemantic(anyString(), eq("codex"), eq(context.sessionScopeHash()), eq("P"),
                eq(batch), eq("codex"), eq(null))).thenReturn(receipt);
        McpClientContextHolder.set(context);

        assertThat(tool.learnV2(batch)).isEqualTo(receipt);
        verify(audit).log(any(), eq("memory.learn"), any(), eq(1), any(Long.class), eq("success"), any(), any());
    }

    @Test
    void compactContextOperationsFailClosedWhenSessionScopeIsUnavailable() {
        McpClientContext unavailable = new McpClientContext("P", "codex", "key",
                List.of("memory.read", "memory.write", "codebase.read", "rules.read"),
                McpClientContext.SESSION_SCOPE_UNAVAILABLE);
        McpClientContextHolder.set(unavailable);
        UUID binding = UUID.randomUUID();
        LearningBatch batch = new LearningBatch(List.of(new SemanticCandidate("behavior", "summary", "content",
                List.of("t1"), List.of("t1"), List.of(), List.of(), List.of(), null, null)));
        LearningCaptureCommand capture = new LearningCaptureCommand("task-42", "P", binding.toString(),
                List.of(new CaptureCandidate("behavior", "summary", "content",
                        List.of(new CaptureLocator("file", "src/A.java", null, null, "supporting")),
                        List.of(), List.of(), List.of())));

        assertThatThrownBy(() -> tool.resolveV2("P", binding.toString(), "find A", "locate", null, 512, "never"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SESSION_SCOPE_UNAVAILABLE");
        assertThatThrownBy(() -> tool.openV2(List.of("t1"), 256))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SESSION_SCOPE_UNAVAILABLE");
        assertThatThrownBy(() -> tool.learnV2(batch))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SESSION_SCOPE_UNAVAILABLE");
        assertThatThrownBy(() -> tool.capture(capture))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SESSION_SCOPE_UNAVAILABLE");
        assertThatThrownBy(tool::statusV2)
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SESSION_SCOPE_UNAVAILABLE");

        verifyNoInteractions(service);
    }

    @Test
    void postTurnCaptureRequiresWriteAndCodeScopesAndAuditsTheRuntimeOnlyCall() {
        UUID binding = UUID.randomUUID();
        LearningCaptureCommand command = new LearningCaptureCommand("task-42", "P", binding.toString(),
                List.of(new CaptureCandidate("behavior", "summary", "content",
                        List.of(new CaptureLocator("file", "src/A.java", null, null, "supporting")),
                        List.of(), List.of(), List.of())));
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 0, 0, 0, 0);
        McpClientContext context = context("memory.write", "codebase.read");
        when(service.captureSemantic(anyString(), eq("codex"), eq(context.sessionScopeHash()), eq("P"),
                eq(binding), eq("task-42"), eq(command.learningCandidates()), eq("codex"), eq(null)))
                .thenReturn(receipt);
        McpClientContextHolder.set(context);

        assertThat(tool.capture(command)).isEqualTo(receipt);
        verify(audit).log(any(), eq("learning.capture"), any(), eq(1), any(Long.class), eq("success"), any(), any());

        McpClientContextHolder.set(context("memory.write"));
        assertThatThrownBy(() -> tool.capture(command)).isInstanceOf(McpAccessException.class)
                .hasMessageContaining("codebase.read");
        verify(audit).log(any(), eq("learning.capture"), any(), eq(0), any(Long.class), eq("denied_scope"),
                any(), eq("McpAccessException"));
    }

    @Test
    void compactOpenAndStatusAreAuditedWithoutPersistenceIdentifiers() {
        McpClientContext context = context("memory.write", "codebase.read");
        when(service.projectForActiveContext(anyString(), eq("codex"), eq(context.sessionScopeHash())))
                .thenReturn("P");
        when(service.openActiveContext(anyString(), eq("codex"), eq(context.sessionScopeHash()), eq("P"),
                eq(List.of("t1")), eq(256))).thenReturn(new OpenedContext(UUID.randomUUID(),
                        List.of(new OpenedSource("t1", UUID.randomUUID(), "src/A.java", 4, 9,
                                "bounded content", true, false))));
        CompactLearnReceipt status = new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 0, 0, 0, 0);
        when(service.operationStatus(anyString(), eq(context.sessionScopeHash()))).thenReturn(status);
        McpClientContextHolder.set(context);

        assertThat(tool.openV2(List.of("t1"), 256).items()).singleElement();
        assertThat(tool.statusV2()).isEqualTo(status);

        verify(audit).log(any(), eq("context.open"), any(), eq(1), any(Long.class), eq("success"), any(), any());
        verify(audit).log(any(), eq("operation.status"), any(), eq(1), any(Long.class), eq("success"), any(), any());
    }

    @Test
    void scenarioCThreeDiscoveriesUseOneBatchLearnCall() {
        SemanticCandidate first = new SemanticCandidate("navigation", "source", "content", List.of("t1"),
                List.of("t1"), List.of(), List.of(), List.of(), null, null);
        SemanticCandidate second = new SemanticCandidate("change_point", "dependency", "content", List.of("t2"),
                List.of("t2"), List.of(), List.of(), List.of(), null, null);
        SemanticCandidate third = new SemanticCandidate("test_address", "regression", "content", List.of("t3"),
                List.of("t3"), List.of(), List.of(), List.of(), null, null);
        LearningBatch batch = new LearningBatch(List.of(first, second, third));
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 3, 0, 0, 0, 0, 0, 0);
        McpClientContext context = context("memory.write", "codebase.read");
        when(service.projectForActiveContext(anyString(), eq("codex"), eq(context.sessionScopeHash())))
                .thenReturn("P");
        when(service.learnSemantic(anyString(), eq("codex"), eq(context.sessionScopeHash()), eq("P"),
                eq(batch), eq("codex"), eq(null))).thenReturn(receipt);
        McpClientContextHolder.set(context);

        assertThat(tool.learnV2(batch).created()).isEqualTo(3);
        verify(service).learnSemantic(anyString(), eq("codex"), eq(context.sessionScopeHash()), eq("P"),
                eq(batch), eq("codex"), eq(null));
    }

    @Test
    void learnSurfacesProjectionBackpressureAsNamedRetryableMcpError() {
        UUID contextId = UUID.randomUUID();
        UUID handle = UUID.randomUUID();
        when(service.projectForContext(anyString(), eq(contextId))).thenReturn("P");
        when(service.learn(anyString(), eq("P"), eq(contextId), eq(handle), eq(List.of()), eq("codex"), eq(null)))
                .thenThrow(new MemoryProjectionQueueFullException(2));
        McpClientContextHolder.set(context("codebase.read", "memory.write"));

        assertThatThrownBy(() -> tool.learn(1, contextId.toString(), handle.toString(), List.of(), "codex", null))
                .isInstanceOf(MemoryProjectionQueueFullException.class)
                .hasMessage("MEMORY_PROJECTION_QUEUE_FULL: capacity=2");
        verify(audit).log(any(), eq("memory.learn"), any(), eq(0), any(Long.class), eq("queue_full"),
                eq(java.util.Map.of("retryable", true)), eq("MemoryProjectionQueueFullException"));
    }

    private static McpClientContext context(String... scopes) {
        return new McpClientContext("P", "codex", "key", List.of(scopes));
    }

    private static McpClientContext context() {
        return context("memory.read");
    }
}
