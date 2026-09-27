package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import org.junit.jupiter.api.Test;

class ClaudeProviderStubTest {

    @Test
    void returnsDeterministicSourceGroundedAnswer() {
        ClaudeProviderStub provider = new ClaudeProviderStub();
        SourceSnippet source = new SourceSnippet(
                "chunk-1",
                "Standards",
                "docs/sample/public-standards.md",
                "MoneyTL value object kullanilir.",
                0.0,
                "fresh",
                Instant.parse("2026-01-01T00:00:00Z"));

        GenerationResponse response = provider.generate(new GenerationRequest(
                "TL tutarlar nasil modellenir?",
                "alex",
                List.of(source),
                "fresh",
                "claude-stub",
                null,
                0,
                "knowledgeai"));

        assertThat(response.provider()).isEqualTo("claude-stub");
        assertThat(response.injectedKnowledgeChunkCount()).isEqualTo(1);
        assertThat(response.answer()).contains("MoneyTL value object kullanilir.");
        assertThat(response.answer()).contains("[chunk-1]");
        assertThat(response.answer()).contains("docs/sample/public-standards.md");
    }
}
