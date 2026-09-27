package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class MemoryVectorProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorProjectionWorker.class);

    private final MemoryVectorProjectionStore store;
    private final MemoryVectorProjectionCoordinator coordinator;
    private final MemoryVectorProjectionMetrics metrics;
    private final MemoryVectorProjectionProperties properties;
    private final TransactionTemplate transactions;

    MemoryVectorProjectionWorker(MemoryVectorProjectionStore store,
            MemoryVectorProjectionCoordinator coordinator,
            MemoryVectorProjectionMetrics metrics,
            MemoryVectorProjectionProperties properties,
            TransactionTemplate transactions) {
        this.store = store;
        this.coordinator = coordinator;
        this.metrics = metrics;
        this.properties = properties;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${ai-orchestration.memory.projection.poll-interval-ms:1000}")
    public void poll() {
        if (properties.mode() != MemoryVectorProjectionMode.OUTBOX) {
            return;
        }
        for (int index = 0; index < properties.batchSize(); index++) {
            Boolean found = transactions.execute(status -> processOne());
            if (!Boolean.TRUE.equals(found)) {
                return;
            }
        }
    }

    private boolean processOne() {
        MemoryVectorProjectionWork work = store.lockNextAvailable().orElse(null);
        if (work == null) {
            return false;
        }
        Instant started = Instant.now();
        try {
            coordinator.applyCurrent(work);
            MemoryVectorProjectionStore.CompletionResult result = store.complete(work);
            String outcome = result == MemoryVectorProjectionStore.CompletionResult.SUPERSEDED
                    ? "superseded" : "success";
            if (result == MemoryVectorProjectionStore.CompletionResult.SUPERSEDED) {
                metrics.record("superseded");
            }
            metrics.latency(Duration.between(started, Instant.now()), outcome);
        } catch (RuntimeException failure) {
            MemoryVectorProjectionStore.RetryResult result = store.retry(work, failure, properties.maxAttempts());
            String outcome = switch (result) {
                case DEAD_LETTER -> "dead_letter";
                case RETRY -> "retry";
                case SUPERSEDED -> "superseded";
            };
            metrics.record(outcome);
            metrics.latency(Duration.between(started, Instant.now()), outcome);
            if (result != MemoryVectorProjectionStore.RetryResult.SUPERSEDED) {
                log.warn("Memory vector projection {}: memoryId={} revision={} errorType={}",
                        outcome, work.memoryId(), work.revision(), failure.getClass().getSimpleName());
            }
        }
        return true;
    }
}
