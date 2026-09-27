package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Optional;

public interface ScannerFileStateRepository {

    Optional<ScannerFileState> find(String filePath);

    default Optional<ScannerFileState> find(String projectKey, String filePath) {
        return find(filePath);
    }

    void upsert(ScannerFileState state);
}
