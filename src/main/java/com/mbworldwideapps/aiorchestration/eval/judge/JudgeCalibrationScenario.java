package com.mbworldwideapps.aiorchestration.eval.judge;

import java.util.List;

public record JudgeCalibrationScenario(
        String id,
        String category,
        String answer,
        List<String> expectedConcepts,
        List<String> requiredLiterals,
        List<String> forbiddenTerms,
        List<String> sourceChunkIds,
        boolean expectedPass,
        String reason) {
}
