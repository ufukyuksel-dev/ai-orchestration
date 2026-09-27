package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryVectorProjectionCoordinatorTest {

    private MemoryRepository memories;
    private MemoryVectorIndex vectors;
    private MemoryVectorProjectionStore store;
    private MemoryVectorProjectionMetrics metrics;
    private MemoryVectorProjectionDescriptor descriptors;

    @BeforeEach
    void setUp() {
        memories = mock(MemoryRepository.class);
        vectors = mock(MemoryVectorIndex.class);
        store = mock(MemoryVectorProjectionStore.class);
        metrics = mock(MemoryVectorProjectionMetrics.class);
        MemoryEmbeddingProperties embedding = new MemoryEmbeddingProperties(
                "internal", "hashing", "v1", "nfkc-v1", "legacy-v1", "",
                MemoryShadowMode.OFF, "", null, null, null, null, null, "");
        descriptors = new MemoryVectorProjectionDescriptor(
                new MemoryEmbeddingContract(new HashingEmbeddingModel(64), embedding),
                new MemoryRetrievalTextBuilder());
    }

    @Test
    void metadataOnlyChangeSkipsSecondEmbeddingTrigger() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.SYNC);
        MemoryItem original = item("same text", MemoryStatus.ACTIVE, Map.of("note", "one"));
        when(store.nextSynchronousRevision(original.id())).thenReturn(1L, 2L);
        when(store.state(original.id())).thenReturn(Optional.empty());

        coordinator.changed(new MemoryItemChangedEvent(original));
        ArgumentCaptor<MemoryVectorProjectionState> state = ArgumentCaptor.forClass(MemoryVectorProjectionState.class);
        verify(store).record(state.capture());

        reset(store);
        MemoryItem metadataOnly = copy(original, original.vectorId(), "same text", MemoryStatus.ACTIVE,
                Map.of("note", "two"));
        when(store.nextSynchronousRevision(original.id())).thenReturn(2L);
        when(store.state(original.id())).thenReturn(Optional.of(state.getValue()));

        coordinator.changed(new MemoryItemChangedEvent(metadataOnly, original));

        verify(vectors, times(1)).upsert(any());
        verify(metrics).record("metadata_skip");
    }

    @Test
    void textChangeDeletesPreviousVectorAndEmbedsCurrentFingerprint() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.SYNC);
        MemoryItem previous = item("old text", MemoryStatus.ACTIVE, Map.of());
        UUID nextVector = MemoryService.deterministicVectorId(
                previous.scope(), previous.projectKey(), previous.summary(), "new text");
        MemoryItem changed = copy(previous, nextVector, "new text", MemoryStatus.ACTIVE, Map.of());
        when(store.nextSynchronousRevision(previous.id())).thenReturn(1L);
        when(store.state(previous.id())).thenReturn(Optional.empty());

        coordinator.changed(new MemoryItemChangedEvent(changed, previous));

        ArgumentCaptor<MemoryItem> deleted = ArgumentCaptor.forClass(MemoryItem.class);
        verify(vectors).delete(deleted.capture());
        assertThat(deleted.getValue().vectorId()).isEqualTo(previous.vectorId());
        verify(vectors).upsert(changed);
        verify(store, never()).enqueue(any(), any(), any(Integer.class));
        ArgumentCaptor<MemoryVectorProjectionState> state = ArgumentCaptor.forClass(MemoryVectorProjectionState.class);
        verify(store).record(state.capture());
        assertThat(state.getValue().embeddingFingerprint()).hasSize(64);
        verify(metrics).record("upsert");
    }

    @Test
    void embeddingFingerprintChangeForcesUpsertWithCurrentFingerprint() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.SYNC);
        MemoryItem current = item("same text", MemoryStatus.ACTIVE, Map.of());
        MemoryVectorProjectionDescriptor.Descriptor descriptor = descriptors.describe(current);
        when(store.nextSynchronousRevision(current.id())).thenReturn(8L);
        when(store.state(current.id())).thenReturn(Optional.of(new MemoryVectorProjectionState(
                current.id(), 7L, current.vectorId(), descriptor.retrievalTextHash(), descriptor.projectionHash(),
                "0".repeat(64), MemoryVectorProjectionState.Operation.UPSERT, Instant.now())));

        coordinator.changed(new MemoryItemChangedEvent(current));

        verify(vectors).upsert(current);
        ArgumentCaptor<MemoryVectorProjectionState> state = ArgumentCaptor.forClass(MemoryVectorProjectionState.class);
        verify(store).record(state.capture());
        assertThat(state.getValue().embeddingFingerprint()).isEqualTo(descriptor.embeddingFingerprint())
                .isNotEqualTo("0".repeat(64));
    }

    @Test
    void filterAffectingStatusChangeRefreshesVectorMetadataWithoutChangingText() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.SYNC);
        MemoryItem active = item("same text", MemoryStatus.ACTIVE, Map.of());
        MemoryItem stale = copy(active, active.vectorId(), active.text(), MemoryStatus.STALE, active.metadata());
        MemoryVectorProjectionDescriptor.Descriptor activeDescriptor = descriptors.describe(active);
        when(store.nextSynchronousRevision(stale.id())).thenReturn(2L);
        when(store.state(stale.id())).thenReturn(Optional.of(new MemoryVectorProjectionState(
                active.id(), 1L, active.vectorId(), activeDescriptor.retrievalTextHash(),
                activeDescriptor.projectionHash(), activeDescriptor.embeddingFingerprint(),
                MemoryVectorProjectionState.Operation.UPSERT, Instant.now())));

        coordinator.changed(new MemoryItemChangedEvent(stale, active));

        verify(vectors).upsert(stale);
        ArgumentCaptor<MemoryVectorProjectionState> state = ArgumentCaptor.forClass(MemoryVectorProjectionState.class);
        verify(store).record(state.capture());
        assertThat(state.getValue().retrievalTextHash()).isEqualTo(activeDescriptor.retrievalTextHash());
        assertThat(state.getValue().projectionHash()).isNotEqualTo(activeDescriptor.projectionHash());
    }

    @Test
    void archivedCurrentStateDeletesWithoutUpsert() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.SYNC);
        MemoryItem archived = item("archived", MemoryStatus.ARCHIVED, Map.of());
        when(store.nextSynchronousRevision(archived.id())).thenReturn(4L);
        when(store.state(archived.id())).thenReturn(Optional.of(new MemoryVectorProjectionState(
                archived.id(), 3L, archived.vectorId(), "a".repeat(64), "b".repeat(64), "c".repeat(64),
                MemoryVectorProjectionState.Operation.UPSERT, Instant.now())));

        coordinator.changed(new MemoryItemChangedEvent(archived));

        verify(vectors).delete(any());
        verify(vectors, never()).upsert(any());
        verify(metrics).record("delete");
    }

    @Test
    void outboxModeEnqueuesAndNeverAlsoPublishesSynchronously() {
        MemoryVectorProjectionCoordinator coordinator = coordinator(MemoryVectorProjectionMode.OUTBOX);
        MemoryItem current = item("queued", MemoryStatus.ACTIVE, Map.of());
        when(store.enqueue(current.id(), null, 10_000)).thenReturn(1L);

        coordinator.changed(new MemoryItemChangedEvent(current));

        verify(store).enqueue(current.id(), null, 10_000);
        verify(store, never()).nextSynchronousRevision(any());
        verify(vectors, never()).upsert(any());
        verify(vectors, never()).delete(any());
    }

    private MemoryVectorProjectionCoordinator coordinator(MemoryVectorProjectionMode mode) {
        return new MemoryVectorProjectionCoordinator(memories, vectors, store, descriptors, metrics,
                new MemoryVectorProjectionProperties(mode, 10_000, 20, 5, 1_000L));
    }

    private static MemoryItem item(String text, MemoryStatus status, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id,
                MemoryService.deterministicVectorId(MemoryScope.PROJECT, "P", "summary", text),
                MemoryScope.PROJECT, "P", MemoryType.DISCOVERY, "summary", text, List.of("tag"),
                0.8, status, MemorySourceType.MANUAL, "test:" + id, "test", metadata,
                Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    private static MemoryItem copy(MemoryItem item, UUID vectorId, String text, MemoryStatus status,
            Map<String, Object> metadata) {
        return new MemoryItem(item.id(), vectorId, item.scope(), item.projectKey(), item.memoryType(),
                item.summary(), text, item.tags(), item.confidence(), status, item.sourceType(), item.sourceRef(),
                item.owner(), metadata, item.createdAt(), Instant.now(), item.lastUsedAt(), item.lastVerifiedAt(),
                item.expiresAt());
    }
}
