package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.List;

public record JudgeRequest(
        String scenarioId,
        String answer,
        List<String> expectedConcepts,
        List<String> requiredLiterals,
        List<String> forbiddenTerms,
        List<String> sourceChunkIds,
        String requestedProvider) {

    public JudgeRequest {
        expectedConcepts = expectedConcepts == null ? List.of() : List.copyOf(expectedConcepts);
        requiredLiterals = requiredLiterals == null ? List.of() : List.copyOf(requiredLiterals);
        forbiddenTerms = forbiddenTerms == null ? List.of() : List.copyOf(forbiddenTerms);
        sourceChunkIds = sourceChunkIds == null ? List.of() : List.copyOf(sourceChunkIds);
    }
}
