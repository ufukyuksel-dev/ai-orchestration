package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Observation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Operation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OperationClaim;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ResearchContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.WorkspaceBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DiscoveryRoute;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningProfile;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.KnowledgeBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.PurgeResult;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DuplicateCandidate;

public interface AgentLearningRepository {

    default List<DiscoveryRoute> findDiscoveryRoutes(String projectKey, List<UUID> memoryIds, int limit) {
        return List.of();
    }

    WorkspaceBinding insertBinding(WorkspaceBinding binding);

    Optional<WorkspaceBinding> findBinding(UUID id);

    ResearchContext insertContext(ResearchContext context, UUID learningHandle, UUID requestId,
            Instant operationExpiresAt);

    /**
     * Atomically creates a hook-owned context and its task-keyed operation. The
     * unique operation key makes duplicate finalization delivery replay-safe.
     */
    default ResearchContext insertCaptureContext(ResearchContext context, Operation operation,
            String sessionScopeHash, String operationKey) {
        insertContext(context, operation.learningHandle(), operation.requestId(), operation.expiresAt());
        return context;
    }

    Operation insertOperation(Operation operation);

    default Operation reserveSemanticOperation(Operation operation, String sessionScopeHash, String operationKey) {
        return insertOperation(operation);
    }

    default Optional<Operation> findOperationByKey(String operationKey) {
        return Optional.empty();
    }

    default Optional<Operation> findLatestUncertainOperation(String principalKey, String projectKey,
            String sessionScopeHash, Instant now) {
        return Optional.empty();
    }

    default Optional<Operation> findLatestSessionOperation(String principalKey, String sessionScopeHash,
            Instant now) {
        return Optional.empty();
    }

    default void replaceCompletedResult(UUID requestId, String resultJson, Instant now) {
    }

    Optional<ResearchContext> findContext(UUID id);

    default Optional<ResearchContext> findActiveContext(String principalKey, String projectKey,
            String clientId, String sessionScopeHash, Instant now) {
        return Optional.empty();
    }

    default Optional<ResearchContext> findLatestActiveContext(String principalKey, String clientId,
            String sessionScopeHash, Instant now) {
        return Optional.empty();
    }

    default void supersedeActiveContexts(String principalKey, String projectKey, String clientId,
            String sessionScopeHash, Instant now) {
    }

    default void insertKnowledgeBindings(UUID contextId, List<KnowledgeBinding> bindings) {
    }

    default List<KnowledgeBinding> findKnowledgeBindings(UUID contextId) {
        return List.of();
    }

    Observation insertObservation(Observation observation);

    List<Observation> findObservations(UUID contextId, List<UUID> evidenceIds);

    default List<Observation> findLatestObservations(UUID contextId, List<String> targetIds) {
        return List.of();
    }

    OperationClaim claim(UUID learningHandle, UUID contextId, String principalKey, String projectKey,
            String payloadHash, Instant leaseUntil, Instant now);

    Optional<Operation> findOperation(UUID requestId, UUID learningHandle);

    void complete(UUID requestId, String payloadHash, String resultJson, Instant now);

    void retryableError(UUID requestId, String payloadHash, String resultJson, Instant now);

    void lockCanonical(String projectKey, String canonicalHash);

    Optional<UUID> findCanonicalMemory(String projectKey, String canonicalHash);

    default List<DuplicateCandidate> findAnchorDuplicateCandidates(String projectKey,
            List<String> canonicalRefs, int limit) {
        return List.of();
    }

    Optional<LearningProfile> lockLearningProfile(UUID memoryId, String projectKey);

    void insertLearningMetadata(UUID memoryId, String projectKey, String canonicalHash, UUID contextId,
            String principalKey, String producerRuntime, String producerModel, String captureCoverage,
            Candidate candidate, List<Target> targets, List<Observation> evidence);

    void replaceLearningMetadata(UUID memoryId, String projectKey, long expectedRevision,
            String expectedCanonicalHash, String canonicalHash, UUID contextId, String principalKey,
            String producerRuntime, String producerModel, String captureCoverage,
            Candidate candidate, List<Target> targets, List<Observation> evidence);

    default void updateIdentityRevisions(UUID memoryId, String semanticIdentity,
            String contentRevision, String evidenceRevision) {
    }

    default void refreshLearningEvidence(UUID memoryId, String semanticIdentity,
            String evidenceRevision, List<Observation> evidence) {
    }

    void markLearningProfileStale(UUID memoryId);

    PurgeResult purgeExpired(Instant now, int batchSize);
}
