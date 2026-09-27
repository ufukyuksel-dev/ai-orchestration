package com.mbworldwideapps.aiorchestration.cli;

import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanProgressReporter;

/**
 * Runs a scan on a single worker thread while the calling thread polls the run's live metadata and prints
 * one progress line per interval to stderr, so a minutes-long semantic scan is no longer silent on the CLI.
 * The final result JSON stays on stdout and is printed by the caller ONLY on the successful return of
 * {@link #run}; on worker failure the {@link ExecutionException} is unwrapped and its cause rethrown, so the
 * caller never reaches the JSON print and the CLI exits non-zero with a clean error (no partial stdout).
 *
 * <p>Extracted from {@link Phase0CliRunner} so the worker/poll/unwrap contract is unit-testable without
 * Spring: the interval guard is enforced in the constructor and the sleep is injectable, so the threaded
 * paths can be driven deterministically with latches instead of wall-clock waits.
 */
final class CliScanProgressRunner {

    /** Seam for the inter-poll wait so tests can coordinate deterministically instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final boolean progressEnabled;
    private final long intervalMillis;
    private final PrintStream progressOut;
    private final Sleeper sleeper;

    CliScanProgressRunner(boolean progressEnabled, long intervalMillis, PrintStream progressOut) {
        this(progressEnabled, intervalMillis, progressOut, Thread::sleep);
    }

    CliScanProgressRunner(boolean progressEnabled, long intervalMillis, PrintStream progressOut, Sleeper sleeper) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("progress interval must be > 0 ms, was " + intervalMillis);
        }
        this.progressEnabled = progressEnabled;
        this.intervalMillis = intervalMillis;
        this.progressOut = progressOut;
        this.sleeper = sleeper;
    }

    /**
     * Submits {@code scanWork} to a daemon worker, prints progress until it finishes (when enabled), then
     * returns its result. {@code poller} is polled each tick for the live run row; an empty result (the run
     * row not written yet, or an older run) renders a {@code starting} line rather than an error. On worker
     * failure the cause is rethrown unwrapped. The worker executor is always shut down.
     */
    <T> T run(Callable<T> scanWork, Supplier<Optional<CodeScanRunRecord>> poller) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cli-scan-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<T> future = worker.submit(scanWork);
            if (progressEnabled) {
                while (!future.isDone()) {
                    progressOut.println(ScanProgressReporter.line(poller.get().orElse(null)));
                    try {
                        sleeper.sleep(intervalMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            try {
                return future.get();
            } catch (ExecutionException executionFailure) {
                Throwable cause = executionFailure.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw executionFailure;
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
