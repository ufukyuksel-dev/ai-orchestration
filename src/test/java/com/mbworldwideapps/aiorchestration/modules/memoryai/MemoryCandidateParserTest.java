package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class MemoryCandidateParserTest {

    private final MemoryCandidateParser parser = new MemoryCandidateParser(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void parsesValidCandidateEnvelope() {
        MemoryCandidateParser.ParseResult result = parser.parse("""
                {"candidates":[{"memoryType":"rule","summary":"No hacks","text":"Use proper fixes.",
                "tags":["engineering"],"confidence":0.91,"reasoning":"Explicit project rule."}]}
                """);

        assertThat(result).isInstanceOf(MemoryCandidateParser.ParseResult.Success.class);
        AutoCuratedMemoryCandidate candidate =
                ((MemoryCandidateParser.ParseResult.Success) result).candidates().getFirst();
        assertThat(candidate.memoryType()).isEqualTo(MemoryType.RULE);
        assertThat(candidate.confidence()).isEqualTo(0.91);
    }

    @Test
    void rejectsInvalidJson() {
        MemoryCandidateParser.ParseResult result = parser.parse("not-json");

        assertThat(result).isInstanceOf(MemoryCandidateParser.ParseResult.Failure.class);
        assertThat(((MemoryCandidateParser.ParseResult.Failure) result).reason())
                .isEqualTo(MemoryCandidateParser.FailureReason.JSON_PARSE_ERROR);
    }

    @Test
    void rejectsSchemaViolation() {
        MemoryCandidateParser.ParseResult result = parser.parse("{\"candidates\":[{\"summary\":\"missing\"}]}");

        assertThat(result).isInstanceOf(MemoryCandidateParser.ParseResult.Failure.class);
        assertThat(((MemoryCandidateParser.ParseResult.Failure) result).reason())
                .isEqualTo(MemoryCandidateParser.FailureReason.SCHEMA_VIOLATION);
    }

    @Test
    void dropsInvalidCandidateWithoutLosingValidSiblings() {
        String oversizedText = "x".repeat(MemoryAtomLimits.TEXT_MAX_CHARS + 1);

        MemoryCandidateParser.ParseResult result = parser.parse("""
                {"candidates":[
                  {"memoryType":"rule","summary":"Valid atom","text":"Use focused memory atoms.",
                   "tags":["memory"],"confidence":0.91,"reasoning":"Durable project rule."},
                  {"memoryType":"rule","summary":"Oversized atom","text":"%s",
                   "tags":["memory"],"confidence":0.91,"reasoning":"Too large."}
                ]}
                """.formatted(oversizedText));

        assertThat(result).isInstanceOf(MemoryCandidateParser.ParseResult.Success.class);
        MemoryCandidateParser.ParseResult.Success success =
                (MemoryCandidateParser.ParseResult.Success) result;
        assertThat(success.candidates()).hasSize(1);
        assertThat(success.candidates().getFirst().summary()).isEqualTo("Valid atom");
        assertThat(success.droppedCandidateCount()).isEqualTo(1);
        assertThat(success.droppedCandidateDetails().getFirst()).contains("text");
    }
}
