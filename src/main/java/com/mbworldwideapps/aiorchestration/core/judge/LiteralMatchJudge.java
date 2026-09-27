package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class LiteralMatchJudge implements JudgeRubric {

    @Override
    public String id() {
        return "literal-match";
    }

    @Override
    public JudgeResult judge(JudgeRequest request) {
        List<String> reasons = new ArrayList<>();
        for (String literal : request.requiredLiterals()) {
            if (!JudgeText.contains(request.answer(), literal)) {
                reasons.add("missing-literal:" + safeLabel(literal));
            }
        }
        for (String forbidden : request.forbiddenTerms()) {
            if (JudgeText.contains(request.answer(), forbidden)) {
                reasons.add("forbidden-term:" + safeLabel(forbidden));
            }
        }
        for (String chunkId : request.sourceChunkIds()) {
            if (!JudgeText.contains(request.answer(), "[" + chunkId + "]")) {
                reasons.add("missing-citation:" + safeLabel(chunkId));
            }
        }
        boolean passed = reasons.isEmpty();
        return new JudgeResult(id(), passed, passed ? 5 : 0, reasons, "literal", false);
    }

    private static String safeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 48 ? value : value.substring(0, 48);
    }
}
