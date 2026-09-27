package com.mbworldwideapps.aiorchestration.modules.memoryai;

public class MemoryPolicyViolationException extends RuntimeException {

    private final String reason;

    public MemoryPolicyViolationException(String reason) {
        super("Memory policy rejected the request: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
