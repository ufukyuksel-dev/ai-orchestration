package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * Returns the shared pre-call chars/4 heuristic. This is intentionally an
     * estimate and must not be reported as tokenizer-measured usage or a model
     * token hard cap.
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    public static long estimateInjected(GenerationRequest request) {
        long sourceTokens = request.sources().stream()
                .mapToLong(source -> estimate(source.snippet()))
                .sum();
        return estimate(request.memoryContextBlock()) + sourceTokens;
    }
}
