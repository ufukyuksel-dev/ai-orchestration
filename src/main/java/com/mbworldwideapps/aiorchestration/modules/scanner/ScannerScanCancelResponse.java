package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

public record ScannerScanCancelResponse(
        UUID scanRunId,
        String status,
        boolean cancelled,
        String message) {
}
