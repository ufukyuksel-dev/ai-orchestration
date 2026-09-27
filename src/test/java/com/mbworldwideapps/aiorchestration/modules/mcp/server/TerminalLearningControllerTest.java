package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TerminalLearningControllerTest {

    private final ScannerMcpTool scanner = mock(ScannerMcpTool.class);
    private final AgentLearningMcpTool learning = mock(AgentLearningMcpTool.class);
    private final TerminalLearningController controller = new TerminalLearningController(scanner, learning);

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void rejectsAnythingExceptTheTypedCopilotTerminalClient() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-AI-Orch-Client")).thenReturn("codex");
        McpClientContextHolder.set(context("scope-1"));

        assertThatThrownBy(() -> controller.capture(
                new TerminalLearningController.CaptureRequest("/repo", "task-1", List.of()), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COPILOT_TERMINAL_REQUIRED");

        verify(scanner, never()).resolveProject(any(), any());
        verify(learning, never()).capture(any());
    }

    @Test
    void rejectsSpoofedHeaderWhenAuthenticatedClientIsDifferent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-AI-Orch-Client")).thenReturn(TerminalLearningController.COPILOT_CLIENT);
        McpClientContextHolder.set(new McpClientContext("P", "another-client", "key",
                List.of("memory.write"), McpAuditLogger.sha256Hex("another-client:scope-1")));

        assertThatThrownBy(() -> controller.capture(
                new TerminalLearningController.CaptureRequest("/repo", "task-1", List.of()), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COPILOT_TERMINAL_REQUIRED");

        verify(scanner, never()).resolveProject(any(), any());
        verify(learning, never()).capture(any());
    }

    @Test
    void derivesProjectAndDelegatesOneLocatorBasedBatch() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-AI-Orch-Client")).thenReturn(TerminalLearningController.COPILOT_CLIENT);
        UUID binding = UUID.randomUUID();
        when(scanner.resolveProject("/repo", null)).thenReturn(
                new ScannerProjectResolveResponse("/repo", "P", "P", true, true, "fingerprint", binding));
        CaptureCandidate candidate = new CaptureCandidate("behavior", "Route behavior", "Route is selected here.",
                List.of(new CaptureLocator("symbol", "com.acme.Route#select", "select()",
                        "src/Route.java", "primary_change_point")),
                List.of(), List.of(), List.of());
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 0, 0, 0, 0);
        when(learning.capture(any())).thenReturn(receipt);
        McpClientContextHolder.set(context("scope-1"));

        var actual = controller.capture(
                new TerminalLearningController.CaptureRequest("/repo", "task-1", List.of(candidate)), request);

        assertThat(actual).isEqualTo(receipt);
        verify(learning).capture(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskRunId().equals("task-1")
                        && command.projectKey().equals("P")
                        && command.workspaceBindingId().equals(binding.toString())
                        && command.learningCandidates().equals(List.of(candidate))));
    }

    private static McpClientContext context(String rawScope) {
        String clientId = TerminalLearningController.COPILOT_CLIENT;
        return new McpClientContext("P", clientId, "local",
                List.of("memory.read", "memory.write", "codebase.read", "rules.read"),
                McpAuditLogger.sha256Hex(clientId + ":" + rawScope));
    }
}
