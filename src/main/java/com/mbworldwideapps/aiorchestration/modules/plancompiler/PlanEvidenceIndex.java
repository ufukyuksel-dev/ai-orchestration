package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PlanEvidenceIndex(
        Map<String, Set<String>> repositoryRoles,
        List<LayerEdgeEvidence> layerEdges,
        boolean layerEvidenceComplete,
        Map<String, String> tagEvidenceMismatches) {

    public PlanEvidenceIndex {
        if (repositoryRoles == null || layerEdges == null || tagEvidenceMismatches == null) {
            throw new IllegalArgumentException("plan evidence index collections are required");
        }
        Map<String, Set<String>> roleCopy = new LinkedHashMap<>();
        repositoryRoles.forEach((targetId, roles) -> {
            if (targetId == null || targetId.isBlank() || roles == null) {
                throw new IllegalArgumentException("repository role evidence is incomplete");
            }
            roleCopy.put(targetId, roles.stream().sorted().collect(java.util.stream.Collectors.toUnmodifiableSet()));
        });
        repositoryRoles = Map.copyOf(roleCopy);
        layerEdges = List.copyOf(layerEdges);
        tagEvidenceMismatches = Map.copyOf(tagEvidenceMismatches);
    }

    public static PlanEvidenceIndex empty() {
        return new PlanEvidenceIndex(Map.of(), List.of(), false, Map.of());
    }
}
