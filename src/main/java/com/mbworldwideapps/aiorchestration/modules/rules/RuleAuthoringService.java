package com.mbworldwideapps.aiorchestration.modules.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audited public facade for immutable direct-human rule authoring. */
@Service
public class RuleAuthoringService {

    private final RuleAuthoringDraftRepository drafts;
    private final RulePromotionService promotionService;
    private final ObjectMapper canonicalMapper;

    public RuleAuthoringService(RuleAuthoringDraftRepository drafts, RulePromotionService promotionService,
            ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.promotionService = promotionService;
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public RuleAuthoringDraftResult draft(String projectKey, DirectRuleDraftRequest request, String actor) {
        String canonicalProjectKey = canonicalProjectKey(projectKey);
        String canonicalActor = required(actor, "draft actor");
        if (request == null) {
            throw new IllegalArgumentException("direct rule draft request is required");
        }
        RulePromotionCandidate candidate = promotionService.canonicalCandidate(
                request.toCandidate(canonicalProjectKey));
        RulePromotionPreview preview = promotionService.preview(candidate);
        String candidateJson = writeCanonical(DirectRuleDraftRequest.fromCandidate(candidate));
        String candidateHash = sha256(candidateJson);
        Instant now = Instant.now();
        RuleAuthoringDraft draft = new RuleAuthoringDraft(UUID.randomUUID(), canonicalProjectKey,
                candidateJson, candidateHash, canonicalActor, now, null, null, null, null, null);
        drafts.insert(draft);
        return new RuleAuthoringDraftResult(draft.id(), draft.projectKey(), draft.candidateHash(),
                preview.workflowContractVersion(), summary(candidate.statement()));
    }

    @Transactional(readOnly = true)
    public RuleAuthoringPreviewResult preview(UUID draftId, String expectedProjectKey) {
        RuleAuthoringDraft draft = drafts.findById(requireId(draftId))
                .orElseThrow(() -> new IllegalArgumentException("rule authoring draft not found: " + draftId));
        requireProject(draft, expectedProjectKey);
        RulePromotionCandidate candidate = storedCandidate(draft);
        RulePromotionPreview preview = promotionService.preview(candidate);
        return previewResult(draft, preview);
    }

    @Transactional
    public RuleAuthoringPromotionResult promote(DirectRulePromotionRequest request, String actor) {
        if (request == null) {
            throw new IllegalArgumentException("direct rule promotion request is required");
        }
        if (!request.aiInterpretedAsApproval()) {
            throw new IllegalArgumentException("explicit human approval is required");
        }
        if (!Double.isFinite(request.agentConfidence())
                || request.agentConfidence() < 0.0d || request.agentConfidence() > 1.0d) {
            throw new IllegalArgumentException("agent confidence must be between 0.0 and 1.0");
        }
        required(request.humanTurnRef(), "human turn reference");
        required(request.humanRawText(), "human approval text");
        String canonicalActor = required(actor, "promotion actor");
        RuleAuthoringDraft draft = drafts.findByIdForUpdate(requireId(request.draftId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "rule authoring draft not found: " + request.draftId()));
        requireProject(draft, request.expectedProjectKey());
        requireHash("candidate", draft.candidateHash(), request.expectedCandidateHash());
        RulePromotionCandidate candidate = storedCandidate(draft);
        String requestHash = promotionRequestHash(request, canonicalActor);

        if (draft.promoted()) {
            requireHash("promotion request", draft.promotedRequestHash(), requestHash);
            requireHash("approval content", draft.promotedApprovalHash(), request.expectedApprovalContentHash());
            return promotionResult(draft, true);
        }

        RulePromotionPreview preview = promotionService.preview(candidate);
        requireHash("approval content", preview.approvalContentHash(), request.expectedApprovalContentHash());
        requireHash("confirmation card", preview.confirmationCardHash(), request.expectedConfirmationCardHash());
        if (!preview.workflowContractVersion().equals(request.workflowContractVersion())) {
            throw new IllegalStateException("workflow contract mismatch");
        }
        RuleHumanApprovalEvidence approval = new RuleHumanApprovalEvidence(canonicalActor,
                required(request.humanTurnRef(), "human turn reference"),
                required(request.humanRawText(), "human approval text"),
                request.aiInterpretedAsApproval(), request.agentConfidence());
        RuleVersion version = promotionService.promote(new PromotionRequest(candidate,
                request.expectedApprovalContentHash(), request.expectedConfirmationCardHash(),
                request.workflowContractVersion(), approval));
        requirePromotionEvidence(version, canonicalActor, request);
        Instant promotedAt = Instant.now();
        if (!drafts.bindPromotion(draft.id(), draft.candidateHash(), version.ruleId(), version.version(),
                version.approvalContentHash(), requestHash, promotedAt)) {
            throw new IllegalStateException("concurrent rule draft promotion detected");
        }
        RuleAuthoringDraft promoted = new RuleAuthoringDraft(draft.id(), draft.projectKey(), draft.candidateJson(),
                draft.candidateHash(), draft.createdBy(), draft.createdAt(), version.ruleId(), version.version(),
                version.approvalContentHash(), requestHash, promotedAt);
        return promotionResult(promoted, false);
    }

    private RulePromotionCandidate storedCandidate(RuleAuthoringDraft draft) {
        try {
            DirectRuleDraftRequest stored = canonicalMapper.readValue(draft.candidateJson(),
                    DirectRuleDraftRequest.class);
            RulePromotionCandidate candidate = promotionService.canonicalCandidate(stored.toCandidate(draft.projectKey()));
            String canonicalJson = writeCanonical(DirectRuleDraftRequest.fromCandidate(candidate));
            requireHash("stored candidate", draft.candidateHash(), sha256(canonicalJson));
            return candidate;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored rule authoring candidate is invalid", exception);
        }
    }

    private static void requirePromotionEvidence(RuleVersion version, String actor,
            DirectRulePromotionRequest request) {
        if (!version.approvedBy().equals(actor)
                || !version.humanTurnRef().equals(request.humanTurnRef().trim())
                || !version.humanRawTextHash().equals(
                        sha256(required(request.humanRawText(), "human approval text")))) {
            throw new IllegalStateException("promotion replay carries different human approval evidence");
        }
    }

    private String promotionRequestHash(DirectRulePromotionRequest request, String actor) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("actor", actor);
        material.put("agentConfidence", request.agentConfidence());
        material.put("aiInterpretedAsApproval", request.aiInterpretedAsApproval());
        material.put("candidateHash", request.expectedCandidateHash());
        material.put("confirmationCardHash", request.expectedConfirmationCardHash());
        material.put("draftId", request.draftId().toString());
        material.put("humanRawTextHash", sha256(required(request.humanRawText(), "human approval text")));
        material.put("humanTurnRef", required(request.humanTurnRef(), "human turn reference"));
        material.put("projectKey", canonicalProjectKey(request.expectedProjectKey()));
        material.put("promotionApprovalHash", request.expectedApprovalContentHash());
        material.put("workflowContractVersion", request.workflowContractVersion());
        return sha256(writeCanonical(material));
    }

