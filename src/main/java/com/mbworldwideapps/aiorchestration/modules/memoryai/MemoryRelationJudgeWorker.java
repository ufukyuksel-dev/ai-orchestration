package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.MemoryRelationJudgeProperties;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.*;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MemoryRelationJudgeWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryRelationJudgeWorker.class);
    private final MemoryRepository memories;
    private final MemoryVectorIndex vectors;
    private final RuleMemoryInjectionFilter rules;
    private final MemoryRelationJudgeContract contract;
    private final MemoryRelationJudgmentStore store;
    private final ScannerPayloadRedactor redactor;
    private final MemoryRelationJudgeProperties properties;
    private final ThreadPoolExecutor worker;
    private final ThreadPoolExecutor calls;
    private final TimeBoxedLLMProvider provider;

    public MemoryRelationJudgeWorker(MemoryRepository memories, MemoryVectorIndex vectors, RuleMemoryInjectionFilter rules,
            MemoryRelationJudgeContract contract, MemoryRelationJudgmentStore store, ScannerPayloadRedactor redactor,
            LLMGateway gateway, MemoryRelationJudgeProperties properties) {
        this.memories = memories; this.vectors = vectors; this.rules = rules; this.contract = contract;
        this.store = store; this.redactor = redactor; this.properties = properties;
        worker = executor(new ArrayBlockingQueue<>(properties.queueCapacity()), "memory-relation-judge");
        // One waiting slot absorbs the hand-off between a completed Future and its executor becoming idle.
        calls = executor(new ArrayBlockingQueue<>(1), "memory-relation-judge-provider");
        provider = new TimeBoxedLLMProvider(new LLMProvider() {
            @Override public String id() { return "memory-relation-judge-auto"; }
            @Override public GenerationResponse generate(GenerationRequest request) { return gateway.generate(request); }
        }, properties.timeout().toMillis(), calls);
    }

    private static ThreadPoolExecutor executor(BlockingQueue<Runnable> queue, String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue, task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(MemoryItemChangedEvent event) {
        if (!properties.enabled() || event.item().status() != MemoryStatus.ACTIVE) return;
        UUID source = event.item().id();
        try {
            worker.execute(() -> {
                try { evaluate(source); }
                catch (RuntimeException e) {
                    // Provider/SQL exception text may contain unredacted data; only classify the failure.
                    log.warn("Memory judge job failed: memoryId={} errorType={}", source, e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Memory judge queue rejected: memoryId={} queueSize={}", source, worker.getQueue().size());
        }
    }

    private void evaluate(UUID sourceId) {
        MemoryItem source = memories.findById(sourceId).orElse(null);
        if (!eligible(source, source == null ? null : source.projectKey(), true)) return;
        String query = source.summary() + "\n" + source.text();
        bounded(query);
        query = redactor.redact(query);
        bounded(query);
        var filter = new MemoryEligibilityFilter(source.projectKey(), null, false, false,
                Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE), 0.0, true);
        String expectedSourceHash = hash(source);
        List<ScoredMemoryRef> candidates = vectors.search(query, 6, filter);
        Set<UUID> seen = new HashSet<>();
        int evaluated = 0;
        // The index returns ranked candidates; bound both reads and model calls even for a faulty index adapter.
        for (ScoredMemoryRef candidate : candidates.stream().limit(6).toList()) {
            if (Thread.currentThread().isInterrupted() || evaluated == 5) break;
            if (candidate == null || candidate.item() == null || !Double.isFinite(candidate.similarityScore())) continue;
            UUID targetId = candidate.item().id();
            if (sourceId.equals(targetId) || !seen.add(targetId)) continue;
            MemoryItem current = memories.findById(sourceId).orElse(null);
            if (!eligible(current, source.projectKey(), true) || !hash(current).equals(expectedSourceHash)) break;
            MemoryItem target = memories.findById(targetId).orElse(null);
            if (!filter.accepts(target) || !eligible(target, source.projectKey(), false)) continue;
            evaluated++;
            var guard = new MemoryRelationJudgmentStore.Guard(source.projectKey(), sourceId, targetId,
                    expectedSourceHash, hash(target), MemoryRelationJudgeContract.RUBRIC_VERSION);
            if (store.alreadyEvaluated(guard)) continue;
            judge(guard, current, target);
        }
    }

    private void judge(MemoryRelationJudgmentStore.Guard guard, MemoryItem source, MemoryItem target) {
        String prompt;
        try { prompt = contract.prompt(source, target); }
        catch (IllegalArgumentException e) {
            store.failed(guard, MemoryRelationJudgmentStore.Failure.MALFORMED);
            return;
        }
        GenerationResponse response;
        try {
            response = provider.generate(new GenerationRequest(prompt, source.projectKey(), List.of(), "fresh",
                    null, "", 0, "memory-relation-judge"));
        } catch (LLMProviderTimeoutException e) {
            store.failed(guard, MemoryRelationJudgmentStore.Failure.TIMEOUT);
            return;
        } catch (RuntimeException e) {
            store.failed(guard, MemoryRelationJudgmentStore.Failure.PROVIDER_ERROR);
            return;
        }
        if (response == null || response.degraded()) store.failed(guard, MemoryRelationJudgmentStore.Failure.DEGRADED);
        else store.complete(guard, response.answer(), properties.confidenceThreshold());
    }

    private boolean eligible(MemoryItem item, String project, boolean source) {
        return item != null && project != null && item.scope() == MemoryScope.PROJECT && project.equals(item.projectKey())
                && (item.status() == MemoryStatus.ACTIVE || (!source && item.status() == MemoryStatus.STALE))
                && (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now()))
                && !(item.memoryType() == MemoryType.RULE && item.metadata().containsKey("promotedRuleId"))
                && rules.eligibleForAutomaticInjection(item.id(), item.memoryType());
    }

    private static String hash(MemoryItem item) { return MemoryRelationService.contentHash(item.summary(), item.text()); }
    private static void bounded(String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 16_384)
            throw new IllegalArgumentException("Judge search input exceeds bound");
    }

    @PreDestroy public void close() { worker.shutdownNow(); calls.shutdownNow(); }
}
