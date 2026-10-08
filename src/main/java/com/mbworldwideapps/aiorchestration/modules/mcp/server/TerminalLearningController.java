package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningCaptureCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow backend surface for the typed {@code ai_orch} terminal bridge. Any authenticated client may use it; the
 * learning tool applies the same scope and session checks as its MCP form.
 */
@RestController
@RequestMapping("/terminal/api/learning")
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${ai-orchestration.learning.enabled:false}' == 'true'")
public final class TerminalLearningController {

    private final ScannerMcpTool scanner;
    private final AgentLearningMcpTool learning;

    public TerminalLearningController(ScannerMcpTool scanner, AgentLearningMcpTool learning) {
        this.scanner = scanner;
        this.learning = learning;
    }

    public record CaptureRequest(@NotBlank @Size(max = 4096) String cwd,
            @NotBlank @Size(max = 128) String taskRunId,
            List<CaptureCandidate> learningCandidates) {
        public CaptureRequest {
            learningCandidates = learningCandidates == null ? List.of() : List.copyOf(learningCandidates);
        }
    }

    @PostMapping("/capture")
    public CompactLearnReceipt capture(@Valid @RequestBody CaptureRequest request) {
        ScannerProjectResolveResponse project = scanner.resolveProject(request.cwd(), null);
        if (project.workspaceBindingId() == null) {
            throw new IllegalStateException("TERMINAL_WORKSPACE_BINDING_UNAVAILABLE");
        }
        LearningCaptureCommand command = new LearningCaptureCommand(request.taskRunId(), project.projectKey(),
                project.workspaceBindingId().toString(), request.learningCandidates());
        return learning.capture(command);
    }
}
