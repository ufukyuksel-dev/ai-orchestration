package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public record GenerationResponse(
        String answer,
        String provider,
        long latencyMs,
        boolean degraded,
        DegradedReason degradedReason,
        int inputTokensEstimate,
        int outputTokensEstimate,
        int injectedMemoryCount,
        int injectedKnowledgeChunkCount,
        long injectedTokenEstimate) {

    public GenerationResponse {
        if (degradedReason == null) {
            degradedReason = DegradedReason.NONE;
        }
        if (!degraded) {
            degradedReason = DegradedReason.NONE;
        }
        if (injectedMemoryCount < 0) {
            injectedMemoryCount = 0;
        }
        if (injectedKnowledgeChunkCount < 0) {
            injectedKnowledgeChunkCount = 0;
        }
        if (injectedTokenEstimate < 0) {
            injectedTokenEstimate = 0L;
        }
    }
}
