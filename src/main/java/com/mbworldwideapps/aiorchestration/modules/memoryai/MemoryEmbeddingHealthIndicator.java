package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("memoryEmbedding")
public class MemoryEmbeddingHealthIndicator implements HealthIndicator {

    private final MemoryEmbeddingContract contract;
    private final AiOrchestrationProperties properties;

    public MemoryEmbeddingHealthIndicator(MemoryEmbeddingContract contract,
            AiOrchestrationProperties properties) {
        this.contract = contract;
        this.properties = properties;
    }

    @Override
    public Health health() {
        MemoryEmbeddingFingerprint fingerprint = contract.activeFingerprint();
        MemoryEmbeddingContract.Compatibility compatibility = contract.activeCompatibility();
        Health.Builder builder = compatibility == MemoryEmbeddingContract.Compatibility.MISMATCH
                ? Health.down()
                : Health.up();
        return builder
                .withDetail("compatibility", compatibility.name())
                .withDetail("runtimeModelClass", fingerprint.runtimeModelClass())
                .withDetail("provider", fingerprint.provider())
                .withDetail("modelIdentity", fingerprint.modelIdentity())
                .withDetail("modelRevision", fingerprint.modelRevision())
                .withDetail("dimensions", fingerprint.dimensions())
                .withDetail("normalization", fingerprint.normalization())
                .withDetail("retrievalTextSchemaVersion", fingerprint.retrievalTextSchemaVersion())
                .withDetail("indexFingerprint", fingerprint.value())
                .withDetail("expectedIndexFingerprint", contract.expectedActiveFingerprint())
                .withDetail("collection", properties.memory().episodicCollectionName())
                .build();
    }
}
