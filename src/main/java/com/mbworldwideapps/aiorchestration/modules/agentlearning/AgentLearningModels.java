package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AgentLearningModels {

    private AgentLearningModels() {
    }

    public record WorkspaceBinding(UUID id, String principalKey, String clientId, String projectKey,
            String repositoryFingerprint, Path rootPath, Instant createdAt, Instant expiresAt) {
    }

    public record Target(String targetId, String relativePath, String symbolRef, String role,
            String resolvedHash, Integer startLine, Integer endLine, String locatorKind) {

        public Target {
            locatorKind = locatorKind == null || locatorKind.isBlank()
                    ? symbolRef == null || symbolRef.isBlank() ? "file" : "symbol"
                    : locatorKind;
        }

        public Target(String targetId, String relativePath, String symbolRef, String role,
                String resolvedHash, Integer startLine, Integer endLine) {
            this(targetId, relativePath, symbolRef, role, resolvedHash, startLine, endLine,
                    symbolRef == null || symbolRef.isBlank() ? "file" : "symbol");
        }
    }

    public record ReferenceTarget(String targetId, UUID referenceId, String relativePath,
            String contentHash, String sectionKey, UUID sourceMemoryId) {
    }

    public record ResearchContext(UUID id, UUID workspaceBindingId, String principalKey, String clientId,
            String sessionScopeHash, String projectKey, UUID contextEpoch, String intent, String queryHash, String traceCoverage,
            Instant createdAt, Instant expiresAt, List<Target> targets, List<ReferenceTarget> references) {
        public ResearchContext {
            targets = targets == null ? List.of() : List.copyOf(targets);
            references = references == null ? List.of() : List.copyOf(references);
        }

        public ResearchContext(UUID id, UUID workspaceBindingId, String principalKey, String clientId,
                String projectKey, UUID contextEpoch, String intent, String queryHash, String traceCoverage,
                Instant createdAt, Instant expiresAt, List<Target> targets) {
            this(id, workspaceBindingId, principalKey, clientId, null, projectKey, contextEpoch, intent, queryHash,
                    traceCoverage, createdAt, expiresAt, targets, List.of());
        }

        public ResearchContext(UUID id, UUID workspaceBindingId, String principalKey, String clientId,
                String projectKey, UUID contextEpoch, String intent, String queryHash, String traceCoverage,
                Instant createdAt, Instant expiresAt, List<Target> targets, List<ReferenceTarget> references) {
            this(id, workspaceBindingId, principalKey, clientId, null, projectKey, contextEpoch, intent, queryHash,
                    traceCoverage, createdAt, expiresAt, targets, references);
        }
    }

    public record KnowledgeBinding(String ref, UUID memoryId, long learningRevision, String canonicalHash) {
    }

    public record Observation(UUID id, UUID contextId, String targetId, String operationKind,
            String resourceRef, String rawContentHash, String redactedContentHash, UUID workspaceSnapshotId,
            int byteCount, String provenance, Instant observedAt) {
    }

    public record Knowledge(UUID id, String type, String text, boolean contentComplete,
            String verification, String freshness, Long learningRevision, String canonicalHash) {
        public Knowledge(UUID id, String type, String text, boolean contentComplete,
                String verification, String freshness) {
            this(id, type, text, contentComplete, verification, freshness, null, null);
        }
    }

    public record LearnedAnchor(String relativePath, String symbolRef, String locatorKind,
            String role, int priority, String expectedHash, String resolutionState) {

        public LearnedAnchor(String relativePath, String symbolRef, String role, int priority,
                String expectedHash, String resolutionState) {
            this(relativePath, symbolRef,
                    symbolRef == null || symbolRef.isBlank() ? "file" : "symbol",
                    role, priority, expectedHash, resolutionState);
        }
    }

    public record DiscoveryRoute(UUID memoryId, String summary, String text, String verification, boolean stale,
            long learningRevision, String canonicalHash, List<LearnedAnchor> anchors) {
        public DiscoveryRoute {
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
        }

        public DiscoveryRoute(UUID memoryId, String summary, String text, String verification, boolean stale,
                List<LearnedAnchor> anchors) {
            this(memoryId, summary, text, verification, stale, 1L, null, anchors);
        }
    }

    public record SuggestedTarget(String relativePath, String symbolRef, String role,
            String expectedHash, UUID sourceMemoryId, Integer startLine, Integer endLine) {

        public SuggestedTarget(String relativePath, String symbolRef, String role,
                String expectedHash, UUID sourceMemoryId) {
            this(relativePath, symbolRef, role, expectedHash, sourceMemoryId, null, null);
        }
    }

    public record SuggestedReference(UUID referenceId, String relativePath, String contentHash,
            String sectionKey, UUID sourceMemoryId, String sourceCheck) {
    }

    public record TaskContextPlan(List<Knowledge> knowledge, List<SuggestedTarget> targets,
            List<SuggestedReference> references, List<String> gaps, int omittedKnowledge, int omittedTargets,
            boolean retrievalUnavailable) {
        public TaskContextPlan {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            targets = targets == null ? List.of() : List.copyOf(targets);
            references = references == null ? List.of() : List.copyOf(references);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public TaskContextPlan(List<Knowledge> knowledge, List<SuggestedTarget> targets,
                List<String> gaps, int omittedKnowledge, int omittedTargets, boolean retrievalUnavailable) {
            this(knowledge, targets, List.of(), gaps, omittedKnowledge, omittedTargets, retrievalUnavailable);
        }

        public static TaskContextPlan empty() {
            return new TaskContextPlan(List.of(), List.of(), List.of(), List.of(), 0, 0, false);
        }
    }

    public record ContextReservation(ResearchContext context, UUID learningHandle, UUID requestId,
            String status, List<Knowledge> knowledge, Map<String, String> sourceChecks,
            List<String> gaps, int omittedKnowledge, int omittedTargets) {
        public ContextReservation {
            status = status == null || status.isBlank() ? "MISS" : status;
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            sourceChecks = sourceChecks == null ? Map.of() : Map.copyOf(sourceChecks);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public ContextReservation(ResearchContext context, UUID learningHandle, UUID requestId) {
            this(context, learningHandle, requestId, context.targets().isEmpty() ? "MISS" : "FOCUSED",
                    List.of(), Map.of(), context.targets().isEmpty() ? List.of("no_resolved_hints") : List.of(),
                    0, 0);
        }
    }

    public record OpenedSource(String targetId, UUID evidenceId, String path, int startLine, int endLine,
            String content, boolean contentComplete, boolean changedSinceResolve) {
    }

    public record OpenedReference(String targetId, UUID referenceId, String path, String sectionKey,
            String title, String content, boolean contentComplete, boolean changedSinceResolve,
            String visibleHash) {
    }

    public record OpenedContext(UUID contextId, List<OpenedSource> items, List<OpenedReference> references) {
        public OpenedContext {
            items = items == null ? List.of() : List.copyOf(items);
            references = references == null ? List.of() : List.copyOf(references);
        }

        public OpenedContext(UUID contextId, List<OpenedSource> items) {
            this(contextId, items, List.of());
        }
    }

    public record Anchor(String targetId, String role) {
    }

    /** Agent-facing learning: semantic values and context-local refs only. */
    public record SemanticCandidate(String kind, String summary, String content,
            List<String> anchors, List<String> evidence,
            @JsonProperty(required = false) List<String> appliesWhen,
            @JsonProperty(required = false) List<String> limitations,
            @JsonProperty(required = false) List<String> reusableFor,
            @JsonProperty(required = false) String correctionRef,
            @JsonProperty(required = false) LearningReference reference) {
        public SemanticCandidate {
            anchors = copy(anchors);
            evidence = copy(evidence);
            appliesWhen = copy(appliesWhen);
            limitations = copy(limitations);
            reusableFor = copy(reusableFor);
        }
    }

    public record LearningReference(String details) {
    }

    public record LearningBatch(List<SemanticCandidate> learnings) {
        public LearningBatch {
            learnings = learnings == null ? List.of() : List.copyOf(learnings);
        }
    }

    /** Human-readable code locator supplied by a runtime finalization hook. */
    public record CaptureLocator(String kind, String ref,
            @JsonProperty(required = false) String signature,
            @JsonProperty(required = false) String path,
            @JsonProperty(required = false) String role) {
    }

    /**
     * Final-response sidecar candidate. Runtime and persistence identifiers are
     * intentionally absent; the backend restores them from the verified binding.
     */
    public record CaptureCandidate(String kind, String summary, String content,
            List<CaptureLocator> locators,
            @JsonProperty(required = false) List<String> appliesWhen,
            @JsonProperty(required = false) List<String> limitations,
            @JsonProperty(required = false) List<String> reusableFor,
            @JsonProperty(required = false) String correctionRef,
            @JsonProperty(required = false) LearningReference reference) {
        public CaptureCandidate {
            locators = locators == null ? List.of() : List.copyOf(locators);
            appliesWhen = copy(appliesWhen);
            limitations = copy(limitations);
            reusableFor = copy(reusableFor);
        }

        public CaptureCandidate(String kind, String summary, String content,
                List<CaptureLocator> locators, List<String> appliesWhen,
                List<String> limitations, List<String> reusableFor) {
            this(kind, summary, content, locators, appliesWhen, limitations, reusableFor, null, null);
        }
    }

    /** One idempotent post-turn capture request owned by the runtime/harness. */
    public record LearningCaptureCommand(String taskRunId, String projectKey,
            String workspaceBindingId, List<CaptureCandidate> learningCandidates) {
        public LearningCaptureCommand {
            learningCandidates = learningCandidates == null ? List.of() : List.copyOf(learningCandidates);
        }
    }

    /** Authoritative default learn result. Persistence identifiers are debug-only. */
    public record CompactLearnReceipt(String status, int created, int updated, int reused,
            int rejected, int conflicts, int referencesCreated, int referencesReused) {
    }

    public record Candidate(String kind, String summary, String content, List<String> appliesWhen,
            List<String> limitations, List<Anchor> anchors, List<UUID> evidenceIds, List<String> reusableFor,
            UUID correctionTargetId, Long expectedLearningRevision, String expectedCanonicalHash) {
        public Candidate {
            appliesWhen = copy(appliesWhen);
            limitations = copy(limitations);
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            reusableFor = copy(reusableFor);
        }

        public Candidate(String kind, String summary, String content, List<String> appliesWhen,
                List<String> limitations, List<Anchor> anchors, List<UUID> evidenceIds, List<String> reusableFor) {
            this(kind, summary, content, appliesWhen, limitations, anchors, evidenceIds, reusableFor,
                    null, null, null);
        }
    }

    public record LearningProfile(UUID memoryId, String projectKey, long learningRevision,
            String canonicalHash, String usefulnessState, String semanticIdentity,
            String contentRevision, String evidenceRevision) {
    }

    public record DuplicateCandidate(UUID memoryId, String kind, String summary, String text,
            List<String> anchorSignatures) {
        public DuplicateCandidate {
            anchorSignatures = anchorSignatures == null ? List.of() : List.copyOf(anchorSignatures);
        }
    }

    public record PurgeResult(int operations, int contexts, int bindings) {
        public int total() {
            return operations + contexts + bindings;
        }
    }

    public record CandidateResult(int candidate, String outcome, UUID memoryId, boolean persisted,
            String verification, String indexState, String graphState, String reason) {
    }

    public record LearnReceipt(int schemaVersion, String status, UUID requestId,
            List<CandidateResult> results, UUID nextLearningHandle) {
        public LearnReceipt {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record Operation(UUID requestId, UUID learningHandle, UUID contextId, String principalKey,
            String projectKey, String payloadHash, String state, String resultJson, Instant leaseUntil,
            Instant createdAt, Instant updatedAt, Instant expiresAt) {
    }

    public enum ClaimKind {
        CLAIMED,
        REPLAY,
        QUEUED,
        CONFLICT
    }

    public record OperationClaim(ClaimKind kind, Operation operation) {
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
