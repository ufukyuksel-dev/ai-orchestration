package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Map;

public record MemoryInlineApprovalRenderResponse(
        String status,
        boolean display,
        String markdown,
        MemoryInlineApprovalCard activeItem,
        int totalPending,
        Map<String, Object> metadata) {

    public static MemoryInlineApprovalRenderResponse none() {
        return new MemoryInlineApprovalRenderResponse("none", false, "", null, 0, Map.of());
    }

    public static MemoryInlineApprovalRenderResponse degraded(String reason) {
        return new MemoryInlineApprovalRenderResponse("degraded", false, "", null, 0,
                Map.of("reason", reason == null ? "unknown" : reason));
    }
}
