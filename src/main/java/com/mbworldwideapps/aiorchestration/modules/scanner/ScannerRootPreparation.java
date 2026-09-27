package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

public record ScannerRootPreparation(
        String projectKey,
        String rootPath,
        int foreignFilesDeleted,
        int foreignFileStatesDeleted,
        int orphanedSemanticAnchorsStaled,
        List<UUID> removedCapsuleIds) {

    public ScannerRootPreparation {
        removedCapsuleIds = removedCapsuleIds == null ? List.of() : List.copyOf(removedCapsuleIds);
    }

    public static ScannerRootPreparation clean(String projectKey, String rootPath) {
        return new ScannerRootPreparation(projectKey, rootPath, 0, 0, 0, List.of());
    }

    public boolean changed() {
        return foreignFilesDeleted > 0 || foreignFileStatesDeleted > 0
                || orphanedSemanticAnchorsStaled > 0 || !removedCapsuleIds.isEmpty();
    }
}
