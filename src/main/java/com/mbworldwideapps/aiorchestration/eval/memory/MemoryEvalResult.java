package com.mbworldwideapps.aiorchestration.eval.memory;

import java.util.List;

public record MemoryEvalResult(
        String scenarioId,
        String category,
        MemoryEvalVariant variant,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        double estimatedCost,
        double qualityScore,
        int iterations,
        boolean ruleCompliance,
        boolean passed,
        long latencyMs,
        int injectedTokenEstimate,
        List<String> injectedMemoryKeys,
        int staleFlaggedCount,
        boolean pollutionCaught,
        boolean piiFalseNegative,
        String reason) {
}
