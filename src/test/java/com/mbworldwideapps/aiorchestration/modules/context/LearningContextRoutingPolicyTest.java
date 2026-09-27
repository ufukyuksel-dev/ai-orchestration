package com.mbworldwideapps.aiorchestration.modules.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LearningContextRoutingPolicyTest {

    private final LearningContextRoutingPolicy policy = new LearningContextRoutingPolicy();

    @Test
    void routesCurrentCodeQuestionToScannerOnly() {
        LearningContextRoutingDecision decision = decide("ScannerService hangi dosyada?", "test", null, null,
                true, true);

        assertThat(decision.requestedMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.reason()).isEqualTo("current_code_intent");
    }

    @Test
    void routesLearnedDecisionQuestionToMemoryOnly() {
        LearningContextRoutingDecision decision = decide("Bu kararı neden aldık?", "test", null, null,
                true, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.MEMORY_ONLY);
        assertThat(decision.reason()).isEqualTo("learned_decision_intent");
    }

    @Test
    void routesMixedCodeAndDecisionQuestionToCombined() {
        LearningContextRoutingDecision decision = decide("Bu service implementation kararı neden böyle?", "test",
                null, null, true, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.reason()).isEqualTo("code_and_learned_decision_intent");
    }

    @Test
    void abstainsForNeutralQuestionWithoutProjectContextSignal() {
        LearningContextRoutingDecision decision = decide("Merhaba, nasılsın?", "orchestrator", null, null,
                true, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.NONE);
        assertThat(decision.reason()).isEqualTo("no_project_context_intent");
    }

    @Test
    void recordsMemoryAbstentionWhenInjectionGateIsDisabled() {
        LearningContextRoutingDecision decision = decide("why was this service decision made?", "test", null,
                "COMBINED", true, false);

        assertThat(decision.requestedMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.memoryAbstentionReason()).isEqualTo("memory_injection_disabled");
    }

    @Test
    void requestCanExplicitlyDisableMemoryInCombinedMode() {
        LearningContextRoutingDecision decision = decide("why was this service decision made?", "test", false,
                "COMBINED", true, true);

        assertThat(decision.requestedMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.memoryAbstentionReason()).isEqualTo("memory_disabled_by_request");
    }

    @Test
    void changeAndDebugTasksEvaluateLearnedNavigationWithoutHistoryWords() {
        LearningContextRoutingDecision change = decide("fallback kosulunu degistir", "change", null, null,
                true, true);
        LearningContextRoutingDecision debug = decide("payment bug debug", "debug", null, null,
                true, true);

        assertThat(change.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(debug.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(change.reason()).isEqualTo("learned_navigation_intent");
    }

    @Test
    void legacySoftwareRoleKeepsNavigationOnlyCodeRequestScannerOnly() {
        LearningContextRoutingDecision decision = decide("fix the bug in PaymentService.java", "software",
                null, null, true, true);

        assertThat(decision.requestedMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(decision.reason()).isEqualTo("current_code_intent");
    }

    @Test
    void explicitResolveCanUseMemoryWhenAutomaticInjectionIsDisabled() {
        LearningContextRequest request = new LearningContextRequest("payment bug debug", "PROJECT", null,
                "debug", 5, 2, 1200, null, false, null, false, false, null);

        LearningContextRoutingDecision decision = policy.decide(request, true, false, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.memoryAbstentionReason()).isBlank();
    }

    @Test
    void explicitCodeResolveUsesMemoryWithoutExactNavigationVerb() {
        LearningContextRequest request = new LearningContextRequest(
                "Locate the fallback-selection code path and focused regression test to add a metric",
                "PROJECT", null, "locate", 5, 2, 1200, null, false, null, false, false, null);

        LearningContextRoutingDecision decision = policy.decide(request, true, false, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.reason()).isEqualTo("explicit_resolve");
        assertThat(decision.memoryAbstentionReason()).isBlank();
    }

    @Test
    void explicitResolveUsesMemoryWithoutKeywordHeuristics() {
        LearningContextRequest request = new LearningContextRequest("Add a metric when fallback selection occurs",
                "PROJECT", null,
                "locate", 5, 2, 1200, null, false, null, false, false, null);

        LearningContextRoutingDecision decision = policy.decide(request, true, false, true);

        assertThat(decision.effectiveMode()).isEqualTo(LearningContextMode.COMBINED);
        assertThat(decision.reason()).isEqualTo("explicit_resolve");
    }

    @Test
    void automaticMechanicalRequestStillAbstains() {
        LearningContextRequest request = new LearningContextRequest("translate supplied sentence", "PROJECT", null,
                "locate", 5, 2, 1200, null, false, null, false, false, null);

        assertThat(policy.decide(request, true, true, false).effectiveMode())
                .isEqualTo(LearningContextMode.NONE);
    }

    @Test
    void explicitResolveStillHonorsMemoryOptOutAndUnavailableConfiguration() {
        LearningContextRequest optedOut = new LearningContextRequest("Add a metric", "PROJECT", null,
                "locate", 5, 2, 1200, false, false, null, false, false, null);
        LearningContextRequest defaultRequest = new LearningContextRequest("Add a metric", "PROJECT", null,
                "locate", 5, 2, 1200, null, false, null, false, false, null);

        assertThat(policy.decide(optedOut, true, false, true).effectiveMode())
                .isEqualTo(LearningContextMode.SCANNER_ONLY);
        assertThat(policy.decide(defaultRequest, false, false, true).effectiveMode())
                .isEqualTo(LearningContextMode.SCANNER_ONLY);
    }

    private LearningContextRoutingDecision decide(String query, String role, Boolean includeMemory,
            String explicitMode, boolean configured, boolean injectionEnabled) {
        LearningContextRequest request = new LearningContextRequest(query, "PROJECT", null, role,
                5, 2, 1200, includeMemory, false, null, false, false, explicitMode);
        return policy.decide(request, configured, injectionEnabled);
    }
}
