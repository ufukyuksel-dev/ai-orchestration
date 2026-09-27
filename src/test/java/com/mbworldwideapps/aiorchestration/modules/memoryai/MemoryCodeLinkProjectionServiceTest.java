package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import org.junit.jupiter.api.Test;

class MemoryCodeLinkProjectionServiceTest {

    private final MemoryLifecycleService lifecycle = mock(MemoryLifecycleService.class);

    @Test
    void skipsWhenLinksDisabled() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryCodeLinkResolver resolver = mock(MemoryCodeLinkResolver.class);

        MemoryCodeLinkProjectionResult result = new MemoryCodeLinkProjectionService(properties(false), repository,
                resolver, lifecycle).linkAll("AI_ORCHESTRATION");

        assertThat(result.skipped()).isTrue();
        verifyNoInteractions(repository, resolver, lifecycle);
    }

    @Test
    void linkAllPaginatesEligibleMemoriesAndObservesDrift() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryCodeLinkResolver resolver = mock(MemoryCodeLinkResolver.class);
        MemoryItem first = item(UUID.randomUUID());
        MemoryItem second = item(UUID.randomUUID());
        MemoryCodeLinkRecord link = link(first.id());
        when(repository.findEligibleForGraphProjection("AI_ORCHESTRATION", null, 2)).thenReturn(List.of(first,
                second));
        when(repository.findEligibleForGraphProjection("AI_ORCHESTRATION", second.id(), 2)).thenReturn(List.of());
        when(resolver.resolve(first)).thenReturn(new MemoryCodeLinkResolveResult(List.of(link), 1, 0, 0, 0, 0, 0, 0));
        when(resolver.resolve(second)).thenReturn(MemoryCodeLinkResolveResult.empty());

        MemoryCodeLinkProjectionResult result = new MemoryCodeLinkProjectionService(properties(true), repository,
                resolver, lifecycle).linkAll("AI_ORCHESTRATION");

        assertThat(result.available()).isTrue();
        assertThat(result.memoriesProcessed()).isEqualTo(2);
        assertThat(result.relationshipsMerged()).isEqualTo(1);
        assertThat(result.unresolvedSourceRefs()).isEqualTo(1);
        verify(lifecycle).observe(eq(first), any(MemoryCodeLinkResolveResult.class));
        verify(lifecycle).observe(eq(second), any(MemoryCodeLinkResolveResult.class));
        verify(repository).findEligibleForGraphProjection("AI_ORCHESTRATION", null, 2);
        verify(repository).findEligibleForGraphProjection("AI_ORCHESTRATION", second.id(), 2);
    }

    @Test
    void emptyResolvedLocatorSetIsStillObservedForDrift() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryCodeLinkResolver resolver = mock(MemoryCodeLinkResolver.class);
        MemoryItem item = item(UUID.randomUUID());
        when(repository.findById(item.id())).thenReturn(java.util.Optional.of(item));
        when(resolver.resolve(item)).thenReturn(MemoryCodeLinkResolveResult.empty());

        MemoryCodeLinkProjectionResult result = new MemoryCodeLinkProjectionService(properties(true), repository,
                resolver, lifecycle).linkMemory(item.id());

        assertThat(result.available()).isTrue();
        assertThat(result.relationshipsMerged()).isZero();
        verify(lifecycle).observe(eq(item), any(MemoryCodeLinkResolveResult.class));
    }

    private static MemoryCodeLinkRecord link(UUID memoryId) {
        return new MemoryCodeLinkRecord("edge-" + memoryId, memoryId, "AI_ORCHESTRATION",
                MemoryCodeRelationshipType.EVIDENCES, MemoryCodeTargetKind.CAPSULE, null,
                "AI_ORCHESTRATION|target|project_insight", null, null, "source_ref", 0.9,
                "scanner", Map.of(), "hash", false, 1.0);
    }

    private static MemoryItem item(UUID id) {
        Instant now = Instant.parse("2026-05-20T08:00:00Z");
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "AI_ORCHESTRATION",
                MemoryType.DECISION, "Summary", "Text", List.of("scanner"), 0.8, MemoryStatus.ACTIVE,
                MemorySourceType.SCANNED, "scanner-flow-insight:" + UUID.randomUUID(), "scanner", Map.of(),
                now, now, null, now, null);
    }

    private static MemoryCodeLinkProperties properties(boolean memoryCodeLinksEnabled) {
        return new MemoryCodeLinkProperties(memoryCodeLinksEnabled, true, false, 0.72, 5, 2, 20);
    }
}
