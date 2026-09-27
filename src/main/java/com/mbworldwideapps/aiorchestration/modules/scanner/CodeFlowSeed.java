package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeFlowSeed(
        String projectKey,
        UUID scanRunId,
        UUID symbolId,
        String capsuleKind,
        String triggerKind,
        String title,
        String entryRef,
        String filePath,
        String endpoint,
        List<String> annotations,
        List<String> injectedTypes,
        List<String> callRefs,
        double score,
        Map<String, Object> evidence) {

    public CodeFlowSeed {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        injectedTypes = injectedTypes == null ? List.of() : List.copyOf(injectedTypes);
        callRefs = callRefs == null ? List.of() : List.copyOf(callRefs);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
