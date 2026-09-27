package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

public interface MemoryRepository {

    MemoryItem save(MemoryItem item);

    Optional<MemoryItem> findById(UUID id);

    /**
     * Returns and locks one memory row for the lifetime of the caller's
     * transaction. In-memory/test repositories can safely fall back to their
     * ordinary lookup; JDBC implementations must use {@code FOR UPDATE}.
     */
    default Optional<MemoryItem> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    /**
     * Batch hydration hook used by vector retrieval. Implementations backed by a database should override this
     * method so one vector search never turns into one SQL query per candidate.
     */
    default List<MemoryItem> findAllByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    Optional<MemoryItem> findBySourceRef(String sourceRef);

    /** Transactional curated-source lookup using the same mutation lock. */
    default Optional<MemoryItem> findBySourceRefForUpdate(String sourceRef) {
        return findBySourceRef(sourceRef);
    }

    List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey);

    default List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey, int limit, int offset) {
        return list(scope, status, projectKey).stream()
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    default List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<MemoryItem> out = new java.util.ArrayList<>();
        for (MemoryStatus status : statuses) {
            for (MemoryScope scope : MemoryScope.values()) {
                out.addAll(list(scope, status, null));
            }
        }
        return List.copyOf(out);
    }

    default List<MemoryItem> findEligibleForGraphProjection(String projectKey, UUID afterId, int limit) {
        return listByStatuses(Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE)).stream()
                .filter(item -> projectKey == null || projectKey.isBlank()
                        || (item.scope() == MemoryScope.PROJECT && projectKey.trim().equals(item.projectKey())))
                .filter(item -> afterId == null || item.id().compareTo(afterId) > 0)
                .sorted(java.util.Comparator.comparing(MemoryItem::id))
                .limit(Math.max(1, limit))
                .toList();
    }

    MemoryItem update(MemoryItem item);

    boolean updateStatus(UUID id, MemoryStatus status);

    void insertEvent(MemoryEvent event);

    List<MemoryEvent> eventsForMemory(UUID memoryId);

    void insertReviewQueue(ReviewQueueItem item);

    List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId);

    default int updateReviewQueueStatus(UUID memoryId, ReviewStatus expectedStatus, ReviewStatus status,
            Instant reviewedAt, Map<String, Object> metadata) {
        throw new UnsupportedOperationException("updateReviewQueueStatus is not implemented");
    }

    default List<ReviewQueueItem> listReviewQueue(ReviewStatus status) {
        throw new UnsupportedOperationException("listReviewQueue is not implemented");
    }

    default List<MemoryItem> search(String query, int limit) {
        throw new UnsupportedOperationException("search is not implemented");
    }

    default List<MemoryItem> searchForAutomaticInjection(String query, int limit) {
        return search(query, limit);
    }

    /**
     * Bounded lexical retrieval with eligibility applied before the candidate limit whenever the backing store
     * supports it. Implementations must still expect the caller to re-check the hydrated item.
     */
    default List<MemoryItem> searchEligible(String query, int limit, MemoryEligibilityFilter filter) {
        if (filter == null) {
            return search(query, limit);
        }
        return search(query, Math.max(limit, limit * 4)).stream()
                .filter(filter::accepts)
                .limit(Math.max(1, limit))
                .toList();
    }

    default long countIndexable() {
        return listByStatuses(Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE)).stream()
                .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(Instant.now()))
                .count();
    }

    default int deleteByScopeAndProjectKey(MemoryScope scope, String projectKey) {
        throw new UnsupportedOperationException("deleteByScopeAndProjectKey is not implemented");
    }

    default void deleteById(UUID id) {
        throw new UnsupportedOperationException("deleteById is not implemented");
    }

    default boolean existsByTextHashSince(String textHashPrefix, String projectKey, Instant since) {
        return false;
    }
}
