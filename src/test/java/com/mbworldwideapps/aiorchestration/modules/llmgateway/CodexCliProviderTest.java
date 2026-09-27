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

class CodexCliProviderTest {

    @Test
    void sendsPromptToCodexCliAndParsesJsonResult() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                0,
                "{\"result\":\"diff --git a/App.tsx b/App.tsx\"}",
                "",
                42L,
                false));
        CodexCliProvider provider = new CodexCliProvider(runner, properties(List.of()), new ObjectMapper());

        GenerationResponse response = provider.generate(request("Generate patch", "memory block"));

        assertThat(response.provider()).isEqualTo("codex");
        assertThat(response.answer()).isEqualTo("diff --git a/App.tsx b/App.tsx");
        assertThat(response.latencyMs()).isEqualTo(42L);
        assertThat(runner.command).startsWith("codex", "exec", "--json", "--sandbox", "read-only",
                "--output-last-message");
        assertThat(runner.command).doesNotContain("--ask-for-approval", "never");
        assertThat(runner.command).containsSequence("--model", "gpt-test");
        assertThat(runner.command).endsWith("-");
        assertThat(runner.stdinInput).isEqualTo("memory block\n\nGenerate patch");
        assertThat(runner.timeoutMs).isEqualTo(15_000L);
    }

    @Test
    void usesReportedTurnUsageRatherThanTextLength() {
        String stream = """
                {"type":"item.completed","item":{"type":"agent_message","text":"earlier commentary"}}
                {"type":"item.completed","item":{"type":"reasoning","text":"not an answer"}}
                {"type":"thread.started","thread_id":"example"}
                {"type":"item.completed","item":{"type":"agent_message","text":"ok"}}
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":80,"output_tokens":10}}
                {"type":"turn.completed","usage":{"input_tokens":24763,"cached_input_tokens":24448,"output_tokens":122,"reasoning_output_tokens":40}}
                """;
        GenerationResponse response = responseFor(stream);
        assertThat(response.answer()).isEqualTo("ok");
        assertThat(response.inputTokensEstimate()).isEqualTo(24763);
        assertThat(response.outputTokensEstimate()).isEqualTo(122);
    }

    @Test
    void absentOrInvalidUsageRetainsExplicitEstimateFallback() {
        for (String usage : List.of("{}",
                "{\"input_tokens\":\"100\",\"cached_input_tokens\":0,\"output_tokens\":2}",
                "{\"input_tokens\":-1,\"cached_input_tokens\":0,\"output_tokens\":2}",
                "{\"input_tokens\":100,\"cached_input_tokens\":101,\"output_tokens\":2}",
                "{\"input_tokens\":2147483648,\"cached_input_tokens\":0,\"output_tokens\":2}")) {
            GenerationResponse response = responseFor("{\"result\":\"ok\"}\n{\"type\":\"turn.completed\",\"usage\":" + usage + "}");
            assertThat(response.inputTokensEstimate()).isEqualTo(TokenEstimator.estimate("Generate patch"));
            assertThat(response.outputTokensEstimate()).isEqualTo(TokenEstimator.estimate("ok"));
        }
        GenerationResponse absent = responseFor("{\"result\":\"ok\",\"usage\":{\"input_tokens\":999,\"output_tokens\":222}}");
        assertThat(absent.inputTokensEstimate()).isEqualTo(TokenEstimator.estimate("Generate patch"));
    }

    @Test
    void ignoresUnrelatedUsageAndRejectsUnrepresentableFinalCounter() {
        GenerationResponse unrelated = responseFor("""
                {"type":"item.completed","item":{"type":"agent_message","text":"ok","usage":{"input_tokens":9999,"cached_input_tokens":0,"output_tokens":999}}}
                {"type":"turn.completed","usage":{"input_tokens":7,"cached_input_tokens":2,"output_tokens":3}}
                """);
        assertThat(unrelated.inputTokensEstimate()).isEqualTo(7);
        GenerationResponse overflow = responseFor("""
                {"result":"ok"}
                {"type":"turn.completed","usage":{"input_tokens":2147483647,"cached_input_tokens":0,"output_tokens":2}}
                {"type":"turn.completed","usage":{"input_tokens":2147483648,"cached_input_tokens":0,"output_tokens":2}}
                """);
        assertThat(overflow.inputTokensEstimate()).isEqualTo(TokenEstimator.estimate("Generate patch"));
    }

    private static GenerationResponse responseFor(String stdout) {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(0, stdout, "", 1L, false));
        return new CodexCliProvider(runner, properties(List.of()), new ObjectMapper())
                .generate(request("Generate patch", null));
    }

    @Test
    void appendsConfiguredExtraArgs() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                0, "{\"result\":\"ok\"}", "", 1L, false));
        CodexCliProvider provider = new CodexCliProvider(runner,
                properties(List.of("--ephemeral")), new ObjectMapper());

        provider.generate(request("Generate patch", null));

        assertThat(runner.command).endsWith("--ephemeral", "-");
    }

    @Test
    void timeoutMapsToProviderTimeoutException() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                -1, "", "", 15_000L, true));
        CodexCliProvider provider = new CodexCliProvider(runner, properties(List.of()), new ObjectMapper());

        assertThatThrownBy(() -> provider.generate(request("Generate patch", null)))
                .isInstanceOf(LLMProviderTimeoutException.class)
                .hasMessageContaining("codex");
    }

    @Test
    void nonZeroExitFailsClearly() {
        RecordingProcessRunner runner = new RecordingProcessRunner(new ProcessRunner.Result(
                2, "", "not logged in", 10L, false));
        CodexCliProvider provider = new CodexCliProvider(runner, properties(List.of()), new ObjectMapper());

        assertThatThrownBy(() -> provider.generate(request("Generate patch", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("codex CLI failed")
                .hasMessageContaining("not logged in");
    }

    @Test
    @Tag("codex-cli-real")
    void realCodexCliSmokeIsOptIn() {
        assumeTrue(Boolean.getBoolean("codex.cli.real.enabled"));
        CodexCliProvider provider = new CodexCliProvider(new DefaultProcessRunner(),
                new ExternalCliProviderProperties(true, "claude", null, 60_000L, List.of(), "codex",
                        null, 30_000L, List.of(), true),
                new ObjectMapper());

        GenerationResponse response = provider.generate(request("Return exactly the text: ok", null));

        assertThat(response.answer()).containsIgnoringCase("ok");
    }

    private static GenerationRequest request(String question, String memoryContextBlock) {
        return new GenerationRequest(question, "alex", List.of(), "fresh", "codex",
                memoryContextBlock, 0, "software-codegen");
    }

    private static ExternalCliProviderProperties properties(List<String> extraArgs) {
        return new ExternalCliProviderProperties(true, "claude", null, 60_000L, List.of(),
                "codex", "gpt-test", 15_000L, extraArgs, true);
    }

    private static final class RecordingProcessRunner implements ProcessRunner {
        private final Result result;
        private List<String> command = new ArrayList<>();
        private String stdinInput;
        private long timeoutMs;

        private RecordingProcessRunner(Result result) {
            this.result = result;
        }

        @Override
        public Result run(List<String> command, String stdinInput, long timeoutMs) {
            this.command = List.copyOf(command);
            this.stdinInput = stdinInput;
            this.timeoutMs = timeoutMs;
            return result;
        }
    }
}
