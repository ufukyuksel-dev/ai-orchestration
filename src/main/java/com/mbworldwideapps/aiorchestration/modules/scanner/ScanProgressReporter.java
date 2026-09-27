package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;

/**
 * Formats a single human-readable progress line from a scan run's live metadata, for CLI stderr output
 * during a long scan. Pure function of the polled {@link CodeScanRunRecord} - no I/O, no scanner logic;
 * it only consumes the heartbeat/counter metadata that the scanner already emits.
 */
public final class ScanProgressReporter {

    private ScanProgressReporter() {
    }

    /**
     * One progress line. A null run (poll before {@code startRun} wrote anything) renders a "starting"
     * placeholder rather than an error. {@code filesDiscovered} prefers the live metadata value and falls
     * back to the record column (older runs / pre-heartbeat window).
     */
    public static String line(CodeScanRunRecord run) {
        if (run == null) {
            return "scan starting...";
        }
        Map<String, Object> metadata = run.metadata();
        String phase = string(metadata.get("phase"), "starting");
        int filesScanned = run.filesScanned();
        int filesDiscovered = integer(metadata.get("filesDiscovered"), run.filesDiscovered());
        String currentFile = string(metadata.get("currentFile"), "-");
        String currentSymbol = string(metadata.get("currentSymbolFqn"), "-");
        int llm = integer(metadata.get("llmSummariesAttempted"), 0);
        int written = integer(metadata.get("semanticCapsulesWritten"), 0);
        int extractive = integer(metadata.get("extractiveDefaultCapsules"), 0);
        int errors = integer(metadata.get("semanticErrors"), 0);
        String lastProgressAt = string(metadata.get("lastProgressAt"), "-");
        return String.format(
                "scan %s | files %d/%d | %s | %s | llm=%d written=%d extractive=%d errors=%d | %s",
                phase, filesScanned, filesDiscovered, currentFile, currentSymbol, llm, written, extractive,
                errors, lastProgressAt);
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
