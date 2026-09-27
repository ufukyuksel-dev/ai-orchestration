package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LearningContextPropertiesTest {

    @Test
    void normalizesInvalidDefaultsAndCapsDepthAtThree() {
        LearningContextProperties properties = new LearningContextProperties(false, 0, 1, 9, 99,
                0, 1, 0, 0, true, true, false, false, false);

        assertThat(properties.defaultTopK()).isEqualTo(5);
        assertThat(properties.maxTopK()).isEqualTo(20);
        assertThat(properties.defaultMaxDepth()).isEqualTo(3);
        assertThat(properties.maxDepth()).isEqualTo(3);
        assertThat(properties.defaultTokenBudget()).isEqualTo(1200);
        assertThat(properties.maxTokenBudget()).isEqualTo(4000);
        assertThat(properties.catastrophicItemMultiplier()).isEqualTo(2);
        assertThat(properties.maxRenderedItemChars()).isEqualTo(800);
    }
}
