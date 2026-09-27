package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.ExternalCliProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.software.codegen.external-cli",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class ClaudeCliProvider implements LLMProvider {

    public static final String PROVIDER_ID = "claude";

    private final ProcessRunner processRunner;
    private final ExternalCliProviderProperties properties;
    private final ObjectMapper objectMapper;

    public ClaudeCliProvider(ProcessRunner processRunner, ExternalCliProviderProperties properties,
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
        ProcessRunner.Result result = processRunner.run(command(prompt), null, properties.claudeCliTimeoutMs());
        if (result.timedOut()) {
            throw new LLMProviderTimeoutException(PROVIDER_ID, properties.claudeCliTimeoutMs(),
                    new TimeoutException("claude CLI timed out"));
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("claude CLI failed (exit=" + result.exitCode() + "): "
                    + truncate(result.stderr(), 500));
        }
        String answer = extractAnswer(result.stdout());
        long latencyMs = result.latencyMs() > 0 ? result.latencyMs()
                : Duration.between(started, Instant.now()).toMillis();
        return new GenerationResponse(
                answer,
                PROVIDER_ID,
                latencyMs,
                false,
                DegradedReason.NONE,
                TokenEstimator.estimate(prompt),
                TokenEstimator.estimate(answer),
                request.injectedMemoryCount(),
                request.sources().size(),
                TokenEstimator.estimateInjected(request));
    }

    private List<String> command(String prompt) {
        List<String> command = new ArrayList<>();
        command.add(properties.claudeCliPath());
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("json");
        command.add("--tools");
        command.add("");
        if (properties.claudeCliModel() != null && !properties.claudeCliModel().isBlank()) {
            command.add("--model");
            command.add(properties.claudeCliModel());
        }
        command.addAll(properties.claudeCliArgsExtra());
        return command;
    }

    private static String prompt(GenerationRequest request) {
        if (request.memoryContextBlock() == null || request.memoryContextBlock().isBlank()) {
            return request.question();
        }
        return request.memoryContextBlock() + "\n\n" + request.question();
    }

    private String extractAnswer(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new IllegalStateException("claude CLI output missing 'result' field: " + truncate(json, 300));
            }
            return result.asText();
        } catch (IOException e) {
            throw new IllegalStateException("claude CLI output not valid JSON: " + truncate(json, 300), e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
