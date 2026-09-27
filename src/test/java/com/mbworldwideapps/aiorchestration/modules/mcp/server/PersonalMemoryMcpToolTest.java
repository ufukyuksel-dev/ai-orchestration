package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class PersonalMemoryMcpToolTest {
    VectorStore store = mock(VectorStore.class);
    ScannerPayloadRedactor redactor = mock(ScannerPayloadRedactor.class);
    McpAuditLogger audit = mock(McpAuditLogger.class);
    PersonalMemoryMcpTool tool = new PersonalMemoryMcpTool(store, redactor, audit);

    @BeforeEach void setup() {
        McpClientContextHolder.set(new McpClientContext("FIRST", "codex", "local", List.of("memory.read", "memory.write")));
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
    }
    @AfterEach void clear() { McpClientContextHolder.clear(); }

    @Test void savesActualRedactedTextWithoutProjectAndAudits() {
        when(redactor.redact("sensitive")).thenReturn("safe file location");
        var saved = tool.save("sensitive");
        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).add(captor.capture());
        var doc = (Document) captor.getValue().getFirst();
        assertThat(doc.getText()).isEqualTo("safe file location");
        assertThat(saved.content()).isEqualTo(doc.getText());
        assertThat(doc.getMetadata()).containsOnlyKeys("savedAt");
        assertThat(doc.getId()).isEqualTo(saved.id());
        verify(audit).log(any(), eq("personal_memory.save"), isNull(), eq(1), any(Instant.class), eq("success"));
    }
    @Test void searchesAcrossProjectsWithBoundedSemanticScores() {
        McpClientContextHolder.set(new McpClientContext("OTHER", "claude", "local", List.of("memory.read")));
        var doc = Document.builder().id("fact").text("Company website").metadata(Map.of("savedAt", "now")).score(0.87).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        assertThat(tool.search("business page", null)).containsExactly(new PersonalMemoryMcpTool.Match("fact", "Company website", 0.87, "now"));
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(3);
        assertThat(request.getValue().getFilterExpression()).isNull();
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
    }
    @Test void emptyResultDoesNotInventFacts() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(tool.search("unknown", 1)).isEmpty();
    }
    @Test void rejectsBlankOversizedInputsAndInvalidLimitsBeforeVectorCalls() {
        for (String value : new String[]{null, " ", "é".repeat(4097)}) {
            assertThatThrownBy(() -> tool.save(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tool.search(value, 3)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int limit : new int[]{0,11}) assertThatThrownBy(() -> tool.search("query",limit)).isInstanceOf(IllegalArgumentException.class).hasMessage("topK must be 1..10");
        assertThatThrownBy(() -> tool.save(" ")).hasMessage("Text must be nonblank");
        assertThatThrownBy(() -> tool.save("é".repeat(4097))).hasMessage("Text must be at most 8192 bytes UTF-8");
        verifyNoInteractions(store);
    }
    @Test void deniesBearerAndMissingScopeBeforeEmbeddingAndAudits() {
        for (var context : List.of(
                new McpClientContext("FIRST","remote","key",List.of("memory.read","memory.write")),
                new McpClientContext("FIRST","local","local",List.of()))) {
            McpClientContextHolder.set(context);
            assertThatThrownBy(() -> tool.save("fact")).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.search("query",3)).isInstanceOf(McpAccessException.class);
            verify(audit).log(eq(context),eq("personal_memory.save"),isNull(),eq(0),any(),eq("denied_scope"));
            verify(audit).log(eq(context),eq("personal_memory.search"),isNull(),eq(0),any(),eq("denied_scope"));
        }
        verifyNoInteractions(store,redactor);
    }
    @Test void typedSettingsKeepDifferentEmbeddingSpacesSeparate() {
        var defaults = new com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties(null,null,null,null);
        var changed = new com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties("bge-m3",512,0.6,4096);
        assertThat(defaults.collectionName()).isNotEqualTo(changed.collectionName());
        assertThatThrownBy(() -> new com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties("",1024,0.5,8192))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties(null,1024,Double.NaN,8192))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void backendErrorsAreAuditedWithoutLeakingUpstreamText() {
        doThrow(new RuntimeException("SENTINEL_SECRET")).when(store).add(anyList());
        assertThatThrownBy(() -> tool.save("fact")).hasMessage("Personal memory save failed").hasNoCause();
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("SENTINEL_SECRET"));
        assertThatThrownBy(() -> tool.search("query",3)).hasMessage("Personal memory search failed").hasNoCause();
        verify(audit).log(any(),eq("personal_memory.save"),isNull(),eq(0),any(),eq("error"));
        verify(audit).log(any(),eq("personal_memory.search"),isNull(),eq(0),any(),eq("error"));
    }
}
