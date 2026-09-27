package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public interface MemoryCodeLinkProjectionScheduler {

    void linkMemory(UUID memoryId);

    void linkAll(String projectKey);

    MemoryCodeLinkProjectionStatus status();

    static MemoryCodeLinkProjectionScheduler noop() {
        return new MemoryCodeLinkProjectionScheduler() {
            @Override
            public void linkMemory(UUID memoryId) {
            }

            @Override
            public void linkAll(String projectKey) {
            }

            @Override
            public MemoryCodeLinkProjectionStatus status() {
                return MemoryCodeLinkProjectionStatus.disabled();
            }
        };
    }
}
