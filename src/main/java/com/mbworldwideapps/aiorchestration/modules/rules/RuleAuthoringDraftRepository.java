package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RuleAuthoringDraftRepository {

    void insert(RuleAuthoringDraft draft);

    Optional<RuleAuthoringDraft> findById(UUID draftId);

    Optional<RuleAuthoringDraft> findByIdForUpdate(UUID draftId);

    boolean bindPromotion(UUID draftId, String expectedCandidateHash, UUID ruleId, int ruleVersion,
            String approvalContentHash, String promotionRequestHash, Instant promotedAt);
}
