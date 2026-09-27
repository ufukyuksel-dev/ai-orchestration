package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PlanCheckContext(
        AnalystPlanSchemaV2 plan,
        Set<String> applicableTargetIds,
        PlanEvidenceIndex evidence) {

    public PlanCheckContext {
        if (plan == null || applicableTargetIds == null || applicableTargetIds.isEmpty() || evidence == null) {
            throw new IllegalArgumentException("plan check context is incomplete");
        }
        applicableTargetIds = Set.copyOf(applicableTargetIds);
        Set<String> actualTargets = plan.targets().stream()
                .map(AnalystPlanSchemaV2.Target::targetId)
                .collect(java.util.stream.Collectors.toSet());
        if (!actualTargets.containsAll(applicableTargetIds)) {
            throw new IllegalArgumentException("plan check context references an unknown target");
        }
    }

    public List<AnalystPlanSchemaV2.Target> applicableTargets() {
        return plan.targets().stream()
                .filter(target -> applicableTargetIds.contains(target.targetId()))
                .toList();
    }

    public Map<String, AnalystPlanSchemaV2.Intent> intentsById() {
        Map<String, AnalystPlanSchemaV2.Intent> intents = new LinkedHashMap<>();
        plan.intents().forEach(intent -> intents.put(intent.intentId(), intent));
        return Map.copyOf(intents);
    }
}
