package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemoryInlineApprovalTurnService {

    private static final Logger log = LoggerFactory.getLogger(MemoryInlineApprovalTurnService.class);

    private final MemoryInlineApprovalProxy approvalProxy;
    private final MemoryConfirmService memoryConfirmService;

    public MemoryInlineApprovalTurnService(MemoryInlineApprovalProxy approvalProxy,
            MemoryConfirmService memoryConfirmService) {
        this.approvalProxy = approvalProxy;
        this.memoryConfirmService = memoryConfirmService;
    }

    public MemoryInlineApprovalRenderResponse pollAndRender(String projectKey, String sessionId, String turnId) {
        try {
            MemoryInlineApprovalPendingResponse pending = approvalProxy.pending(projectKey, 25, 0);
            if (pending.items().isEmpty()) {
                return MemoryInlineApprovalRenderResponse.none();
            }
            MemoryInlineApprovalCard active = pending.items().getFirst();
            return new MemoryInlineApprovalRenderResponse(
                    "pending",
                    true,
                    renderCard(active, pending.count()),
                    active,
                    pending.count(),
                    Map.of("sessionId", safe(sessionId), "turnId", safe(turnId)));
        } catch (RuntimeException e) {
            log.warn("Inline memory approval polling failed projectKey={} sessionId={} turnId={} errorType={}",
                    projectKey, sessionId, turnId, e.getClass().getSimpleName());
            return MemoryInlineApprovalRenderResponse.degraded(e.getClass().getSimpleName());
        }
    }

    public MemoryInlineApprovalTurnResponse handleHumanTurn(AgentInterpretedDecisionRequest request) {
        if (request == null || request.memoryId() == null) {
            return MemoryInlineApprovalTurnResponse.noDecision();
        }
        MemoryConfirmService.ConfirmCommand command = new MemoryConfirmService.ConfirmCommand(
                request.memoryId(),
                request.decision(),
                request.aiInterpretedAsApproval(),
                request.humanRawText(),
                request.agentConfidence(),
                request.humanTurnRef(),
                request.editedText(),
                request.reason(),
                "human:inline:" + safe(request.sessionId()),
                request.projectKey());
        try {
            MemoryConfirmResponse applied = memoryConfirmService.confirm(command);
            return new MemoryInlineApprovalTurnResponse("applied", true,
                    renderApplied(applied), applied, Map.of("memoryId", applied.memoryId().toString()));
        } catch (MemoryConfirmService.ConfirmValidationException e) {
            return new MemoryInlineApprovalTurnResponse("invalid", false, e.getMessage(), null,
                    Map.of("errorType", e.getClass().getSimpleName(), "reason", e.reason()));
        } catch (MemoryConfirmService.MemoryConfirmAccessException e) {
            return new MemoryInlineApprovalTurnResponse("denied_scope", false, e.getMessage(), null,
                    Map.of("errorType", e.getClass().getSimpleName()));
        } catch (IllegalArgumentException e) {
            return new MemoryInlineApprovalTurnResponse("invalid", false, e.getMessage(), null,
                    Map.of("errorType", e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            log.warn("Inline memory approval decision failed projectKey={} sessionId={} turnRef={} errorType={}",
                    request.projectKey(), request.sessionId(), request.humanTurnRef(), e.getClass().getSimpleName());
            return new MemoryInlineApprovalTurnResponse("degraded", false, "", null,
                    Map.of("errorType", e.getClass().getSimpleName()));
        }
    }

    private static String renderCard(MemoryInlineApprovalCard card, int totalPending) {
        String prefix = card.memoryId().toString().substring(0, 8);
        return """
                Pending memory review (1 of %d):
                  - ID: %s
                  - Scope: %s%s
                  - Type: %s | confidence: %.2f
                  - Reason: %s
                  - Proposed: "%s"

                Ask the human for approve/reject/edit, interpret the answer, then call memory.confirm with this ID.
                """.formatted(totalPending, prefix, card.scope(), scopeDecision(card), card.type(),
                card.confidence(), card.reason(), truncate(card.proposedContent())).strip();
    }

    private static String scopeDecision(MemoryInlineApprovalCard card) {
        if (card.scopeDecisionMode() == null || card.scopeDecisionMode().isBlank()) {
            return "";
        }
        String reason = card.scopeDecisionReason() == null || card.scopeDecisionReason().isBlank()
                ? ""
                : ", " + card.scopeDecisionReason();
        return " (" + card.scopeDecisionMode() + reason + ")";
    }

    private static String renderApplied(MemoryConfirmResponse response) {
        if ("edit".equals(response.decision())) {
            return "Memory review updated: original rejected, edited replacement approved. ID: "
                    + response.replacement().id();
        }
        return "Memory review %s. ID: %s, status: %s".formatted(
                response.decision(), response.memoryId(), response.status());
    }

    private static String truncate(String value) {
        return MemoryTextPreview.truncate(value, MemoryAtomLimits.EXCERPT_MAX_CHARS);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
