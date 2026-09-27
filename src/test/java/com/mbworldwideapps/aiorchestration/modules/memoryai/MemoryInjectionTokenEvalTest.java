package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 memory-token regression guard: a deterministic, provider-free eval that quantifies the compact
 * injection win versus legacy full-text injection for the same memories, and fails if compaction regresses.
 * Complements the runtime invariant guard (SoftwareCodegenIT#memoryEnabledPromptStaysWithinNoMemoryBudget).
 */
class MemoryInjectionTokenEvalTest {

    @Test
    void compactInjectionMateriallyReducesPromptTokensVersusLegacyFullText() {
        List<MemoryContextItem> items = List.of(
                longMemory("global:rules", "Architecture rule", 30),
                longMemory("project:naming", "Naming convention", 45),
                longMemory("global:security", "Security policy", 60));
        MemoryContextResponse context = new MemoryContextResponse(items,
                items.stream().map(MemoryContextItem::memoryId).toList(), items.size(),
                items.stream().mapToInt(MemoryContextItem::tokenEstimate).sum(),
                Map.of("global", 2, "project", 1), 0, 0, 0, 1L);

        int compactTokens = TokenEstimator.estimate(new MemoryContextBuilder().build(context));
        int legacyTokens = TokenEstimator.estimate(legacyFullTextBlock(items));

        // Compact injection uses materially fewer tokens (>= 50% reduction here) than legacy full-text injection.
        assertThat(compactTokens).isLessThan(legacyTokens);
        assertThat(compactTokens).isLessThanOrEqualTo(legacyTokens / 2);
        // The signal is preserved: every citation is still present in the compact block.
        assertThat(new MemoryContextBuilder().build(context))
                .contains("global:rules")
                .contains("project:naming")
                .contains("global:security");
    }

    private static MemoryContextItem longMemory(String citationId, String summary, int repetitions) {
        String text = ("This is a durable engineering fact that would historically be injected verbatim into every "
                + "prompt, inflating tokens well beyond what a compact preview needs. ").repeat(repetitions);
        MemoryScope scope = citationId.startsWith("project") ? MemoryScope.PROJECT : MemoryScope.GLOBAL;
        UUID id = UUID.randomUUID();
        return new MemoryContextItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), citationId,
                scope, scope == MemoryScope.PROJECT ? "AI_ORCHESTRATION" : null, MemoryType.RULE, summary,
                text, 0.9, false, 5, 0.5, Instant.now());
    }

    private static String legacyFullTextBlock(List<MemoryContextItem> items) {
        StringBuilder builder = new StringBuilder("=== Active rules from memory ===\n");
        for (MemoryContextItem item : items) {
            builder.append("[").append(item.citationId()).append("] (confidence=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", item.confidence())).append(") ")
                    .append(item.text().replaceAll("\\s+", " ").trim()).append('\n');
        }
        builder.append("=== End rules ===");
        return builder.toString();
    }
}
