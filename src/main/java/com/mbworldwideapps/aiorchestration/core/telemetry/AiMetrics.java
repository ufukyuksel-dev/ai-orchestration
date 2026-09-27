package com.mbworldwideapps.aiorchestration.core.telemetry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import org.springframework.stereotype.Component;

@Component
public class AiMetrics {

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of("ask", "index", "pii_reject");
    private static final Set<String> ALLOWED_DECISIONS = Set.of("success", "no_context", "rejected", "failed");
    private static final Set<String> ALLOWED_ROUTES = Set.of("knowledgeai.extractive", "knowledgeai.generated");
    private static final Set<String> ALLOWED_CRAWLER_REJECT_REASONS = Set.of(
            "scheme-not-allowed",
            "domain-not-allowed",
            "private-or-reserved-ip",
            "content-type-not-allowed",
            "body-too-large",
            "too-many-redirects",
            "dns-resolution-failed",
            "robots-disallowed",
            "redirect-missing-location",
            "missing-host",
            "blank-url",
            "invalid-url",
            "userinfo-not-allowed",
            "fetch-io-error",
            "fetch-interrupted");
    private static final Set<String> ALLOWED_MEMORY_LOAD_REJECT_REASONS = Set.of(
            "parse-error",
            "validation-error",
            "unsupported-file",
            "load-io-error");
    private static final Set<String> ALLOWED_MEMORY_PII_REJECT_PATTERNS = Set.of(
            "tckn",
            "iban",
            "email",
            "phone",
            "payment-card");
    private static final Set<String> ALLOWED_MEMORY_POLICY_REJECT_REASONS = Set.of(
            "sensitive-term",
            "write-source-scope-denied");
    private static final Set<String> ALLOWED_ORCHESTRATOR_INTENTS = Set.of(
            "analyze_code",
            "write_code",
            "scan_codebase",
            "general_question",
            "ambiguous");
    private static final Set<String> ALLOWED_ORCHESTRATOR_TARGETS = Set.of(
            "analyst",
            "software",
            "scanner",
            "direct_answer",
            "human_clarification");
    private static final Set<String> ALLOWED_CLASSIFIER_MODES = Set.of(
            "keyword",
            "llm",
            "hybrid",
            "fallback");
    private static final Set<String> ALLOWED_SOFTWARE_OUTCOMES = Set.of(
            "diff_ready",
            "applied",
            "test_failed",
            "secret_blocked",
            "compliance_blocked",
            "approval_blocked",
            "composite_prepare_failed",
            "composite_apply_blocked",
            "partial_apply",
            "coordination_deferred",
            "recovery_completed",
            "compensated_abort",
            "failed");
    private static final Set<String> ALLOWED_COMPLIANCE_SEVERITIES = Set.of(
            "info", "low", "medium", "high", "critical");
    private static final Set<String> ALLOWED_COMPLIANCE_PACK_STATUSES = Set.of(
            "illustrative", "approved");
    private static final Set<String> ALLOWED_LLM_PROVIDERS = Set.of(
            "local-qwen",
            "claude-stub",
            "claude",
            "codex",
            "ide-bridge",
            "extractive");
    private static final Set<String> ALLOWED_LLM_ROLES = Set.of(
            "knowledgeai",
            "analyst",
            "software",
            "orchestrator",
            "scanner",
            "memory-curator",
            "test",
            "unknown");
    private static final Set<String> ALLOWED_GENERATION_OUTCOMES = Set.of("success", "timeout", "error");
    private static final Set<String> ALLOWED_CONTEXT_CHANNELS = Set.of("memory", "knowledge");
    private static final Set<String> ALLOWED_CANARY_OUTCOMES = Set.of(
            "global-enabled",
            "allowed-user",
            "percentage",
            "disabled",
            "not-selected");

