package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultUnresolvedSymbolScanScheduler implements UnresolvedSymbolScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefaultUnresolvedSymbolScanScheduler.class);

    private final ScannerAsyncService scanner;

    public DefaultUnresolvedSymbolScanScheduler(ScannerAsyncService scanner) {
        this.scanner = scanner;
    }

    @Override
    public void schedule(String projectKey, Path rootPath, List<String> sourcePaths) {
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            return;
        }
        ScannerScanStartResponse queued = scanner.start(new ScanCodebaseRequest(
                rootPath.toString(), projectKey, false, null, null, false, null,
                null, null, 0, 0, 0, false));
        log.info("Queued structural scan for unresolved learning symbols: projectKey={} scanRunId={} paths={}",
                projectKey, queued.scanRunId(), sourcePaths);
    }
}
