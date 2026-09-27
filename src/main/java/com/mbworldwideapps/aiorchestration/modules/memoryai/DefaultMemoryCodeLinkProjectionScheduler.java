package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultMemoryCodeLinkProjectionScheduler implements MemoryCodeLinkProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryCodeLinkProjectionScheduler.class);

    private final MemoryCodeLinkProperties properties;
    private final MemoryCodeLinkProjectionService projectionService;
    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicReference<MemoryCodeLinkProjectionStatus> status =
            new AtomicReference<>(MemoryCodeLinkProjectionStatus.disabled());

    public DefaultMemoryCodeLinkProjectionScheduler(MemoryCodeLinkProperties properties,
            MemoryCodeLinkProjectionService projectionService) {
        this.properties = properties;
        this.projectionService = projectionService;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), runnable -> {
            Thread thread = new Thread(runnable, "memory-code-link-projection");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void linkMemory(UUID memoryId) {
        if (!enabled()) {
            status.set(MemoryCodeLinkProjectionStatus.disabled());
            return;
        }
        status.set(MemoryCodeLinkProjectionStatus.queued(memoryId, "", droppedCount.get()));
        enqueue(memoryId, "", () -> projectionService.linkMemory(memoryId));
    }

    @Override
    public void linkAll(String projectKey) {
        if (!enabled()) {
            status.set(MemoryCodeLinkProjectionStatus.disabled());
            return;
        }
        String effectiveProjectKey = projectKey == null ? "" : projectKey.trim();
        status.set(MemoryCodeLinkProjectionStatus.queued(null, effectiveProjectKey, droppedCount.get()));
        enqueue(null, effectiveProjectKey, () -> projectionService.linkAll(effectiveProjectKey));
    }

    @Override
    public MemoryCodeLinkProjectionStatus status() {
        return status.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void enqueue(UUID memoryId, String projectKey, ProjectionTask task) {
        try {
            executor.execute(() -> runProjection(memoryId, projectKey, task));
        } catch (RejectedExecutionException e) {
            long dropped = droppedCount.incrementAndGet();
            status.set(MemoryCodeLinkProjectionStatus.rejected(memoryId, projectKey, dropped,
                    "memory_code_link_queue_full"));
            log.warn("Memory-code link projection queue is full; dropping request: memoryId={} projectKey={}",
                    memoryId, projectKey);
        }
    }

    private void runProjection(UUID memoryId, String projectKey, ProjectionTask task) {
        Instant started = Instant.now();
        status.set(MemoryCodeLinkProjectionStatus.running(memoryId, projectKey, started, droppedCount.get()));
        try {
            MemoryCodeLinkProjectionResult result = task.run();
            Instant completed = Instant.now();
            if (result.skipped()) {
                status.set(new MemoryCodeLinkProjectionStatus(properties.enabled(), "skipped",
                        memoryId, projectKey, result.projectionRunId(), started, completed, result,
                        droppedCount.get(), result.degradedReason()));
            } else if (!result.available()) {
                status.set(MemoryCodeLinkProjectionStatus.failed(memoryId, projectKey, started, completed,
                        droppedCount.get(), result.degradedReason()));
            } else {
                status.set(MemoryCodeLinkProjectionStatus.completed(result, started, completed, droppedCount.get()));
            }
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Memory-code link projection failed: memoryId={} projectKey={}", memoryId, projectKey, e);
            status.set(MemoryCodeLinkProjectionStatus.failed(memoryId, projectKey, started, Instant.now(),
                    droppedCount.get(), e.getClass().getSimpleName()));
        }
    }

    private boolean enabled() {
        return properties.enabled();
    }

    @FunctionalInterface
    private interface ProjectionTask {
        MemoryCodeLinkProjectionResult run();
    }
}
