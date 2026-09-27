package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class SemanticJudge implements JudgeRubric {

    private final JudgeProviderPolicy providerPolicy;

    public SemanticJudge(JudgeProviderPolicy providerPolicy) {
        this.providerPolicy = providerPolicy;
    }

    @Override
    public String id() {
        return "semantic";
    }

    @Override
    public JudgeResult judge(JudgeRequest request) {
        JudgeProviderDecision decision = providerPolicy.select(request.requestedProvider());
        List<String> reasons = new ArrayList<>();
        if (request.expectedConcepts().isEmpty()) {
            reasons.add("no-expected-concepts");
            return new JudgeResult(id(), false, 0, reasons, decision.provider(), decision.externalProviderUsed());
        }
        long matched = request.expectedConcepts().stream()
                .filter(concept -> JudgeText.contains(request.answer(), concept))
                .count();
        double overlap = matched / (double) request.expectedConcepts().size();
        int score = Math.min(5, (int) Math.round(overlap * 5.0));
        if (score < providerPolicy.properties().semanticPassThreshold()) {
            reasons.add("semantic-overlap-below-threshold");
        }
        return new JudgeResult(id(), reasons.isEmpty(), score, reasons,
                decision.provider(), decision.externalProviderUsed());
    }
}
