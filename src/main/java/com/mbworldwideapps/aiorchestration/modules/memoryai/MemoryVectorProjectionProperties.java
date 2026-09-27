package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.memory.projection")
public record MemoryVectorProjectionProperties(
        MemoryVectorProjectionMode mode,
        Integer queueCapacity,
        Integer batchSize,
        Integer maxAttempts,
        Long pollIntervalMs) {

    @ConstructorBinding
    public MemoryVectorProjectionProperties {
        mode = mode == null ? MemoryVectorProjectionMode.SYNC : mode;
        queueCapacity = queueCapacity == null ? 10_000 : queueCapacity;
        batchSize = batchSize == null ? 20 : batchSize;
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        pollIntervalMs = pollIntervalMs == null ? 1_000L : pollIntervalMs;
        if (queueCapacity < 1 || queueCapacity > 1_000_000) {
            throw new IllegalArgumentException("memory projection queueCapacity must be 1..1000000");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("memory projection batchSize must be 1..1000");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("memory projection maxAttempts must be 1..100");
        }
        if (pollIntervalMs < 100 || pollIntervalMs > 3_600_000) {
            throw new IllegalArgumentException("memory projection pollIntervalMs must be 100..3600000");
        }
    }

    public static MemoryVectorProjectionProperties defaults() {
        return new MemoryVectorProjectionProperties(null, null, null, null, null);
    }
}
