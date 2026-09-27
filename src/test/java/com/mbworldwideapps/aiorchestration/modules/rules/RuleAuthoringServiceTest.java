package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuleAuthoringServiceTest {

    private static final String PROJECT_KEY = "AI_ORCHESTRATION";
    private static final String ACTOR = "mcp:codex";
    private static final String VERSION_HASH = "1".repeat(64);
    private static final String APPROVAL_HASH = "2".repeat(64);
    private static final String CARD_HASH = "3".repeat(64);
    private static final RulePromotionPreview PREVIEW = new RulePromotionPreview(
            RulePromotionService.WORKFLOW_CONTRACT_VERSION, VERSION_HASH, APPROVAL_HASH,
            "Complete server confirmation card", CARD_HASH);

    private RuleAuthoringDraftRepository drafts;
    private RulePromotionService promotionService;
    private RuleAuthoringService service;

    @BeforeEach
    void setUp() {
        drafts = mock(RuleAuthoringDraftRepository.class);
        promotionService = mock(RulePromotionService.class);
        when(promotionService.canonicalCandidate(any(RulePromotionCandidate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(promotionService.preview(any(RulePromotionCandidate.class))).thenReturn(PREVIEW);
        service = new RuleAuthoringService(drafts, promotionService, new ObjectMapper());
    }

    @Test
    void draftsServerOwnedDirectHumanCandidateAndPreviewsImmutableCopy() {
        RuleAuthoringDraftResult result = service.draft(PROJECT_KEY, candidate(), ACTOR);

        ArgumentCaptor<RulePromotionCandidate> canonical = ArgumentCaptor.forClass(RulePromotionCandidate.class);
        verify(promotionService).canonicalCandidate(canonical.capture());
        assertThat(canonical.getValue().originMemoryId()).isNull();
        assertThat(canonical.getValue().expectedOriginContentHash()).isNull();
        assertThat(canonical.getValue().provenance()).isEqualTo(RuleProvenance.DIRECT_HUMAN_POLICY);
        assertThat(canonical.getValue().projectKey()).isEqualTo(PROJECT_KEY);

        ArgumentCaptor<RuleAuthoringDraft> inserted = ArgumentCaptor.forClass(RuleAuthoringDraft.class);
        verify(drafts).insert(inserted.capture());
        RuleAuthoringDraft draft = inserted.getValue();
        assertThat(result.draftId()).isEqualTo(draft.id());
        assertThat(result.candidateHash()).isEqualTo(draft.candidateHash());
        assertThat(draft.candidateJson()).contains("Controllers stay thin")
                .doesNotContain("originMemoryId", "expectedOriginContentHash", "provenance");

        when(drafts.findById(draft.id())).thenReturn(Optional.of(draft));
        RuleAuthoringPreviewResult preview = service.preview(draft.id(), PROJECT_KEY);

        assertThat(preview.candidateHash()).isEqualTo(draft.candidateHash());
        assertThat(preview.approvalContentHash()).isEqualTo(APPROVAL_HASH);
        assertThat(preview.confirmationCardHash()).isEqualTo(CARD_HASH);
    }

    @Test
    void promotesOnceAndReplaysOnlyTheExactHumanEvidence() {
        RuleAuthoringDraft draft = createDraft();
        UUID ruleId = UUID.randomUUID();
        DirectRulePromotionRequest request = promotionRequest(draft, " Approved exactly ", true);
        RuleVersion version = promotedVersion(ruleId, "Approved exactly", request.humanTurnRef());
        when(promotionService.promote(any(PromotionRequest.class))).thenReturn(version);
        when(drafts.bindPromotion(eq(draft.id()), eq(draft.candidateHash()), eq(ruleId), eq(1),
                eq(APPROVAL_HASH), any(String.class), any(Instant.class))).thenReturn(true);
        when(drafts.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));

        RuleAuthoringPromotionResult first = service.promote(request, ACTOR);

        ArgumentCaptor<PromotionRequest> promoted = ArgumentCaptor.forClass(PromotionRequest.class);
        verify(promotionService).promote(promoted.capture());
        assertThat(promoted.getValue().approval().approvedBy()).isEqualTo(ACTOR);
        assertThat(promoted.getValue().approval().humanRawText()).isEqualTo("Approved exactly");
        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        verify(drafts).bindPromotion(eq(draft.id()), eq(draft.candidateHash()), eq(ruleId), eq(1),
                eq(APPROVAL_HASH), requestHash.capture(), any(Instant.class));
        assertThat(first.replayed()).isFalse();

        RuleAuthoringDraft bound = new RuleAuthoringDraft(draft.id(), draft.projectKey(), draft.candidateJson(),
                draft.candidateHash(), draft.createdBy(), draft.createdAt(), ruleId, 1, APPROVAL_HASH,
                requestHash.getValue(), Instant.now());
        when(drafts.findByIdForUpdate(draft.id())).thenReturn(Optional.of(bound));

        RuleAuthoringPromotionResult replay = service.promote(request, ACTOR);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.ruleId()).isEqualTo(ruleId);
        verify(promotionService, times(1)).promote(any(PromotionRequest.class));

        DirectRulePromotionRequest changedEvidence = promotionRequest(draft, "Different approval", true);
        assertThatThrownBy(() -> service.promote(changedEvidence, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion request");
        verify(promotionService, times(1)).promote(any(PromotionRequest.class));
    }

    @Test
    void rejectsStaleCandidateCardAndWorkflowBeforeActivation() {
        RuleAuthoringDraft draft = createDraft();
        when(drafts.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));

        DirectRulePromotionRequest staleCandidate = new DirectRulePromotionRequest(draft.id(), PROJECT_KEY,
                "f".repeat(64), APPROVAL_HASH, CARD_HASH, PREVIEW.workflowContractVersion(),
                "Approved", "turn-1", true, 0.99);
        assertThatThrownBy(() -> service.promote(staleCandidate, ACTOR))
                .hasMessageContaining("candidate changed");

        DirectRulePromotionRequest staleCard = new DirectRulePromotionRequest(draft.id(), PROJECT_KEY,
                draft.candidateHash(), APPROVAL_HASH, "f".repeat(64), PREVIEW.workflowContractVersion(),
                "Approved", "turn-1", true, 0.99);
        assertThatThrownBy(() -> service.promote(staleCard, ACTOR))
                .hasMessageContaining("confirmation card");

        DirectRulePromotionRequest staleWorkflow = new DirectRulePromotionRequest(draft.id(), PROJECT_KEY,
                draft.candidateHash(), APPROVAL_HASH, CARD_HASH, "stale-workflow",
                "Approved", "turn-1", true, 0.99);
        assertThatThrownBy(() -> service.promote(staleWorkflow, ACTOR))
                .hasMessageContaining("workflow contract");

        verify(promotionService, never()).promote(any(PromotionRequest.class));
    }

    @Test
    void rejectsMissingHumanApprovalBeforeReadingDraft() {
        DirectRulePromotionRequest request = new DirectRulePromotionRequest(UUID.randomUUID(), PROJECT_KEY,
                "1".repeat(64), "2".repeat(64), "3".repeat(64),
                RulePromotionService.WORKFLOW_CONTRACT_VERSION, "Maybe", "turn-1", false, 0.99);

        assertThatThrownBy(() -> service.promote(request, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit human approval");

        verify(drafts, never()).findByIdForUpdate(any(UUID.class));
        verify(promotionService, never()).promote(any(PromotionRequest.class));
    }

    private RuleAuthoringDraft createDraft() {
        service.draft(PROJECT_KEY, candidate(), ACTOR);
        ArgumentCaptor<RuleAuthoringDraft> captured = ArgumentCaptor.forClass(RuleAuthoringDraft.class);
        verify(drafts).insert(captured.capture());
        return captured.getValue();
    }

    private DirectRuleDraftRequest candidate() {
        return new DirectRuleDraftRequest("Controllers stay thin", "Keep domain policy in services",
                RuleEnforcement.CONTEXT, false, null, Map.of(), List.of(), List.of(), List.of());
    }

    private DirectRulePromotionRequest promotionRequest(
            RuleAuthoringDraft draft, String humanRawText, boolean approved) {
        return new DirectRulePromotionRequest(draft.id(), draft.projectKey(), draft.candidateHash(),
                APPROVAL_HASH, CARD_HASH, PREVIEW.workflowContractVersion(), humanRawText,
                "turn-42", approved, 0.99);
    }

    private RuleVersion promotedVersion(UUID ruleId, String humanRawText, String humanTurnRef) {
        return new RuleVersion(ruleId, 1, candidate().statement(), candidate().rationale(),
                RuleEnforcement.CONTEXT, false, null, Map.of(), null, VERSION_HASH, null, null,
                APPROVAL_HASH, CARD_HASH, sha256(humanRawText), PREVIEW.workflowContractVersion(),
                RuleProvenance.DIRECT_HUMAN_POLICY, ACTOR, Instant.now(), humanTurnRef, Instant.now());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
