package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ScanProgressReporterTest {

    @Test
    void nullRunRendersStartingPlaceholderNotError() {
        assertThat(ScanProgressReporter.line(null)).contains("starting");
    }

    @Test
    void emptyMetadataRendersStartingPhase() {
        assertThat(ScanProgressReporter.line(run(Map.of(), 0, 0))).contains("scan starting");
    }

    @Test
    void lineShowsPhaseFileRatioCurrentSymbolAndCounters() {
        String line = ScanProgressReporter.line(run(Map.of(
                "phase", "semantic-symbols",
                "currentFile", "PaymentController.java",
                "currentSymbolFqn", "com.example.PaymentController#approve",
                "llmSummariesAttempted", 3, "semanticCapsulesWritten", 2,
                "extractiveDefaultCapsules", 1, "semanticErrors", 0,
                "lastProgressAt", "2026-07-05T10:00:00Z"), 5, 20));
        assertThat(line)
                .contains("semantic-symbols")
                .contains("5/20")
                .contains("com.example.PaymentController#approve")
                .contains("llm=3").contains("written=2").contains("extractive=1").contains("errors=0");
    }

    @Test
    void filesDiscoveredPrefersMetadataThenFallsBackToRecordColumn() {
        // metadata value wins over the record column (which can be 0 at start).
        assertThat(ScanProgressReporter.line(run(Map.of("filesDiscovered", 12), 3, 0))).contains("3/12");
        // no metadata -> record column.
        assertThat(ScanProgressReporter.line(run(Map.of(), 3, 7))).contains("3/7");
    }

    @Test
    void midLlmCallShowsSymbolAndAttemptWithoutAdvancingFilesScanned() {
        // Two snapshots during one long LLM call: filesScanned stays, currentSymbol + attempts visible
        // (CLI mirror of the PR-1B pre-call heartbeat invariant).
        CodeScanRunRecord a = run(Map.of("phase", "semantic-symbols", "currentSymbolFqn", "X#m",
                "llmSummariesAttempted", 1), 4, 10);
        CodeScanRunRecord b = run(Map.of("phase", "semantic-symbols", "currentSymbolFqn", "X#m",
                "llmSummariesAttempted", 1), 4, 10);
        assertThat(ScanProgressReporter.line(a)).contains("4/10").contains("X#m").contains("llm=1");
        assertThat(ScanProgressReporter.line(b)).contains("4/10").contains("X#m").contains("llm=1");
    }

    private static CodeScanRunRecord run(Map<String, Object> metadata, int filesScanned, int filesDiscovered) {
        return new CodeScanRunRecord(UUID.randomUUID(), "P", "/r", "running", Instant.now(), null, "codex",
                "gpt-5", false, filesDiscovered, filesScanned, 0, 0, 0, metadata);
    }
}
