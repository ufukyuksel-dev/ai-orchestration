package com.mbworldwideapps.aiorchestration.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord;

/**
 * Contract for the CLI scan progress runner: it runs the scan on a worker thread, prints one progress line
 * per interval to the supplied stderr while the scan is in flight, returns the scan result on success, and
 * unwraps the worker failure cause (so the caller prints no result JSON and the CLI exits non-zero). All
 * coordination is via latches, not wall-clock sleeps, so the threaded cases are deterministic.
 */
class CliScanProgressRunnerTest {

    @Test
    void nonPositiveIntervalFailsFast() {
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new CliScanProgressRunner(true, 0L, err))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("> 0");
        assertThatThrownBy(() -> new CliScanProgressRunner(true, -5L, err))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void progressDisabledPrintsNothingAndReturnsResult() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        CliScanProgressRunner runner = new CliScanProgressRunner(false, 1000L, err);

        String result = runner.run(() -> "SCAN_JSON", () -> Optional.of(record(Map.of(), 1, 1)));

        assertThat(result).isEqualTo("SCAN_JSON");
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void workerFailureUnwrapsCauseAndYieldsNoResult() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        CliScanProgressRunner runner = new CliScanProgressRunner(false, 1000L, err);

        // ExecutionException cause is rethrown verbatim, not wrapped -> caller never reaches the JSON print.
        assertThatThrownBy(() -> runner.run(
                () -> { throw new IllegalStateException("scan boom"); },
                Optional::empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scan boom");
    }

    @Test
    void printsAtLeastOneProgressLineToStderrThenReturnsResult() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        CountDownLatch pollObserved = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);

        // Deterministic sleeper: mark that the loop finished one poll+print, then block until released.
        CliScanProgressRunner.Sleeper sleeper = millis -> {
            pollObserved.countDown();
            releaseScan.await();
        };
        CliScanProgressRunner runner = new CliScanProgressRunner(true, 50L, err, sleeper);

        Callable<String> scanWork = () -> {
            releaseScan.await();
            return "SCAN_JSON";
        };
        Supplier<Optional<CodeScanRunRecord>> poller =
                () -> Optional.of(record(Map.of("phase", "semantic-symbols"), 2, 10));

        ExecutorService driver = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = driver.submit(() -> runner.run(scanWork, poller));
            assertThat(pollObserved.await(5, TimeUnit.SECONDS)).isTrue();
            releaseScan.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("SCAN_JSON");
        } finally {
            driver.shutdownNow();
        }
        assertThat(errBuf.toString(StandardCharsets.UTF_8))
                .contains("scan semantic-symbols")
                .contains("2/10");
    }

    @Test
    void emptyPollBeforeStartRendersStartingLineNotError() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        CountDownLatch pollObserved = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        CliScanProgressRunner.Sleeper sleeper = millis -> {
            pollObserved.countDown();
            releaseScan.await();
        };
        CliScanProgressRunner runner = new CliScanProgressRunner(true, 50L, err, sleeper);

        Callable<String> scanWork = () -> {
            releaseScan.await();
            return "OK";
        };

        ExecutorService driver = Executors.newSingleThreadExecutor();
        try {
            // Poller returns empty: the run row is not written yet (poll-before-startRun race).
            Future<String> result = driver.submit(() -> runner.run(scanWork, Optional::empty));
            assertThat(pollObserved.await(5, TimeUnit.SECONDS)).isTrue();
            releaseScan.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        } finally {
            driver.shutdownNow();
        }
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("scan starting");
    }

    private static CodeScanRunRecord record(Map<String, Object> metadata, int filesScanned, int filesDiscovered) {
        return new CodeScanRunRecord(UUID.randomUUID(), "P", "/r", "running", Instant.now(), null, "codex",
                "gpt-5", false, filesDiscovered, filesScanned, 0, 0, 0, metadata);
    }
}
