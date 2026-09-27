package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrchestratorRoutingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsRoutingProperties() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.orchestrator.routing.hybrid-enabled=false",
                        "ai-orchestration.orchestrator.routing.llm-provider=ide-bridge:codex",
                        "ai-orchestration.orchestrator.routing.llm-timeout-ms=1500",
                        "ai-orchestration.orchestrator.routing.llm-max-tokens=64",
                        "ai-orchestration.orchestrator.routing.commit-threshold=0.9",
                        "ai-orchestration.orchestrator.routing.ambiguous-floor=0.4",
                        "ai-orchestration.orchestrator.routing.memory-top-k=2",
                        "ai-orchestration.orchestrator.routing.knowledge-top-k=4",
                        "ai-orchestration.orchestrator.routing.total-context-token-cap=3000")
                .run(context -> {
                    OrchestratorRoutingProperties properties = context.getBean(OrchestratorRoutingProperties.class);

                    assertThat(properties.hybridEnabled()).isFalse();
                    assertThat(properties.llmProvider()).isEqualTo("ide-bridge:codex");
                    assertThat(properties.llmTimeoutMs()).isEqualTo(1500L);
                    assertThat(properties.llmMaxTokens()).isEqualTo(64);
                    assertThat(properties.commitThreshold()).isEqualTo(0.9);
                    assertThat(properties.ambiguousFloor()).isEqualTo(0.4);
                    assertThat(properties.memoryTopK()).isEqualTo(2);
                    assertThat(properties.knowledgeTopK()).isEqualTo(4);
                    assertThat(properties.totalContextTokenCap()).isEqualTo(3000);
                });
    }

    @Test
    void defaultsToHybridEnabled() {
        contextRunner.run(context -> {
            OrchestratorRoutingProperties properties = context.getBean(OrchestratorRoutingProperties.class);

            assertThat(properties.hybridEnabled()).isTrue();
            assertThat(properties.commitThreshold()).isEqualTo(0.85);
            assertThat(properties.ambiguousFloor()).isEqualTo(0.5);
            assertThat(properties.totalContextTokenCap()).isEqualTo(4000);
        });
    }

    @Configuration
    @EnableConfigurationProperties(OrchestratorRoutingProperties.class)
    static class TestConfig {
    }
}
