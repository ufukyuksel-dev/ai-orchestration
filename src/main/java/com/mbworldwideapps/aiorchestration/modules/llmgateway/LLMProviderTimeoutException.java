package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public class LLMProviderTimeoutException extends RuntimeException {

    private final String providerId;
    private final long timeoutMs;

    public LLMProviderTimeoutException(String providerId, long timeoutMs, Throwable cause) {
        super("LLM provider timed out: " + providerId + " after " + timeoutMs + " ms", cause);
        this.providerId = providerId;
        this.timeoutMs = timeoutMs;
    }

    public String providerId() {
        return providerId;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
