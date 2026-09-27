package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Map;
import java.util.UUID;

public record MemoryCodeLinkRecord(
        String edgeId,
        UUID memoryId,
        String projectKey,
        MemoryCodeRelationshipType relationshipType,
        MemoryCodeTargetKind targetKind,
        String directoryPath,
        String capsuleLogicalKey,
        UUID fileId,
        UUID symbolId,
        String resolution,
        double confidence,
        String provider,
        Map<String, Object> evidence,
        String outputHash,
        boolean stale,
        double weight) {

    public MemoryCodeLinkRecord {
        if (edgeId == null || edgeId.isBlank()) {
            throw new IllegalArgumentException("edgeId is required");
        }
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId is required");
        }
        if (relationshipType == null) {
            throw new IllegalArgumentException("relationshipType is required");
        }
        if (targetKind == null) {
            throw new IllegalArgumentException("targetKind is required");
        }
        if (targetKind == MemoryCodeTargetKind.DIRECTORY) {
            directoryPath = new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator(
                    com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind.DIRECTORY,
                    directoryPath, null, null).ref();
        } else if (directoryPath != null) {
            throw new IllegalArgumentException("directoryPath requires DIRECTORY target");
        }
        projectKey = projectKey == null ? "" : projectKey.trim();
        resolution = resolution == null ? "" : resolution.trim();
        provider = provider == null ? "" : provider.trim();
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        if (confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
        if (weight < 0.0) {
            weight = 0.0;
        }
    }
}
