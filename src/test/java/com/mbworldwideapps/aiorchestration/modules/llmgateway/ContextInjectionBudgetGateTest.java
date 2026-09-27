package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import org.junit.jupiter.api.Test;

class ContextInjectionBudgetGateTest {

    @Test
    void memoryFallbackEstimateUsesCompactPromptTextNotFullText() {
        // Item without a precomputed estimate (tokenEstimate = 0) exercises the defensive fallback.
        // Prompt rendering injects promptText(), so the gate must size the compact text: the item stays
        // under a memory cap that the full text would exceed.
        String longText = "Rule. " + "extra ".repeat(80);
        MemoryContextItem item = new MemoryContextItem(UUID.randomUUID(), UUID.randomUUID(), "global:x",
                MemoryScope.GLOBAL, null, MemoryType.RULE, "Summary", longText,
                0.9, false, 0, 0.5, Instant.now());
        assertThat(TokenEstimator.estimate(item.text())).isGreaterThan(50);
        assertThat(TokenEstimator.estimate(item.promptText())).isLessThan(50);
        MemoryContextResponse memory = new MemoryContextResponse(List.of(item), List.of(item.memoryId()), 1, 0,
                Map.of("global", 1), 0, 0, 1L);

        ContextInjectionBudgetGate gate = new ContextInjectionBudgetGate();
        ContextInjectionBudgetGate.TrimResult result = gate.trim(List.of(), memory,
                new ContextInjectionBudgetGate.BudgetConfig(50, 3000, 4500));

        assertThat(result.trimmedMemoryContext().injectedMemoryIds()).contains(item.memoryId());
        assertThat(result.memoryTokens()).isEqualTo(TokenEstimator.estimate(item.promptText()));
    }

    @Test
    void trimPreservesRelevanceFilteredCount() {
        MemoryContextItem item = new MemoryContextItem(UUID.randomUUID(), UUID.randomUUID(), "global:x",
                MemoryScope.GLOBAL, null, MemoryType.RULE, "Summary", "short fact",
                0.9, false, 3, 0.5, Instant.now());
        // 9-arg canonical carrying relevanceFilteredCount = 5 (candidates dropped by the injection gate upstream).
        MemoryContextResponse memory = new MemoryContextResponse(List.of(item), List.of(item.memoryId()), 1,
                item.tokenEstimate(), Map.of("global", 1), 0, 0, 5, 1L);

        ContextInjectionBudgetGate.TrimResult result = new ContextInjectionBudgetGate().trim(List.of(), memory);

        assertThat(result.trimmedMemoryContext().relevanceFilteredCount()).isEqualTo(5);
    }
}
