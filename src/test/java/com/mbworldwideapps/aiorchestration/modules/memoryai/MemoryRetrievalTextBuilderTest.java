package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemoryRetrievalTextBuilderTest {

    private final MemoryRetrievalTextBuilder builder = new MemoryRetrievalTextBuilder();

    @Test
    void legacySchemaPreservesTextOnly() {
        MemoryItem item = item("Summary", "Atomic condition", List.of("PaymentService"));

        assertThat(builder.build(item, "legacy-v1")).isEqualTo("Atomic condition");
    }

    @Test
    void discoverySchemaIncludesBoundedDistinctSignalWithoutAuditMetadata() {
        MemoryItem item = item("Payment routing", "ERR_PAY_042 maps to retryable", List.of(
                "PaymentService", "retry", "PaymentService"));

        assertThat(builder.build(item, "discovery-v2"))
                .isEqualTo("Payment routing\nERR_PAY_042 maps to retryable\ntag: PaymentService\ntag: retry")
                .doesNotContain(item.id().toString(), "created_at", "source_ref");
    }

    private static MemoryItem item(String summary, String text, List<String> tags) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "P", MemoryType.DISCOVERY,
                summary, text, tags, 0.9, MemoryStatus.ACTIVE, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of("audit", "hidden"), Instant.now(), Instant.now(), null,
                Instant.now(), null);
    }
}
