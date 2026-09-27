package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MemoryVectorIndexingListener {

    private final MemoryVectorProjectionCoordinator coordinator;
    private final MemoryVectorIndex legacyVectorIndex;

    @Autowired
    public MemoryVectorIndexingListener(MemoryVectorProjectionCoordinator coordinator) {
        this.coordinator = coordinator;
        this.legacyVectorIndex = null;
    }

    /** Backwards-compatible focused-test constructor; production uses the coordinator. */
    MemoryVectorIndexingListener(MemoryVectorIndex memoryVectorIndex) {
        this.coordinator = null;
        this.legacyVectorIndex = memoryVectorIndex;
    }

    @EventListener
    @Transactional
    public void onMemoryChanged(MemoryItemChangedEvent event) {
        if (coordinator != null) {
            coordinator.changed(event);
        } else if (event.item().status() == MemoryStatus.ACTIVE || event.item().status() == MemoryStatus.STALE) {
            legacyVectorIndex.upsert(event.item());
        } else {
            legacyVectorIndex.delete(event.item());
        }
    }
}
