package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class HybridJudge implements JudgeRubric {

    private final LiteralMatchJudge literalMatchJudge;
    private final SemanticJudge semanticJudge;

    public HybridJudge(LiteralMatchJudge literalMatchJudge, SemanticJudge semanticJudge) {
        this.literalMatchJudge = literalMatchJudge;
        this.semanticJudge = semanticJudge;
    }

    @Override
    public String id() {
        return "hybrid";
    }

    @Override
    public JudgeResult judge(JudgeRequest request) {
        JudgeResult literal = literalMatchJudge.judge(request);
        if (!literal.passed()) {
            return new JudgeResult(id(), false, literal.score(), literal.reasons(),
                    literal.provider(), literal.externalProviderUsed());
        }
        JudgeResult semantic = semanticJudge.judge(request);
        List<String> reasons = new ArrayList<>(semantic.reasons());
        return new JudgeResult(id(), semantic.passed(), semantic.score(), reasons,
                semantic.provider(), semantic.externalProviderUsed());
    }
}
