package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

@Component
public class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Result run(List<String> command, String stdinInput, long timeoutMs) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Instant started = Instant.now();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start process: " + command.getFirst(), e);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            if (stdinInput != null) {
                process.getOutputStream().write(stdinInput.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();
            boolean finished = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            long latencyMs = Duration.between(started, Instant.now()).toMillis();
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, stdoutNow(stdout), stdoutNow(stderr), latencyMs, true);
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join(), latencyMs, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Process interrupted: " + command.getFirst(), e);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("Failed to write process stdin: " + command.getFirst(), e);
        }
    }

    private static CompletableFuture<String> readAsync(java.io.InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (inputStream) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read process stream", e);
            }
        });
    }

    private static String stdoutNow(CompletableFuture<String> future) {
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : "";
    }
}
