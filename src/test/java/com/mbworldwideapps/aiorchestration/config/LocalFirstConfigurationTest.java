package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class LocalFirstConfigurationTest {

    @Test
    void mergedProfileEnablesLearningAndMemoryInjectionByDefault() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        loader.load("application-local-first", new ClassPathResource("application-local-first.yml"))
                .forEach(environment.getPropertySources()::addFirst);

        assertThat(environment.getProperty("ai-orchestration.learning.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ai-orchestration.learning.capture-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ai-orchestration.learning.navigation-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ai-orchestration.learning-context.include-memory-fallback",
                Boolean.class)).isTrue();
        assertThat(environment.getProperty("ai-orchestration.memory.injection-enabled", Boolean.class)).isTrue();
        AiOrchestrationProperties bound = Binder.get(environment)
                .bind("ai-orchestration", Bindable.of(AiOrchestrationProperties.class))
                .orElseThrow(() -> new AssertionError("ai-orchestration properties did not bind"));
        assertThat(bound.memory().injectionEnabled()).isTrue();
    }
}
