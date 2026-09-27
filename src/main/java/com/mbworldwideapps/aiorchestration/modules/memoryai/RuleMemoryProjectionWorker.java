package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotently refreshes the vector payload of promoted rule origins. Progress is durable per work item.
 */
@Component
public class RuleMemoryProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(RuleMemoryProjectionWorker.class);
    private static final int MAX_BATCH = 20;

    private final RuleMemoryProjectionOutbox outbox;
    private final MemoryRepository memoryRepository;
    private final MemoryVectorIndex vectorIndex;
    private final TransactionTemplate transactions;

    @Autowired
    public RuleMemoryProjectionWorker(RuleMemoryProjectionOutbox outbox, MemoryRepository memoryRepository,
            MemoryVectorIndex vectorIndex, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.memoryRepository = memoryRepository;
        this.vectorIndex = vectorIndex;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${ai-orchestration.rules.projection-poll-interval-ms:1000}")
    public void poll() {
        for (int index = 0; index < MAX_BATCH; index++) {
            Boolean found = transactions.execute(status -> processOne());
            if (!Boolean.TRUE.equals(found)) {
                return;
            }
        }
    }

    private boolean processOne() {
        Optional<RuleMemoryProjectionWork> candidate = outbox.lockNextAvailable();
        if (candidate.isEmpty()) {
            return false;
        }
        RuleMemoryProjectionWork work = candidate.orElseThrow();
        try {
            MemoryItem item = memoryRepository.findById(work.memoryId())
                    .orElseThrow(() -> new IllegalStateException(
                            "projection memory is missing: " + work.memoryId()));
            boolean vectorProjected = work.vectorProjected();
            boolean graphMarked = work.graphMarked();
            boolean semanticMarked = work.semanticMarked();

            if (!vectorProjected) {
                if (item.status() == MemoryStatus.ACTIVE || item.status() == MemoryStatus.STALE) {
                    vectorIndex.upsert(authorityProjection(item, work));
                } else {
                    vectorIndex.delete(item);
                }
                outbox.markVectorProjected(work);
                vectorProjected = true;
            }

            // Graph and semantic-anchor projections were retired with Neo4j; the vector index is the only
            // projection left, so those markers complete immediately.
            if (!graphMarked) {
                outbox.markGraphMarked(work);
                graphMarked = true;
            }
            if (!semanticMarked) {
                outbox.markSemanticMarked(work);
                semanticMarked = true;
            }

            if (vectorProjected && graphMarked && semanticMarked) {
                outbox.complete(work);
            }
        } catch (RuntimeException failure) {
            outbox.retryLater(work, failure);
            log.warn("Rule memory projection will retry: memoryId={} ruleId={} version={}",
                    work.memoryId(), work.ruleId(), work.ruleVersion(), failure);
        }
        return true;
    }

    private static MemoryItem authorityProjection(MemoryItem item, RuleMemoryProjectionWork work) {
        Map<String, Object> metadata = new LinkedHashMap<>(item.metadata());
        metadata.put("promotedRuleId", work.ruleId().toString());
        metadata.put("promotedRuleVersion", work.ruleVersion());
        return new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(), item.memoryType(),
                item.summary(), item.text(), item.tags(), item.confidence(), item.status(), item.sourceType(),
                item.sourceRef(), item.owner(), metadata, item.createdAt(), item.updatedAt(), item.lastUsedAt(),
                item.lastVerifiedAt(), item.expiresAt());
    }
}
