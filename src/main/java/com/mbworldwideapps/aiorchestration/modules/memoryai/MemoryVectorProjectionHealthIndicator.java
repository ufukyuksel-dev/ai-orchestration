package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("memoryVectorProjection")
class MemoryVectorProjectionHealthIndicator implements HealthIndicator {

    private final MemoryVectorProjectionStore store;
    private final MemoryVectorProjectionProperties properties;

    MemoryVectorProjectionHealthIndicator(MemoryVectorProjectionStore store,
            MemoryVectorProjectionProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public Health health() {
        long queueDepth = store.queueDepth();
        long deadLetters = store.deadLetterCount();
        Health.Builder builder = deadLetters > 0 ? Health.outOfService() : Health.up();
        return builder
                .withDetail("mode", properties.mode().name())
                .withDetail("queueDepth", queueDepth)
                .withDetail("queueCapacity", properties.queueCapacity())
                .withDetail("deadLetterCount", deadLetters)
                .withDetail("batchSize", properties.batchSize())
                .withDetail("maxAttempts", properties.maxAttempts())
                .build();
    }
}
