package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ExternalCliProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsExternalCliProperties() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.software.codegen.external-cli.enabled=false",
                        "ai-orchestration.software.codegen.external-cli.claude-cli-path=/opt/bin/claude",
                        "ai-orchestration.software.codegen.external-cli.claude-cli-model=sonnet-test",
                        "ai-orchestration.software.codegen.external-cli.claude-cli-timeout-ms=12345",
                        "ai-orchestration.software.codegen.external-cli.claude-cli-args-extra[0]=--permission-mode",
                        "ai-orchestration.software.codegen.external-cli.claude-cli-args-extra[1]=acceptEdits",
                        "ai-orchestration.software.codegen.external-cli.codex-cli-path=/opt/bin/codex",
                        "ai-orchestration.software.codegen.external-cli.codex-cli-model=gpt-test",
                        "ai-orchestration.software.codegen.external-cli.codex-cli-timeout-ms=23456",
                        "ai-orchestration.software.codegen.external-cli.codex-cli-args-extra[0]=--ephemeral",
                        "ai-orchestration.software.codegen.external-cli.fallback-to-local-qwen=false")
                .run(context -> {
                    ExternalCliProviderProperties properties = context.getBean(ExternalCliProviderProperties.class);

                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.claudeCliPath()).isEqualTo("/opt/bin/claude");
                    assertThat(properties.claudeCliModel()).isEqualTo("sonnet-test");
                    assertThat(properties.claudeCliTimeoutMs()).isEqualTo(12_345L);
                    assertThat(properties.claudeCliArgsExtra()).containsExactly("--permission-mode", "acceptEdits");
                    assertThat(properties.codexCliPath()).isEqualTo("/opt/bin/codex");
                    assertThat(properties.codexCliModel()).isEqualTo("gpt-test");
                    assertThat(properties.codexCliTimeoutMs()).isEqualTo(23_456L);
                    assertThat(properties.codexCliArgsExtra()).containsExactly("--ephemeral");
                    assertThat(properties.fallbackToLocalQwen()).isFalse();
                });
    }

    @Test
    void defaultsToSessionBasedCliProvider() {
        contextRunner.run(context -> {
            ExternalCliProviderProperties properties = context.getBean(ExternalCliProviderProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.claudeCliPath()).isEqualTo("claude");
            assertThat(properties.claudeCliTimeoutMs()).isEqualTo(60_000L);
            assertThat(properties.claudeCliArgsExtra()).isEmpty();
            assertThat(properties.codexCliPath()).isEqualTo("codex");
            assertThat(properties.codexCliModel()).isNull();
            assertThat(properties.codexCliTimeoutMs()).isEqualTo(60_000L);
            assertThat(properties.codexCliArgsExtra()).isEmpty();
            assertThat(properties.fallbackToLocalQwen()).isTrue();
        });
    }

    @Configuration
    @EnableConfigurationProperties(ExternalCliProviderProperties.class)
    static class TestConfig {
    }
}
