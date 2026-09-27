package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SimpleRoutingPolicyTest {

    @Test
    void autoRoutingUsesActuallyBoundGenerationConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ai-orchestration.chat-model", "benchmark-placeholder",
                "ai-orchestration.generation.provider", "codex",
                "ai-orchestration.generation.timeout-ms", "90000",
                "ai-orchestration.generation.enabled", "true"));
        var bound = new Binder(source).bind("ai-orchestration", AiOrchestrationProperties.class).get();
        assertThat(bound.generation().provider()).isEqualTo("codex");
        assertThat(bound.generation().timeoutMs()).isEqualTo(90000L);
        assertThat(bound.generation().enabled()).isTrue();
        assertThat(new SimpleRoutingPolicy(bound).select(new GenerationRequest(
                "q", "u", List.of(), "fresh", null, "", 0, "memory-relation-judge")))
                .isEqualTo("codex");
    }

    @Test
    void usesExplicitProviderWhenPresent() {
        SimpleRoutingPolicy policy = new SimpleRoutingPolicy(properties("local-qwen"));

        String provider = policy.select(new GenerationRequest("q", "u", List.of(), "fresh", "claude-stub",
                null, 0, "test"));

        assertThat(provider).isEqualTo("claude-stub");
    }

    @Test
    void fallsBackToConfiguredProvider() {
        SimpleRoutingPolicy policy = new SimpleRoutingPolicy(properties("local-qwen"));

        String provider = policy.select(new GenerationRequest("q", "u", List.of(), "fresh", null,
                null, 0, "test"));

        assertThat(provider).isEqualTo("local-qwen");
    }

    private static AiOrchestrationProperties properties(String provider) {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(true, provider, true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                365);
    }
}
