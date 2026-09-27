package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MemoryVectorIndexBackfill {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorIndexBackfill.class);

    private final MemoryRepository repository;
    private final MemoryVectorIndex vectorIndex;
    private final boolean enabled;

    public MemoryVectorIndexBackfill(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            @Value("${ai-orchestration.memory.backfill-on-startup:false}") boolean enabled) {
        this.repository = repository;
        this.vectorIndex = vectorIndex;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnReady() {
        backfill();
    }

    public BackfillResult backfill() {
        if (!enabled) {
            log.info("memory vector backfill skipped: disabled by property");
            return new BackfillResult(false, 0, 0, 0, 0, "disabled");
        }
        Instant started = Instant.now();
        List<MemoryItem> items;
        try {
            items = repository.listByStatuses(Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE));
        } catch (RuntimeException e) {
            log.error("memory vector backfill aborted: repository list failed", e);
            return new BackfillResult(true, 0, 0, 0, 0,
                    "repository_list_failed:" + e.getClass().getSimpleName());
        }
        int succeeded = 0;
        int failed = 0;
        for (MemoryItem item : items) {
            try {
                vectorIndex.upsert(item);
                succeeded++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("memory vector backfill failed for item {}: {}", item.id(), e.getMessage());
            }
        }
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();
        log.info("memory vector backfill complete: total={}, upserted={}, failed={}, elapsedMs={}",
                items.size(), succeeded, failed, elapsedMs);
        return new BackfillResult(true, items.size(), succeeded, failed, elapsedMs,
                failed == 0 ? "" : "partial_failures:" + failed);
    }

    public record BackfillResult(
            boolean enabled,
            int total,
            int upserted,
            int failed,
            long elapsedMs,
            String warning) {

        public String readinessNote() {
            if (!enabled) {
                return "memory_vector_backfill=disabled";
            }
            String note = "memory_vector_backfill=enabled,total=%d,upserted=%d,failed=%d,elapsedMs=%d"
                    .formatted(total, upserted, failed, elapsedMs);
            return warning == null || warning.isBlank() ? note : note + ",warning=" + warning;
        }
    }
}
