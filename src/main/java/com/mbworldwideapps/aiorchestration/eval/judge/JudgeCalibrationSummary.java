package com.mbworldwideapps.aiorchestration.eval.judge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record JudgeCalibrationSummary(
        int scenarioCount,
        int resultCount,
        Map<String, Double> agreementByJudge,
        Map<String, ConfusionMatrix> confusionByJudge,
        boolean hybridAgreementGatePassed,
        List<JudgeCalibrationResult> results,
        Path reportPath) {

    public record ConfusionMatrix(int truePositive, int trueNegative, int falsePositive, int falseNegative) {
    }
}
