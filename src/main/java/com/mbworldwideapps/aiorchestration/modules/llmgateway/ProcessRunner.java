package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.List;

public interface ProcessRunner {

    Result run(List<String> command, String stdinInput, long timeoutMs);

    record Result(int exitCode, String stdout, String stderr, long latencyMs, boolean timedOut) {
    }
}