    private static RuleAuthoringPreviewResult previewResult(
            RuleAuthoringDraft draft, RulePromotionPreview preview) {
        return new RuleAuthoringPreviewResult(draft.id(), draft.projectKey(), draft.candidateHash(),
                preview.workflowContractVersion(), preview.versionContentHash(), preview.approvalContentHash(),
                preview.confirmationCard(), preview.confirmationCardHash());
    }

    private static RuleAuthoringPromotionResult promotionResult(RuleAuthoringDraft draft, boolean replayed) {
        return new RuleAuthoringPromotionResult(draft.id(), draft.projectKey(), draft.candidateHash(),
                draft.promotedRuleId(), draft.promotedRuleVersion(), draft.promotedApprovalHash(), replayed);
    }

    private void requireProject(RuleAuthoringDraft draft, String expectedProjectKey) {
        String expected = canonicalProjectKey(expectedProjectKey);
        if (!java.util.Objects.equals(draft.projectKey(), expected)) {
            throw new IllegalStateException("rule draft project changed or is unauthorized");
        }
    }

    private String writeCanonical(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("rule authoring payload is not serializable", exception);
        }
    }

    private static void requireHash(String label, String actual, String expected) {
        if (!isSha256(expected) || !constantTimeEquals(actual, expected)) {
            throw new IllegalStateException(label + " changed or is invalid");
        }
    }

    private static String canonicalProjectKey(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("project key must be non-blank when supplied");
        }
        String canonical = value.trim();
        if (canonical.length() > 256) {
            throw new IllegalArgumentException("project key exceeds 256 characters");
        }
        return canonical;
    }

    private static UUID requireId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("draft id is required");
        }
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String summary(String statement) {
        return statement.length() <= 200 ? statement : statement.substring(0, 197) + "...";
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return actual != null && expected != null && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
