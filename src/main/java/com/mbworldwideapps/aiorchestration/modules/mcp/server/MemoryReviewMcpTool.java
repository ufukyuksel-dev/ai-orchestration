package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryConfirmResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryConfirmService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalDecisionResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalPendingResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalProxy;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryReviewMcpTool {

    private static final String HUMAN_CONFIRMED_APPROVAL_SCOPE = "memory.human_confirmed_approve";
    private static final String PENDING_SCOPE = "memory.pending";

    private final MemoryService memoryService;
    private final MemoryInlineApprovalProxy inlineApprovalProxy;
    private final MemoryConfirmService memoryConfirmService;
    private final McpAuditLogger auditLogger;

    @Autowired
    public MemoryReviewMcpTool(MemoryService memoryService, MemoryInlineApprovalProxy inlineApprovalProxy,
            MemoryConfirmService memoryConfirmService, McpAuditLogger auditLogger) {
        this.memoryService = memoryService;
        this.inlineApprovalProxy = inlineApprovalProxy;
        this.memoryConfirmService = memoryConfirmService;
        this.auditLogger = auditLogger;
    }

    public MemoryReviewMcpTool(MemoryService memoryService, MemoryReviewService memoryReviewService,
            MemoryInlineApprovalProxy inlineApprovalProxy, McpAuditLogger auditLogger) {
        this(memoryService, inlineApprovalProxy,
                new MemoryConfirmService(memoryService, memoryReviewService, new PiiScrubber(),
                        new com.mbworldwideapps.aiorchestration.config.MemoryConfirmProperties(0.75)),
                auditLogger);
    }

    @McpTool(name = "memory.pending", description = "List pending memory approval cards for this project so the agent can ask the human for confirmation.")
    public MemoryInlineApprovalPendingResponse pending(
            @McpToolParam(description = "Maximum number of pending cards to return, capped at 25", required = false) Integer limit,
            @McpToolParam(description = "Offset for paginating pending cards", required = false) Integer offset,
            @McpToolParam(description = "Optional project key. Local-first can list pending cards for any local project.", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProjectKey = McpProjectKeys.effective(context, projectKey);
        requireScope(context, PENDING_SCOPE, "memory.pending", effectiveProjectKey, started);
        try {
            MemoryInlineApprovalPendingResponse response = inlineApprovalProxy.pending(effectiveProjectKey, limit,
                    offset);
            auditLogger.log(context, "memory.pending", McpAuditLogger.hashQuery(effectiveProjectKey),
                    response.items().size(), latencyMs(started), "success", Map.of(
                            "count", response.count(),
                            "limit", response.limit(),
                            "offset", response.offset(),
                            "projectKey", effectiveProjectKey),
                    null);
            return response;
        } catch (RuntimeException e) {
            auditLogger.log(context, "memory.pending", McpAuditLogger.hashQuery(effectiveProjectKey), 0,
                    latencyMs(started), "error", Map.of("projectKey", effectiveProjectKey),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    public MemoryInlineApprovalPendingResponse pending(Integer limit, Integer offset) {
        return pending(limit, offset, null);
    }

    @Deprecated
    @McpTool(name = "memory.review.decide", description = "Deprecated. Use memory.confirm for the canonical AI-mediated path. Keeps legacy decision synonyms for older clients.")
    public MemoryInlineApprovalDecisionResponse decide(
            @McpToolParam(description = "Memory item UUID") String memoryId,
            @McpToolParam(description = "Decision: approve, reject, edit, or legacy Turkish/English synonym") String decision,
            @McpToolParam(description = "Required active human turn identifier") String humanTurnRef,
            @McpToolParam(description = "Edited memory text, required when decision is edit", required = false) String editedText,
            @McpToolParam(description = "Optional human-provided reason", required = false) String reason,
            @McpToolParam(description = "Optional project key. Local-first can decide pending memory from any local project.", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, HUMAN_CONFIRMED_APPROVAL_SCOPE, "memory.review.decide", memoryId, started);
        String normalizedDecision = normalizeLegacyDecision(decision);
        Boolean interpretedApproval = switch (normalizedDecision) {
            case "approve", "edit" -> true;
            case "reject" -> false;
            default -> null;
        };
        MemoryConfirmService.ConfirmCommand command = new MemoryConfirmService.ConfirmCommand(
                UUID.fromString(memoryId),
                normalizedDecision,
                interpretedApproval,
                blankToNull(reason) == null ? normalizedDecision : reason.trim(),
                1.0,
                humanTurnRef,
                editedText,
                safeReason(reason, "via_deprecated_memory_review_decide"),
                actor(context, humanTurnRef),
                projectKeyForMemory(context, memoryId, projectKey));
        try {
            MemoryReviewService.HumanConfirmationAudit audit = memoryConfirmService.auditFor(command);
            MemoryConfirmResponse response = memoryConfirmService.confirm(command);
            Map<String, Object> metadata = successMetadata(response, audit, true);
            auditLogger.log(context, "memory.review.decide", McpAuditLogger.hashQuery(memoryId), 1,
                    latencyMs(started), "success", metadata, null);
            return toInlineResponse(response, actor(context, humanTurnRef));
        } catch (MemoryConfirmService.MemoryConfirmAccessException e) {
            auditLogger.log(context, "memory.review.decide", McpAuditLogger.hashQuery(memoryId), 0,
                    latencyMs(started), "denied_scope", Map.of("deprecated", true), e.getClass().getSimpleName());
            throw new McpAccessException(e.getMessage());
        } catch (MemoryConfirmService.ConfirmValidationException e) {
            auditLogger.log(context, "memory.review.decide", McpAuditLogger.hashQuery(memoryId), 0,
                    latencyMs(started), "denied", withDeprecated(e.metadata()), e.reason());
            throw e;
        } catch (McpAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            auditLogger.log(context, "memory.review.decide", McpAuditLogger.hashQuery(memoryId), 0,
                    latencyMs(started), "error", Map.of("deprecated", true), e.getClass().getSimpleName());
            throw e;
        }
    }

    public MemoryInlineApprovalDecisionResponse decide(String memoryId, String decision, String humanTurnRef,
            String editedText, String reason) {
        return decide(memoryId, decision, humanTurnRef, editedText, reason, null);
    }

    @McpTool(name = "memory.confirm", description = "Apply an AI-interpreted human confirmation to pending memory. Raw human text is scrubbed and hashed before audit persistence.")
    public MemoryConfirmResponse confirm(
            @McpToolParam(description = "Memory item UUID") String memoryId,
            @McpToolParam(description = "Decision: approve, reject, or edit") String decision,
            @McpToolParam(description = "Whether the agent interpreted the human reply as approval") Boolean aiInterpretedAsApproval,
            @McpToolParam(description = "Raw human reply text for scrubbed/hash audit") String humanRawText,
            @McpToolParam(description = "Agent confidence in the interpreted decision, 0.0-1.0") Double agentConfidence,
            @McpToolParam(description = "Required active human turn identifier") String humanTurnRef,
            @McpToolParam(description = "Edited memory text, required when decision is edit", required = false) String editedText,
            @McpToolParam(description = "Optional human-provided reason", required = false) String reason,
            @McpToolParam(description = "Optional project key. Local-first can confirm pending memory from any local project.", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        requireScope(context, HUMAN_CONFIRMED_APPROVAL_SCOPE, "memory.confirm", memoryId, started);
        MemoryConfirmService.ConfirmCommand command = new MemoryConfirmService.ConfirmCommand(
                UUID.fromString(memoryId),
                decision,
                aiInterpretedAsApproval,
                humanRawText,
                agentConfidence,
                humanTurnRef,
                editedText,
                safeReason(reason, "via_mcp_memory_confirm"),
                actor(context, humanTurnRef),
                projectKeyForMemory(context, memoryId, projectKey));
        try {
            MemoryReviewService.HumanConfirmationAudit audit = memoryConfirmService.auditFor(command);
            MemoryConfirmResponse response = memoryConfirmService.confirm(command);
            auditLogger.log(context, "memory.confirm", McpAuditLogger.hashQuery(memoryId), 1, latencyMs(started),
                    "success", successMetadata(response, audit, false), null);
            return response;
        } catch (MemoryConfirmService.MemoryConfirmAccessException e) {
            auditLogger.log(context, "memory.confirm", McpAuditLogger.hashQuery(memoryId), 0, latencyMs(started),
                    "denied_scope", Map.of(), e.getClass().getSimpleName());
            throw new McpAccessException(e.getMessage());
        } catch (MemoryConfirmService.ConfirmValidationException e) {
            auditLogger.log(context, "memory.confirm", McpAuditLogger.hashQuery(memoryId), 0, latencyMs(started),
                    "denied", e.metadata(), e.reason());
            throw e;
        } catch (McpAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            auditLogger.log(context, "memory.confirm", McpAuditLogger.hashQuery(memoryId), 0, latencyMs(started),
                    "error", Map.of(), e.getClass().getSimpleName());
            throw e;
        }
    }

    public MemoryConfirmResponse confirm(String memoryId, String decision, Boolean aiInterpretedAsApproval,
            String humanRawText, Double agentConfidence, String humanTurnRef, String editedText, String reason) {
        return confirm(memoryId, decision, aiInterpretedAsApproval, humanRawText, agentConfidence, humanTurnRef,
                editedText, reason, null);
    }

    private MemoryInlineApprovalDecisionResponse toInlineResponse(MemoryConfirmResponse response, String actor) {
        MemoryItem item = memoryService.findById(response.memoryId());
        return new MemoryInlineApprovalDecisionResponse(response.memoryId(), response.decision(), item,
                response.replacement(), actor);
    }

    private void requireScope(McpClientContext context, String requiredScope, String toolName, String query,
            Instant started) {
        if (!context.hasScope(requiredScope)) {
            auditLogger.log(context, toolName, query, 0, started, "denied_scope");
            throw new McpAccessException("Missing MCP scope: " + requiredScope);
        }
    }

    private String projectKeyForMemory(McpClientContext context, String memoryId, String requestedProjectKey) {
        if (blankToNull(requestedProjectKey) != null) {
            return McpProjectKeys.effective(context, requestedProjectKey);
        }
        if (!McpProjectKeys.isLocalTrust(context)) {
            return context.projectKey();
        }
        MemoryItem item = memoryService.findById(UUID.fromString(memoryId));
        if (item.projectKey() != null && !item.projectKey().isBlank()) {
            return item.projectKey();
        }
        return context.projectKey();
    }

    private static Map<String, Object> successMetadata(MemoryConfirmResponse response,
            MemoryReviewService.HumanConfirmationAudit audit, boolean deprecated) {
        Map<String, Object> metadata = new LinkedHashMap<>(MemoryConfirmService.successMetadata(response, audit));
        if (deprecated) {
            metadata.put("deprecated", true);
        }
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> withDeprecated(Map<String, Object> metadata) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        copy.put("deprecated", true);
        return Map.copyOf(copy);
    }

    private static String normalizeLegacyDecision(String decision) {
        String normalized = blankToNull(decision) == null ? "" : decision.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "approve", "approved", "onayla", "onay" -> "approve";
            case "reject", "rejected", "reddet", "red" -> "reject";
            case "edit", "duzelt", "düzelt", "düzenle", "duzenle", "düzeltip kaydet", "duzeltip kaydet" -> "edit";
            default -> normalized;
        };
    }

    private static String actor(McpClientContext context, String humanTurnRef) {
        String turnRef = blankToNull(humanTurnRef) == null ? "missing" : humanTurnRef.trim();
        return "mcp:%s:turn:%s".formatted(context.clientId(), turnRef);
    }

    private static String safeReason(String reason, String defaultReason) {
        return blankToNull(reason) == null ? defaultReason : reason.trim();
    }

    private static long latencyMs(Instant started) {
        return java.time.Duration.between(started, Instant.now()).toMillis();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
