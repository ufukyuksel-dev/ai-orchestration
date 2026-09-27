package com.mbworldwideapps.aiorchestration.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import org.junit.jupiter.api.Test;

class DefaultPolicyEngineTest {

    private final DefaultPolicyEngine policyEngine = new DefaultPolicyEngine(
            new PolicyProperties(true, true, "admin-token", List.of("ACME-LOAN-001", "internal-prod-svc")));

    @Test
    void manualMemoryCanWriteCuratedScopes() {
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.MANUAL, MemoryScope.GLOBAL).allowed()).isTrue();
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.MANUAL, MemoryScope.PROJECT).allowed()).isTrue();
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.MANUAL, MemoryScope.USER).allowed()).isTrue();
    }

    @Test
    void correctionSignalsCannotWriteGlobalMemory() {
        PolicyDecision decision = policyEngine.evaluateMemoryWrite(
                MemorySourceType.CORRECTION_SIGNAL, MemoryScope.GLOBAL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_WRITE_SOURCE_SCOPE_DENIED);
    }

    @Test
    void correctionSignalsCanWriteReviewControlledProjectMemory() {
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.CORRECTION_SIGNAL, MemoryScope.PROJECT).allowed())
                .isTrue();
    }

    @Test
    void scannedMemoryCanWriteProjectButNotGlobalMemory() {
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.SCANNED, MemoryScope.PROJECT).allowed())
                .isTrue();

        PolicyDecision decision = policyEngine.evaluateMemoryWrite(MemorySourceType.SCANNED, MemoryScope.GLOBAL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_WRITE_SOURCE_SCOPE_DENIED);
    }

    @Test
    void mcpExternalCanWriteGlobalAndProjectMemoryOnly() {
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.MCP_EXTERNAL, MemoryScope.GLOBAL).allowed())
                .isTrue();
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.MCP_EXTERNAL, MemoryScope.PROJECT).allowed())
                .isTrue();

        for (MemoryScope deniedScope : List.of(MemoryScope.USER, MemoryScope.EPISODIC)) {
            PolicyDecision decision = policyEngine.evaluateMemoryWrite(MemorySourceType.MCP_EXTERNAL, deniedScope);
            assertThat(decision.allowed())
                    .as("MCP_EXTERNAL must not write %s scope", deniedScope)
                    .isFalse();
            assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_WRITE_SOURCE_SCOPE_DENIED);
        }
    }

    @Test
    void autoCuratedCanWriteGlobalAndProjectMemoryOnly() {
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.AUTO_CURATED, MemoryScope.GLOBAL).allowed())
                .isTrue();
        assertThat(policyEngine.evaluateMemoryWrite(MemorySourceType.AUTO_CURATED, MemoryScope.PROJECT).allowed())
                .isTrue();

        for (MemoryScope deniedScope : List.of(MemoryScope.USER, MemoryScope.EPISODIC)) {
            PolicyDecision decision = policyEngine.evaluateMemoryWrite(MemorySourceType.AUTO_CURATED, deniedScope);
            assertThat(decision.allowed())
                    .as("AUTO_CURATED must not write %s scope", deniedScope)
                    .isFalse();
            assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_WRITE_SOURCE_SCOPE_DENIED);
        }
    }

    @Test
    void promotionHasNoProducerAndMustStayDenied() {
        // No MemoryService.create(sourceType=PROMOTION) path exists; MemoryReviewService.promote()
        // preserves the original sourceType. This test guards against a future regression where
        // someone adds a promotion-write path without updating the policy.
        for (MemoryScope scope : MemoryScope.values()) {
            PolicyDecision decision = policyEngine.evaluateMemoryWrite(MemorySourceType.PROMOTION, scope);
            assertThat(decision.allowed())
                    .as("PROMOTION must not be writable on %s scope (no producer)", scope)
                    .isFalse();
            assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_WRITE_SOURCE_SCOPE_DENIED);
        }
    }

    @Test
    void sensitiveTermsAreRejectedWithoutExposingTheTermInReason() {
        PolicyDecision decision = policyEngine.evaluateMemoryContent(
                "Bunu hatırla: ACME-LOAN-001 özel ürünü loglara basılmamalı.");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DefaultPolicyEngine.REASON_SENSITIVE_TERM);
        assertThat(decision.reason()).doesNotContain("ACME-LOAN-001");
    }
}
