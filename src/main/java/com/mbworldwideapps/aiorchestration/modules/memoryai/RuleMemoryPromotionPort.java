package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

/**
 * Narrow port through which the rule promotion transaction activates the
 * originating RULE memory. The implementation trusts database evidence, not the
 * caller: it verifies inside the current transaction that the promoted rule
 * definition/version rows really exist for this memory and that the memory
 * content still matches what the human approved.
 */
public interface RuleMemoryPromotionPort {

    /**
     * Locks and validates the origin before rule authority rows are inserted.
     * Implementations may retain transaction-local evidence for the activation
     * call. The default keeps lightweight non-memory test ports source-compatible.
     */
    default void preparePromotionOrigin(UUID memoryId, String expectedOriginContentHash) {
        // No-op only for alternate/test ports. The production implementation is fail-closed.
    }

    MemoryItem activatePromotedRule(UUID memoryId, UUID ruleId, int ruleVersion,
            String expectedOriginContentHash, String expectedApprovalContentHash,
            String expectedConfirmationCardHash);
}
