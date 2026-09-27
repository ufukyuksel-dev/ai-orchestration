package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemoryVectorIndexingListenerTest {

    @Test
    void indexesActiveOrStaleAcrossAllScopes() {
        FakeMemoryVectorIndex index = new FakeMemoryVectorIndex();
        MemoryVectorIndexingListener listener = new MemoryVectorIndexingListener(index);

        MemoryItem activeEpisodic = item(MemoryScope.EPISODIC, MemoryStatus.ACTIVE);
        MemoryItem staleEpisodic = item(MemoryScope.EPISODIC, MemoryStatus.STALE);
        MemoryItem activeProject = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        MemoryItem staleProject = item(MemoryScope.PROJECT, MemoryStatus.STALE);
        MemoryItem activeGlobal = item(MemoryScope.GLOBAL, MemoryStatus.ACTIVE);
        MemoryItem activeUser = item(MemoryScope.USER, MemoryStatus.ACTIVE);
        MemoryItem pendingEpisodic = item(MemoryScope.EPISODIC, MemoryStatus.PENDING_REVIEW);
        MemoryItem pendingProject = item(MemoryScope.PROJECT, MemoryStatus.PENDING_REVIEW);
        MemoryItem rejectedGlobal = item(MemoryScope.GLOBAL, MemoryStatus.REJECTED);

        listener.onMemoryChanged(new MemoryItemChangedEvent(activeEpisodic));
        listener.onMemoryChanged(new MemoryItemChangedEvent(staleEpisodic));
        listener.onMemoryChanged(new MemoryItemChangedEvent(activeProject));
        listener.onMemoryChanged(new MemoryItemChangedEvent(staleProject));
        listener.onMemoryChanged(new MemoryItemChangedEvent(activeGlobal));
        listener.onMemoryChanged(new MemoryItemChangedEvent(activeUser));
        listener.onMemoryChanged(new MemoryItemChangedEvent(pendingEpisodic));
        listener.onMemoryChanged(new MemoryItemChangedEvent(pendingProject));
        listener.onMemoryChanged(new MemoryItemChangedEvent(rejectedGlobal));

        assertThat(index.upserted).containsExactly(activeEpisodic.id(), staleEpisodic.id(), activeProject.id(),
                staleProject.id(), activeGlobal.id(), activeUser.id());
        assertThat(index.deleted).containsExactly(pendingEpisodic.id(), pendingProject.id(), rejectedGlobal.id());
    }

    private static MemoryItem item(MemoryScope scope, MemoryStatus status) {
        UUID id = UUID.randomUUID();
        String projectKey = switch (scope) {
            case PROJECT -> "AI_ORCHESTRATION";
            case USER -> "user:demo";
            case GLOBAL -> null;
            case EPISODIC -> "AI_ORCHESTRATION";
        };
        return new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), scope,
                projectKey,
                MemoryType.RULE, "Summary", "Memory text", List.of(), 0.8, status, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    private static final class FakeMemoryVectorIndex implements MemoryVectorIndex {
        private final List<UUID> upserted = new ArrayList<>();
        private final List<UUID> deleted = new ArrayList<>();

        @Override
        public void upsert(MemoryItem item) {
            upserted.add(item.id());
        }

        @Override
        public void delete(MemoryItem item) {
            deleted.add(item.id());
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            return List.of();
        }
    }
}
