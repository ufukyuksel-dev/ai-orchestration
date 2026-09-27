package com.mbworldwideapps.aiorchestration.eval.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record MemoryEvalSummary(
        int scenarioCount,
        int resultCount,
        Map<MemoryEvalVariant, Double> successRateByVariant,
        Map<MemoryEvalVariant, Double> qualityByVariant,
        double categoryBDelta,
        double pollutionCatchRate,
        int piiFalseNegativeCount,
        long p95LatencyMs,
        int maxInjectedTokens,
        boolean categoryBGatePassed,
        boolean pollutionGatePassed,
        boolean piiGatePassed,
        boolean phase1RegressionPassed,
        boolean contentLeanPassed,
        boolean memoryBudgetPassed,
        Path reportPath,
        Path csvPath,
        List<MemoryEvalResult> results) {
}
