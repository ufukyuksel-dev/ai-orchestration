package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mbworldwideapps.aiorchestration.config.OrchestratorRoutingProperties;
import com.mbworldwideapps.aiorchestration.config.SoftwareCodegenProperties;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;

class LocalQwenProviderTest {

    @Test
    void callsChatModelWithGroundedPrompt() {
        AtomicReference<Prompt> promptRef = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            promptRef.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("MoneyTL kullanilir [chunk-1]"))));
        };
        LocalQwenProvider provider = new LocalQwenProvider(objectProvider(chatModel), new GroundedPromptBuilder());
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
                "local-qwen",
                null,
                0,
                "knowledgeai"));

        assertThat(response.provider()).isEqualTo("local-qwen");
        assertThat(response.injectedKnowledgeChunkCount()).isEqualTo(1);
        assertThat(response.answer()).contains("MoneyTL kullanilir [chunk-1]");
        assertThat(promptRef.get().getContents()).contains("Sadece verilen kaynak chunk");
        assertThat(promptRef.get().getContents()).contains("id: chunk-1");
        assertThat(promptRef.get().getContents()).contains("MoneyTL value object kullanilir.");
        assertThat(promptRef.get().getOptions()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions options = (OllamaChatOptions) promptRef.get().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3:8b");
        assertThat(options.getNumPredict()).isEqualTo(192);
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getThinkOption().toJsonValue()).isEqualTo(false);
        assertThat(options.getOutputSchema()).isNull();
    }

    @Test
    void constrainsAnalystOutputWithJsonSchemaWithoutAddingPromptText() {
        AtomicReference<Prompt> promptRef = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            promptRef.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        };
        LocalQwenProvider provider = new LocalQwenProvider(objectProvider(chatModel), new GroundedPromptBuilder());

        provider.generate(new GenerationRequest(
                "Analyze the target",
                "alex",
                List.of(),
                "fresh",
                "local-qwen",
                "Learning context:\n- exact evidence",
                0,
                "analyst"));

        OllamaChatOptions options = (OllamaChatOptions) promptRef.get().getOptions();
        assertThat(options.getOutputSchema())
                .contains("\"testStrategy\":{\"type\":\"array\"")
                .contains("\"mitigation\":{\"type\":\"string\"}");
        assertThat(promptRef.get().getContents()).doesNotContain("definitions");
    }

    @Test
    void usesOrchestratorSpecificOptionsForRoutingRole() {
        AtomicReference<Prompt> promptRef = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            promptRef.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"target\":\"SOFTWARE\",\"confidence\":0.8,\"reasoning\":\"write intent\"}"))));
        };
        OrchestratorRoutingProperties routingProperties = new OrchestratorRoutingProperties(
                true, "local-qwen", 2500L, 64, 0.2, 0.85, 0.5, 3, 3, 1200, 2800, 4000);
        LocalQwenProvider provider = new LocalQwenProvider(objectProvider(chatModel), new GroundedPromptBuilder(),
                routingProperties, SoftwareCodegenProperties.disabledDefaults(), "qwen3:8b", 192, 0.0, false);

        provider.generate(new GenerationRequest(
                "review and implement this change",
                "alex",
                List.of(),
                "fresh",
                "local-qwen",
                "",
                0,
                "orchestrator"));

        OllamaChatOptions options = (OllamaChatOptions) promptRef.get().getOptions();
        assertThat(options.getNumPredict()).isEqualTo(64);
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(promptRef.get().getContents()).contains("Route the user task");
    }

    @Test
    void relationJudgeUsesPairRubricWithoutKnowledgeCitationInstructions() {
        AtomicReference<Prompt> promptRef = new AtomicReference<>();
        ChatModel model = prompt -> {
            promptRef.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"related\":false,\"relationshipType\":\"unrelated\",\"confidence\":0.9,\"explanation\":\"Different facts\"}"))));
        };
        LocalQwenProvider provider = new LocalQwenProvider(objectProvider(model), new GroundedPromptBuilder());
        String rubric = "Evaluate SOURCE and TARGET. Return exactly the specified JSON.\nSOURCE: untrusted fact\nTARGET: another fact";
        provider.generate(new GenerationRequest(rubric, "benchmark", List.of(), "fresh",
                null, "", 0, "memory-relation-judge"));

        assertThat(promptRef.get().getUserMessage().getText()).isEqualTo(rubric);
        assertThat(promptRef.get().getSystemMessage().getText()).contains("JSON", "untrusted");
        assertThat(promptRef.get().getContents()).doesNotContain("Kaynak chunk", "[<chunkId>]", "Cevabi Turkce");
        assertThat(((OllamaChatOptions) promptRef.get().getOptions()).getThinkOption().toJsonValue()).isEqualTo(false);
    }

    private static ObjectProvider<ChatModel> objectProvider(ChatModel chatModel) {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject(Object... args) {
                return chatModel;
            }

            @Override
            public ChatModel getObject() {
                return chatModel;
            }
        };
    }
}
