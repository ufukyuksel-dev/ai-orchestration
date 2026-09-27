package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.MemoryConfirmProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryConfirmService {

    private final MemoryService memoryService;
    private final MemoryReviewService memoryReviewService;
    private final PiiScrubber piiScrubber;
    private final MemoryConfirmProperties properties;

    public MemoryConfirmService(MemoryService memoryService, MemoryReviewService memoryReviewService,
            PiiScrubber piiScrubber, MemoryConfirmProperties properties) {
        this.memoryService = memoryService;
        this.memoryReviewService = memoryReviewService;
        this.piiScrubber = piiScrubber;
        this.properties = properties;
    }

    @Transactional(noRollbackFor = ConfirmValidationException.class)
    public MemoryConfirmResponse confirm(ConfirmCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("confirm command is required");
        }
        MemoryItem item = memoryService.findById(command.memoryId());
        enforceProjectBoundary(command, item);
        String decision = normalizeDecision(command.decision());
        String actor = blankToNull(command.actor()) == null ? "human:inline-confirm" : command.actor().trim();
        MemoryReviewService.HumanConfirmationAudit audit = humanAudit(command);

        validatePendingStatus(item, actor, audit);
        validateHumanTurnRef(command.humanTurnRef(), item, actor, audit);
        validateAgentConfidence(command.agentConfidence(), item, actor, audit);
        validateDecisionIntent(decision, command.aiInterpretedAsApproval(), command.editedText(), item, actor, audit);

        return switch (decision) {
            case "approve" -> approveConfirmed(item, actor, safeReason(command.reason()), audit, true);
            case "reject" -> rejectConfirmed(item, actor, safeReason(command.reason()), audit, false);
            case "edit" -> editConfirmed(item, actor, safeReason(command.reason()), audit, command.editedText().trim());
            default -> throw deny(item, actor, "invalid_decision", audit, Map.of("decision", decision));
        };
    }

    private MemoryConfirmResponse approveConfirmed(MemoryItem item, String actor, String reason,
            MemoryReviewService.HumanConfirmationAudit audit, boolean aiInterpretedAsApproval) {
        MemoryItem approved = memoryReviewService.approveHumanConfirmed(item.id(), actor, reason, audit);
        return new MemoryConfirmResponse(approved.id(), approved.status().value(), "approve",
                aiInterpretedAsApproval, null);
    }

    private MemoryConfirmResponse rejectConfirmed(MemoryItem item, String actor, String reason,
            MemoryReviewService.HumanConfirmationAudit audit, boolean aiInterpretedAsApproval) {
        MemoryItem rejected = memoryReviewService.rejectHumanConfirmed(item.id(), actor, reason, audit);
        return new MemoryConfirmResponse(rejected.id(), rejected.status().value(), "reject",
                aiInterpretedAsApproval, null);
    }

    private MemoryConfirmResponse editConfirmed(MemoryItem original, String actor, String reason,
            MemoryReviewService.HumanConfirmationAudit audit, String editedText) {
        Map<String, Object> metadata = new LinkedHashMap<>(original.metadata() == null ? Map.of() : original.metadata());
        metadata.put("editedFrom", original.id().toString());
        metadata.put("editedBy", actor);
        metadata.put("humanConfirmed", true);
        metadata.put("humanTurnRef", audit.humanTurnRef());
        metadata.put("aiInterpretedAsApproval", audit.aiInterpretedAsApproval());
        metadata.put("agentConfidence", audit.agentConfidence());
        metadata.put("humanRawTextScrubbed", audit.humanRawTextScrubbed());
        metadata.put("humanRawTextHash", audit.humanRawTextHash());
        metadata.put("humanRawTextLength", audit.humanRawTextLength());
        metadata.put("originalSourceRef", original.sourceRef());
        metadata.put("originalConfidence", original.confidence());
        metadata.put("confidence", original.confidence());

        MemoryItem edited = memoryService.create(new CreateMemoryRequest(
                original.scope(),
                original.projectKey(),
                original.memoryType(),
                original.summary(),
                editedText,
                original.tags() == null ? List.of() : original.tags(),
                original.confidence(),
                MemoryStatus.PENDING_REVIEW,
                original.sourceType(),
                "memory-confirm-edit:%s:%s".formatted(original.id(), UUID.randomUUID()),
                actor,
                metadata,
                Instant.now(),
                original.expiresAt()));
        memoryReviewService.rejectForEdit(original.id(), actor, reason, edited.id(), audit);
        MemoryItem approved = memoryReviewService.approveAfterHumanEdit(edited.id(), actor, reason, original.id(),
                audit);
        MemoryItem rejectedOriginal = memoryService.findById(original.id());
        return new MemoryConfirmResponse(rejectedOriginal.id(), rejectedOriginal.status().value(), "edit",
                true, approved);
    }

    private static void enforceProjectBoundary(ConfirmCommand command, MemoryItem item) {
        if (item.scope() == MemoryScope.PROJECT && item.projectKey() != null
                && !item.projectKey().equals(command.projectKey())) {
            throw new MemoryConfirmAccessException("Memory belongs to a different project");
        }
    }

    private void validatePendingStatus(MemoryItem item, String actor,
            MemoryReviewService.HumanConfirmationAudit audit) {
        if (item.status() == MemoryStatus.PENDING_REVIEW) {
            return;
        }
        String reason = switch (item.status()) {
            case ACTIVE -> "memory_already_active";
            case REJECTED -> "memory_already_rejected";
            case ARCHIVED -> "memory_archived";
            default -> "memory_not_pending";
        };
        throw deny(item, actor, reason, audit, Map.of("status", item.status().value()));
    }

    private void validateHumanTurnRef(String humanTurnRef, MemoryItem item, String actor,
            MemoryReviewService.HumanConfirmationAudit audit) {
        if (blankToNull(humanTurnRef) == null) {
            throw deny(item, actor, "human_turn_ref_required", audit, Map.of());
        }
    }

    private void validateAgentConfidence(Double agentConfidence, MemoryItem item, String actor,
            MemoryReviewService.HumanConfirmationAudit audit) {
        if (agentConfidence == null || agentConfidence < properties.minAgentConfidence()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("lowAgentConfidence", true);
            metadata.put("minAgentConfidence", properties.minAgentConfidence());
            if (agentConfidence != null) {
                metadata.put("agentConfidence", agentConfidence);
            }
            throw deny(item, actor, "low_agent_confidence", audit, metadata);
        }
    }

    private void validateDecisionIntent(String decision, Boolean aiInterpretedAsApproval, String editedText,
            MemoryItem item, String actor, MemoryReviewService.HumanConfirmationAudit audit) {
        if (aiInterpretedAsApproval == null) {
            throw deny(item, actor, "ai_interpreted_as_approval_required", audit, Map.of());
        }
        switch (decision) {
            case "approve" -> {
                if (!aiInterpretedAsApproval) {
                    throw deny(item, actor, "decision_intent_mismatch", audit,
                            Map.of("decision", decision, "aiInterpretedAsApproval", false));
                }
            }
            case "reject" -> {
                if (aiInterpretedAsApproval) {
                    throw deny(item, actor, "decision_intent_mismatch", audit,
                            Map.of("decision", decision, "aiInterpretedAsApproval", true));
                }
            }
            case "edit" -> {
                if (!aiInterpretedAsApproval) {
                    throw deny(item, actor, "decision_intent_mismatch", audit,
                            Map.of("decision", decision, "aiInterpretedAsApproval", false));
                }
                if (blankToNull(editedText) == null) {
                    throw deny(item, actor, "edited_text_required", audit, Map.of("decision", decision));
                }
            }
            default -> throw deny(item, actor, "invalid_decision", audit, Map.of("decision", decision));
        }
    }

    private ConfirmValidationException deny(MemoryItem item, String actor, String reason,
            MemoryReviewService.HumanConfirmationAudit audit, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", "denied");
        metadata.put("reason", reason);
        metadata.put("memoryId", item.id().toString());
        metadata.put("status", item.status().value());
        metadata.put("humanRawTextScrubbed", audit.humanRawTextScrubbed());
        metadata.put("humanRawTextHash", audit.humanRawTextHash());
        metadata.put("humanRawTextLength", audit.humanRawTextLength());
        if (audit.aiInterpretedAsApproval() != null) {
            metadata.put("aiInterpretedAsApproval", audit.aiInterpretedAsApproval());
        }
        if (audit.agentConfidence() != null) {
            metadata.put("agentConfidence", audit.agentConfidence());
        }
        if (extraMetadata != null) {
            extraMetadata.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    metadata.put(key, value);
                }
            });
        }
        memoryReviewService.recordHumanConfirmDenied(item.id(), actor, reason, audit, metadata);
        return new ConfirmValidationException(reason, metadata);
    }

    private MemoryReviewService.HumanConfirmationAudit humanAudit(ConfirmCommand command) {
        String raw = command.humanRawText() == null ? "" : command.humanRawText();
        return new MemoryReviewService.HumanConfirmationAudit(
                blankToNull(command.humanTurnRef()),
                command.aiInterpretedAsApproval(),
                command.agentConfidence(),
                piiScrubber.mask(raw),
                sha256(raw),
                raw.length());
    }

    public static Map<String, Object> successMetadata(MemoryConfirmResponse response,
            MemoryReviewService.HumanConfirmationAudit audit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", response.decision());
        metadata.put("newStatus", response.status());
        metadata.put("humanConfirmed", true);
        metadata.put("humanTurnRef", audit.humanTurnRef());
        metadata.put("aiInterpretedAsApproval", response.aiInterpretedAsApproval());
        metadata.put("agentConfidence", audit.agentConfidence());
        metadata.put("humanRawTextScrubbed", audit.humanRawTextScrubbed());
        metadata.put("humanRawTextHash", audit.humanRawTextHash());
        metadata.put("humanRawTextLength", audit.humanRawTextLength());
        if (response.replacement() != null) {
            metadata.put("replacementMemoryId", response.replacement().id().toString());
            metadata.put("replacementStatus", response.replacement().status().value());
        }
        return metadata;
    }

    public MemoryReviewService.HumanConfirmationAudit auditFor(ConfirmCommand command) {
        return humanAudit(command);
    }

    private static String normalizeDecision(String decision) {
        String normalized = blankToNull(decision) == null ? "" : decision.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "approve", "reject", "edit" -> normalized;
            default -> normalized;
        };
    }

    private static String safeReason(String reason) {
        return blankToNull(reason) == null ? "via_memory_confirm" : reason.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record ConfirmCommand(
            UUID memoryId,
            String decision,
            Boolean aiInterpretedAsApproval,
            String humanRawText,
            Double agentConfidence,
            String humanTurnRef,
            String editedText,
            String reason,
            String actor,
            String projectKey) {
    }

    public static class ConfirmValidationException extends IllegalArgumentException {
        private final String reason;
        private final Map<String, Object> metadata;

        public ConfirmValidationException(String reason, Map<String, Object> metadata) {
            super(reason);
            this.reason = reason;
            this.metadata = Map.copyOf(metadata);
        }

        public String reason() {
            return reason;
        }

        public Map<String, Object> metadata() {
            return metadata;
        }
    }

    public static class MemoryConfirmAccessException extends RuntimeException {

        public MemoryConfirmAccessException(String message) {
            super(message);
        }
    }
}
