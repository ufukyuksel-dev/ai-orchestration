package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.core.security.AdminTokenValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/memory/inline-approval")
public class MemoryInlineApprovalController {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final MemoryInlineApprovalProxy approvalProxy;
    private final MemoryInlineApprovalTurnService turnService;
    private final AdminTokenValidator adminTokenValidator;

    public MemoryInlineApprovalController(MemoryInlineApprovalProxy approvalProxy,
            MemoryInlineApprovalTurnService turnService, AdminTokenValidator adminTokenValidator) {
        this.approvalProxy = approvalProxy;
        this.turnService = turnService;
        this.adminTokenValidator = adminTokenValidator;
    }

    @GetMapping("/pending")
    public MemoryInlineApprovalPendingResponse pending(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        adminTokenValidator.require(adminToken);
        return approvalProxy.pending(projectKey, limit, offset);
    }

    @GetMapping("/poll-render")
    public MemoryInlineApprovalRenderResponse pollRender(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String turnId) {
        adminTokenValidator.require(adminToken);
        return turnService.pollAndRender(projectKey, sessionId, turnId);
    }

    @PostMapping("/turn-decision")
    public MemoryInlineApprovalTurnResponse turnDecision(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @RequestBody AgentInterpretedDecisionRequest request) {
        adminTokenValidator.require(adminToken);
        return turnService.handleHumanTurn(request);
    }
}
