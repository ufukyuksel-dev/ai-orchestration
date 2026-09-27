package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItemChangedEvent;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import org.junit.jupiter.api.Test;

class DiscoveryLearningLifecycleListenerTest {

    @Test
    void onlyExternalDiscoveryEditsStaleTheLearningProfile() {
        AgentLearningRepository repository = mock(AgentLearningRepository.class);
        DiscoveryLearningLifecycleListener listener = new DiscoveryLearningLifecycleListener(repository);
        MemoryItem discovery = item(MemoryType.DISCOVERY);
        MemoryItem decision = item(MemoryType.DECISION);

        listener.onMemoryChanged(new MemoryItemChangedEvent(discovery,
                MemoryItemChangedEvent.Origin.EXTERNAL_EDIT));
        listener.onMemoryChanged(new MemoryItemChangedEvent(discovery,
                MemoryItemChangedEvent.Origin.LEARNING_CORRECTION));
        listener.onMemoryChanged(new MemoryItemChangedEvent(decision,
                MemoryItemChangedEvent.Origin.EXTERNAL_EDIT));

        verify(repository).markLearningProfileStale(discovery.id());
        verify(repository, never()).markLearningProfileStale(decision.id());
    }

    private static MemoryItem item(MemoryType type) {
        Instant now = Instant.now();
        return new MemoryItem(UUID.randomUUID(), UUID.randomUUID(), MemoryScope.PROJECT, "P", type,
                "summary", "text", List.of(), 0.9, MemoryStatus.ACTIVE, MemorySourceType.MCP_EXTERNAL,
                "test", "tester", Map.of(), now, now, null, now, null);
    }
}
