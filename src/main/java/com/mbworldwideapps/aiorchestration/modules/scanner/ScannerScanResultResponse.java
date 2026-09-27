package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScannerScanResultResponse(
        UUID scanRunId,
        String status,
        boolean done,
        ScanCodebaseResponse result,
        List<CodeDiagnosticRecord> diagnostics,
        Map<String, Object> metadata) {

    public ScannerScanResultResponse {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
