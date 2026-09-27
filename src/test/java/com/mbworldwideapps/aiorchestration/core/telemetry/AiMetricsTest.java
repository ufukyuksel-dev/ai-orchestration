package com.mbworldwideapps.aiorchestration.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AiMetricsTest {

    @Test
    void allowlistPreventsContentBearingTagValues() {
        String value = AiMetrics.allowlisted("prompt=/secret?token=123", Set.of("ask", "index"), "other");

        assertThat(value).isEqualTo("other");
    }

    @Test
    void crawlerRejectReasonAllowsOnlyControlledValuesAndHttpStatusBuckets() {
        assertThat(AiMetrics.crawlerRejectReason("private-or-reserved-ip")).isEqualTo("private-or-reserved-ip");
        assertThat(AiMetrics.crawlerRejectReason("http-status-404")).isEqualTo("http-status-404");
        assertThat(AiMetrics.crawlerRejectReason("prompt=/secret?token=123")).isEqualTo("other");
    }

    @Test
    void memoryLoadRejectReasonAllowsOnlyControlledValues() {
        assertThat(AiMetrics.allowlisted("validation-error", Set.of("validation-error"), "other"))
                .isEqualTo("validation-error");
        assertThat(AiMetrics.allowlisted("text=secret", Set.of("validation-error"), "other"))
                .isEqualTo("other");
    }

    @Test
    void memoryPiiRejectMetricUsesAllowlistedPatternTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.recordMemoryPiiReject("tckn");
        metrics.recordMemoryPiiReject("text=10000000146");

        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "tckn").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_pii_rejects_total", "pattern", "other").count())
                .isEqualTo(1.0);
    }

    @Test
    void memoryPolicyRejectMetricUsesAllowlistedReasonTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.recordMemoryPolicyReject("sensitive-term");
        metrics.recordMemoryPolicyReject("text=ACME-LOAN-001");

        assertThat(registry.counter("ai_orchestration_memory_policy_rejects_total", "reason", "sensitive-term")
                .count()).isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_memory_policy_rejects_total", "reason", "other")
                .count()).isEqualTo(1.0);
    }

    @Test
    void orchestratorIntentMetricUsesAllowlistedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.recordOrchestratorIntent("write_code", "software", "keyword");
        metrics.recordOrchestratorIntent("prompt=secret", "target=secret", "mode=secret");

        assertThat(registry.counter("ai_orchestration_orchestrator_intents_total",
                "intent", "write_code", "targetAgent", "software", "classifierMode", "keyword").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_orchestrator_intents_total",
                "intent", "other", "targetAgent", "other", "classifierMode", "fallback").count()).isEqualTo(1.0);
    }

    @Test
    void taskStateV2OutcomesRemainBoundedButAreNotCollapsedToOther() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        for (String outcome : new String[] {
                "composite_prepare_failed",
                "composite_apply_blocked",
                "partial_apply",
                "coordination_deferred",
                "recovery_completed",
                "compensated_abort"}) {
            metrics.recordSoftwareTaskOutcome(outcome);
            assertThat(registry.counter("ai_orchestration_software_tasks_total", "outcome", outcome).count())
                    .as("outcome %s", outcome)
                    .isEqualTo(1.0);
        }
        metrics.recordSoftwareTaskOutcome("prompt=secret");
        assertThat(registry.counter("ai_orchestration_software_tasks_total", "outcome", "other").count())
                .isEqualTo(1.0);
    }

    @Test
    void complianceFindingMetricUsesAllowlistedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.recordComplianceFinding("critical", "approved");
        metrics.recordComplianceFinding("text=secret", "pack=secret");

        assertThat(registry.counter("ai_orchestration_compliance_findings_total",
                "severity", "critical", "packStatus", "approved").count()).isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_compliance_findings_total",
                "severity", "other", "packStatus", "other").count()).isEqualTo(1.0);
    }

    @Test
    void generationMetricsUseAllowlistedTagsAndRecordFallbacks() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.recordGenerationCall("local-qwen", "knowledgeai", "success", false, 42L);
        metrics.recordGenerationCall("local-qwen", "memory-curator", "success", false, 11L);
        metrics.recordGenerationCall("prompt=secret", "user=alex", "bad", true, 10L);
        metrics.recordGenerationFallback("local-qwen", "knowledgeai", DegradedReason.TIMEOUT);
        metrics.recordGenerationInjectedTokens("local-qwen", "knowledgeai", "memory", 120L);
        metrics.recordCanaryDecision("percentage");

        assertThat(registry.counter("ai_orchestration_generation_calls_total",
                "provider", "local-qwen", "role", "knowledgeai", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_generation_calls_total",
                "provider", "local-qwen", "role", "memory-curator", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_generation_calls_total",
                "provider", "other", "role", "unknown", "outcome", "error").count()).isEqualTo(1.0);
        assertThat(registry.counter("ai_orchestration_generation_fallback_total",
                "provider", "local-qwen", "role", "knowledgeai", "reason", "timeout").count()).isEqualTo(1.0);
        assertThat(registry.summary("ai_orchestration_generation_injected_tokens",
                "provider", "local-qwen", "role", "knowledgeai", "channel", "memory").totalAmount())
                .isEqualTo(120.0);
        assertThat(registry.counter("ai_orchestration_canary_decisions_total", "outcome", "percentage").count())
                .isEqualTo(1.0);
    }

    @Test
    void genericTelemetryRecordEmitsGenerationFallbackAndContextMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);

        metrics.record("ask", "knowledgeai.generated", "success", Map.of(
                "generationProvider", "local-qwen",
                "generationRole", "knowledgeai",
                "generationDegraded", true,
                "generationDegradedReason", "PROVIDER_ERROR",
                "memoryContextTokens", 10,
                "knowledgeContextTokens", 20));

        assertThat(registry.counter("ai_orchestration_generation_fallback_total",
                "provider", "local-qwen", "role", "knowledgeai", "reason", "provider_error").count())
                .isEqualTo(1.0);
        assertThat(registry.summary("ai_orchestration_generation_injected_tokens",
                "provider", "local-qwen", "role", "knowledgeai", "channel", "memory").totalAmount())
                .isEqualTo(10.0);
        assertThat(registry.summary("ai_orchestration_generation_injected_tokens",
                "provider", "local-qwen", "role", "knowledgeai", "channel", "knowledge").totalAmount())
                .isEqualTo(20.0);
    }
}
