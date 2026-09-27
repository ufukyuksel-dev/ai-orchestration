package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

@Component
public class MemoryWriteGateParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MemoryWriteGateParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public sealed interface ParseResult permits ParseResult.Success, ParseResult.Failure {

        record Success(MemoryWriteGate.GateDecision decision) implements ParseResult {
        }

        record Failure(FailureReason reason, String detail) implements ParseResult {
        }
    }

    public enum FailureReason {
        JSON_PARSE_ERROR,
        SCHEMA_VIOLATION
    }

    public ParseResult parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ParseResult.Failure(FailureReason.JSON_PARSE_ERROR, "empty response");
        }
        GateEnvelope envelope;
        try {
            envelope = objectMapper.readValue(extractJsonObject(rawJson), GateEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new ParseResult.Failure(FailureReason.JSON_PARSE_ERROR, e.getMessage());
        }

        Set<ConstraintViolation<GateEnvelope>> violations = validator.validate(envelope);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return new ParseResult.Failure(FailureReason.SCHEMA_VIOLATION, detail);
        }
        MemoryWriteGate.Verdict verdict = parseVerdict(envelope.verdict());
        if (verdict == null) {
            return new ParseResult.Failure(FailureReason.SCHEMA_VIOLATION,
                    "verdict must be AUTO_ACTIVE or REJECTED");
        }
        return new ParseResult.Success(new MemoryWriteGate.GateDecision(
                verdict,
                envelope.gateReason(),
                envelope.suggestedQuestion(),
                envelope.contextHints(),
                envelope.confidence(),
                envelope.debugMetadata()));
    }

    private static MemoryWriteGate.Verdict parseVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        return switch (verdict.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "AUTO_ACTIVE" -> MemoryWriteGate.Verdict.AUTO_ACTIVE;
            // Legacy verdict from an older prompt or a stale model: there is no pending
            // state any more, so an "unsure" answer stores the memory instead of parking it.
            case "NEEDS_HUMAN" -> MemoryWriteGate.Verdict.AUTO_ACTIVE;
            case "REJECTED" -> MemoryWriteGate.Verdict.REJECTED;
            default -> null;
        };
    }

    private static String extractJsonObject(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        return trimmed.substring(start, end + 1);
    }

    private record GateEnvelope(
            @NotBlank String verdict,
            @NotBlank @Size(max = 200) String gateReason,
            @Size(max = 1000) String suggestedQuestion,
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 300) String> contextHints,
            @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
            @NotNull Map<String, Object> debugMetadata) {

        private GateEnvelope {
            contextHints = contextHints == null ? List.of() : List.copyOf(contextHints);
            if (debugMetadata == null || debugMetadata.isEmpty()) {
                debugMetadata = Map.of();
            } else {
                Map<String, Object> sanitized = new LinkedHashMap<>();
                debugMetadata.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        sanitized.put(key, value);
                    }
                });
                debugMetadata = Map.copyOf(sanitized);
            }
        }
    }
}
