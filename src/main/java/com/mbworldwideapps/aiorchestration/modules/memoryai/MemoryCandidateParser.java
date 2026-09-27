package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MemoryCandidateParser {

    private static final Logger log = LoggerFactory.getLogger(MemoryCandidateParser.class);
    private static final int MAX_CANDIDATES = 5;

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MemoryCandidateParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public sealed interface ParseResult permits ParseResult.Success, ParseResult.Failure {

        record Success(
                List<AutoCuratedMemoryCandidate> candidates,
                int droppedCandidateCount,
                List<String> droppedCandidateDetails) implements ParseResult {

            public Success(List<AutoCuratedMemoryCandidate> candidates) {
                this(candidates, 0, List.of());
            }

            public Success {
                candidates = candidates == null ? List.of() : List.copyOf(candidates);
                droppedCandidateDetails = droppedCandidateDetails == null
                        ? List.of()
                        : List.copyOf(droppedCandidateDetails);
            }
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
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(rawJson));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new ParseResult.Failure(FailureReason.JSON_PARSE_ERROR, e.getMessage());
        }

        JsonNode candidatesNode = root.get("candidates");
        if (candidatesNode == null || !candidatesNode.isArray()) {
            return new ParseResult.Failure(FailureReason.SCHEMA_VIOLATION, "candidates: must be an array");
        }
        if (candidatesNode.size() > MAX_CANDIDATES) {
            return new ParseResult.Failure(FailureReason.SCHEMA_VIOLATION,
                    "candidates: size must be between 0 and " + MAX_CANDIDATES);
        }
        if (candidatesNode.isEmpty()) {
            return new ParseResult.Success(List.of());
        }

        List<AutoCuratedMemoryCandidate> accepted = new java.util.ArrayList<>();
        List<String> dropped = new java.util.ArrayList<>();
        for (int i = 0; i < candidatesNode.size(); i++) {
            try {
                AutoCuratedMemoryCandidate candidate =
                        objectMapper.treeToValue(candidatesNode.get(i), AutoCuratedMemoryCandidate.class);
                Set<ConstraintViolation<AutoCuratedMemoryCandidate>> violations = validator.validate(candidate);
                if (violations.isEmpty()) {
                    accepted.add(candidate);
                } else {
                    dropped.add("candidates[" + i + "]: " + violationDetail(violations));
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                dropped.add("candidates[" + i + "]: " + e.getMessage());
            }
        }
        if (!dropped.isEmpty()) {
            log.debug("Dropped {} invalid memory candidate(s): {}", dropped.size(), dropped);
        }
        if (accepted.isEmpty()) {
            return new ParseResult.Failure(FailureReason.SCHEMA_VIOLATION,
                    "no valid candidates after per-candidate validation: " + String.join("; ", dropped));
        }
        return new ParseResult.Success(accepted, dropped.size(), dropped);
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

    private static String violationDetail(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
    }
}
