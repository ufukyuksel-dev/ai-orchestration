package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.ExternalCliProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.software.codegen.external-cli",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class CodexCliProvider implements LLMProvider {

    public static final String PROVIDER_ID = "codex";
    private static final Logger log = LoggerFactory.getLogger(CodexCliProvider.class);

    private final ProcessRunner processRunner;
    private final ExternalCliProviderProperties properties;
    private final ObjectMapper objectMapper;

    public CodexCliProvider(ProcessRunner processRunner, ExternalCliProviderProperties properties,
            ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) {
        Instant started = Instant.now();
        String prompt = prompt(request);
        Path outputPath = createOutputPath();
        try {
            ProcessRunner.Result result = processRunner.run(command(outputPath), prompt, properties.codexCliTimeoutMs());
            CliUsage usage = measuredUsage(result.stdout());
            String role = switch (request.role()) {
                case "memory-write-gate", "memory-relation-judge", "software-codegen" -> request.role();
                default -> "unknown";
            };
            log.info("codex_usage role={} measured={} input_tokens={} cached_input_tokens={} output_tokens={}",
                    role, usage != null, usage == null ? -1 : usage.input(),
                    usage == null ? -1 : usage.cachedInput(), usage == null ? -1 : usage.output());
            if (result.timedOut()) {
                throw new LLMProviderTimeoutException(PROVIDER_ID, properties.codexCliTimeoutMs(),
                        new TimeoutException("codex CLI timed out"));
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException("codex CLI failed (exit=" + result.exitCode() + "): "
                        + truncate(result.stderr(), 500));
            }
            String answer = readAnswer(outputPath, result.stdout());
            long latencyMs = result.latencyMs() > 0 ? result.latencyMs()
                    : Duration.between(started, Instant.now()).toMillis();
            return new GenerationResponse(
                    answer,
                    PROVIDER_ID,
                    latencyMs,
                    false,
                    DegradedReason.NONE,
                    usage == null ? TokenEstimator.estimate(prompt) : usage.input(),
                    usage == null ? TokenEstimator.estimate(answer) : usage.output(),
                    request.injectedMemoryCount(),
                    request.sources().size(),
                    TokenEstimator.estimateInjected(request));
        } finally {
            deleteQuietly(outputPath);
        }
    }

    private record CliUsage(int input, int cachedInput, int output) { }

    private CliUsage measuredUsage(String stdout) {
        int input = 0;
        int cached = 0;
        int output = 0;
        boolean found = false;
        for (String line : (stdout == null ? "" : stdout).lines().toList()) {
            JsonNode event;
            try {
                event = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
            } catch (IOException ignored) {
                continue;
            }
            if (event == null || !"turn.completed".equals(event.path("type").asText())) {
                continue;
            }
            JsonNode usage = event.path("usage");
            JsonNode in = usage.path("input_tokens");
            JsonNode cachedIn = usage.path("cached_input_tokens");
            JsonNode out = usage.path("output_tokens");
            if (!validCount(in) || !validCount(cachedIn) || !validCount(out)
                    || cachedIn.intValue() > in.intValue()) {
                return null; // Partial or invalid totals must not be reported as measured usage.
            }
            // CLI emits cumulative thread totals; keep the latest, never sum snapshots.
            input = in.intValue();
            cached = cachedIn.intValue();
            output = out.intValue();
            found = true;
        }
        return found ? new CliUsage(input, cached, output) : null;
    }

    private static boolean validCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    private List<String> command(Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(properties.codexCliPath());
        command.add("exec");
        command.add("--json");
        command.add("--sandbox");
        command.add("read-only");
        command.add("--output-last-message");
        command.add(outputPath.toString());
        if (properties.codexCliModel() != null && !properties.codexCliModel().isBlank()) {
            command.add("--model");
            command.add(properties.codexCliModel());
        }
        command.addAll(properties.codexCliArgsExtra());
        command.add("-");
        return command;
    }

    private static String prompt(GenerationRequest request) {
        if (request.memoryContextBlock() == null || request.memoryContextBlock().isBlank()) {
            return request.question();
        }
        return request.memoryContextBlock() + "\n\n" + request.question();
    }

    private String readAnswer(Path outputPath, String stdout) {
        try {
            if (Files.exists(outputPath) && Files.size(outputPath) > 0) {
                String answer = Files.readString(outputPath).trim();
                if (!answer.isBlank()) {
                    return answer;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read codex CLI output file: " + outputPath, e);
        }
        return extractAnswer(stdout);
    }

    private String extractAnswer(String output) {
        String trimmed = output == null ? "" : output.trim();
        if (trimmed.isBlank()) {
            throw new IllegalStateException("codex CLI produced no output");
        }
        String answer = extractJsonAnswer(trimmed);
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        List<String> lines = trimmed.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() > 1) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                answer = extractJsonAnswer(lines.get(i));
                if (answer != null && !answer.isBlank()) {
                    return answer;
                }
            }
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            throw new IllegalStateException("codex CLI output missing result/output/content/message field: "
                    + truncate(trimmed, 300));
        }
        return trimmed;
    }

    private String extractJsonAnswer(String json) {
        try {
            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            JsonNode answer = findAnswerNode(root);
            return answer == null ? null : answer.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonNode findAnswerNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if ("item.completed".equals(node.path("type").asText())) {
            JsonNode item = node.path("item");
            JsonNode text = item.path("text");
            return "agent_message".equals(item.path("type").asText()) && text.isTextual() ? text : null;
        }
        if (node.isTextual()) {
            return node;
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode child : node) {
                JsonNode value = findAnswerNode(child);
                if (value != null && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
            }
            return values.isEmpty() ? null : com.fasterxml.jackson.databind.node.TextNode.valueOf(String.join("\n", values));
        }
        for (String field : List.of("result", "output", "content", "message", "text", "final_response", "msg")) {
            JsonNode value = findAnswerNode(node.path(field));
            if (value != null && !value.asText().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path createOutputPath() {
        try {
            return Files.createTempFile("codex-cli-output-", ".txt");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create codex CLI output file", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for temporary CLI output.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
