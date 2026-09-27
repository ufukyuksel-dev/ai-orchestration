package com.mbworldwideapps.aiorchestration.config;

import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    /**
     * Offline token-hash embedding, used only when {@code spring.ai.model.embedding=hashing} (hermetic tests, a
     * machine without Ollama). The normal default is Ollama bge-m3 from Spring AI: it matches a request to a
     * memory by meaning and across languages (a Turkish task finds an English card), which hashing cannot.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "hashing")
    EmbeddingModel embeddingModel(AiOrchestrationProperties properties) {
        return new HashingEmbeddingModel(properties.embeddingDimensions());
    }
}
