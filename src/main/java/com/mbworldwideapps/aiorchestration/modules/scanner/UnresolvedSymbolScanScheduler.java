package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.file.Path;
import java.util.List;

/** Queues backend-owned structural scanning for declared learning symbols. */
public interface UnresolvedSymbolScanScheduler {

    void schedule(String projectKey, Path rootPath, List<String> sourcePaths);

    static UnresolvedSymbolScanScheduler noop() {
        return (projectKey, rootPath, sourcePaths) -> { };
    }
}
