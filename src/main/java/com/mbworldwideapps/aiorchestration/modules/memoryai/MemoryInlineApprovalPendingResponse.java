package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

public record MemoryInlineApprovalPendingResponse(
        List<MemoryInlineApprovalCard> items,
        int count,
        int limit,
        int offset) {
}
