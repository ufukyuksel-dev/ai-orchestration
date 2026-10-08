package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TerminalLearningControllerTest {

    private final ScannerMcpTool scanner = mock(ScannerMcpTool.class);
    private final AgentLearningMcpTool learning = mock(AgentLearningMcpTool.class);
    private final TerminalLearningController controller = new TerminalLearningController(scanner, learning);

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void acceptsAnyAgentClient() {
        UUID binding = UUID.randomUUID();
        when(scanner.resolveProject("/repo", null)).thenReturn(
                new ScannerProjectResolveResponse("/repo", "P", "P", true, true, "fingerprint", binding));
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 0, 0, 0, 0, 0, 0, 0);
        when(learning.capture(any())).thenReturn(receipt);

        for (String client : List.of("claude-code", "codex", "copilot-cli-memory-skill")) {
            McpClientContextHolder.set(context(client, "scope-1"));
            assertThat(controller.capture(
                    new TerminalLearningController.CaptureRequest("/repo", "task-" + client, List.of())))
                    .isEqualTo(receipt);
        }
    }

    @Test
    void derivesProjectAndDelegatesOneLocatorBasedBatch() {
        UUID binding = UUID.randomUUID();
        when(scanner.resolveProject("/repo", null)).thenReturn(
                new ScannerProjectResolveResponse("/repo", "P", "P", true, true, "fingerprint", binding));
        CaptureCandidate candidate = new CaptureCandidate("behavior", "Route behavior", "Route is selected here.",
                List.of(new CaptureLocator("symbol", "com.acme.Route#select", "select()",
                        "src/Route.java", "primary_change_point")),
                List.of(), List.of(), List.of());
        CompactLearnReceipt receipt = new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 0, 0, 0, 0);
        when(learning.capture(any())).thenReturn(receipt);
        McpClientContextHolder.set(context("codex", "scope-1"));

        var actual = controller.capture(
                new TerminalLearningController.CaptureRequest("/repo", "task-1", List.of(candidate)));

        assertThat(actual).isEqualTo(receipt);
        verify(learning).capture(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskRunId().equals("task-1")
                        && command.projectKey().equals("P")
                        && command.workspaceBindingId().equals(binding.toString())
                        && command.learningCandidates().equals(List.of(candidate))));
    }

    private static McpClientContext context(String clientId, String rawScope) {
        return new McpClientContext("P", clientId, "local",
                List.of("memory.read", "memory.write", "codebase.read", "rules.read"),
                McpAuditLogger.sha256Hex(clientId + ":" + rawScope));
    }
}
