package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {

    private final MemoryRepository repository;
    private final AiOrchestrationProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final PolicyEngine policyEngine;
    private final PiiScrubber piiScrubber;
    private final MemoryVectorIndex memoryVectorIndex;
    private final RuleMemoryActivationPolicy ruleActivationPolicy;
    private final RuleMemoryLinkLookup ruleMemoryLinkLookup;

    @Autowired
    public MemoryService(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher eventPublisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            MemoryVectorIndex memoryVectorIndex, RuleMemoryActivationPolicy ruleActivationPolicy,
            RuleMemoryLinkLookup ruleMemoryLinkLookup) {
        this.repository = repository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.policyEngine = policyEngine;
        this.piiScrubber = piiScrubber;
        this.memoryVectorIndex = Objects.requireNonNull(memoryVectorIndex, "memoryVectorIndex");
        this.ruleActivationPolicy = Objects.requireNonNull(ruleActivationPolicy, "ruleActivationPolicy");
        this.ruleMemoryLinkLookup = Objects.requireNonNull(ruleMemoryLinkLookup, "ruleMemoryLinkLookup");
    }

    @Transactional
    public MemoryItem create(CreateMemoryRequest request) {
        validateCreate(request, false);
        return createValidated(request);
    }

    /** Dedicated admission sink for evidence-checked memory.learn discoveries. */
    @Transactional(noRollbackFor = MemoryPolicyViolationException.class)
    public MemoryItem createDiscovery(CreateMemoryRequest request) {
        validateCreate(request, true);
        if (request.memoryType() != MemoryType.DISCOVERY) {
            throw new IllegalArgumentException("createDiscovery accepts only discovery memory");
        }
        if (request.scope() != MemoryScope.PROJECT || request.status() != MemoryStatus.ACTIVE) {
            throw new IllegalArgumentException("discovery admission requires active project memory");
        }
        if (request.sourceRef() == null || !request.sourceRef().startsWith("learning:")) {
            throw new IllegalArgumentException("discovery admission requires a learning sourceRef");
        }
        return createValidated(request);
    }

    /** Evidence-backed in-place revision used only after AgentLearningService CAS validation. */
    @Transactional(noRollbackFor = MemoryPolicyViolationException.class)
    public MemoryItem correctDiscovery(UUID memoryId, String expectedProjectKey, CreateMemoryRequest request) {
        validateCreate(request, true);
        if (request.memoryType() != MemoryType.DISCOVERY
                || request.scope() != MemoryScope.PROJECT
                || request.status() != MemoryStatus.ACTIVE) {
            throw new IllegalArgumentException("discovery correction requires active project discovery memory");
        }
        String projectKey = normalizeProjectKey(expectedProjectKey);
        if (projectKey == null || !projectKey.equals(normalizeProjectKey(request.projectKey()))) {
            throw new IllegalArgumentException("DISCOVERY_CORRECTION_PROJECT_MISMATCH");
        }
        if (request.sourceRef() == null || !request.sourceRef().startsWith("learning:")) {
            throw new IllegalArgumentException("discovery correction requires a learning sourceRef");
        }
        MemoryItem existing = repository.findByIdForUpdate(memoryId)
                .orElseThrow(() -> new MemoryNotFoundException(memoryId));
        if (existing.memoryType() != MemoryType.DISCOVERY
                || existing.scope() != MemoryScope.PROJECT
                || !projectKey.equals(existing.projectKey())) {
            throw new IllegalArgumentException("DISCOVERY_CORRECTION_TARGET_INVALID");
        }
        enforcePolicy(request);
        Instant now = Instant.now();
        List<String> tags = normalizeTags(request.tags());
        double confidence = request.confidence() == null
                ? properties.memory().defaultConfidence() : request.confidence();
        Map<String, Object> metadata = request.metadata() == null
                ? Map.of() : new LinkedHashMap<>(request.metadata());
        MemoryItem updated = new MemoryItem(existing.id(),
                deterministicVectorId(MemoryScope.PROJECT, projectKey, request.summary(), request.text()),
                MemoryScope.PROJECT, projectKey, MemoryType.DISCOVERY,
                request.summary().trim(), request.text().trim(), tags, confidence, MemoryStatus.ACTIVE,
                request.sourceType(), blankToNull(request.sourceRef()), blankToNull(request.owner()), metadata,
                existing.createdAt(), now, existing.lastUsedAt(), request.lastVerifiedAt(), null);
        repository.update(updated);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), existing.id(), MemoryEventType.UPDATED,
                correctionEventMetadata(existing, updated), now));
        eventPublisher.publishEvent(new MemoryItemChangedEvent(updated,
                MemoryItemChangedEvent.Origin.LEARNING_CORRECTION, existing));
        return updated;
    }

    private MemoryItem createValidated(CreateMemoryRequest request) {
        enforcePolicy(request);
        Instant now = Instant.now();
        List<String> tags = normalizeTags(request.tags());
        double confidence = request.confidence() == null ? properties.memory().defaultConfidence() : request.confidence();
        MemoryStatus status = request.status() == null ? MemoryStatus.PENDING_REVIEW : request.status();
        ruleActivationPolicy.assertActivationAllowed(request.memoryType(), null, status);
        String projectKey = normalizeProjectKey(request.projectKey());
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata());
        UUID id = UUID.randomUUID();
        UUID vectorId = deterministicVectorId(request.scope(), projectKey, request.summary(), request.text());

        MemoryItem item = new MemoryItem(
                id,
                vectorId,
                request.scope(),
                projectKey,
                request.memoryType(),
                request.summary().trim(),
                request.text().trim(),
                tags,
                confidence,
                status,
                request.sourceType(),
                blankToNull(request.sourceRef()),
                blankToNull(request.owner()),
                metadata,
                now,
                now,
                null,
                request.lastVerifiedAt(),
                request.expiresAt());
        repository.save(item);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.CREATED,
                createdEventMetadata(item), now));
        if (status == MemoryStatus.PENDING_REVIEW) {
            repository.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), id, reviewReason(confidence),
                    ReviewStatus.OPEN, reviewQueueMetadata(item), now, null));
        }
        eventPublisher.publishEvent(new MemoryItemChangedEvent(item));
        return item;
    }

    @Transactional
    public CuratedMemoryUpsertResult upsertCurated(CreateMemoryRequest request, String contentHash) {
        validateCreate(request, false);
        enforcePolicy(request);
        if (request.sourceRef() == null || request.sourceRef().isBlank()) {
            throw new IllegalArgumentException("sourceRef is required for curated memory upsert");
        }
        String normalizedHash = blankToNull(contentHash);
        if (normalizedHash == null) {
            throw new IllegalArgumentException("contentHash is required for curated memory upsert");
        }
        return repository.findBySourceRefForUpdate(request.sourceRef())
                .map(existing -> upsertExistingCurated(existing, request, normalizedHash))
                .orElseGet(() -> new CuratedMemoryUpsertResult(CuratedMemoryUpsertAction.LOADED,
                        create(coerceCuratedStatus(request))));
    }

    /**
     * Curated loads never fail on the rule chokepoint; a would-be ACTIVE RULE
     * becomes a PENDING_REVIEW promotion candidate instead.
     */
    private CreateMemoryRequest coerceCuratedStatus(CreateMemoryRequest request) {
        MemoryStatus requested = request.status() == null ? MemoryStatus.ACTIVE : request.status();
        MemoryStatus effective = ruleActivationPolicy.coerceCandidateStatus(request.memoryType(), requested);
        if (effective == requested) {
            return request;
        }
        return new CreateMemoryRequest(request.scope(), request.projectKey(), request.memoryType(),
                request.summary(), request.text(), request.tags(), request.confidence(), effective,
                request.sourceType(), request.sourceRef(), request.owner(), request.metadata(),
                request.lastVerifiedAt(), request.expiresAt());
    }

    public MemoryItem findById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new MemoryNotFoundException(id));
    }

    public Optional<MemoryItem> findBySourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySourceRef(sourceRef.trim());
    }

    public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
        return repository.list(scope, status, normalizeProjectKey(projectKey));
    }

    public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey, int limit, int offset) {
        int effectiveLimit = Math.min(Math.max(1, limit), 100);
        int effectiveOffset = Math.max(0, offset);
        return repository.list(scope, status, normalizeProjectKey(projectKey), effectiveLimit, effectiveOffset);
    }

    @Transactional
    public MemoryItem updateStatus(UUID id, MemoryStatus status, String actor, String reason) {
        MemoryItem existing = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new MemoryNotFoundException(id));
        if (existing.memoryType() == MemoryType.RULE
                && status != existing.status()
                && ruleMemoryLinkLookup.hasLinkedDefinition(existing.id())) {
            throw new IllegalArgumentException(
                    "RULE memory linked to immutable rule history is lifecycle-managed by rules tools");
        }
        ruleActivationPolicy.assertActivationAllowed(existing.memoryType(), existing.status(), status);
        if (!repository.updateStatus(id, status)) {
            throw new MemoryNotFoundException(id);
        }
        MemoryItem updated = findById(id);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.STATUS_CHANGED,
                statusChangedMetadata(existing.status(), status, actor, reason), Instant.now()));
        eventPublisher.publishEvent(new MemoryItemChangedEvent(updated, existing));
        return updated;
    }

    public List<MemoryEvent> eventsForMemory(UUID memoryId) {
        return repository.eventsForMemory(memoryId);
    }

    public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
        return repository.reviewQueueForMemory(memoryId);
    }

    public AiOrchestrationProperties.Memory memoryConfig() {
        return properties.memory();
    }

    private CuratedMemoryUpsertResult upsertExistingCurated(
            MemoryItem existing, CreateMemoryRequest request, String contentHash) {
        String existingHash = existing.metadata().get("contentHash") instanceof String hash ? hash : null;
        boolean linkedRuleAuthority = existing.memoryType() == MemoryType.RULE
                && ruleMemoryLinkLookup.hasLinkedDefinition(existing.id());
        if ((ruleActivationPolicy.enabled() || linkedRuleAuthority)
                && existing.memoryType() != request.memoryType()
                && (existing.memoryType() == MemoryType.RULE || request.memoryType() == MemoryType.RULE)) {
            throw new IllegalArgumentException(
                    "curated RULE memory type cannot be changed in place; create a separate candidate");
        }
        if (contentHash.equals(existingHash)) {
            return new CuratedMemoryUpsertResult(CuratedMemoryUpsertAction.SKIPPED, existing);
        }
        if ((ruleActivationPolicy.enabled() || linkedRuleAuthority) && existing.memoryType() == MemoryType.RULE
                && (linkedRuleAuthority || existing.status() == MemoryStatus.ACTIVE)) {
            return sourceDriftRevisionCandidate(existing, request, contentHash);
        }

        Instant now = Instant.now();
        String projectKey = normalizeProjectKey(request.projectKey());
        List<String> tags = normalizeTags(request.tags());
        double confidence = request.confidence() == null ? properties.memory().defaultConfidence() : request.confidence();
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata());
        UUID vectorId = deterministicVectorId(request.scope(), projectKey, request.summary(), request.text());
        MemoryItem updated = new MemoryItem(
                existing.id(),
                vectorId,
                request.scope(),
                projectKey,
                request.memoryType(),
                request.summary().trim(),
                request.text().trim(),
                tags,
                confidence,
                ruleActivationPolicy.coerceCandidateStatus(request.memoryType(),
                        request.status() == null ? MemoryStatus.ACTIVE : request.status()),
                request.sourceType(),
                blankToNull(request.sourceRef()),
                blankToNull(request.owner()),
                metadata,
                existing.createdAt(),
                now,
                existing.lastUsedAt(),
                request.lastVerifiedAt(),
                request.expiresAt());
        repository.update(updated);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), existing.id(), MemoryEventType.UPDATED,
                updatedEventMetadata(existing, updated, existingHash, contentHash), now));
        eventPublisher.publishEvent(new MemoryItemChangedEvent(updated, existing));
        return new CuratedMemoryUpsertResult(CuratedMemoryUpsertAction.UPDATED, updated);
    }

    /**
     * A promoted/active curated RULE whose source file changed is never mutated
     * in place: the active evidence stays as approved and the drift becomes a
     * separate PENDING_REVIEW revision candidate for rules.promote.
     */
    private CuratedMemoryUpsertResult sourceDriftRevisionCandidate(
            MemoryItem existing, CreateMemoryRequest request, String contentHash) {
        Map<String, Object> metadata = request.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.metadata());
        metadata.put("revisionOf", existing.id().toString());
        metadata.put("sourceDrift", true);
        metadata.put("contentHash", contentHash);
        // Keep the approved row's sourceRef stable. A full SHA-256 over length-prefixed
        // identity fields gives retries one deterministic candidate without trusting a
        // caller-supplied hash prefix as a unique identifier.
        String sourceRef = request.sourceRef().trim();
        String revisionIdentity = lengthPrefixed(sourceRef)
                + lengthPrefixed(existing.id().toString())
                + lengthPrefixed(contentHash)
                + lengthPrefixed(request.scope().value())
                + lengthPrefixed(normalizeProjectKey(request.projectKey()))
                + lengthPrefixed(request.memoryType().value())
                + lengthPrefixed(request.summary().trim())
                + lengthPrefixed(request.text().trim());
        String revisionRef = sourceRef + "#revision-" + RuleMemoryContentHash.sha256(revisionIdentity);
        CreateMemoryRequest candidate = new CreateMemoryRequest(request.scope(), request.projectKey(),
                request.memoryType(), request.summary(), request.text(), request.tags(), request.confidence(),
                MemoryStatus.PENDING_REVIEW, request.sourceType(), revisionRef, request.owner(), metadata,
                request.lastVerifiedAt(), request.expiresAt());
        return repository.findBySourceRef(revisionRef)
                .map(existingCandidate -> {
                    if (!matchesRevisionCandidate(existingCandidate, existing, candidate, contentHash)) {
                        throw new IllegalStateException(
                                "curated RULE revision identity collision for sourceRef " + revisionRef);
                    }
                    return new CuratedMemoryUpsertResult(CuratedMemoryUpsertAction.SKIPPED, existingCandidate);
                })
                .orElseGet(() -> new CuratedMemoryUpsertResult(CuratedMemoryUpsertAction.LOADED,
                        create(candidate)));
    }

    private static boolean matchesRevisionCandidate(MemoryItem actual, MemoryItem origin,
            CreateMemoryRequest expected, String contentHash) {
        return actual.scope() == expected.scope()
                && Objects.equals(actual.projectKey(), normalizeProjectKey(expected.projectKey()))
                && actual.memoryType() == expected.memoryType()
                && actual.summary().equals(expected.summary().trim())
                && actual.text().equals(expected.text().trim())
                && Objects.equals(actual.sourceRef(), expected.sourceRef())
                && Objects.equals(actual.metadata().get("revisionOf"), origin.id().toString())
                && Objects.equals(actual.metadata().get("sourceDrift"), true)
                && Objects.equals(actual.metadata().get("contentHash"), contentHash);
    }

    private static String lengthPrefixed(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() + ":" + normalized;
    }

    static UUID deterministicVectorId(MemoryScope scope, String projectKey, String summary, String text) {
        String key = "memory-vector:v1:%s:%s:%s:%s".formatted(
                scope.value(),
                projectKey == null ? "" : projectKey,
                normalizeForVectorKey(summary),
                normalizeForVectorKey(text));
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateCreate(CreateMemoryRequest request, boolean allowDiscovery) {
        if (!allowDiscovery && request.memoryType() == MemoryType.DISCOVERY) {
            throw new IllegalArgumentException("generic memory create does not accept discovery; use memory.learn");
        }
        if ((request.scope() == MemoryScope.PROJECT || request.scope() == MemoryScope.USER)
                && blankToNull(request.projectKey()) == null) {
            throw new IllegalArgumentException("projectKey is required for " + request.scope().value() + " memory");
        }
    }

    private void enforcePolicy(CreateMemoryRequest request) {
        piiScrubber.firstMatchReason(request.text()).ifPresent(reason -> {
            throw new MemoryPolicyViolationException("pii-" + reason);
        });
        PolicyDecision writeDecision = policyEngine.evaluateMemoryWrite(request.sourceType(), request.scope());
        if (!writeDecision.allowed()) {
            throw new MemoryPolicyViolationException(writeDecision.reason());
        }
        PolicyDecision contentDecision = policyEngine.evaluateMemoryContent(request.text());
        if (!contentDecision.allowed()) {
            throw new MemoryPolicyViolationException(contentDecision.reason());
        }
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String normalizeProjectKey(String projectKey) {
        return blankToNull(projectKey) == null ? null : projectKey.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeForVectorKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static ReviewReason reviewReason(double confidence) {
        return confidence < 0.8 ? ReviewReason.LOW_CONFIDENCE : ReviewReason.PROMOTION_CANDIDATE;
    }

    private static Map<String, Object> createdEventMetadata(MemoryItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", item.scope().value());
        metadata.put("memoryType", item.memoryType().value());
        metadata.put("status", item.status().value());
        metadata.put("tagCount", item.tags().size());
        metadata.put("sourceType", item.sourceType().value());
        metadata.put("projectKeyPresent", item.projectKey() != null);
        metadata.put("vectorId", item.vectorId().toString());
        return metadata;
    }

    private static Map<String, Object> reviewQueueMetadata(MemoryItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", item.scope().value());
        metadata.put("memoryType", item.memoryType().value());
        metadata.put("confidence", item.confidence());
        metadata.put("tagCount", item.tags().size());
        metadata.put("sourceType", item.sourceType().value());
        return metadata;
    }

    private static Map<String, Object> statusChangedMetadata(
            MemoryStatus oldStatus, MemoryStatus newStatus, String actor, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("oldStatus", oldStatus.value());
        metadata.put("newStatus", newStatus.value());
        metadata.put("actor", blankToNull(actor) == null ? "system" : actor.trim());
        metadata.put("reasonProvided", blankToNull(reason) != null);
        metadata.put("reasonLength", blankToNull(reason) == null ? 0 : reason.trim().length());
        return metadata;
    }

    private static Map<String, Object> updatedEventMetadata(
            MemoryItem oldItem, MemoryItem newItem, String oldHash, String newHash) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", newItem.scope().value());
        metadata.put("memoryType", newItem.memoryType().value());
        metadata.put("status", newItem.status().value());
        metadata.put("tagCount", newItem.tags().size());
        metadata.put("sourceType", newItem.sourceType().value());
        metadata.put("projectKeyPresent", newItem.projectKey() != null);
        metadata.put("vectorIdChanged", !oldItem.vectorId().equals(newItem.vectorId()));
        metadata.put("contentHashChanged", !newHash.equals(oldHash));
        metadata.put("oldContentHashPrefix", oldHash == null ? null : oldHash.substring(0, Math.min(12, oldHash.length())));
        metadata.put("newContentHashPrefix", newHash.substring(0, Math.min(12, newHash.length())));
        return metadata;
    }

    private static Map<String, Object> correctionEventMetadata(MemoryItem oldItem, MemoryItem newItem) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", newItem.scope().value());
        metadata.put("memoryType", newItem.memoryType().value());
        metadata.put("oldStatus", oldItem.status().value());
        metadata.put("newStatus", newItem.status().value());
        metadata.put("vectorIdChanged", !oldItem.vectorId().equals(newItem.vectorId()));
        metadata.put("learningCorrection", true);
        return metadata;
    }
}
