package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public record PlanRuleCheckResult(
        PlanRuleCheckStatus status,
        String code,
        String message,
        List<String> targetIds,
        Set<EvidenceTrust> evidenceTrust) {

    private static final Set<EvidenceTrust> FINAL_ONLY = EnumSet.of(
            EvidenceTrust.ACTUAL_DIFF, EvidenceTrust.AST_VERIFIED, EvidenceTrust.TEST_VERIFIED);

    public PlanRuleCheckResult {
        if (status == null || code == null || code.isBlank() || message == null || message.isBlank()
                || targetIds == null || evidenceTrust == null) {
            throw new IllegalArgumentException("complete plan rule check result is required");
        }
        code = canonical(code);
        message = canonical(message);
        targetIds = targetIds.stream().map(PlanRuleCheckResult::canonical).distinct().sorted().toList();
        evidenceTrust = Set.copyOf(evidenceTrust);
        if (evidenceTrust.stream().anyMatch(FINAL_ONLY::contains)) {
            throw new IllegalArgumentException("plan checker cannot claim final-code evidence");
        }
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
