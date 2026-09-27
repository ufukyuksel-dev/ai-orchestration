package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.McpToolSurfaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpToolSurfaceFilterTest {

    private static final String TOOLS = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
            + "{\"name\":\"session.bootstrap\"},{\"name\":\"memory.write\"},{\"name\":\"memory.search\"}]}}";

    private final McpToolSurfaceFilter filter = new McpToolSurfaceFilter(
            new McpToolSurfaceProperties("lean", List.of("session.bootstrap", "memory.search"), null, null),
            new ObjectMapper());

    private MockHttpServletResponse call(String body, String sseAnswer, String profileHeader, String client)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (profileHeader != null) request.addHeader("X-AI-Orch-Tools", profileHeader);
        if (client != null) request.addHeader("X-AI-Orch-Client", client);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
                // the downstream handler must still see the full request body
                String seen = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                res.getOutputStream().write((seen.contains("tools/list") || seen.contains("initialize") ? sseAnswer
                        : seen.equals(body) ? "{}" : "{\"truncated\":" + seen.length() + "}")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }));
        return response;
    }

    @Test
    void leanProfileListsOnlyEverydayToolsInSseAnswers() throws Exception {
        String sse = "id:1\nevent:message\ndata:" + TOOLS + "\n\n";
        String out = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sse, null, null)
                .getContentAsString();
        assertThat(out).contains("session.bootstrap", "memory.search").doesNotContain("memory.write");
        assertThat(out).startsWith("id:1\nevent:message\ndata:");
    }

    @Test
    void fullProfileHeaderAndFullProfileClientsSeeEverything() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        assertThat(call(body, TOOLS, "full", null).getContentAsString()).contains("memory.write");
        assertThat(call(body, TOOLS, null, "copilot-cli-memory-skill").getContentAsString())
                .contains("memory.write");
    }

    @Test
    void minimalProfileListsOnlyTheToolsAnAgentCallsItself() throws Exception {
        String tools = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"session.bootstrap\"},"
                + "{\"name\":\"memory.learn\"},{\"name\":\"rules.instructions\"},{\"name\":\"memory.search\"}]}}";
        String out = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", tools, "minimal", null)
                .getContentAsString();
        assertThat(out).contains("memory.learn", "rules.instructions").doesNotContain("session.bootstrap", "memory.search");
    }

    @Test
    void minimalProfileGetsInstructionsWithoutTheBootstrapStep() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"instructions\":\"1) First call session.bootstrap(...)\"}}";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        String minimal = call(body, "id:1\nevent:message\ndata:" + init + "\n\n", "minimal", null).getContentAsString();
        assertThat(minimal).contains("arrive with the user's request").doesNotContain("session.bootstrap")
                .startsWith("id:1\nevent:message\ndata:");
        assertThat(call(body, init, "lean", null).getContentAsString()).contains("session.bootstrap");
    }

    @Test
    void otherRequestsPassThroughUntouched() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"memory.write\"}}";
        assertThat(call(body, TOOLS, null, null).getContentAsString()).isEqualTo("{}");
    }

    @Test
    void largeToolCallsReachTheHandlerByteForByte() throws Exception {
        String text = "x".repeat(200 * 1024);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"last_job.save\",\"arguments\":{\"text\":\"" + text + "\"}}}";
        assertThat(call(body, TOOLS, null, null).getContentAsString()).isEqualTo("{}");
    }
}
