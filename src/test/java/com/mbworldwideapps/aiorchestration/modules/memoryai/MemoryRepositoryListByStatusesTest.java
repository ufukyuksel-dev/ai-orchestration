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

class MemoryRepositoryListByStatusesTest {

    @Test
    void emptyStatusSetReturnsEmptyList() {
        FakeRepository repository = new FakeRepository();
        repository.put(item(MemoryStatus.ACTIVE));

        assertThat(repository.listByStatuses(Set.of())).isEmpty();
        assertThat(repository.listByStatuses(null)).isEmpty();
    }

    @Test
    void singleStatusReturnsOnlyMatchingItems() {
        FakeRepository repository = new FakeRepository();
        MemoryItem active = item(MemoryStatus.ACTIVE);
        MemoryItem stale = item(MemoryStatus.STALE);
        MemoryItem pending = item(MemoryStatus.PENDING_REVIEW);
        repository.put(active, stale, pending);

        List<MemoryItem> activeOnly = repository.listByStatuses(Set.of(MemoryStatus.ACTIVE));

        assertThat(activeOnly).extracting(MemoryItem::id).containsExactly(active.id());
    }

    @Test
    void multipleStatusesReturnUnionOfMatches() {
        FakeRepository repository = new FakeRepository();
        MemoryItem active = item(MemoryStatus.ACTIVE);
        MemoryItem stale = item(MemoryStatus.STALE);
        MemoryItem pending = item(MemoryStatus.PENDING_REVIEW);
        repository.put(active, stale, pending);

        List<MemoryItem> activeOrStale = repository.listByStatuses(Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE));

        assertThat(activeOrStale).extracting(MemoryItem::id).containsExactlyInAnyOrder(active.id(), stale.id());
        assertThat(activeOrStale).extracting(MemoryItem::id).doesNotContain(pending.id());
    }

    @Test
    void defaultIteratesAllScopesAndStatuses() {
        DefaultOnlyRepository repository = new DefaultOnlyRepository();
        MemoryItem userActive = item(MemoryScope.USER, MemoryStatus.ACTIVE);
        MemoryItem projectActive = item(MemoryScope.PROJECT, MemoryStatus.ACTIVE);
        MemoryItem globalStale = item(MemoryScope.GLOBAL, MemoryStatus.STALE);
        MemoryItem episodicPending = item(MemoryScope.EPISODIC, MemoryStatus.PENDING_REVIEW);
        repository.put(userActive, projectActive, globalStale, episodicPending);

        List<MemoryItem> activeOrStale = repository.listByStatuses(
                Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE));

        assertThat(activeOrStale).extracting(MemoryItem::id)
                .containsExactlyInAnyOrder(userActive.id(), projectActive.id(), globalStale.id());
    }

    private static MemoryItem item(MemoryStatus status) {
        return item(MemoryScope.PROJECT, status);
    }

    private static MemoryItem item(MemoryScope scope, MemoryStatus status) {
        UUID id = UUID.randomUUID();
        String projectKey = scope == MemoryScope.GLOBAL ? null : "AI_ORCHESTRATION";
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
        public List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
            if (statuses == null || statuses.isEmpty()) {
                return List.of();
            }
            return items.values().stream().filter(item -> statuses.contains(item.status())).toList();
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
            return new ArrayList<>(items.values());
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

    private static final class DefaultOnlyRepository implements MemoryRepository {
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
}
