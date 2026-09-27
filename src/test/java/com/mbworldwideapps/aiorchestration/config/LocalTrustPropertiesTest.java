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
    void defaultLocalTrustDoesNotGrantAutoActiveWriteScope() {
        LocalTrustProperties properties = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                LocalTrustProperties.DEFAULT_LOCAL_SCOPES);

        assertThat(properties.autoActiveWrite()).isFalse();
        assertThat(properties.effectiveLocalScopes()).doesNotContain("memory.auto_active_write");
        assertThat(properties.effectiveLocalScopes()).contains(
                "context.graph.read", "context.semantic.read",
                "rules.read", "rules.write", "rules.confirm", "rules.global.confirm");
    }

    @Test
    void autoActiveWriteFlagAddsScopeOnce() {
        LocalTrustProperties properties = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                List.of("memory.read", "memory.auto_active_write"), true);

        assertThat(properties.effectiveLocalScopes())
                .contains("memory.read", "memory.auto_active_write");
        assertThat(properties.effectiveLocalScopes().stream()
                .filter("memory.auto_active_write"::equals)
                .count()).isEqualTo(1);
    }

    @Test
    void autoActiveScopeIsWithheldWhenFlagIsFalseEvenIfConfiguredInLocalScopes() {
        LocalTrustProperties properties = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                List.of("memory.read", "memory.auto_active_write"), false);

        assertThat(properties.localScopes()).containsExactly("memory.read");
        assertThat(properties.effectiveLocalScopes()).doesNotContain("memory.auto_active_write");
    }

    @Test
    void bindsFromSpringConfigurationProperties() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.local-trust.enabled=true",
                        "ai-orchestration.local-trust.default-project-key=PROJECT_A",
                        "ai-orchestration.local-trust.default-client-id=codex",
                        "ai-orchestration.local-trust.local-scopes[0]=memory.read",
                        "ai-orchestration.local-trust.auto-active-write=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalTrustProperties.class);
                    LocalTrustProperties properties = context.getBean(LocalTrustProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.defaultProjectKey()).isEqualTo("PROJECT_A");
                    assertThat(properties.defaultClientId()).isEqualTo("codex");
                    assertThat(properties.effectiveLocalScopes())
                            .containsExactly("memory.read", "memory.auto_active_write");
                });
    }

    @EnableConfigurationProperties(LocalTrustProperties.class)
    static class Config {
    }
}
