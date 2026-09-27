package com.mbworldwideapps.aiorchestration.core.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class JudgeRubricTest {

    @Test
    void literalJudgeRequiresExactTokensAndCitations() {
        LiteralMatchJudge judge = new LiteralMatchJudge();

        JudgeResult result = judge.judge(new JudgeRequest(
                "HG-01",
                "Credit risk threshold 720 olmalidir [chunk-1]",
                List.of(),
                List.of("720"),
                List.of("850"),
                List.of("chunk-1"),
                null));

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(5);
    }

    @Test
    void literalJudgeFailsForbiddenTerms() {
        LiteralMatchJudge judge = new LiteralMatchJudge();

        JudgeResult result = judge.judge(new JudgeRequest(
                "HG-01",
                "Credit risk threshold 850 olabilir [chunk-1]",
                List.of(),
                List.of("720"),
                List.of("850"),
                List.of("chunk-1"),
                null));

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("missing-literal:720", "forbidden-term:850");
    }

    @Test
    void hybridJudgeCombinesLiteralAndSemanticChecks() {
        JudgeProviderPolicy policy = new JudgeProviderPolicy(new JudgeProperties(
                "claude-stub", false, List.of(), 4, 0.6));
        HybridJudge judge = new HybridJudge(new LiteralMatchJudge(), new SemanticJudge(policy));

        JudgeResult result = judge.judge(new JudgeRequest(
                "MA-01",
                "Servis isimleri acme-svc-domain-service formunu kullanir ve MoneyTL zorunludur [chunk-1]",
                List.of("servis isimleri", "MoneyTL", "acme-svc"),
                List.of("MoneyTL", "acme-svc"),
                List.of("Float"),
                List.of("chunk-1"),
                null));

        assertThat(result.passed()).isTrue();
        assertThat(result.judgeId()).isEqualTo("hybrid");
        assertThat(result.externalProviderUsed()).isFalse();
    }

    @Test
    void providerPolicyRejectsExternalProviderByDefault() {
        JudgeProviderPolicy policy = new JudgeProviderPolicy(new JudgeProperties(
                "claude-stub", false, List.of("external-judge"), 4, 0.6));

        assertThatThrownBy(() -> policy.select("external-judge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void providerPolicyAllowsExplicitExternalOptIn() {
        JudgeProviderPolicy policy = new JudgeProviderPolicy(new JudgeProperties(
                "claude-stub", true, List.of("external-judge"), 4, 0.6));

        JudgeProviderDecision decision = policy.select("external-judge");

        assertThat(decision.provider()).isEqualTo("external-judge");
        assertThat(decision.externalProviderUsed()).isTrue();
    }
}
