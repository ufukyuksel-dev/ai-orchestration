package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalTrustPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultLocalTrustGrantsEveryScopeIncludingFormerlyGatedOnes() {
        LocalTrustProperties properties = new LocalTrustProperties(true, "PROJECT_A", "local-client", null);

        assertThat(properties.effectiveLocalScopes()).contains(
                "memory.approve", "memory.admin", "memory.auto_active_write", "provider.*",
                "memory.human_confirmed_approve", "scanner.insights.write-memory",
                "context.graph.read", "context.semantic.read",
                "rules.read", "rules.write", "rules.confirm", "rules.global.confirm");
    }

    @Test
    void configuredScopesAreTrimmedAndDeduplicated() {
        LocalTrustProperties properties = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                List.of(" memory.read", "memory.auto_active_write", "memory.read", ""));

        assertThat(properties.effectiveLocalScopes()).containsExactly("memory.read", "memory.auto_active_write");
    }

    @Test
    void bindsFromSpringConfigurationProperties() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.local-trust.enabled=true",
                        "ai-orchestration.local-trust.default-project-key=PROJECT_A",
                        "ai-orchestration.local-trust.default-client-id=codex",
                        "ai-orchestration.local-trust.local-scopes[0]=memory.read")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalTrustProperties.class);
                    LocalTrustProperties properties = context.getBean(LocalTrustProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.defaultProjectKey()).isEqualTo("PROJECT_A");
                    assertThat(properties.defaultClientId()).isEqualTo("codex");
                    assertThat(properties.effectiveLocalScopes()).containsExactly("memory.read");
                });
    }

    @EnableConfigurationProperties(LocalTrustProperties.class)
    static class Config {
    }
}
