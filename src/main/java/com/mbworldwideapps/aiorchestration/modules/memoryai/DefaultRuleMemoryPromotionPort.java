package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Package-private implementation of the promotion port. Evidence checks run
 * against the same transaction the promotion service opened, so a rollback of
 * the rule rows also rolls back the memory activation.
 */
@Component
class DefaultRuleMemoryPromotionPort implements RuleMemoryPromotionPort {

    private final MemoryRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final RuleMemoryProjectionOutbox projectionOutbox;
    private final RuleMemoryLinkLookup linkLookup;

    @Autowired
    DefaultRuleMemoryPromotionPort(MemoryRepository repository, JdbcTemplate jdbcTemplate,
            RuleMemoryProjectionOutbox projectionOutbox, RuleMemoryLinkLookup linkLookup) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.projectionOutbox = projectionOutbox;
        this.linkLookup = linkLookup;
    }

    DefaultRuleMemoryPromotionPort(MemoryRepository repository, JdbcTemplate jdbcTemplate,
            RuleMemoryProjectionOutbox projectionOutbox) {
        this(repository, jdbcTemplate, projectionOutbox, new JdbcRuleMemoryLinkLookup(jdbcTemplate));
    }

    @Override
    public void preparePromotionOrigin(UUID memoryId, String expectedOriginContentHash) {
        requireTransaction();
        requireEvidenceHash(expectedOriginContentHash, "origin content");
        if (TransactionSynchronizationManager.hasResource(this)) {
            throw new IllegalStateException("a rule memory origin is already prepared in this transaction");
        }
        MemoryItem memory = repository.findByIdForUpdate(memoryId)
                .orElseThrow(() -> new IllegalStateException("promotion origin memory not found: " + memoryId));
        if (memory.memoryType() != MemoryType.RULE) {
            throw new IllegalStateException("promotion origin memory is not RULE-typed: " + memoryId);
        }
        boolean pending = memory.status() == MemoryStatus.PENDING_REVIEW;
        boolean derivedLegacy = memory.status() == MemoryStatus.ACTIVE
                && !linkLookup.hasLinkedDefinition(memoryId);
        if (!pending && !derivedLegacy) {
            throw new IllegalStateException(
                    "promotion origin must be pending_review or an ACTIVE unlinked legacy RULE: " + memoryId);
        }
        String actualOriginHash = RuleMemoryContentHash.compute(memory.summary(), memory.text());
        if (!actualOriginHash.equals(expectedOriginContentHash)) {
            throw new IllegalStateException(
                    "memory changed since preview (stale origin content hash) for memory " + memoryId);
        }
        PreparedOrigin prepared = new PreparedOrigin(memoryId, expectedOriginContentHash, memory.status());
        TransactionSynchronizationManager.bindResource(this, prepared);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.hasResource(DefaultRuleMemoryPromotionPort.this)) {
                    TransactionSynchronizationManager.unbindResource(DefaultRuleMemoryPromotionPort.this);
                }
            }
        });
    }

    @Override
    public MemoryItem activatePromotedRule(UUID memoryId, UUID ruleId, int ruleVersion,
            String expectedOriginContentHash, String expectedApprovalContentHash,
            String expectedConfirmationCardHash) {
        requireTransaction();
        requireEvidenceHash(expectedOriginContentHash, "origin content");
        requireEvidenceHash(expectedApprovalContentHash, "approval content");
        requireEvidenceHash(expectedConfirmationCardHash, "confirmation card");
        PreparedOrigin prepared = preparedOrigin(memoryId, expectedOriginContentHash);
        MemoryItem memory = repository.findByIdForUpdate(memoryId)
                .orElseThrow(() -> new IllegalStateException("promotion origin memory not found: " + memoryId));
        if (memory.memoryType() != MemoryType.RULE) {
            throw new IllegalStateException("promotion origin memory is not RULE-typed: " + memoryId);
        }
        if (memory.status() != prepared.initialStatus()) {
            throw new IllegalStateException(
                    "promotion origin memory status changed after preparation: " + memoryId);
        }
        String actualOriginHash = RuleMemoryContentHash.compute(memory.summary(), memory.text());
        if (!actualOriginHash.equals(expectedOriginContentHash)) {
            throw new IllegalStateException(
                    "memory changed since preview (stale origin content hash) for memory " + memoryId);
        }

        Integer evidenceCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rule_definitions d
                JOIN rule_versions v
                  ON v.rule_id = d.id AND v.version = d.current_version
                WHERE d.id = ?
                  AND d.origin_memory_id = ?
                  AND d.status = 'active'
                  AND d.current_version = ?
                  AND v.origin_memory_id = ?
                  AND v.origin_content_hash = ?
                  AND v.approval_content_hash = ?
                  AND v.confirmation_card_hash = ?
                  AND btrim(v.approved_by) <> ''
                  AND btrim(v.human_turn_ref) <> ''
                  AND btrim(v.human_raw_text_hash) <> ''
                  AND btrim(v.workflow_contract_version) <> ''
                """, Integer.class, ruleId, memoryId, ruleVersion, memoryId, expectedOriginContentHash,
                expectedApprovalContentHash, expectedConfirmationCardHash);
        if (evidenceCount == null || evidenceCount != 1) {
            throw new IllegalStateException(
                    "no exact promotion evidence links pending memory " + memoryId
                            + " to active rule " + ruleId + " version " + ruleVersion);
        }

        Instant now = Instant.now();
        Map<String, Object> metadata = new LinkedHashMap<>(memory.metadata());
        metadata.put("promotedRuleId", ruleId.toString());
        metadata.put("promotedRuleVersion", ruleVersion);
        MemoryItem updated = new MemoryItem(
                memory.id(), memory.vectorId(), memory.scope(), memory.projectKey(), memory.memoryType(),
                memory.summary(), memory.text(), memory.tags(), memory.confidence(), MemoryStatus.ACTIVE,
                memory.sourceType(), memory.sourceRef(), memory.owner(), metadata,
                memory.createdAt(), now, memory.lastUsedAt(), memory.lastVerifiedAt(), memory.expiresAt());
        repository.update(updated);
        MemoryEventType eventType = prepared.initialStatus() == MemoryStatus.PENDING_REVIEW
                ? MemoryEventType.STATUS_CHANGED
                : MemoryEventType.PROMOTED;
        repository.insertEvent(new MemoryEvent(UUID.randomUUID(), memory.id(), eventType,
                Map.of(
                        "from", memory.status().value(),
                        "to", MemoryStatus.ACTIVE.value(),
                        "action", "rule_promotion",
                        "ruleId", ruleId.toString(),
                        "ruleVersion", ruleVersion),
                now));
        projectionOutbox.enqueue(memory.id(), ruleId, ruleVersion);
        TransactionSynchronizationManager.unbindResource(this);
        return updated;
    }

    private PreparedOrigin preparedOrigin(UUID memoryId, String expectedOriginContentHash) {
        Object resource = TransactionSynchronizationManager.getResource(this);
        if (!(resource instanceof PreparedOrigin prepared)
                || !prepared.memoryId().equals(memoryId)
                || !prepared.expectedOriginContentHash().equals(expectedOriginContentHash)) {
            throw new IllegalStateException(
                    "rule memory activation requires origin preparation in the same transaction");
        }
        return prepared;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("rule memory activation requires an actual promotion transaction");
        }
    }

    private static void requireEvidenceHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    field + " hash must be a 64-character SHA-256 value for rule memory activation");
        }
    }

    private record PreparedOrigin(
            UUID memoryId,
            String expectedOriginContentHash,
            MemoryStatus initialStatus) {
    }
}
