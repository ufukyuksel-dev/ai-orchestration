package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
class MemoryVectorProjectionCoordinator {

    private final MemoryRepository memories;
    private final MemoryVectorIndex vectors;
    private final MemoryVectorProjectionStore store;
    private final MemoryVectorProjectionDescriptor descriptors;
    private final MemoryVectorProjectionMetrics metrics;
    private final MemoryVectorProjectionProperties properties;

    MemoryVectorProjectionCoordinator(MemoryRepository memories, MemoryVectorIndex vectors,
            MemoryVectorProjectionStore store, MemoryVectorProjectionDescriptor descriptors,
            MemoryVectorProjectionMetrics metrics, MemoryVectorProjectionProperties properties) {
        this.memories = memories;
        this.vectors = vectors;
        this.store = store;
        this.descriptors = descriptors;
        this.metrics = metrics;
        this.properties = properties;
    }

    void changed(MemoryItemChangedEvent event) {
        UUID previousVectorId = previousVectorId(event);
        if (properties.mode() == MemoryVectorProjectionMode.OUTBOX) {
            try {
                store.enqueue(event.item().id(), previousVectorId, properties.queueCapacity());
            } catch (MemoryProjectionQueueFullException e) {
                metrics.record("queue_rejected");
                throw e;
            }
            return;
        }
        long revision = store.nextSynchronousRevision(event.item().id());
        apply(event.item(), revision, previousVectorId);
    }

    void applyCurrent(MemoryVectorProjectionWork work) {
        MemoryItem current = memories.findById(work.memoryId()).orElse(null);
        if (current == null) {
            Optional<MemoryVectorProjectionState> state = store.state(work.memoryId());
            state.ifPresent(existing -> vectors.delete(itemWithVectorId(null, work.memoryId(), existing.vectorId())));
            return;
        }
        apply(current, work.revision(), work.previousVectorId());
    }

    private void apply(MemoryItem item, long revision, UUID previousVectorId) {
        MemoryVectorProjectionDescriptor.Descriptor descriptor = descriptors.describe(item);
        Optional<MemoryVectorProjectionState> previous = store.state(item.id());
        if (!descriptor.indexable()) {
            UUID vectorId = previous.map(MemoryVectorProjectionState::vectorId)
                    .orElse(previousVectorId == null ? item.vectorId() : previousVectorId);
            vectors.delete(itemWithVectorId(item, item.id(), vectorId));
            store.record(state(item, revision, vectorId, descriptor,
                    MemoryVectorProjectionState.Operation.DELETE));
            metrics.record("delete");
            return;
        }

        if (previous.isPresent() && sameProjection(previous.orElseThrow(), item, descriptor)) {
            store.record(state(item, revision, item.vectorId(), descriptor,
                    MemoryVectorProjectionState.Operation.UPSERT));
            metrics.record("metadata_skip");
            return;
        }

        UUID oldVectorId = previous.map(MemoryVectorProjectionState::vectorId).orElse(previousVectorId);
        if (oldVectorId != null && !oldVectorId.equals(item.vectorId())) {
            vectors.delete(itemWithVectorId(item, item.id(), oldVectorId));
        }
        vectors.upsert(item);
        store.record(state(item, revision, item.vectorId(), descriptor,
                MemoryVectorProjectionState.Operation.UPSERT));
        metrics.record("upsert");
    }

    private static boolean sameProjection(MemoryVectorProjectionState state, MemoryItem item,
            MemoryVectorProjectionDescriptor.Descriptor descriptor) {
        return state.appliedOperation() == MemoryVectorProjectionState.Operation.UPSERT
                && state.vectorId().equals(item.vectorId())
                && state.retrievalTextHash().equals(descriptor.retrievalTextHash())
                && state.projectionHash().equals(descriptor.projectionHash())
                && state.embeddingFingerprint().equals(descriptor.embeddingFingerprint());
    }

    private static MemoryVectorProjectionState state(MemoryItem item, long revision, UUID vectorId,
            MemoryVectorProjectionDescriptor.Descriptor descriptor,
            MemoryVectorProjectionState.Operation operation) {
        return new MemoryVectorProjectionState(item.id(), revision, vectorId,
                descriptor.retrievalTextHash(), descriptor.projectionHash(), descriptor.embeddingFingerprint(),
                operation, Instant.now());
    }

    private static UUID previousVectorId(MemoryItemChangedEvent event) {
        MemoryItem previous = event.previousItem();
        return previous != null && !previous.vectorId().equals(event.item().vectorId())
                ? previous.vectorId() : null;
    }

    private static MemoryItem itemWithVectorId(MemoryItem template, UUID memoryId, UUID vectorId) {
        if (template == null) {
            Instant now = Instant.now();
            return new MemoryItem(memoryId, vectorId, MemoryScope.PROJECT, "projection-recovery",
                    MemoryType.DISCOVERY, "projection delete", "projection delete", java.util.List.of(),
                    0.0, MemoryStatus.ARCHIVED, MemorySourceType.MANUAL, null, "projection-worker",
                    Map.of(), now, now, null, null, null);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(template.metadata());
        return new MemoryItem(template.id(), vectorId, template.scope(), template.projectKey(), template.memoryType(),
                template.summary(), template.text(), template.tags(), template.confidence(), template.status(),
                template.sourceType(), template.sourceRef(), template.owner(), metadata, template.createdAt(),
                template.updatedAt(), template.lastUsedAt(), template.lastVerifiedAt(), template.expiresAt());
    }
}
