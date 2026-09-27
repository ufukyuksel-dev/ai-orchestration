package com.mbworldwideapps.aiorchestration.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.learning")
public record AgentLearningProperties(
        boolean enabled,
        boolean captureEnabled,
        boolean navigationEnabled,
        boolean shadowMode,
        int maxCandidatesPerWrite,
        int maxEvidencePerCandidate,
        int defaultContextTokenBudget,
        int maxContextTokenBudget,
        int maxSourceTargetsPerOpen,
        int researchContextTtlHours,
        int operationRetentionDays,
        int operationLeaseSeconds,
        int maxSourceFileBytes,
        int cleanupBatchSize,
        long cleanupIntervalMs) {

    @ConstructorBinding
    public AgentLearningProperties {
        if (maxCandidatesPerWrite <= 0) maxCandidatesPerWrite = 3;
        if (maxCandidatesPerWrite > 3) maxCandidatesPerWrite = 3;
        if (maxEvidencePerCandidate <= 0) maxEvidencePerCandidate = 12;
        if (maxEvidencePerCandidate > 12) maxEvidencePerCandidate = 12;
        if (defaultContextTokenBudget <= 0) defaultContextTokenBudget = 1200;
        if (maxContextTokenBudget < defaultContextTokenBudget) maxContextTokenBudget = 4000;
        if (maxSourceTargetsPerOpen <= 0) maxSourceTargetsPerOpen = 3;
        if (maxSourceTargetsPerOpen > 3) maxSourceTargetsPerOpen = 3;
        if (researchContextTtlHours <= 0) researchContextTtlHours = 24;
        if (operationRetentionDays <= 0) operationRetentionDays = 7;
        if (operationLeaseSeconds <= 0) operationLeaseSeconds = 300;
        if (maxSourceFileBytes <= 0) maxSourceFileBytes = 1_048_576;
        if (cleanupBatchSize <= 0) cleanupBatchSize = 500;
        if (cleanupBatchSize > 1000) cleanupBatchSize = 1000;
        if (cleanupIntervalMs <= 0) cleanupIntervalMs = 3_600_000;
    }

    public Duration contextTtl() {
        return Duration.ofHours(researchContextTtlHours);
    }

    public Duration operationRetention() {
        return Duration.ofDays(operationRetentionDays);
    }

    public Duration operationLease() {
        return Duration.ofSeconds(operationLeaseSeconds);
    }
}
