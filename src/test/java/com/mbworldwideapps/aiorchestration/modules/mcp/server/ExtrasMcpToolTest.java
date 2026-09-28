package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

class ExtrasMcpToolTest {

    @Component
    static class FakeJobs {
        @McpTool(name = "job_memory.search", description = "test")
        public String search(String query, Integer limit, List<String> tags) {
            return query + "|" + limit + "|" + tags;
        }

        @McpTool(name = "session.bootstrap", description = "test")
        public String bootstrap(String rootPath, String task, Boolean loadRules, Boolean remember, String knownEtag,
                String cursor) {
            return rootPath + "|" + loadRules + "|" + remember;
        }

        @McpTool(name = "rules.draft", description = "test")
        public String draft(String projectKey, String candidateJson) {
            return projectKey + "|" + candidateJson;
        }

        @McpTool(name = "memory.write", description = "not reachable through extras")
        public String write(String text) {
            return text;
        }
    }

    private ExtrasMcpTool tool() {
        var context = new GenericApplicationContext();
        context.registerBean(FakeJobs.class);
        context.refresh();
        return new ExtrasMcpTool(context, new ObjectMapper());
    }

    @Test
    void runsTheRealToolWithItsNamedArguments() {
        assertThat(tool().extras("job_memory.search", Map.of("query", "deploy", "limit", 3, "tags", List.of("ops"))))
                .isEqualTo("deploy|3|[ops]");
        assertThat(tool().extras("job_memory.search", null)).isEqualTo("null|null|null");
        // the answer to "load the rules?" goes through the normal bootstrap: preference saved, all rules returned
        assertThat(tool().extras("session.bootstrap", Map.of("rootPath", "/repo", "loadRules", true, "remember", true)))
                .isEqualTo("/repo|true|true");
        // a rule the user asked for can be drafted without the rule tools in every turn's schema list
        assertThat(tool().extras("rules.draft", Map.of("projectKey", "p", "candidateJson", "{}"))).isEqualTo("p|{}");
    }

    @Test
    void onlyTheOccasionalToolsAreReachable() {
        assertThatThrownBy(() -> tool().extras("memory.write", Map.of("text", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("op must be one of");
    }
}
