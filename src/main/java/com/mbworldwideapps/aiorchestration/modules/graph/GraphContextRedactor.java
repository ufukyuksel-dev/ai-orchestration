package com.mbworldwideapps.aiorchestration.modules.graph;

import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.springframework.stereotype.Component;

@Component
class GraphContextRedactor {

    private final ScannerPayloadRedactor scannerPayloadRedactor;

    GraphContextRedactor(ScannerPayloadRedactor scannerPayloadRedactor) {
        this.scannerPayloadRedactor = scannerPayloadRedactor;
    }

    String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return scannerPayloadRedactor.redact(text);
    }
}
