package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemoryVectorIndexBackfillTest {

    @Test
    void upsertsAllActiveAndStaleAcrossScopes() {
        FakeRepository repository = new FakeRepository();
        MemoryItem activeProject = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        MemoryItem staleProject = item(MemoryScope.PROJECT, MemoryStatus.STALE);
        MemoryItem activeGlobal = item(MemoryScope.GLOBAL, MemoryStatus.ACTIVE);
        MemoryItem activeUser = item(MemoryScope.USER, MemoryStatus.ACTIVE);
        MemoryItem activeEpisodic = item(MemoryScope.EPISODIC, MemoryStatus.ACTIVE);
        MemoryItem pendingProject = item(MemoryScope.PROJECT, MemoryStatus.PENDING_REVIEW);
        MemoryItem rejectedGlobal = item(MemoryScope.GLOBAL, MemoryStatus.REJECTED);
        repository.put(activeProject, staleProject, activeGlobal, activeUser, activeEpisodic, pendingProject,
                rejectedGlobal);
        FakeIndex index = new FakeIndex();
        MemoryVectorIndexBackfill backfill = new MemoryVectorIndexBackfill(repository, index, true);

        MemoryVectorIndexBackfill.BackfillResult result = backfill.backfill();

        assertThat(index.upserted).containsExactlyInAnyOrder(activeProject.id(), staleProject.id(),
                activeGlobal.id(), activeUser.id(), activeEpisodic.id());
        assertThat(index.upserted).doesNotContain(pendingProject.id(), rejectedGlobal.id());
        assertThat(result.enabled()).isTrue();
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.upserted()).isEqualTo(5);
        assertThat(result.failed()).isZero();
        assertThat(result.readinessNote()).contains("memory_vector_backfill=enabled,total=5,upserted=5,failed=0");
    }

    @Test
    void skipsWhenDisabled() {
        FakeRepository repository = new FakeRepository();
        repository.put(item(MemoryScope.PROJECT, MemoryStatus.ACTIVE));
        FakeIndex index = new FakeIndex();
        MemoryVectorIndexBackfill backfill = new MemoryVectorIndexBackfill(repository, index, false);

        MemoryVectorIndexBackfill.BackfillResult result = backfill.backfill();

        assertThat(index.upserted).isEmpty();
        assertThat(result.enabled()).isFalse();
        assertThat(result.readinessNote()).isEqualTo("memory_vector_backfill=disabled");
    }

    @Test
    void continuesPastSingleItemFailure() {
        FakeRepository repository = new FakeRepository();
        MemoryItem first = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        MemoryItem failing = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        MemoryItem last = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        repository.put(first, failing, last);
        FakeIndex index = new FakeIndex();
        index.failOnId = failing.id();
        MemoryVectorIndexBackfill backfill = new MemoryVectorIndexBackfill(repository, index, true);

        MemoryVectorIndexBackfill.BackfillResult result = backfill.backfill();

        assertThat(index.upserted).contains(first.id(), last.id());
        assertThat(index.upserted).doesNotContain(failing.id());
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.upserted()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.readinessNote()).contains("warning=partial_failures:1");
    }

    private static MemoryItem item(MemoryScope scope, MemoryStatus status) {
        UUID id = UUID.randomUUID();
        String projectKey = switch (scope) {
            case PROJECT -> "AI_ORCHESTRATION";
            case USER -> "user:demo";
            case GLOBAL -> null;
            case EPISODIC -> "AI_ORCHESTRATION";
        };
        return new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), scope, projectKey,
                MemoryType.RULE, "Summary", "Memory text " + id, List.of(), 0.8, status,
                MemorySourceType.MANUAL, "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null,
                Instant.now(), null);
    }

    private static final class FakeRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new LinkedHashMap<>();

        void put(MemoryItem... values) {
            for (MemoryItem value : values) {
                items.put(value.id(), value);
            }
        }

        @Override
        public MemoryItem save(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public Optional<MemoryItem> findById(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<MemoryItem> findBySourceRef(String sourceRef) {
            return Optional.empty();
        }

        @Override
        public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
            return items.values().stream()
                    .filter(item -> scope == null || item.scope() == scope)
                    .filter(item -> status == null || item.status() == status)
                    .filter(item -> projectKey == null || projectKey.equals(item.projectKey()))
                    .toList();
        }

        @Override
        public List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
            return items.values().stream().filter(item -> statuses.contains(item.status())).toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            return false;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return List.of();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return List.of();
        }
    }

    private static final class FakeIndex implements MemoryVectorIndex {
        final List<UUID> upserted = new ArrayList<>();
        UUID failOnId;

        @Override
        public void upsert(MemoryItem item) {
            if (failOnId != null && failOnId.equals(item.id())) {
                throw new RuntimeException("forced backfill failure");
            }
            upserted.add(item.id());
        }

        @Override
        public void delete(MemoryItem item) {
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            return List.of();
        }
    }
}
