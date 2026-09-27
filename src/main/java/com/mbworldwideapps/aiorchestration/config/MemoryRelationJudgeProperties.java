package com.mbworldwideapps.aiorchestration.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.memory.relation-judge")
public record MemoryRelationJudgeProperties(Boolean enabled, Integer queueCapacity, Duration timeout, Double confidenceThreshold) {
    public MemoryRelationJudgeProperties {
        enabled = enabled == null ? true : enabled;
        queueCapacity = queueCapacity == null ? 256 : queueCapacity;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        confidenceThreshold = confidenceThreshold == null ? .85 : confidenceThreshold;
        if (queueCapacity < 1 || queueCapacity > 10_000) throw new IllegalArgumentException("Judge queue capacity must be 1..10000");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Judge timeout must be 1ms..2m");
        if (!Double.isFinite(confidenceThreshold) || confidenceThreshold <= 0 || confidenceThreshold > 1)
            throw new IllegalArgumentException("Judge threshold must be in (0,1]");
    }
}
