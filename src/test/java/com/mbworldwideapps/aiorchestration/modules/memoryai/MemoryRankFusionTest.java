package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemoryRankFusionTest {

    @Test
    void rankFusionDeduplicatesCanonicalMemoryAndRewardsAgreement() {
        MemoryItem shared = item("shared");
        MemoryItem denseOnly = item("dense");
        MemoryItem lexicalOnly = item("lexical");

        List<ScoredMemoryRef> fused = MemoryRankFusion.fuse(List.of(
                List.of(new ScoredMemoryRef(denseOnly, 0.99), new ScoredMemoryRef(shared, 0.70)),
                List.of(new ScoredMemoryRef(shared, 1.0), new ScoredMemoryRef(lexicalOnly, 0.5),
                        new ScoredMemoryRef(shared, 0.1))), 10);

        assertThat(fused).extracting(ref -> ref.item().id())
                .containsExactly(shared.id(), denseOnly.id(), lexicalOnly.id());
        assertThat(fused).extracting(ref -> ref.item().id()).doesNotHaveDuplicates();
        assertThat(fused.get(0).similarityScore()).isBetween(0.0, 1.0);
    }

    private static MemoryItem item(String text) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "P", MemoryType.DISCOVERY,
                text, text, List.of(), 0.8, MemoryStatus.ACTIVE, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, Instant.now(), null);
    }
}
