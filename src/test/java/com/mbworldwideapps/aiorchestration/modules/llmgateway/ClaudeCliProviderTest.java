package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.ExternalCliProviderProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ClaudeCliProviderTest {

    @Test
    void sendsPromptToClaudeCliAndParsesJsonResult() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                0,
                "{\"type\":\"result\",\"result\":\"diff --git a/App.java b/App.java\"}",
                "",
                42L,
                false));
        ClaudeCliProvider provider = new ClaudeCliProvider(runner, properties(List.of()), new ObjectMapper());

        GenerationResponse response = provider.generate(request("Generate patch", "memory block"));

        assertThat(response.provider()).isEqualTo("claude");
        assertThat(response.answer()).isEqualTo("diff --git a/App.java b/App.java");
        assertThat(response.latencyMs()).isEqualTo(42L);
        assertThat(runner.command).containsExactly("claude", "-p", "memory block\n\nGenerate patch",
                "--output-format", "json", "--tools", "", "--model", "sonnet-test");
        assertThat(runner.timeoutMs).isEqualTo(15_000L);
    }

    @Test
    void appendsConfiguredExtraArgs() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                0, "{\"result\":\"ok\"}", "", 1L, false));
        ClaudeCliProvider provider = new ClaudeCliProvider(runner,
                properties(List.of("--permission-mode", "acceptEdits")), new ObjectMapper());

        provider.generate(request("Generate patch", null));

        assertThat(runner.command).endsWith("--permission-mode", "acceptEdits");
    }

    @Test
    void timeoutMapsToProviderTimeoutException() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                -1, "", "", 15_000L, true));
        ClaudeCliProvider provider = new ClaudeCliProvider(runner, properties(List.of()), new ObjectMapper());

        assertThatThrownBy(() -> provider.generate(request("Generate patch", null)))
                .isInstanceOf(LLMProviderTimeoutException.class)
                .hasMessageContaining("claude");
    }

    @Test
    void nonZeroExitFailsClearly() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                2, "", "not logged in", 10L, false));
        ClaudeCliProvider provider = new ClaudeCliProvider(runner, properties(List.of()), new ObjectMapper());

        assertThatThrownBy(() -> provider.generate(request("Generate patch", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude CLI failed")
                .hasMessageContaining("not logged in");
    }

    @Test
    @Tag("claude-cli-real")
    void realClaudeCliSmokeIsOptIn() {
        assumeTrue(Boolean.getBoolean("claude.cli.real.enabled"));
        ClaudeCliProvider provider = new ClaudeCliProvider(new DefaultProcessRunner(),
                new ExternalCliProviderProperties(true, "claude", null, 30_000L, List.of(), "codex",
                        null, 60_000L, List.of(), true),
                new ObjectMapper());

        GenerationResponse response = provider.generate(request("Return exactly the text: ok", null));

        assertThat(response.answer()).containsIgnoringCase("ok");
    }

    private static GenerationRequest request(String question, String memoryContextBlock) {
        return new GenerationRequest(question, "alex", List.of(), "fresh", "claude",
                memoryContextBlock, 0, "software-codegen");
    }

    private static ExternalCliProviderProperties properties(List<String> extraArgs) {
        return new ExternalCliProviderProperties(true, "claude", "sonnet-test", 15_000L, extraArgs,
                "codex", null, 60_000L, List.of(), true);
    }

    private static final class RecordingProcessRunner implements ProcessRunner {
        private final Result result;
        private List<String> command = new ArrayList<>();
        private long timeoutMs;

        private RecordingProcessRunner(Result result) {
            this.result = result;
        }

        @Override
        public Result run(List<String> command, String stdinInput, long timeoutMs) {
            this.command = List.copyOf(command);
            this.timeoutMs = timeoutMs;
            assertThat(stdinInput).isNull();
            return result;
        }
    }
}
