package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public class ContextBudgetExceededException extends RuntimeException {

    private final long actualTokens;
    private final long capTokens;

    public ContextBudgetExceededException(String message, long actualTokens, long capTokens) {
        super(message);
        this.actualTokens = actualTokens;
        this.capTokens = capTokens;
    }

    public long actualTokens() {
        return actualTokens;
    }

    public long capTokens() {
        return capTokens;
    }
}
