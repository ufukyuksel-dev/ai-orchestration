package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bounded, content-free telemetry for learning cost and outcomes. */
@Component
public final class AgentLearningMetrics {

    private final Counter toolCalls;
    private final Counter learnCalls;
    private final DistributionSummary candidates;
    private final DistributionSummary payloadTokens;
    private final DistributionSummary responseTokens;
    private final Counter duplicates;
    private final Counter reused;
    private final Counter referencesCreated;
    private final Counter evidenceReadsAvoided;
    private final Counter validationRetries;
    private final Counter conflicts;
    private final Counter contentDiffersWithoutEvidenceChange;

    public AgentLearningMetrics(MeterRegistry registry) {
        toolCalls = registry.counter("learning.tool_calls_per_task");
        learnCalls = registry.counter("learning.learn_calls_per_task");
        candidates = registry.summary("learning.candidates_per_batch");
        payloadTokens = registry.summary("learning.payload_tokens");
        responseTokens = registry.summary("learning.response_tokens");
        duplicates = registry.counter("learning.duplicate_rate");
        reused = registry.counter("learning.reused_rate");
        referencesCreated = registry.counter("learning.reference_creation_rate");
        evidenceReadsAvoided = registry.counter("learning.evidence_duplicate_read_rate");
        validationRetries = registry.counter("learning.validation_retry_rate");
        conflicts = registry.counter("learning.conflict_rate");
        contentDiffersWithoutEvidenceChange = registry.counter(
                "learning.content_differs_without_evidence_change");
    }

    public void toolCall() { toolCalls.increment(); }
    public void evidenceReadAvoided() { evidenceReadsAvoided.increment(); }
    public void validationFailure() { validationRetries.increment(); }
    public void contentDiffersWithoutEvidenceChange() { contentDiffersWithoutEvidenceChange.increment(); }

    public void learnCall(int candidateCount, int payloadTokenCount) {
        learnCalls.increment();
        candidates.record(candidateCount);
        payloadTokens.record(payloadTokenCount);
    }

    public void receipt(AgentLearningModels.CompactLearnReceipt receipt, int responseTokenCount) {
        responseTokens.record(responseTokenCount);
        duplicates.increment(receipt.reused());
        reused.increment(receipt.reused());
        referencesCreated.increment(receipt.referencesCreated());
        conflicts.increment(receipt.conflicts());
    }
}
