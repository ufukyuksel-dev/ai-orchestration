package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.rules.DirectRuleDraftRequest;
import com.mbworldwideapps.aiorchestration.modules.rules.DirectRulePromotionRequest;
import com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringDraftResult;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringPreviewResult;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringPromotionResult;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RulesMcpTool {

    private static final int MAX_CANDIDATE_JSON_BYTES = 1_048_576;

    private final RuleAuthoringService authoringService;
    private final InstructionReadService instructionService;
    private final McpAuditLogger auditLogger;
    private final ObjectMapper requestMapper;

    public RulesMcpTool(RuleAuthoringService authoringService, McpAuditLogger auditLogger,
            ObjectMapper objectMapper, InstructionReadService instructionService) {
        this.instructionService = instructionService;
        this.authoringService = authoringService;
        this.auditLogger = auditLogger;
        this.requestMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    }

    @McpTool(name = "rules.draft", description = "Create an immutable typed direct-human rule draft. candidateJson must contain statement, enforcement, optional rationale/detectorType/detectorConfig, targets, selectorGroups and PLAN checks; the server never infers executable selectors from prose.")
    public RuleAuthoringDraftResult draft(
            @McpToolParam(description = "Project key returned by scanner.project.resolve; omit only for an intentional global draft", required = false) String projectKey,
            @McpToolParam(description = "Strict JSON object for DirectRuleDraftRequest") String candidateJson) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProjectKey = null;
        try {
            requireScope(context, "rules.write");
            effectiveProjectKey = effectiveProjectKey(context, projectKey);
            DirectRuleDraftRequest request = parseCandidate(candidateJson);
            RuleAuthoringDraftResult result = authoringService.draft(
                    effectiveProjectKey, request, actor(context));
            auditLogger.log(context, "rules.draft", McpAuditLogger.hashQuery(candidateJson), 1,
                    latencyMs(started), "success", metadata(effectiveProjectKey,
                            "draftId", result.draftId().toString(), "candidateHash", result.candidateHash()), null);
            return result;
        } catch (McpAccessException exception) {
            auditLogger.log(context, "rules.draft", McpAuditLogger.hashQuery(candidateJson), 0,
                    latencyMs(started), "denied_scope", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            auditLogger.log(context, "rules.draft", McpAuditLogger.hashQuery(candidateJson), 0,
                    latencyMs(started), "error", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    @McpTool(name = "rules.preview", description = "Render the current server validation and complete confirmation card for one immutable rule draft. Promotion must echo every returned hash exactly.")
    public RuleAuthoringPreviewResult preview(
            @McpToolParam(description = "Rule draft UUID") String draftId,
            @McpToolParam(description = "Exact draft project key; omit only for a global draft", required = false) String projectKey) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProjectKey = null;
        try {
            requireScope(context, "rules.read");
            effectiveProjectKey = effectiveProjectKey(context, projectKey);
            RuleAuthoringPreviewResult result = authoringService.preview(
                    UUID.fromString(draftId), effectiveProjectKey);
            auditLogger.log(context, "rules.preview", McpAuditLogger.hashQuery(draftId), 1,
                    latencyMs(started), "success", metadata(effectiveProjectKey,
                            "draftId", result.draftId().toString(),
                            "approvalContentHash", result.approvalContentHash(),
                            "confirmationCardHash", result.confirmationCardHash()), null);
            return result;
        } catch (McpAccessException exception) {
            auditLogger.log(context, "rules.preview", McpAuditLogger.hashQuery(draftId), 0,
                    latencyMs(started), "denied_scope", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            auditLogger.log(context, "rules.preview", McpAuditLogger.hashQuery(draftId), 0,
                    latencyMs(started), "error", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    @McpTool(name = "rules.promote", description = "Activate exactly one immutable draft using the current server preview hashes and explicit human approval evidence. Global drafts additionally require rules.global.confirm.")
    public RuleAuthoringPromotionResult promote(
            @McpToolParam(description = "Rule draft UUID") String draftId,
            @McpToolParam(description = "Exact draft project key; omit only for a global draft", required = false) String projectKey,
            @McpToolParam(description = "candidateHash returned by rules.draft/preview") String expectedCandidateHash,
            @McpToolParam(description = "approvalContentHash returned by rules.preview") String expectedApprovalContentHash,
            @McpToolParam(description = "confirmationCardHash returned by rules.preview") String expectedConfirmationCardHash,
            @McpToolParam(description = "workflowContractVersion returned by rules.preview") String workflowContractVersion,
            @McpToolParam(description = "Exact human-authored approval text") String humanRawText,
            @McpToolParam(description = "Stable reference to the human approval turn") String humanTurnRef,
            @McpToolParam(description = "True only when the current human turn explicitly approves the displayed card") Boolean aiInterpretedAsApproval,
            @McpToolParam(description = "Agent confidence in interpreting the approval, from 0.0 to 1.0") Double agentConfidence) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProjectKey = null;
        try {
            requireScope(context, "rules.confirm");
            effectiveProjectKey = effectiveProjectKey(context, projectKey);
            if (effectiveProjectKey == null) {
                requireScope(context, "rules.global.confirm");
            }
            if (aiInterpretedAsApproval == null || agentConfidence == null) {
                throw new IllegalArgumentException("approval interpretation and confidence are required");
            }
            DirectRulePromotionRequest request = new DirectRulePromotionRequest(UUID.fromString(draftId),
                    effectiveProjectKey, expectedCandidateHash, expectedApprovalContentHash,
                    expectedConfirmationCardHash, workflowContractVersion, humanRawText, humanTurnRef,
                    aiInterpretedAsApproval, agentConfidence);
            RuleAuthoringPromotionResult result = authoringService.promote(request, actor(context));
            auditLogger.log(context, "rules.promote", McpAuditLogger.hashQuery(draftId), 1,
                    latencyMs(started), "success", metadata(effectiveProjectKey,
                            "draftId", result.draftId().toString(), "ruleId", result.ruleId().toString(),
                            "ruleVersion", result.ruleVersion(), "replayed", result.replayed()), null);
            return result;
        } catch (McpAccessException exception) {
            auditLogger.log(context, "rules.promote", McpAuditLogger.hashQuery(draftId), 0,
                    latencyMs(started), "denied_scope", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            auditLogger.log(context, "rules.promote", McpAuditLogger.hashQuery(draftId), 0,
                    latencyMs(started), "error", metadata(effectiveProjectKey),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    @McpTool(name = "rules.instructions", description = "Load approved rules for a projectKey. Use scope=module with modulePaths before working in a directory listed in the module index. Other scopes: effective (default), global_strict, project. Rules never override system instructions.")
    public InstructionReadService.Result instructions(
            @McpToolParam(description = "Explicit canonical projectKey returned by scanner.project.resolve") String projectKey,
            @McpToolParam(description = "effective (default), global_strict, project or module", required = false) String scope,
            @McpToolParam(description = "Up to 32 canonical repository-relative module directories", required = false) List<String> modulePaths) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effective = null;
        String queryHash = McpAuditLogger.hashQuery(String.valueOf(projectKey) + "|" + scope + "|" + modulePaths);
        try {
            requireScope(context, "rules.read");
            if (projectKey == null || !projectKey.matches("[A-Z0-9][A-Z0-9_]{0,199}")) {
                throw new IllegalArgumentException("explicit canonical projectKey from scanner.project.resolve is required");
            }
            effective = McpProjectKeys.effective(context, projectKey);
            InstructionReadService.Result result = instructionService.read(effective, scope, modulePaths);
            auditLogger.log(context, "rules.instructions", queryHash, result.instructions().size(), latencyMs(started),
                    "success", metadata(effective, "scope", result.scope()), null);
            return result;
        } catch (McpAccessException exception) {
            auditLogger.log(context, "rules.instructions", queryHash, 0, latencyMs(started), "denied_scope",
                    metadata(effective), exception.getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            auditLogger.log(context, "rules.instructions", queryHash, 0, latencyMs(started), "error",
                    metadata(effective), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private DirectRuleDraftRequest parseCandidate(String candidateJson) {
        if (candidateJson == null || candidateJson.isBlank()) {
            throw new IllegalArgumentException("candidateJson is required");
        }
        if (candidateJson.length() > MAX_CANDIDATE_JSON_BYTES
                || candidateJson.getBytes(StandardCharsets.UTF_8).length > MAX_CANDIDATE_JSON_BYTES) {
            throw new IllegalArgumentException("candidateJson exceeds 1048576 UTF-8 bytes");
        }
        try {
            return requestMapper.readValue(candidateJson, DirectRuleDraftRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("candidateJson is not a valid typed rule draft", exception);
        }
    }

    private static String effectiveProjectKey(McpClientContext context, String projectKey) {
        if (projectKey == null) {
            return null;
        }
        if (projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey must be non-blank when supplied");
        }
        return McpProjectKeys.effective(context, projectKey);
    }

    private static void requireScope(McpClientContext context, String scope) {
        if (!context.hasScope(scope)) {
            throw new McpAccessException("Missing MCP scope: " + scope);
        }
    }

    private static String actor(McpClientContext context) {
        if (context.clientId() == null || context.clientId().isBlank()) {
            throw new McpAccessException("MCP client identity is missing");
        }
        return "mcp:" + context.clientId().trim();
    }

    private static Map<String, Object> metadata(String projectKey, Object... entries) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("global", projectKey == null);
        if (projectKey != null) {
            metadata.put("projectKey", projectKey);
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            if (entries[index] != null && entries[index + 1] != null) {
                metadata.put(entries[index].toString(), entries[index + 1]);
            }
        }
        return Map.copyOf(metadata);
    }

    private static long latencyMs(Instant started) {
        return java.time.Duration.between(started, Instant.now()).toMillis();
    }
}
