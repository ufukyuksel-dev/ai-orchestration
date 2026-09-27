package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

/** Test-only bridge for integration tests that exercise the package-owned mutation boundary. */
public final class RulePromotionServiceTestAccess {

    private RulePromotionServiceTestAccess() {
    }

    public static RuleVersion promote(RulePromotionService service, PromotionRequest request) {
        return service.promote(request);
    }

    public static RuleVersion promoteVersion(RulePromotionService service, UUID ruleId,
            int expectedCurrentVersion, String expectedCurrentContentHash, PromotionRequest request) {
        return service.promoteVersion(ruleId, expectedCurrentVersion, expectedCurrentContentHash, request);
    }

    public static RuleDefinition deprecate(RulePromotionService service, RuleDeprecationRequest request) {
        return service.deprecate(request);
    }
}
