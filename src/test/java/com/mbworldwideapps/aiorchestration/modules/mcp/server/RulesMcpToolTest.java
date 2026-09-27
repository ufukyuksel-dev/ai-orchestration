package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.modules.rules.DirectRuleDraftRequest;
import com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringDraftResult;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleAuthoringService;
import com.mbworldwideapps.aiorchestration.modules.rules.RulePromotionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RulesMcpToolTest {

    private final InMemoryAccessLogRepository accessLogRepository = new InMemoryAccessLogRepository();
    private RuleAuthoringService authoringService;
    private RulesMcpTool tool;
    private InstructionReadService instructions;

    @BeforeEach
    void setUp() {
        authoringService = mock(RuleAuthoringService.class);
        instructions = mock(InstructionReadService.class);
        tool = new RulesMcpTool(authoringService,
                new McpAuditLogger(accessLogRepository, properties()), new ObjectMapper(), instructions);
    }

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void draftRequiresRulesWriteAndAuditsDenial() {
        McpClientContextHolder.set(new McpClientContext(
                "PROJECT_A", "codex", "mcp_test", List.of("rules.read")));

        assertThatThrownBy(() -> tool.draft("PROJECT_A", candidateJson()))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("rules.write");

        verify(authoringService, never()).draft(any(), any(), any());
        assertThat(accessLogRepository.entries).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.toolName()).isEqualTo("rules.draft");
                    assertThat(entry.decision()).isEqualTo("denied_scope");
                    assertThat(entry.errorClass()).isEqualTo("McpAccessException");
                });
    }

    @Test
    void draftParsesTypedCandidateUsesServerActorAndAuditsSuccess() {
        McpClientContextHolder.set(new McpClientContext(
                "PROJECT_A", "codex", "mcp_test", List.of("rules.write")));
        UUID draftId = UUID.randomUUID();
        RuleAuthoringDraftResult expected = new RuleAuthoringDraftResult(draftId, "PROJECT_A",
                "a".repeat(64), RulePromotionService.WORKFLOW_CONTRACT_VERSION, "Controllers stay thin");
        when(authoringService.draft(eq("PROJECT_A"), any(DirectRuleDraftRequest.class), eq("mcp:codex")))
                .thenReturn(expected);

        RuleAuthoringDraftResult result = tool.draft("PROJECT_A", candidateJson());

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<DirectRuleDraftRequest> request = ArgumentCaptor.forClass(DirectRuleDraftRequest.class);
        verify(authoringService).draft(eq("PROJECT_A"), request.capture(), eq("mcp:codex"));
        assertThat(request.getValue().statement()).isEqualTo("Controllers stay thin");
        assertThat(accessLogRepository.entries).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.toolName()).isEqualTo("rules.draft");
                    assertThat(entry.decision()).isEqualTo("success");
                    assertThat(entry.metadata()).containsEntry("projectKey", "PROJECT_A")
                            .containsEntry("draftId", draftId.toString());
                });
    }

    @Test
    void instructionsRequireScopeAndExactProjectAndAuditEveryOutcome() {
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test", List.of()));
        assertThatThrownBy(() -> tool.instructions("PROJECT_A", null, null)).isInstanceOf(McpAccessException.class);
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test", List.of("rules.read")));
        assertThatThrownBy(() -> tool.instructions("PROJECT_B", null, null)).isInstanceOf(McpAccessException.class);
        assertThatThrownBy(() -> tool.instructions(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        var expected = new InstructionReadService.Result("PROJECT_A", "effective", 1, 2, true, List.of());
        when(instructions.read("PROJECT_A", null, null)).thenReturn(expected);
        assertThat(tool.instructions("PROJECT_A", null, null)).isEqualTo(expected);
        assertThat(accessLogRepository.entries).extracting(entry -> entry.decision())
                .containsExactly("denied_scope", "denied_scope", "error", "success");
    }

    private static String candidateJson() {
        return """
                {
                  "statement": "Controllers stay thin",
                  "rationale": "Keep domain policy in services",
                  "enforcement": "CONTEXT",
                  "appliesAll": false,
                  "targets": [],
                  "selectorGroups": [],
                  "checks": []
                }
                """;
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200, 160, 5, "logs/test-rules-mcp.jsonl", 384, "qwen3:8b",
                null, null, null, null, null, null, null,
                new AiOrchestrationProperties.Mcp(true, "sse", "/mcp", true, 5, 50, 10_000L),
                365);
    }

    private static final class InMemoryAccessLogRepository implements McpAccessLogRepository {
        private final List<McpAccessLogEntry> entries = new ArrayList<>();

        @Override
        public void insert(McpAccessLogEntry entry) {
            entries.add(entry);
        }
    }
}
