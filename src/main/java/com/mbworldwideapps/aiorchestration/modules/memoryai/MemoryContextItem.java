package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;

public record MemoryContextItem(
        UUID memoryId,
        UUID vectorId,
        String citationId,
        MemoryScope scope,
        String projectKey,
        MemoryType memoryType,
        String summary,
        String text,
        String promptText,
        double confidence,
        boolean stale,
        int tokenEstimate,
        double semanticScore,
        Instant lastVerifiedAt,
        String sourceRef) {

    public MemoryContextItem {
        if (promptText == null || promptText.isBlank()) {
            promptText = MemoryTextPreview.promptText(summary, text);
        }
        sourceRef = sourceRef == null ? "" : sourceRef.trim();
    }

    /** Backwards-compatible constructor retained for the original full prompt-text shape. */
    public MemoryContextItem(UUID memoryId, UUID vectorId, String citationId, MemoryScope scope, String projectKey,
            MemoryType memoryType, String summary, String text, String promptText, double confidence, boolean stale,
            int tokenEstimate, double semanticScore, Instant lastVerifiedAt) {
        this(memoryId, vectorId, citationId, scope, projectKey, memoryType, summary, text, promptText, confidence,
                stale, tokenEstimate, semanticScore, lastVerifiedAt, "");
    }

    /**
     * Backwards-compatible constructor: derives the compact {@link #promptText()} from summary+text.
     * Prompt rendering uses {@link #promptText()} while {@link #text()} keeps the full memory text.
     */
    public MemoryContextItem(UUID memoryId, UUID vectorId, String citationId, MemoryScope scope, String projectKey,
            MemoryType memoryType, String summary, String text, double confidence, boolean stale, int tokenEstimate,
            double semanticScore, Instant lastVerifiedAt) {
        this(memoryId, vectorId, citationId, scope, projectKey, memoryType, summary, text, null, confidence, stale,
                tokenEstimate, semanticScore, lastVerifiedAt, "");
    }
}
