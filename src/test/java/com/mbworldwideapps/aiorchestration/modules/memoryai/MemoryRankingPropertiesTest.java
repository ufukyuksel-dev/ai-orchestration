package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MemoryRankingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsCostSafeRankingSettings() {
        contextRunner.withPropertyValues(
                "ai-orchestration.memory.ranking.oversample-factor=3",
                "ai-orchestration.memory.ranking.min-injection-similarity=0.31",
                "ai-orchestration.memory.ranking.min-search-similarity=0.17")
                .run(context -> {
                    MemoryRankingProperties properties = context.getBean(MemoryRankingProperties.class);
                    assertThat(properties.oversampleFactor()).isEqualTo(3);
                    assertThat(properties.minInjectionSimilarity()).isEqualTo(0.31);
                    assertThat(properties.minSearchSimilarity()).isEqualTo(0.17);
                });
    }

    @Test
    void defaultsAreBoundWithoutExplicitConfiguration() {
        contextRunner.run(context -> {
            MemoryRankingProperties properties = context.getBean(MemoryRankingProperties.class);
            assertThat(properties.oversampleFactor()).isEqualTo(2);
            assertThat(properties.minInjectionSimilarity()).isEqualTo(0.10);
            assertThat(properties.minSearchSimilarity()).isEqualTo(0.10);
        });
    }

    @Configuration
    @EnableConfigurationProperties(MemoryRankingProperties.class)
    static class TestConfig {
    }
}
