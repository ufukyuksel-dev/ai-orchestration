package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;

public record MemoryCodeLinkProjectionStatus(
        boolean enabled,
        String state,
        UUID memoryId,
        String projectKey,
        UUID projectionRunId,
        Instant startedAt,
        Instant completedAt,
        MemoryCodeLinkProjectionResult result,
        long droppedCount,
        String degradedReason) {

    public MemoryCodeLinkProjectionStatus {
        state = state == null ? "" : state;
        projectKey = projectKey == null ? "" : projectKey;
        degradedReason = degradedReason == null ? "" : degradedReason;
        if (droppedCount < 0) {
            droppedCount = 0L;
        }
    }

    public static MemoryCodeLinkProjectionStatus disabled() {
        return new MemoryCodeLinkProjectionStatus(false, "disabled", null, "", null, null, null,
                null, 0L, "disabled");
    }

    public static MemoryCodeLinkProjectionStatus queued(UUID memoryId, String projectKey, long droppedCount) {
        return new MemoryCodeLinkProjectionStatus(true, "queued", memoryId, projectKey, null, null,
                null, null, droppedCount, "");
    }

    public static MemoryCodeLinkProjectionStatus running(UUID memoryId, String projectKey, Instant startedAt,
            long droppedCount) {
        return new MemoryCodeLinkProjectionStatus(true, "running", memoryId, projectKey, null, startedAt,
                null, null, droppedCount, "");
    }

    public static MemoryCodeLinkProjectionStatus completed(MemoryCodeLinkProjectionResult result,
            Instant startedAt, Instant completedAt, long droppedCount) {
        return new MemoryCodeLinkProjectionStatus(true, "completed", result.memoryId(), result.projectKey(),
                result.projectionRunId(), startedAt, completedAt, result, droppedCount, "");
    }

    public static MemoryCodeLinkProjectionStatus failed(UUID memoryId, String projectKey, Instant startedAt,
            Instant completedAt, long droppedCount, String reason) {
        return new MemoryCodeLinkProjectionStatus(true, "failed", memoryId, projectKey, null, startedAt,
                completedAt, null, droppedCount, reason);
    }

    public static MemoryCodeLinkProjectionStatus rejected(UUID memoryId, String projectKey, long droppedCount,
            String reason) {
        return new MemoryCodeLinkProjectionStatus(true, "rejected", memoryId, projectKey, null, null,
                Instant.now(), null, droppedCount, reason);
    }
}
