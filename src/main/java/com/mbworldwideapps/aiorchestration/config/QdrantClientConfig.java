package com.mbworldwideapps.aiorchestration.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class QdrantClientConfig {

    @Bean
    @ConditionalOnMissingBean
    QdrantClient qdrantClient(QdrantVectorStoreProperties qdrantProperties,
            AiOrchestrationProperties aiOrchestrationProperties) {
        boolean checkCompatibility = !aiOrchestrationProperties.qdrant().checkCompatibilitySkipped();
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                qdrantProperties.getHost(),
                qdrantProperties.getPort(),
                qdrantProperties.isUseTls(),
                checkCompatibility);
        if (StringUtils.hasText(qdrantProperties.getApiKey())) {
            builder.withApiKey(qdrantProperties.getApiKey());
        }
        return new QdrantClient(builder.build());
    }
}
