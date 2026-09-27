package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.memory.confirm")
public record MemoryConfirmProperties(
        Double minAgentConfidence) {

    public MemoryConfirmProperties {
        if (minAgentConfidence == null) {
            minAgentConfidence = 0.75;
        }
        if (minAgentConfidence <= 0.0 || minAgentConfidence > 1.0) {
            throw new IllegalArgumentException("memory.confirm.min-agent-confidence must be > 0 and <= 1");
        }
    }
}
