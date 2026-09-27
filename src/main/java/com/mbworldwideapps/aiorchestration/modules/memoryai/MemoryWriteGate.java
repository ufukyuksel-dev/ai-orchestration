package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;

public interface MemoryWriteGate {

    GateDecision evaluate(CreateMemoryRequest request, GateContext context);

    /**
     * Fallback used when no gate bean is wired. There is no pending state: an unconfigured
     * gate stores the memory rather than parking it for an approval step that no longer exists.
     */
    static MemoryWriteGate alwaysActive() {
        return (request, context) -> new GateDecision(Verdict.AUTO_ACTIVE, "gate_not_configured",
                null, List.of(), 0.0, Map.of());
    }

    record GateContext(McpClientContext mcpClientContext, String providerOverride) {
    }

    /** A write is either stored or rejected; there is no human-approval state. */
    enum Verdict {
        AUTO_ACTIVE,
        REJECTED
    }

    record GateDecision(
            Verdict verdict,
            String gateReason,
            String suggestedQuestion,
            List<String> contextHints,
            double confidence,
            Map<String, Object> debugMetadata) {

        public GateDecision {
            verdict = verdict == null ? Verdict.AUTO_ACTIVE : verdict;
            gateReason = gateReason == null || gateReason.isBlank() ? "unspecified" : gateReason.trim();
            suggestedQuestion = suggestedQuestion == null || suggestedQuestion.isBlank()
                    ? null
                    : suggestedQuestion.trim();
            contextHints = contextHints == null ? List.of() : contextHints.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            debugMetadata = sanitizeMetadata(debugMetadata);
        }

        public GateDecision asRejected(String reason, Map<String, Object> extraMetadata) {
            Map<String, Object> merged = new LinkedHashMap<>(debugMetadata);
            if (extraMetadata != null) {
                extraMetadata.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        merged.put(key, value);
                    }
                });
            }
            return new GateDecision(Verdict.REJECTED, reason, suggestedQuestion, contextHints, confidence, merged);
        }

        private static Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            metadata.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    sanitized.put(key, value);
                }
            });
            return Map.copyOf(sanitized);
        }
    }
}
