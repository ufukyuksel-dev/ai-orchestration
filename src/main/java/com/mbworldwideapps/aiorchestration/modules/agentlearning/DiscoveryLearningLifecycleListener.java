package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItemChangedEvent;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryLearningLifecycleListener {

    private final AgentLearningRepository repository;

    public DiscoveryLearningLifecycleListener(AgentLearningRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onMemoryChanged(MemoryItemChangedEvent event) {
        if (event.origin() == MemoryItemChangedEvent.Origin.EXTERNAL_EDIT
                && event.item().memoryType() == MemoryType.DISCOVERY) {
            repository.markLearningProfileStale(event.item().id());
        }
    }
}
