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

    /**
     * Every change of a discovery (edit, correction or code-evidence lifecycle) re-derives its learning profile from
     * the item's current status in the database, so event order does not matter and a tag-only edit stays ACTIVE.
     */
    @EventListener
    public void onMemoryChanged(MemoryItemChangedEvent event) {
        if (event.item() != null && event.item().memoryType() == MemoryType.DISCOVERY) {
            repository.syncLearningProfileWithItem(event.item().id());
        }
    }
}
