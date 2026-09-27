package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DefaultRuleMemoryPromotionPortTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RuleMemoryProjectionOutbox projectionOutbox = mock(RuleMemoryProjectionOutbox.class);
    private final DefaultRuleMemoryPromotionPort port =
            new DefaultRuleMemoryPromotionPort(repository, jdbcTemplate, projectionOutbox);

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.hasResource(port)) {
            TransactionSynchronizationManager.unbindResource(port);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void rejectsCallsOutsideAnActualPromotionTransaction() {
        assertThatThrownBy(() -> port.activatePromotedRule(UUID.randomUUID(), UUID.randomUUID(), 1,
                "a".repeat(64), "b".repeat(64), "c".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verify(repository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNonShaEvidenceBeforeReadingMemory() {
        beginTransaction();

        assertThatThrownBy(() -> port.activatePromotedRule(UUID.randomUUID(), UUID.randomUUID(), 1,
                "not-a-sha", "b".repeat(64), "c".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");

        verify(repository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void canonicalOriginHashCannotCollideAcrossSummaryTextBoundary() {
        assertThat(RuleMemoryContentHash.compute("a\nb", "c"))
                .isNotEqualTo(RuleMemoryContentHash.compute("a", "b\nc"));
    }

    @Test
    void preparesPendingAndDerivedLegacyOriginsButRejectsLinkedActiveMemory() {
        beginTransaction();
        UUID memoryId = UUID.randomUUID();
        MemoryItem active = memory(memoryId, MemoryStatus.ACTIVE);
        when(repository.findByIdForUpdate(memoryId)).thenReturn(Optional.of(active));

        port.preparePromotionOrigin(memoryId, RuleMemoryContentHash.compute(active.summary(), active.text()));
        TransactionSynchronizationManager.unbindResource(port);

        RuleMemoryLinkLookup linked = ignored -> true;
        DefaultRuleMemoryPromotionPort linkedPort = new DefaultRuleMemoryPromotionPort(
                repository, jdbcTemplate, projectionOutbox, linked);
        assertThatThrownBy(() -> linkedPort.preparePromotionOrigin(
                memoryId, RuleMemoryContentHash.compute(active.summary(), active.text())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE unlinked legacy RULE");

        MemoryItem pending = memory(memoryId, MemoryStatus.PENDING_REVIEW);
        when(repository.findByIdForUpdate(memoryId)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> port.preparePromotionOrigin(memoryId, "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale origin content hash");
    }

    @Test
    void requiresExactActiveDefinitionCurrentVersionAndApprovalEvidenceTuple() {
        beginTransaction();
        UUID memoryId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        MemoryItem pending = memory(memoryId, MemoryStatus.PENDING_REVIEW);
        String originHash = RuleMemoryContentHash.compute(pending.summary(), pending.text());
        String approvalHash = "b".repeat(64);
        String cardHash = "c".repeat(64);
        when(repository.findByIdForUpdate(memoryId)).thenReturn(Optional.of(pending));
        port.preparePromotionOrigin(memoryId, originHash);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(ruleId), eq(memoryId), eq(1), eq(memoryId),
                eq(originHash), eq(approvalHash), eq(cardHash))).thenReturn(0);

        assertThatThrownBy(() -> port.activatePromotedRule(memoryId, ruleId, 1,
                originHash, approvalHash, cardHash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact promotion evidence");

        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("d.status = 'active'")
                                && sql.contains("d.current_version = ?")
                                && sql.contains("v.origin_memory_id = ?")
                                && sql.contains("v.origin_content_hash = ?")
                                && sql.contains("v.approval_content_hash = ?")
                                && sql.contains("v.confirmation_card_hash = ?")
                                && sql.contains("human_raw_text_hash")
                                && sql.contains("workflow_contract_version")),
                eq(Integer.class), eq(ruleId), eq(memoryId), eq(1), eq(memoryId), eq(originHash),
                eq(approvalHash), eq(cardHash));
    }

    @Test
    void activatesOnlyAfterAllEvidenceMatches() {
        beginTransaction();
        UUID memoryId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        MemoryItem pending = memory(memoryId, MemoryStatus.PENDING_REVIEW);
        String originHash = RuleMemoryContentHash.compute(pending.summary(), pending.text());
        String approvalHash = "b".repeat(64);
        String cardHash = "c".repeat(64);
        when(repository.findByIdForUpdate(memoryId)).thenReturn(Optional.of(pending));
        port.preparePromotionOrigin(memoryId, originHash);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(ruleId), eq(memoryId), eq(1), eq(memoryId),
                eq(originHash), eq(approvalHash), eq(cardHash))).thenReturn(1);

        MemoryItem activated = port.activatePromotedRule(memoryId, ruleId, 1,
                originHash, approvalHash, cardHash);

        assertThat(activated.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(activated.metadata())
                .containsEntry("promotedRuleId", ruleId.toString())
                .containsEntry("promotedRuleVersion", 1);
        ArgumentCaptor<MemoryItem> updated = ArgumentCaptor.forClass(MemoryItem.class);
        verify(repository).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(MemoryStatus.ACTIVE);
        verify(repository).insertEvent(org.mockito.ArgumentMatchers.any(MemoryEvent.class));
        verify(projectionOutbox).enqueue(memoryId, ruleId, 1);
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static MemoryItem memory(UUID id, MemoryStatus status) {
        Instant now = Instant.parse("2026-08-04T08:00:00Z");
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryType.RULE,
                "  Controllers stay thin  ", "  Business logic belongs in services.  ", List.of("controller"),
                1.0, status, MemorySourceType.MANUAL, "manual:" + id, "alex", Map.of(), now, now,
                null, now, null);
    }
}
