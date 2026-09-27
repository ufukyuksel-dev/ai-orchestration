package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.AdminTokenValidator;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import org.junit.jupiter.api.Test;

class MemoryInlineApprovalControllerTest {

    private final MemoryInlineApprovalProxy proxy = mock(MemoryInlineApprovalProxy.class);
    private final MemoryInlineApprovalTurnService turnService = mock(MemoryInlineApprovalTurnService.class);
    private final MemoryInlineApprovalController controller = new MemoryInlineApprovalController(
            proxy, turnService, new AdminTokenValidator(new PolicyProperties(true, true, "admin-token", List.of())));

    @Test
    void pollRenderRequiresAdminTokenAndDelegates() {
        MemoryInlineApprovalRenderResponse expected = MemoryInlineApprovalRenderResponse.none();
        when(turnService.pollAndRender("PROJECT_A", "session-1", "turn-1")).thenReturn(expected);

        MemoryInlineApprovalRenderResponse actual =
                controller.pollRender("admin-token", "PROJECT_A", "session-1", "turn-1");

        assertThat(actual).isSameAs(expected);
        verify(turnService).pollAndRender("PROJECT_A", "session-1", "turn-1");
    }

    @Test
    void invalidAdminTokenRejectsBeforeDelegation() {
        assertThatThrownBy(() -> controller.pollRender("wrong", "PROJECT_A", "session-1", "turn-1"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("Invalid admin token");

        verifyNoInteractions(turnService);
    }

    @Test
    void turnDecisionRequiresAdminTokenAndDelegates() {
        AgentInterpretedDecisionRequest request = new AgentInterpretedDecisionRequest(
                "PROJECT_A", "session-1", UUID.randomUUID(), "approve", true,
                "onayla", 0.9, "turn-2", null, "ok");
        MemoryInlineApprovalTurnResponse expected = MemoryInlineApprovalTurnResponse.noDecision();
        when(turnService.handleHumanTurn(request)).thenReturn(expected);

        MemoryInlineApprovalTurnResponse actual = controller.turnDecision("admin-token", request);

        assertThat(actual).isSameAs(expected);
        verify(turnService).handleHumanTurn(request);
    }
}
