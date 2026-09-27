package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Map;
import java.util.Set;

/**
 * Detector input for one resolved rule. {@code changedTargets} is the subset
 * applicable to that rule, not every target in the patch. Diff-based detectors
 * use explicit {@link BindingKind#FILE} references to delimit inspected hunks;
 * callers that cannot resolve an applicable file receive an inconclusive result.
 */
public record DetectorInput(
        String diffText,
        Set<TargetRef> changedTargets,
        Map<String, Object> config) {

    public DetectorInput {
        changedTargets = changedTargets == null ? null : Set.copyOf(changedTargets);
        config = RuleImmutableValues.immutableMap(config);
    }
}
