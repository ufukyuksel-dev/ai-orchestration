package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MemoryReviewService {

    private static final Logger log = LoggerFactory.getLogger(MemoryReviewService.class);

    private final MemoryRepository repository;
    private final MemoryVectorIndex memoryVectorIndex;
    private final ApplicationEventPublisher eventPublisher;
    private final PolicyEngine policyEngine;
    private final PiiScrubber piiScrubber;
    private final RuleMemoryActivationPolicy ruleActivationPolicy;
    private final RuleMemoryLinkLookup ruleMemoryLinkLookup;

    @Autowired
    public MemoryReviewService(MemoryRepository repository, MemoryVectorIndex memoryVectorIndex,
            ApplicationEventPublisher eventPublisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            RuleMemoryActivationPolicy ruleActivationPolicy, RuleMemoryLinkLookup ruleMemoryLinkLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.memoryVectorIndex = Objects.requireNonNull(memoryVectorIndex, "memoryVectorIndex");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.piiScrubber = Objects.requireNonNull(piiScrubber, "piiScrubber");
        this.ruleActivationPolicy = Objects.requireNonNull(ruleActivationPolicy, "ruleActivationPolicy");
        this.ruleMemoryLinkLookup = Objects.requireNonNull(ruleMemoryLinkLookup, "ruleMemoryLinkLookup");
    }

    @Transactional
    public MemoryItem approve(UUID id, String actor, String reason) {
        return approveInternal(id, actor, reason, "approve", Map.of());
    }

    @Transactional
    public MemoryItem approveHumanConfirmed(UUID id, String actor, String reason, String humanTurnRef) {
        return approveInternal(id, actor, reason, "approve", humanConfirmationMetadata(humanTurnRef));
    }

    @Transactional
    public MemoryItem approveHumanConfirmed(UUID id, String actor, String reason,
            HumanConfirmationAudit audit) {
        return approveInternal(id, actor, reason, "approve", humanConfirmationMetadata(audit));
    }

    @Transactional
    public MemoryItem approveAfterHumanEdit(UUID id, String actor, String reason, UUID editedFrom) {
        return approveAfterHumanEdit(id, actor, reason, editedFrom, (String) null);
    }

    @Transactional
    public MemoryItem approveAfterHumanEdit(UUID id, String actor, String reason, UUID editedFrom,
            String humanTurnRef) {
        Map<String, Object> metadata = humanConfirmationMetadata(humanTurnRef);
        metadata.put("editedFrom", editedFrom.toString());
        return approveInternal(id, actor, reason, "approve_after_human_edit", metadata);
    }

    @Transactional
    public MemoryItem approveAfterHumanEdit(UUID id, String actor, String reason, UUID editedFrom,
            HumanConfirmationAudit audit) {
        Map<String, Object> metadata = humanConfirmationMetadata(audit);
        metadata.put("editedFrom", editedFrom.toString());
        return approveInternal(id, actor, reason, "approve_after_human_edit", metadata);
    }

    private MemoryItem approveInternal(UUID id, String actor, String reason, String action,
            Map<String, Object> extraMetadata) {
        MemoryItem existing = findById(id);
        if (existing.status() != MemoryStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Only pending_review memory can be approved");
        }
        assertNotLinkedRuleMutation(existing, "approved through the memory review flow");
        ruleActivationPolicy.assertActivationAllowed(existing.memoryType(), existing.status(),
                MemoryStatus.ACTIVE);
        MemoryItem updated = withStatus(existing, MemoryStatus.ACTIVE, Instant.now());
        repository.update(updated);
        closeReviewQueue(id, ReviewStatus.APPROVED, actor, reason, action, extraMetadata);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.STATUS_CHANGED,
                statusMetadata(existing.status(), updated.status(), actor, reason, action, extraMetadata),
                Instant.now()));
        publish(updated, existing);
        return updated;
    }

    @Transactional
    public MemoryItem reject(UUID id, String actor, String reason) {
        return rejectInternal(id, actor, reason, "reject", Map.of());
    }

    @Transactional
    public MemoryItem rejectHumanConfirmed(UUID id, String actor, String reason, String humanTurnRef) {
        return rejectInternal(id, actor, reason, "reject", humanConfirmationMetadata(humanTurnRef));
    }

    @Transactional
    public MemoryItem rejectHumanConfirmed(UUID id, String actor, String reason, HumanConfirmationAudit audit) {
        return rejectInternal(id, actor, reason, "reject", humanConfirmationMetadata(audit));
    }

    @Transactional
    public MemoryItem rejectForEdit(UUID id, String actor, String reason, UUID supersededBy) {
        return rejectForEdit(id, actor, reason, supersededBy, (String) null);
    }

    @Transactional
    public MemoryItem rejectForEdit(UUID id, String actor, String reason, UUID supersededBy, String humanTurnRef) {
        Map<String, Object> metadata = humanConfirmationMetadata(humanTurnRef);
        metadata.put("supersededBy", supersededBy.toString());
        return rejectInternal(id, actor, reason, "reject_for_edit", metadata);
    }

    @Transactional
    public MemoryItem rejectForEdit(UUID id, String actor, String reason, UUID supersededBy,
            HumanConfirmationAudit audit) {
        Map<String, Object> metadata = humanConfirmationMetadata(audit);
        metadata.put("supersededBy", supersededBy.toString());
        return rejectInternal(id, actor, reason, "reject_for_edit", metadata);
    }

    private MemoryItem rejectInternal(UUID id, String actor, String reason, String action,
            Map<String, Object> extraMetadata) {
        MemoryItem existing = findById(id);
        if (existing.status() != MemoryStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Only pending_review memory can be rejected");
        }
        assertNotLinkedRuleMutation(existing, "rejected through the memory review flow");
        MemoryItem updated = withStatus(existing, MemoryStatus.REJECTED, Instant.now());
        repository.update(updated);
        closeReviewQueue(id, ReviewStatus.REJECTED, actor, reason, action, extraMetadata);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.REJECTED,
                statusMetadata(existing.status(), updated.status(), actor, reason, action, extraMetadata),
                Instant.now()));
        publish(updated, existing);
        return updated;
    }

    @Transactional
    public MemoryItem archive(UUID id, String actor, String reason) {
        MemoryItem existing = findById(id);
        assertNotLinkedRuleMutation(existing, "archived");
        MemoryItem updated = withStatus(existing, MemoryStatus.ARCHIVED, Instant.now());
        repository.update(updated);
        closeReviewQueue(id, ReviewStatus.APPROVED, actor, reason, "archive");
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.ARCHIVED,
                statusMetadata(existing.status(), updated.status(), actor, reason, "archive"), Instant.now()));
        publish(updated, existing);
        return updated;
    }

    @Transactional
    public void hardDelete(UUID id, String actor, String reason) {
        MemoryItem existing = findById(id);
        assertNotLinkedRuleMutation(existing, "hard-deleted");
        repository.deleteById(id);
        if (existing.status() == MemoryStatus.ACTIVE || existing.status() == MemoryStatus.STALE) {
            deleteVectorAfterCommit(existing);
        }
    }

    @Transactional
    public MemoryItem recordHumanConfirmDenied(UUID id, String actor, String reason,
            HumanConfirmationAudit audit, Map<String, Object> extraMetadata) {
        MemoryItem existing = findById(id);
        Map<String, Object> metadata = statusMetadata(existing.status(), existing.status(), actor, reason,
                "confirm_denied", humanConfirmationMetadata(audit));
        metadata.put("decision", "denied");
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.UPDATED, metadata,
                Instant.now()));
        return existing;
    }

    @Transactional
    public MemoryItem edit(UUID id, EditMemoryRequest request) {
        MemoryItem existing = findById(id);
        if (existing.memoryType() == MemoryType.RULE
                && ruleMemoryLinkLookup.hasLinkedDefinition(existing.id())) {
            throw new IllegalArgumentException(
                    "RULE memory linked to immutable rule history cannot be edited; "
                            + "create a revision candidate and use rules.promote");
        }
        String summary = blankToNull(request.summary()) == null ? existing.summary() : request.summary().trim();
        String text = blankToNull(request.text()) == null ? existing.text() : request.text().trim();
        List<String> tags = request.tags() == null ? existing.tags() : normalizeTags(request.tags());
        double confidence = request.confidence() == null ? existing.confidence() : request.confidence();
        String requestedScope = blankToNull(request.scope());
        MemoryScope scope = requestedScope == null ? existing.scope() : MemoryScope.from(requestedScope);
        if (requestedScope != null && scope != MemoryScope.GLOBAL && scope != MemoryScope.PROJECT) {
            throw new IllegalArgumentException("memory edit scope must be global or project");
        }
        String projectKey = scope == MemoryScope.GLOBAL
                ? null
                : blankToNull(request.projectKey()) == null ? existing.projectKey() : request.projectKey().trim();
        if (scope == MemoryScope.PROJECT && blankToNull(projectKey) == null) {
            throw new IllegalArgumentException("projectKey is required for project memory");
        }
        String sourceRef = blankToNull(request.sourceRef()) == null ? existing.sourceRef() : request.sourceRef().trim();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (summary.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("summary and text cannot be blank");
        }
        enforceEditPolicy(existing.sourceType(), scope, text);
        Map<String, Object> metadata = new LinkedHashMap<>(MemoryCodeLocatorMetadata.replace(existing.metadata(),
                request.codeLocators(), scope, projectKey));
        metadata.put("reviewContentHash", contentHash(summary, text));
        UUID vectorId = MemoryService.deterministicVectorId(scope, projectKey, summary, text);
        MemoryStatus updatedStatus = existing.memoryType() == MemoryType.DISCOVERY
                ? MemoryStatus.STALE : existing.status();
        MemoryItem updated = new MemoryItem(
                existing.id(),
                vectorId,
                scope,
                projectKey,
                existing.memoryType(),
                summary,
                text,
                tags,
                confidence,
                updatedStatus,
                existing.sourceType(),
                sourceRef,
                existing.owner(),
                metadata,
                existing.createdAt(),
                Instant.now(),
                existing.lastUsedAt(),
                Instant.now(),
                existing.expiresAt());
        repository.update(updated);
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.UPDATED,
                editMetadata(existing, updated, request.actor(), request.reason()), Instant.now()));
        eventPublisher.publishEvent(new MemoryItemChangedEvent(updated,
                MemoryItemChangedEvent.Origin.EXTERNAL_EDIT, existing));
        return updated;
    }

    @Transactional
    public MemoryItem promote(UUID id, PromoteMemoryRequest request) {
        MemoryItem existing = findById(id);
        if (existing.scope() != MemoryScope.EPISODIC) {
            throw new IllegalArgumentException("Only episodic memory can be promoted");
        }
        MemoryScope targetScope = request.scope();
        if (targetScope == MemoryScope.EPISODIC) {
            throw new IllegalArgumentException("Promotion target scope must be global or project");
        }
        if (ruleActivationPolicy.enabled() && existing.memoryType() == MemoryType.RULE) {
            // Scope promotion widens a rule's reach; that is rule authority work, not a memory edit.
            throw new IllegalArgumentException(RuleMemoryActivationPolicy.GUIDANCE);
        }
        String projectKey = normalizeProjectKey(request.projectKey());
        if (targetScope == MemoryScope.PROJECT && projectKey == null) {
            throw new IllegalArgumentException("projectKey is required when promoting to project scope");
        }
        if (targetScope == MemoryScope.GLOBAL) {
            projectKey = null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(existing.metadata());
        metadata.put("promotedFromScope", existing.scope().value());
        metadata.put("reviewContentHash", contentHash(existing.summary(), existing.text()));
        UUID vectorId = MemoryService.deterministicVectorId(targetScope, projectKey, existing.summary(), existing.text());
        MemoryItem updated = new MemoryItem(
                existing.id(),
                vectorId,
                targetScope,
                projectKey,
                existing.memoryType(),
                existing.summary(),
                existing.text(),
                existing.tags(),
                existing.confidence(),
                MemoryStatus.ACTIVE,
                existing.sourceType(),
                existing.sourceRef(),
                existing.owner(),
                metadata,
                existing.createdAt(),
                Instant.now(),
                existing.lastUsedAt(),
                Instant.now(),
                existing.expiresAt());
        repository.update(updated);
        closeReviewQueue(id, ReviewStatus.APPROVED, request.actor(), request.reason(), "promote");
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), id, MemoryEventType.PROMOTED,
                promoteMetadata(existing, updated, request.actor(), request.reason()), Instant.now()));
        publish(updated, existing);
        return updated;
    }

    public List<MemoryItem> search(String query, int limit) {
        return repository.search(query, limit);
    }

    public List<ReviewQueueItem> listReviewQueue(ReviewStatus status) {
        return repository.listReviewQueue(status);
    }

    private MemoryItem findById(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new MemoryNotFoundException(id));
    }

    private void assertNotLinkedRuleMutation(MemoryItem item, String action) {
        if (item.memoryType() == MemoryType.RULE && ruleMemoryLinkLookup.hasLinkedDefinition(item.id())) {
            throw new IllegalArgumentException(
                    "RULE memory linked to immutable rule history cannot be " + action
                            + "; use the rule lifecycle tools");
        }
    }

    private void publish(MemoryItem item, MemoryItem previous) {
        eventPublisher.publishEvent(new MemoryItemChangedEvent(item, previous));
    }

    private void deleteVectorAfterCommit(MemoryItem item) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            memoryVectorIndex.delete(item);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                memoryVectorIndex.delete(item);
            }
        });
    }

    private void closeReviewQueue(UUID id, ReviewStatus status, String actor, String reason, String action) {
        closeReviewQueue(id, status, actor, reason, action, Map.of());
    }

    private void closeReviewQueue(UUID id, ReviewStatus status, String actor, String reason, String action,
            Map<String, Object> extraMetadata) {
        repository.updateReviewQueueStatus(id, ReviewStatus.OPEN, status, Instant.now(),
                reviewQueueCloseMetadata(actor, reason, action, extraMetadata));
    }

    private static MemoryItem withStatus(MemoryItem existing, MemoryStatus status, Instant updatedAt) {
        return new MemoryItem(
                existing.id(),
                existing.vectorId(),
                existing.scope(),
                existing.projectKey(),
                existing.memoryType(),
                existing.summary(),
                existing.text(),
                existing.tags(),
                existing.confidence(),
                status,
                existing.sourceType(),
                existing.sourceRef(),
                existing.owner(),
                existing.metadata(),
                existing.createdAt(),
                updatedAt,
                existing.lastUsedAt(),
                existing.lastVerifiedAt(),
                existing.expiresAt());
    }

    private static Map<String, Object> statusMetadata(
            MemoryStatus oldStatus, MemoryStatus newStatus, String actor, String reason, String action) {
        return statusMetadata(oldStatus, newStatus, actor, reason, action, Map.of());
    }

    private static Map<String, Object> statusMetadata(
            MemoryStatus oldStatus, MemoryStatus newStatus, String actor, String reason, String action,
            Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = baseReviewMetadata(actor, reason, action);
        metadata.put("oldStatus", oldStatus.value());
        metadata.put("newStatus", newStatus.value());
        metadata.putAll(extraMetadata);
        return metadata;
    }

    private static Map<String, Object> editMetadata(MemoryItem oldItem, MemoryItem newItem, String actor, String reason) {
        Map<String, Object> metadata = baseReviewMetadata(actor, reason, "edit");
        metadata.put("summaryChanged", !oldItem.summary().equals(newItem.summary()));
        metadata.put("textHashChanged", !contentHash(oldItem.summary(), oldItem.text())
                .equals(contentHash(newItem.summary(), newItem.text())));
        metadata.put("tagsChanged", !oldItem.tags().equals(newItem.tags()));
        metadata.put("scopeChanged", oldItem.scope() != newItem.scope());
        metadata.put("projectKeyChanged", !java.util.Objects.equals(oldItem.projectKey(), newItem.projectKey()));
        metadata.put("sourceRefChanged", !java.util.Objects.equals(oldItem.sourceRef(), newItem.sourceRef()));
        metadata.put("oldConfidence", oldItem.confidence());
        metadata.put("newConfidence", newItem.confidence());
        metadata.put("vectorIdChanged", !oldItem.vectorId().equals(newItem.vectorId()));
        metadata.put("contentHashPrefix", contentHash(newItem.summary(), newItem.text()).substring(0, 12));
        return metadata;
    }

    private void enforceEditPolicy(MemorySourceType sourceType, MemoryScope scope, String text) {
        piiScrubber.firstMatchReason(text).ifPresent(reason -> {
            throw new MemoryPolicyViolationException("pii-" + reason);
        });
        PolicyDecision writeDecision = policyEngine.evaluateMemoryWrite(sourceType, scope);
        if (!writeDecision.allowed()) {
            throw new MemoryPolicyViolationException(writeDecision.reason());
        }
        PolicyDecision contentDecision = policyEngine.evaluateMemoryContent(text);
        if (!contentDecision.allowed()) {
            throw new MemoryPolicyViolationException(contentDecision.reason());
        }
    }

    private static Map<String, Object> promoteMetadata(
            MemoryItem oldItem, MemoryItem newItem, String actor, String reason) {
        Map<String, Object> metadata = baseReviewMetadata(actor, reason, "promote");
        metadata.put("oldScope", oldItem.scope().value());
        metadata.put("newScope", newItem.scope().value());
        metadata.put("oldStatus", oldItem.status().value());
        metadata.put("newStatus", newItem.status().value());
        metadata.put("projectKeyPresent", newItem.projectKey() != null);
        metadata.put("vectorIdChanged", !oldItem.vectorId().equals(newItem.vectorId()));
        metadata.put("contentHashPrefix", contentHash(newItem.summary(), newItem.text()).substring(0, 12));
        return metadata;
    }

    private static Map<String, Object> reviewQueueCloseMetadata(String actor, String reason, String action) {
        return reviewQueueCloseMetadata(actor, reason, action, Map.of());
    }

    private static Map<String, Object> reviewQueueCloseMetadata(String actor, String reason, String action,
            Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = baseReviewMetadata(actor, reason, action);
        metadata.putAll(extraMetadata);
        return metadata;
    }

    private static Map<String, Object> baseReviewMetadata(String actor, String reason, String action) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action);
        metadata.put("actor", blankToNull(actor) == null ? "reviewer" : actor.trim());
        metadata.put("reasonProvided", blankToNull(reason) != null);
        metadata.put("reasonLength", blankToNull(reason) == null ? 0 : reason.trim().length());
        if (blankToNull(reason) != null) {
            metadata.put("reasonHashPrefix", hashPrefix(reason.trim()));
        }
        return metadata;
    }

    private static Map<String, Object> humanConfirmationMetadata(String humanTurnRef) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("humanConfirmed", true);
        if (blankToNull(humanTurnRef) != null) {
            metadata.put("humanTurnRef", humanTurnRef.trim());
        }
        return metadata;
    }

    private static Map<String, Object> humanConfirmationMetadata(HumanConfirmationAudit audit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("humanConfirmed", true);
        if (audit == null) {
            return metadata;
        }
        if (blankToNull(audit.humanTurnRef()) != null) {
            metadata.put("humanTurnRef", audit.humanTurnRef().trim());
        }
        if (audit.aiInterpretedAsApproval() != null) {
            metadata.put("aiInterpretedAsApproval", audit.aiInterpretedAsApproval());
        }
        if (audit.agentConfidence() != null) {
            metadata.put("agentConfidence", audit.agentConfidence());
        }
        metadata.put("humanRawTextScrubbed", audit.humanRawTextScrubbed() == null
                ? ""
                : audit.humanRawTextScrubbed());
        metadata.put("humanRawTextHash", audit.humanRawTextHash());
        metadata.put("humanRawTextLength", audit.humanRawTextLength());
        return metadata;
    }

    public record HumanConfirmationAudit(
            String humanTurnRef,
            Boolean aiInterpretedAsApproval,
            Double agentConfidence,
            String humanRawTextScrubbed,
            String humanRawTextHash,
            int humanRawTextLength) {
    }

    private static List<String> normalizeTags(List<String> tags) {
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

    private static String contentHash(String summary, String text) {
        return RuleMemoryContentHash.compute(summary, text);
    }

    private static String hashPrefix(String value) {
        return sha256(value).substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
