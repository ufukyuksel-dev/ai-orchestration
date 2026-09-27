package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.List;

public record JudgeResult(
        String judgeId,
        boolean passed,
        int score,
        List<String> reasons,
        String provider,
        boolean externalProviderUsed) {

    public JudgeResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (score < 0) {
            score = 0;
        }
        if (score > 5) {
            score = 5;
        }
    }
}
