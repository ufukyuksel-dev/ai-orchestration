package com.mbworldwideapps.aiorchestration.eval.judge;

public record JudgeCalibrationResult(
        String scenarioId,
        String category,
        String judgeId,
        boolean expectedPass,
        boolean actualPass,
        boolean agreed,
        int score,
        String reason) {
}
