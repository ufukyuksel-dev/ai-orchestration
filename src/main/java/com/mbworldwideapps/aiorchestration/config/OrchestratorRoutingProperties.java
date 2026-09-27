package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.orchestrator.routing")
public record OrchestratorRoutingProperties(
        Boolean hybridEnabled,
        String llmProvider,
        long llmTimeoutMs,
        int llmMaxTokens,
        double llmTemperature,
        double commitThreshold,
        double ambiguousFloor,
        int memoryTopK,
        int knowledgeTopK,
        int memoryContextTokenCap,
        int knowledgeContextTokenCap,
        int totalContextTokenCap) {

    public OrchestratorRoutingProperties {
        if (hybridEnabled == null) {
            hybridEnabled = true;
        }
        if (llmProvider == null || llmProvider.isBlank()) {
            llmProvider = "local-qwen";
        } else {
            llmProvider = llmProvider.trim();
        }
        if (llmTimeoutMs <= 0) {
            llmTimeoutMs = 2500L;
        }
        if (llmMaxTokens <= 0) {
            llmMaxTokens = 128;
        }
        if (llmTemperature < 0.0) {
            llmTemperature = 0.0;
        }
        if (commitThreshold <= 0.0 || commitThreshold > 1.0) {
            commitThreshold = 0.85;
        }
        if (ambiguousFloor <= 0.0 || ambiguousFloor >= commitThreshold) {
            ambiguousFloor = 0.5;
        }
        if (memoryTopK <= 0) {
            memoryTopK = 3;
        }
        if (knowledgeTopK <= 0) {
            knowledgeTopK = 3;
        }
        if (memoryContextTokenCap <= 0) {
            memoryContextTokenCap = 1200;
        }
        if (knowledgeContextTokenCap <= 0) {
            knowledgeContextTokenCap = 2800;
        }
        if (totalContextTokenCap <= 0) {
            totalContextTokenCap = 4000;
        }
    }
}
