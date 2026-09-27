package com.mbworldwideapps.aiorchestration.modules.plancompiler;

public record LayerEdgeEvidence(String targetId, String fromLayer, String toLayer) {
    public LayerEdgeEvidence {
        if (targetId == null || targetId.isBlank() || fromLayer == null || fromLayer.isBlank()
                || toLayer == null || toLayer.isBlank()) {
            throw new IllegalArgumentException("complete layer edge evidence is required");
        }
    }
}