    private final MeterRegistry meterRegistry;

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String eventType, String route, String decision, Map<String, Object> metadata) {
        String safeEventType = allowlisted(eventType, ALLOWED_EVENT_TYPES, "other");
        String safeRoute = allowlisted(route, ALLOWED_ROUTES, "other");
        String safeDecision = allowlisted(decision, ALLOWED_DECISIONS, "other");

        Number inputTokens = number(metadata.get("inputTokensEstimate"));
        Number outputTokens = number(metadata.get("outputTokensEstimate"));
        if (inputTokens != null) {
            Counter.builder("ai_orchestration_tokens_total")
                    .tag("event", safeEventType)
                    .tag("route", safeRoute)
                    .tag("direction", "input")
                    .register(meterRegistry)
                    .increment(inputTokens.doubleValue());
        }
        if (outputTokens != null) {
            Counter.builder("ai_orchestration_tokens_total")
                    .tag("event", safeEventType)
                    .tag("route", safeRoute)
                    .tag("direction", "output")
                    .register(meterRegistry)
                    .increment(outputTokens.doubleValue());
        }

        Number durationMs = number(metadata.get("durationMs"));
        if (durationMs != null) {
            Timer.builder("ai_orchestration_latency")
                    .tag("event", safeEventType)
                    .tag("route", safeRoute)
                    .tag("decision", safeDecision)
                    .register(meterRegistry)
                    .record(durationMs.longValue(), TimeUnit.MILLISECONDS);
        }

        Counter.builder("ai_orchestration_acl_decisions_total")
                .tag("decision", safeDecision)
                .register(meterRegistry)
                .increment();

        recordGenerationFromMetadata(metadata);
    }

    public void recordCrawlerReject(String reason) {
        Counter.builder("ai_orchestration_crawler_rejects_total")
                .tag("reason", crawlerRejectReason(reason))
                .register(meterRegistry)
                .increment();
    }

    public void recordMemoryLoadReject(String reason) {
        Counter.builder("ai_orchestration_memory_load_rejects_total")
                .tag("reason", allowlisted(reason, ALLOWED_MEMORY_LOAD_REJECT_REASONS, "other"))
                .register(meterRegistry)
                .increment();
    }

    public void recordMemoryPiiReject(String pattern) {
        Counter.builder("ai_orchestration_memory_pii_rejects_total")
                .tag("pattern", allowlisted(pattern, ALLOWED_MEMORY_PII_REJECT_PATTERNS, "other"))
                .register(meterRegistry)
                .increment();
    }

    public void recordMemoryPolicyReject(String reason) {
        Counter.builder("ai_orchestration_memory_policy_rejects_total")
                .tag("reason", allowlisted(reason, ALLOWED_MEMORY_POLICY_REJECT_REASONS, "other"))
                .register(meterRegistry)
                .increment();
    }

    public void recordOrchestratorIntent(String intent, String targetAgent, String classifierMode) {
        Counter.builder("ai_orchestration_orchestrator_intents_total")
                .tag("intent", allowlisted(intent, ALLOWED_ORCHESTRATOR_INTENTS, "other"))
                .tag("targetAgent", allowlisted(targetAgent, ALLOWED_ORCHESTRATOR_TARGETS, "other"))
                .tag("classifierMode", allowlisted(classifierMode, ALLOWED_CLASSIFIER_MODES, "fallback"))
                .register(meterRegistry)
                .increment();
    }

    public void recordSoftwareTaskOutcome(String outcome) {
        Counter.builder("ai_orchestration_software_tasks_total")
                .tag("outcome", allowlisted(outcome, ALLOWED_SOFTWARE_OUTCOMES, "other"))
                .register(meterRegistry)
                .increment();
    }

    public void recordComplianceFinding(String severity, String packStatus) {
        Counter.builder("ai_orchestration_compliance_findings_total")
                .tag("severity", allowlisted(severity, ALLOWED_COMPLIANCE_SEVERITIES, "other"))
                .tag("packStatus", allowlisted(packStatus, ALLOWED_COMPLIANCE_PACK_STATUSES, "other"))
                .register(meterRegistry)
                .increment();
    }

    public void recordGenerationCall(String provider, String role, String outcome, boolean degraded, long latencyMs) {
        String safeProvider = provider(provider);
        String safeRole = role(role);
        String safeOutcome = allowlisted(outcome, ALLOWED_GENERATION_OUTCOMES, "error");
        Timer.builder("ai_orchestration_generation_latency_ms")
                .tag("provider", safeProvider)
                .tag("role", safeRole)
                .tag("degraded", String.valueOf(degraded))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
        Counter.builder("ai_orchestration_generation_calls_total")
                .tag("provider", safeProvider)
                .tag("role", safeRole)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .increment();
    }

    public void recordGenerationFallback(String provider, String role, DegradedReason reason) {
        DegradedReason safeReason = reason == null ? DegradedReason.PROVIDER_ERROR : reason;
        if (safeReason == DegradedReason.NONE) {
            return;
        }
        Counter.builder("ai_orchestration_generation_fallback_total")
                .tag("provider", provider(provider))
                .tag("role", role(role))
                .tag("reason", safeReason.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }

    public void recordGenerationInjectedTokens(String provider, String role, String channel, long tokenEstimate) {
        DistributionSummary.builder("ai_orchestration_generation_injected_tokens")
                .tag("provider", provider(provider))
                .tag("role", role(role))
                .tag("channel", allowlisted(channel, ALLOWED_CONTEXT_CHANNELS, "memory"))
                .register(meterRegistry)
                .record(Math.max(0L, tokenEstimate));
    }

    public void recordCanaryDecision(String outcome) {
        Counter.builder("ai_orchestration_canary_decisions_total")
                .tag("outcome", allowlisted(outcome, ALLOWED_CANARY_OUTCOMES, "not-selected"))
                .register(meterRegistry)
                .increment();
    }

    static String crawlerRejectReason(String reason) {
        String normalized = allowlisted(reason, ALLOWED_CRAWLER_REJECT_REASONS, null);
        if (normalized != null) {
            return normalized;
        }
        if (reason != null && reason.trim().toLowerCase(Locale.ROOT).matches("http-status-[1-5][0-9][0-9]")) {
            return reason.trim().toLowerCase(Locale.ROOT);
        }
        return "other";
    }

    static String allowlisted(String value, Set<String> allowedValues, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private void recordGenerationFromMetadata(Map<String, Object> metadata) {
        Object providerValue = metadata.get("generationProvider");
        if (!(providerValue instanceof String provider) || "extractive".equals(provider)) {
            return;
        }
        String role = string(metadata.getOrDefault("generationRole", "knowledgeai"));
        Number memoryTokens = number(metadata.get("memoryContextTokens"));
        if (memoryTokens != null) {
            recordGenerationInjectedTokens(provider, role, "memory", memoryTokens.longValue());
        }
        Number knowledgeTokens = number(metadata.get("knowledgeContextTokens"));
        if (knowledgeTokens != null) {
            recordGenerationInjectedTokens(provider, role, "knowledge", knowledgeTokens.longValue());
        }
        if (Boolean.TRUE.equals(metadata.get("generationDegraded"))) {
            recordGenerationFallback(provider, role, degradedReason(metadata.get("generationDegradedReason")));
        }
    }

    private static DegradedReason degradedReason(Object value) {
        if (value == null) {
            return DegradedReason.PROVIDER_ERROR;
        }
        try {
            return DegradedReason.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DegradedReason.PROVIDER_ERROR;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String provider(String value) {
        if (value == null || value.isBlank()) {
            return "other";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ide-bridge:")) {
            normalized = "ide-bridge";
        }
        return allowlisted(normalized, ALLOWED_LLM_PROVIDERS, "other");
    }

    private static String role(String value) {
        return allowlisted(value, ALLOWED_LLM_ROLES, "unknown");
    }
}
