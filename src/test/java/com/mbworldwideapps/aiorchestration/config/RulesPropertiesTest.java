package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RulesPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsFromNamespacedPrefixAndUsesDocumentedLimits() {
        contextRunner
                .withPropertyValues(
                        "ai-orchestration.rules.enabled=true",
                        "ai-orchestration.rules.max-bindings-per-rule=20",
                        "ai-orchestration.rules.max-glob-length=256",
                        "ai-orchestration.rules.max-detector-config-bytes=4096",
                        "ai-orchestration.rules.max-active-glob-rules-per-project=200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RulesProperties properties = context.getBean(RulesProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.maxBindingsPerRule()).isEqualTo(20);
                    assertThat(properties.maxGlobLength()).isEqualTo(256);
                    assertThat(properties.maxDetectorConfigBytes()).isEqualTo(4096);
                    assertThat(properties.maxActiveGlobRulesPerProject()).isEqualTo(200);
                });
    }

    @Test
    void rejectsLimitsAboveTheContract() {
        contextRunner
                .withPropertyValues("ai-orchestration.rules.max-bindings-per-rule=21")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("ai-orchestration.rules.max-glob-length=257")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("ai-orchestration.rules.max-detector-config-bytes=4097")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("ai-orchestration.rules.max-active-glob-rules-per-project=201")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void legacyRootPrefixCannotDisableRuleAuthority() {
        contextRunner
                .withPropertyValues("rules.enabled=false")
                .run(context -> assertThat(context.getBean(RulesProperties.class).enabled()).isTrue());
    }

    @Test
    void defaultsToEnabledAndAllowsExplicitNamespacedDisable() {
        contextRunner.run(context ->
                assertThat(context.getBean(RulesProperties.class).enabled()).isTrue());
        contextRunner.withPropertyValues("ai-orchestration.rules.enabled=false")
                .run(context -> assertThat(context.getBean(RulesProperties.class).enabled()).isFalse());
    }

    @Configuration
    @EnableConfigurationProperties(RulesProperties.class)
    static class TestConfig {
    }
}
