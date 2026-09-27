package com.mbworldwideapps.aiorchestration.eval.memory;

import java.util.List;

public record MemoryEvalScenario(
        String id,
        String category,
        String taskPrompt,
        String projectKey,
        List<String> requiredMemoryKeys,
        List<String> expectedTerms,
        List<String> forbiddenTerms,
        boolean staleExpected,
        String pollutionText,
        String expectedPollutionDecision) {
}
