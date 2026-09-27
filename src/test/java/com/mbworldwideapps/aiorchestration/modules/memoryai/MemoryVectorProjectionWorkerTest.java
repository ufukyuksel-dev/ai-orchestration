package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MemoryVectorProjectionWorkerTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcMemoryRepository memories;
    private MemoryVectorIndex vectors;
    private MemoryVectorProjectionStore store;
    private MemoryVectorProjectionMetrics metrics;
    private MemoryVectorProjectionProperties properties;
    private MemoryVectorProjectionWorker worker;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE memory_items CASCADE");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        memories = new JdbcMemoryRepository(jdbc, new ObjectMapper());
        vectors = mock(MemoryVectorIndex.class);
        store = new MemoryVectorProjectionStore(jdbc);
        metrics = new MemoryVectorProjectionMetrics(new SimpleMeterRegistry());
        properties = new MemoryVectorProjectionProperties(MemoryVectorProjectionMode.OUTBOX, 2, 10, 3, 1_000L);
        MemoryEmbeddingProperties embedding = new MemoryEmbeddingProperties(
                "internal", "hashing", "v1", "nfkc-v1", "legacy-v1", "",
                MemoryShadowMode.OFF, "", null, null, null, null, null, "");
        MemoryVectorProjectionDescriptor descriptors = new MemoryVectorProjectionDescriptor(
                new MemoryEmbeddingContract(new HashingEmbeddingModel(64), embedding),
                new MemoryRetrievalTextBuilder());
        MemoryVectorProjectionCoordinator coordinator = new MemoryVectorProjectionCoordinator(
                memories, vectors, store, descriptors, metrics, properties);
        worker = new MemoryVectorProjectionWorker(store, coordinator, metrics, properties, transactions);
    }

    @Test
    void workerCrashRetriesSameRevisionAndCompletesIdempotently() {
        MemoryItem item = save("retry", MemoryStatus.ACTIVE);
        enqueue(item.id(), null);
        doThrow(new IllegalStateException("simulated sink crash")).when(vectors).upsert(any());

        worker.poll();

        assertThat(work(item.id()).attemptCount()).isEqualTo(1);
        assertThat(work(item.id()).revision()).isEqualTo(1L);
        jdbc.update("UPDATE memory_vector_projection_work SET available_at=now() WHERE memory_id=?", item.id());
        reset(vectors);

        worker.poll();

        verify(vectors).upsert(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_vector_projection_work", Integer.class))
                .isZero();
        assertThat(store.state(item.id())).get().extracting(MemoryVectorProjectionState::appliedRevision)
                .isEqualTo(1L);
    }

    @Test
    void delayedUpsertRehydratesArchivedStateAndCannotReviveIt() {
        MemoryItem item = save("archive-before-worker", MemoryStatus.ACTIVE);
        enqueue(item.id(), null);
        jdbc.update("UPDATE memory_items SET status='archived', updated_at=now() WHERE id=?", item.id());

        worker.poll();

        verify(vectors).delete(any());
        verify(vectors, times(0)).upsert(any());
        assertThat(store.state(item.id())).get()
                .extracting(MemoryVectorProjectionState::appliedOperation)
                .isEqualTo(MemoryVectorProjectionState.Operation.DELETE);
    }

    @Test
    void metadataOnlyRevisionDoesNotTriggerAnotherEmbeddingCall() {
        MemoryItem item = save("stable-text", MemoryStatus.ACTIVE);
        enqueue(item.id(), null);
        worker.poll();
        jdbc.update("UPDATE memory_items SET confidence=0.7, updated_at=now() WHERE id=?", item.id());
        enqueue(item.id(), null);

        worker.poll();

        verify(vectors, times(1)).upsert(any());
        assertThat(store.state(item.id())).get().extracting(MemoryVectorProjectionState::appliedRevision)
                .isEqualTo(2L);
    }

    @Test
    void boundedQueueRejectsWithoutDroppingExistingWork() {
        MemoryItem first = save("first", MemoryStatus.ACTIVE);
        MemoryItem second = save("second", MemoryStatus.ACTIVE);
        MemoryItem third = save("third", MemoryStatus.ACTIVE);
        enqueue(first.id(), null);
        enqueue(second.id(), null);

        assertThatThrownBy(() -> enqueue(third.id(), null))
                .isInstanceOf(MemoryProjectionQueueFullException.class)
                .hasMessageContaining("capacity=2");
        assertThat(store.queueDepth()).isEqualTo(2L);
    }

    @Test
    void enqueueRollsBackWithOwningSqlTransaction() {
        MemoryItem item = save("rollback", MemoryStatus.ACTIVE);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            store.enqueue(item.id(), null, 2);
            throw new IllegalStateException("roll back memory write");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("roll back memory write");

        assertThat(store.queueDepth()).isZero();
    }

    @Test
    void queueFullRollsBackOwningMemoryWriteWithoutDroppingAcceptedWork() {
        MemoryItem first = save("capacity-first", MemoryStatus.ACTIVE);
        MemoryItem second = save("capacity-second", MemoryStatus.ACTIVE);
        enqueue(first.id(), null);
        enqueue(second.id(), null);
        UUID rejectedId = UUID.randomUUID();
        MemoryItem rejected = new MemoryItem(rejectedId,
                MemoryService.deterministicVectorId(MemoryScope.PROJECT, "P", "summary", "capacity-rejected"),
                MemoryScope.PROJECT, "P", MemoryType.DISCOVERY, "summary", "capacity-rejected", List.of(), 0.8,
                MemoryStatus.ACTIVE, MemorySourceType.MANUAL, "test:" + rejectedId, "test", Map.of(),
                Instant.now(), Instant.now(), null, Instant.now(), null);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            memories.save(rejected);
            store.enqueue(rejected.id(), null, 2);
        })).isInstanceOf(MemoryProjectionQueueFullException.class)
                .hasMessageContaining("capacity=2");

        assertThat(memories.findById(rejected.id())).isEmpty();
        assertThat(store.queueDepth()).isEqualTo(2L);
        assertThat(work(first.id()).revision()).isEqualTo(1L);
        assertThat(work(second.id()).revision()).isEqualTo(1L);
    }

    @Test
    void retryExhaustionIsVisibleAsDeadLetterHealth() {
        MemoryItem item = save("dead-letter", MemoryStatus.ACTIVE);
        enqueue(item.id(), null);
        doThrow(new IllegalStateException("permanent sink failure")).when(vectors).upsert(any());
        MemoryVectorProjectionProperties oneAttempt = new MemoryVectorProjectionProperties(
                MemoryVectorProjectionMode.OUTBOX, 2, 10, 1, 1_000L);
        MemoryVectorProjectionCoordinator coordinator = coordinator(oneAttempt);
        MemoryVectorProjectionWorker oneAttemptWorker = new MemoryVectorProjectionWorker(
                store, coordinator, metrics, oneAttempt, transactions);

        oneAttemptWorker.poll();

        assertThat(store.deadLetterCount()).isEqualTo(1L);
        assertThat(new MemoryVectorProjectionHealthIndicator(store, oneAttempt).health().getStatus().getCode())
                .isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void synchronousRevisionLocksAreStableAndScopedPerMemory() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(MemoryVectorProjectionStore.synchronousLockKey(first))
                .isEqualTo(MemoryVectorProjectionStore.synchronousLockKey(first))
                .isNotEqualTo(MemoryVectorProjectionStore.synchronousLockKey(second));
    }

    @Test
    void staleCompletionAndRetryAreBenignWhenWorkWasSuperseded() {
        MemoryItem item = save("superseded", MemoryStatus.ACTIVE);
        enqueue(item.id(), null);
        MemoryVectorProjectionWork stale = work(item.id());
        enqueue(item.id(), null);

        MemoryVectorProjectionStore.CompletionResult completion = transactions.execute(
                status -> store.complete(stale));
        MemoryVectorProjectionStore.RetryResult retry = transactions.execute(
                status -> store.retry(stale, new IllegalStateException("obsolete"), 3));

        assertThat(completion).isEqualTo(MemoryVectorProjectionStore.CompletionResult.SUPERSEDED);
        assertThat(retry).isEqualTo(MemoryVectorProjectionStore.RetryResult.SUPERSEDED);
        assertThat(work(item.id()).revision()).isEqualTo(2L);
        assertThat(work(item.id()).attemptCount()).isZero();
    }

    @Test
    void supersededFailureContinuesPollingWithoutEscapingScheduler() {
        MemoryVectorProjectionStore mockStore = mock(MemoryVectorProjectionStore.class);
        MemoryVectorProjectionCoordinator mockCoordinator = mock(MemoryVectorProjectionCoordinator.class);
        MemoryVectorProjectionMetrics mockMetrics = mock(MemoryVectorProjectionMetrics.class);
        MemoryVectorProjectionWork stale = new MemoryVectorProjectionWork(
                UUID.randomUUID(), 1L, null, Instant.now(), Instant.now(), 0, null, null);
        when(mockStore.lockNextAvailable()).thenReturn(Optional.of(stale), Optional.empty());
        doThrow(new IllegalStateException("revision moved")).when(mockCoordinator).applyCurrent(stale);
        when(mockStore.retry(org.mockito.ArgumentMatchers.eq(stale), any(RuntimeException.class),
                org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(MemoryVectorProjectionStore.RetryResult.SUPERSEDED);
        MemoryVectorProjectionWorker localWorker = new MemoryVectorProjectionWorker(
                mockStore, mockCoordinator, mockMetrics, properties, transactions);

        localWorker.poll();

        verify(mockStore, times(2)).lockNextAvailable();
        verify(mockMetrics).record("superseded");
    }

    private MemoryVectorProjectionCoordinator coordinator(MemoryVectorProjectionProperties config) {
        MemoryEmbeddingProperties embedding = new MemoryEmbeddingProperties(
                "internal", "hashing", "v1", "nfkc-v1", "legacy-v1", "",
                MemoryShadowMode.OFF, "", null, null, null, null, null, "");
        return new MemoryVectorProjectionCoordinator(memories, vectors, store,
                new MemoryVectorProjectionDescriptor(
                        new MemoryEmbeddingContract(new HashingEmbeddingModel(64), embedding),
                        new MemoryRetrievalTextBuilder()),
                metrics, config);
    }

    private MemoryItem save(String text, MemoryStatus status) {
        UUID id = UUID.randomUUID();
        MemoryItem item = new MemoryItem(id,
                MemoryService.deterministicVectorId(MemoryScope.PROJECT, "P", "summary", text),
                MemoryScope.PROJECT, "P", MemoryType.DISCOVERY, "summary", text, List.of(), 0.8,
                status, MemorySourceType.MANUAL, "test:" + id, "test", Map.of(), Instant.now(), Instant.now(),
                null, Instant.now(), null);
        return memories.save(item);
    }

    private void enqueue(UUID memoryId, UUID previousVectorId) {
        transactions.executeWithoutResult(status -> store.enqueue(memoryId, previousVectorId, 2));
    }

    private MemoryVectorProjectionWork work(UUID memoryId) {
        return jdbc.query("""
                SELECT memory_id, revision, previous_vector_id, requested_at, available_at,
                       attempt_count, last_error, dead_lettered_at
                FROM memory_vector_projection_work WHERE memory_id=?
                """, (rs, rowNum) -> new MemoryVectorProjectionWork(
                        rs.getObject("memory_id", UUID.class), rs.getLong("revision"),
                        rs.getObject("previous_vector_id", UUID.class),
                        rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("available_at").toInstant(),
                        rs.getInt("attempt_count"), rs.getString("last_error"),
                        rs.getTimestamp("dead_lettered_at") == null ? null
                                : rs.getTimestamp("dead_lettered_at").toInstant()), memoryId).getFirst();
    }
}
