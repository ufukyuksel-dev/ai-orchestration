package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.LocalTrustProperties;
import com.mbworldwideapps.aiorchestration.config.McpClientProvidersProperties;
import com.mbworldwideapps.aiorchestration.config.MemoryWriteGateProperties;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationRequest;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationResponse;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMGateway;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class LlmMemoryWriteGateTest {

    private final MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
    private final MemoryContextBuilder contextBuilder = new MemoryContextBuilder();
    private final MemoryWriteGateParser parser = new MemoryWriteGateParser(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void trustedClientCanAutoActivateSafeHighConfidenceMemory() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":["no conflict"],"confidence":0.93,"debugMetadata":{}}
                """);
        LlmMemoryWriteGate gate = gate(gateway, PolicyEngine.allowAll());

        MemoryWriteGate.GateDecision decision = gate.evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.confidence()).isEqualTo(0.93);
        assertThat(gateway.lastRequest.requestedProvider()).isEqualTo("claude");
        assertThat(gateway.lastRequest.memoryContextBlock()).contains("0 eligible similar memories");
    }

    @Test
    void lowConfidenceAutoActiveStaysActiveAndOnlyRecordsTheSignal() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"possible_rule","suggestedQuestion":"Approve?",
                 "contextHints":[],"confidence":0.72,"debugMetadata":{}}
                """);

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.gateReason()).isEqualTo("possible_rule");
        assertThat(decision.debugMetadata()).containsEntry("belowAutoActiveConfidenceFloor", true);
    }

    @Test
    void untrustedClientAutoActiveIsStoredWithoutADowngrade() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":"Approve?",
                 "contextHints":[],"confidence":0.91,"debugMetadata":{}}
                """);

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("external-client", "provider.codex"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.gateReason()).isEqualTo("clear_rule");
        assertThat(decision.debugMetadata()).containsEntry("untrustedClient", true);
        assertThat(gateway.lastRequest).isNotNull();
    }

    @Test
    void untrustedClientProviderOverrideStillRequiresProviderScope() {
        FakeGateway gateway = FakeGateway.responding("unused");

        assertThatThrownBy(() -> gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("external-client"), "codex")))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("missing scope provider.codex");

        assertThat(gateway.lastRequest).isNull();
        verify(retrievalService, never()).retrieve(any(), any(), any());
    }

    @Test
    void localTrustDefaultScopesStillStoreMemoryActive() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":"Approve?",
                 "contextHints":[],"confidence":0.91,"debugMetadata":{}}
                """);
        LocalTrustProperties localTrust = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                LocalTrustProperties.DEFAULT_LOCAL_SCOPES);

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("local-client",
                        localTrust.effectiveLocalScopes().toArray(String[]::new)), null));

        assertThat(localTrust.effectiveLocalScopes()).doesNotContain("memory.auto_active_write");
        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.debugMetadata()).containsEntry("untrustedClient", true);
        assertThat(gateway.lastRequest).isNotNull();
    }

    @Test
    void localTrustAutoActiveFlagAllowsAutoActiveWritesViaScope() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":[],"confidence":0.91,"debugMetadata":{}}
                """);
        LocalTrustProperties localTrust = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                LocalTrustProperties.DEFAULT_LOCAL_SCOPES, true);

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("local-client",
                        localTrust.effectiveLocalScopes().toArray(String[]::new)), null));

        assertThat(localTrust.effectiveLocalScopes()).contains("memory.auto_active_write");
        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
    }

    @Test
    void similarMemoryIsSentExactlyOnceInDedicatedContextBlock() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":[],"confidence":0.91,"debugMetadata":{}}
                """);
        LlmMemoryWriteGate gate = gate(gateway, PolicyEngine.allowAll());
        UUID id = UUID.randomUUID();
        MemoryContextItem item = new MemoryContextItem(id, UUID.randomUUID(), "project:" + id,
                MemoryScope.PROJECT, "PROJECT_A", MemoryType.RULE, "Unique existing rule",
                "unique-memory-marker", 0.9, false, 3, 0.8, Instant.now());
        when(retrievalService.retrieve(any(), eq("PROJECT_A"), any())).thenReturn(new MemoryContextResponse(
                List.of(item), List.of(id), 1, 3, Map.of("project", 1), 0, 0, 1));

        gate.evaluate(request(), new MemoryWriteGate.GateContext(
                context("claude-code", "memory.auto_active_write", "provider.claude"), null));

        assertThat(gateway.lastRequest.memoryContextBlock()).contains("unique-memory-marker");
        assertThat(gateway.lastRequest.question()).doesNotContain("unique-memory-marker");
    }

    @Test
    void sensitiveContentIsRejectedBeforeLlmCall() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":[],"confidence":0.95,"debugMetadata":{}}
                """);
        PolicyEngine policyEngine = new PolicyEngine() {
            @Override
            public PolicyDecision evaluateMemoryWrite(MemorySourceType sourceType, MemoryScope scope) {
                return PolicyDecision.allow();
            }

            @Override
            public PolicyDecision evaluateMemoryContent(String text) {
                return PolicyDecision.deny("sensitive-term");
            }
        };

        MemoryWriteGate.GateDecision decision = gate(gateway, policyEngine).evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.REJECTED);
        assertThat(decision.gateReason()).isEqualTo("sensitive-term");
        assertThat(gateway.lastRequest).isNull();
        verify(retrievalService, never()).retrieve(any(), any(), any());
    }

    @Test
    void oversizedMcpMemoryIsRejectedBeforeLlmCall() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":[],"confidence":0.95,"debugMetadata":{}}
                """);

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(
                request("x".repeat(MemoryAtomLimits.TEXT_MAX_CHARS + 1)),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.REJECTED);
        assertThat(decision.gateReason()).isEqualTo(LlmMemoryWriteGate.REASON_MEMORY_TOO_LARGE);
        assertThat(decision.debugMetadata()).containsEntry("textMaxChars", MemoryAtomLimits.TEXT_MAX_CHARS);
        assertThat(gateway.lastRequest).isNull();
        verify(retrievalService, never()).retrieve(any(), any(), any());
    }

    @Test
    void multiFactMcpMemoryNeedsHumanBeforeLlmCall() {
        FakeGateway gateway = FakeGateway.responding("""
                {"verdict":"AUTO_ACTIVE","gateReason":"clear_rule","suggestedQuestion":null,
                 "contextHints":[],"confidence":0.95,"debugMetadata":{}}
                """);
        String multiFact = """
                - First durable fact.
                - Second durable fact.
                - Third durable fact.
                """;

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(
                request(multiFact),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.REJECTED);
        assertThat(decision.gateReason()).isEqualTo(LlmMemoryWriteGate.REASON_MULTI_FACT_MEMORY);
        assertThat(gateway.lastRequest).isNull();
        verify(retrievalService, never()).retrieve(any(), any(), any());
    }

    @Test
    void malformedLlmResponseFailsOpenWithDegradedReason() {
        FakeGateway gateway = FakeGateway.responding("not-json");

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.gateReason()).isEqualTo(LlmMemoryWriteGate.REASON_DEGRADED);
        assertThat(decision.debugMetadata()).containsEntry("degraded", true);
    }

    @Test
    void llmTimeoutFailsOpenWithDegradedReason() {
        FakeGateway gateway = FakeGateway.throwing(new LLMProviderTimeoutException("claude", 3000,
                new RuntimeException("timeout")));

        MemoryWriteGate.GateDecision decision = gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null));

        assertThat(decision.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(decision.gateReason()).isEqualTo(LlmMemoryWriteGate.REASON_DEGRADED);
        assertThat(decision.debugMetadata()).containsEntry("provider", "claude");
    }

    @Test
    void shouldPropagateAccessErrorsFromGateBody() {
        FakeGateway gateway = FakeGateway.throwing(new McpAccessException("provider denied"));

        assertThatThrownBy(() -> gate(gateway, PolicyEngine.allowAll()).evaluate(request(),
                new MemoryWriteGate.GateContext(context("claude-code", "memory.auto_active_write",
                        "provider.claude"), null)))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("provider denied");
    }

    @Test
    void heuristicGateNeedsNoModelRejectsExactDuplicatesAndKeepsDifferentNearNeighbours() {
        FakeGateway gateway = FakeGateway.responding("{}");
        MemoryWriteGateProperties heuristic = new MemoryWriteGateProperties("heuristic", "qwen", 0.85,
                Duration.ofSeconds(3), Set.of());
        LlmMemoryWriteGate gate = new LlmMemoryWriteGate(gateway, parser,
                new MemoryWriteGateProviderResolver(heuristic, new McpClientProvidersProperties(Map.of()),
                        new ProviderOverrideSanitizer()),
                heuristic, retrievalService, contextBuilder, PolicyEngine.allowAll());
        java.util.UUID existing = java.util.UUID.randomUUID();
        MemoryContextItem twin = new MemoryContextItem(existing, existing, "m1", MemoryScope.PROJECT, "PROJECT_A",
                MemoryType.RULE, "Hexagonal rule", "  Domain code must NOT depend on adapters. ", null, 0.9, false, 20,
                0.97, java.time.Instant.now());
        MemoryContextItem distant = new MemoryContextItem(existing, existing, "m1", MemoryScope.PROJECT, "PROJECT_A",
                MemoryType.RULE, "Other", "Unrelated.", null, 0.9, false, 20, 0.41, java.time.Instant.now());
        // same topic, very similar wording, but a different fact: must not be lost as a "duplicate"
        MemoryContextItem sibling = new MemoryContextItem(existing, existing, "m1", MemoryScope.PROJECT, "PROJECT_A",
                MemoryType.RULE, "Hexagonal rule", "Domain code must not depend on adapters, except mappers.", null,
                0.9, false, 20, 0.97, java.time.Instant.now());
        var ctx = new MemoryWriteGate.GateContext(context("claude-code"), null);

        when(retrievalService.retrieve(any(), eq("PROJECT_A"), any())).thenReturn(response(twin));
        MemoryWriteGate.GateDecision duplicate = gate.evaluate(request(), ctx);
        when(retrievalService.retrieve(any(), eq("PROJECT_A"), any())).thenReturn(response(distant));
        MemoryWriteGate.GateDecision fresh = gate.evaluate(request(), ctx);
        when(retrievalService.retrieve(any(), eq("PROJECT_A"), any())).thenReturn(response(sibling));
        MemoryWriteGate.GateDecision kept = gate.evaluate(request(), ctx);

        assertThat(duplicate.verdict()).isEqualTo(MemoryWriteGate.Verdict.REJECTED);
        assertThat(duplicate.gateReason()).isEqualTo("duplicate_memory");
        assertThat(fresh.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(kept.verdict()).isEqualTo(MemoryWriteGate.Verdict.AUTO_ACTIVE);
        assertThat(kept.gateReason()).isEqualTo("heuristic_near_duplicate_kept");
        assertThat(gateway.lastRequest).isNull();
    }

    private static MemoryContextResponse response(MemoryContextItem item) {
        return new MemoryContextResponse(List.of(item), List.of(item.memoryId()), 1, item.tokenEstimate(),
                Map.of(), 0, 0, 0L);
    }

    private LlmMemoryWriteGate gate(LLMGateway gateway, PolicyEngine policyEngine) {
        when(retrievalService.retrieve(any(), eq("PROJECT_A"), any())).thenReturn(MemoryContextResponse.empty());
        return new LlmMemoryWriteGate(
                gateway,
                parser,
                new MemoryWriteGateProviderResolver(
                        new MemoryWriteGateProperties("sessionProvider", "qwen", 0.85, Duration.ofSeconds(3),
                                Set.of("claude-code", "codex", "self-pipeline")),
                        new McpClientProvidersProperties(Map.of("claude-code", "claude", "external-client",
                                "codex")),
                        new ProviderOverrideSanitizer()),
                new MemoryWriteGateProperties("sessionProvider", "qwen", 0.85, Duration.ofSeconds(3),
                        Set.of("claude-code", "codex", "self-pipeline")),
                retrievalService,
                contextBuilder,
                policyEngine);
    }

    private static CreateMemoryRequest request() {
        return request("Domain code must not depend on adapters.");
    }

    private static CreateMemoryRequest request(String text) {
        return new CreateMemoryRequest(
                MemoryScope.PROJECT,
                "PROJECT_A",
                MemoryType.RULE,
                "Hexagonal rule",
                text,
                List.of("architecture"),
                null,
                null,
                MemorySourceType.MCP_EXTERNAL,
                "test:" + UUID.randomUUID(),
                "claude-code",
                Map.of(),
                Instant.now(),
                null);
    }

    private static McpClientContext context(String clientId, String... scopes) {
        return new McpClientContext("PROJECT_A", clientId, "mcp_abcd", List.of(scopes));
    }

    private static final class FakeGateway implements LLMGateway {
        private final String response;
        private final RuntimeException exception;
        private GenerationRequest lastRequest;

        private FakeGateway(String response, RuntimeException exception) {
            this.response = response;
            this.exception = exception;
        }

        static FakeGateway responding(String response) {
            return new FakeGateway(response, null);
        }

        static FakeGateway throwing(RuntimeException exception) {
            return new FakeGateway(null, exception);
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            lastRequest = request;
            if (exception != null) {
                throw exception;
            }
            return new GenerationResponse(response, "fake", 10, false, DegradedReason.NONE, 10, 5, 0, 0, 0);
        }
    }
}
