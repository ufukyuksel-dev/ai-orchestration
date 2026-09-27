package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MemoryWriteGatePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldFailStartupWhenProviderIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("provider is required");
        });
    }

    @Test
    void shouldFailStartupWhenSessionProviderFallbackIsMissing() {
        contextRunner
                .withPropertyValues("ai-orchestration.memory.write-gate.provider=sessionProvider")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("sessionFallbackProvider is required");
                });
    }

    @Test
    void shouldFailStartupWhenProviderIsInvalid() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.memory.write-gate.provider=gpt-random",
                        "ai-orchestration.memory.write-gate.session-fallback-provider=qwen")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Unsupported memory.write-gate.provider");
                });
    }

    @Test
    void shouldBindSessionProviderAndDefaults() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.memory.write-gate.provider=sessionProvider",
                        "ai-orchestration.memory.write-gate.session-fallback-provider=qwen")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MemoryWriteGateProperties properties = context.getBean(MemoryWriteGateProperties.class);
                    MemoryConfirmProperties confirm = context.getBean(MemoryConfirmProperties.class);

                    assertThat(properties.provider()).isEqualTo(MemoryWriteGateProperties.PROVIDER_SESSION);
                    assertThat(properties.sessionFallbackProvider()).isEqualTo("local-qwen");
                    assertThat(properties.autoActiveConfidence()).isEqualTo(0.85);
                    assertThat(properties.gateTimeout()).hasSeconds(3);
                    assertThat(properties.trustedClientIds())
                            .containsExactlyInAnyOrder("self-pipeline", "claude-code", "codex");
                    assertThat(confirm.minAgentConfidence()).isEqualTo(0.75);
                });
    }

    @Test
    void shouldAllowExplicitEmptyTrustedClients() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.memory.write-gate.provider=sessionProvider",
                        "ai-orchestration.memory.write-gate.session-fallback-provider=qwen",
                        "ai-orchestration.memory.write-gate.trusted-client-ids=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MemoryWriteGateProperties properties = context.getBean(MemoryWriteGateProperties.class);

                    assertThat(properties.trustedClientIds()).isEmpty();
                });
    }

    @Test
    void shouldBindMcpClientProviderMap() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.memory.write-gate.provider=sessionProvider",
                        "ai-orchestration.memory.write-gate.session-fallback-provider=qwen",
                        "ai-orchestration.mcp.client-providers.claude-code=claude",
                        "ai-orchestration.mcp.client-providers.codex=codex",
                        "ai-orchestration.mcp.client-providers.self-pipeline=qwen")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    McpClientProvidersProperties providers = context.getBean(McpClientProvidersProperties.class);

                    assertThat(providers.providerFor("CLAUDE-CODE")).isEqualTo("claude");
                    assertThat(providers.providerFor("codex")).isEqualTo("codex");
                    assertThat(providers.providerFor("self-pipeline")).isEqualTo("local-qwen");
                });
    }

    @Test
    void shouldFailStartupWhenClientProviderMapContainsInvalidProvider() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.memory.write-gate.provider=sessionProvider",
                        "ai-orchestration.memory.write-gate.session-fallback-provider=qwen",
                        "ai-orchestration.mcp.client-providers.codex=gpt-random")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("mcp.client-providers.codex");
                });
    }

    @Configuration
    @EnableConfigurationProperties({
            MemoryWriteGateProperties.class,
            MemoryConfirmProperties.class,
            McpClientProvidersProperties.class
    })
    static class TestConfig {
    }
}
