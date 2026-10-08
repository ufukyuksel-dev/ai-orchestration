package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One bounded pass after startup over STALE project memories: each is observed again with the current code map, so a
 * memory made stale by an older rule (any code change) becomes ACTIVE unless a bound target is really missing.
 * Projects without a completed scan are skipped; the post-scan link run covers them later.
 */
@Component
public class StaleMemoryReconciler {
    private static final Logger log = LoggerFactory.getLogger(StaleMemoryReconciler.class);
    public record Result(int projects, int skippedProjects, int observed, int reactivated) {}
    private final JdbcTemplate jdbc;
    private final MemoryRepository memories;
    private final CodeBaselineRepository baseline;
    private final MemoryCodeLinkResolver resolver;
    private final MemoryLifecycleService lifecycle;
    private final MemoryCodeLinkProperties properties;
    private final boolean enabled;

    public StaleMemoryReconciler(JdbcTemplate jdbc, MemoryRepository memories, CodeBaselineRepository baseline,
            MemoryCodeLinkResolver resolver, MemoryLifecycleService lifecycle, MemoryCodeLinkProperties properties,
            @Value("${ai-orchestration.memory.code-links.reconcile-stale-on-startup:true}") boolean enabled) {
        this.jdbc = jdbc; this.memories = memories; this.baseline = baseline; this.resolver = resolver;
        this.lifecycle = lifecycle; this.properties = properties; this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || !properties.enabled() || !properties.deterministicLinksEnabled()) return;
        Thread thread = new Thread(() -> {
            try { reconcile(); }
            catch (RuntimeException e) { log.warn("Stale memory reconciliation failed", e); }
        }, "stale-memory-reconciler");
        thread.setDaemon(true);
        thread.start();
    }

    public Result reconcile() {
        int projects = 0, skipped = 0, observed = 0, reactivated = 0;
        for (String projectKey : jdbc.queryForList("""
                SELECT DISTINCT project_key FROM memory_items WHERE status='stale' AND scope='project' AND project_key IS NOT NULL
                ORDER BY project_key
                """, String.class)) {
            if (baseline.latestCompletedRun(projectKey).isEmpty()) {
                log.warn("Stale memory reconciliation skipped: projectKey={} reason=no_completed_baseline", projectKey);
                skipped++;
                continue;
            }
            projects++;
            UUID after = null;
            while (true) {
                List<UUID> ids = jdbc.queryForList("""
                        SELECT id FROM memory_items WHERE status='stale' AND scope='project' AND project_key=?
                            AND (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ?
                        """, UUID.class, projectKey, after, after, properties.batchSize());
                if (ids.isEmpty()) break;
                for (UUID id : ids) {
                    MemoryItem item = memories.findById(id).orElse(null);
                    if (item == null) continue;
                    try {
                        observed++;
                        if (lifecycle.observe(item, resolver.resolve(item)) == MemoryLifecycleService.Observation.REVALIDATED) reactivated++;
                    } catch (RuntimeException e) {
                        log.warn("Stale memory reconciliation failed: memoryId={}", id, e);
                    }
                }
                after = ids.getLast();
            }
        }
        if (projects + skipped > 0)
            log.info("Stale memory reconciliation: projects={} skipped={} observed={} reactivated={}", projects, skipped, observed, reactivated);
        return new Result(projects, skipped, observed, reactivated);
    }
}
