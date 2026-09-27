package com.mbworldwideapps.aiorchestration.modules.scanner;

public interface ScannerProjectRootRegistry {

    /**
     * Atomically binds a project key to one normalized repository root and
     * removes baseline facts that were written by older foreign-root scans.
     */
    ScannerRootPreparation prepare(String projectKey, String rootPath);

    static ScannerProjectRootRegistry noop() {
        return ScannerRootPreparation::clean;
    }
}
