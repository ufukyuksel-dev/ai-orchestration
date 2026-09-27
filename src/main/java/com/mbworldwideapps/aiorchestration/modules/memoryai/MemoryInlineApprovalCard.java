package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MemoryInlineApprovalCard(
        UUID memoryId,
        String projectKey,
        String scope,
        String scopeDecisionMode,
        String scopeDecisionReason,
        String summary,
        String type,
        String proposedContent,
        List<String> tags,
        double confidence,
        String reason,
        String sourceRef,
        String sourceType,
        Instant createdAt,
        Map<String, Object> metadata) {

    static MemoryInlineApprovalCard from(MemoryItem item, String reason) {
        Map<String, Object> metadata = item.metadata() == null ? Map.of() : item.metadata();
        return new MemoryInlineApprovalCard(
                item.id(),
                item.projectKey(),
                item.scope().value(),
                metadataString(metadata, "scopeDecisionMode"),
                metadataString(metadata, "scopeDecisionReason"),
                item.summary(),
                item.memoryType().value(),
                item.text(),
                item.tags(),
                item.confidence(),
                reason,
                item.sourceRef(),
                item.sourceType().value(),
                item.createdAt(),
                item.metadata());
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }
}
