package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.common.util.concurrent.Futures;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Points;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class JobMemoryMcpToolTest {
    VectorStore store = mock(VectorStore.class);
    QdrantClient client = mock(QdrantClient.class);
    ScannerPayloadRedactor redactor = mock(ScannerPayloadRedactor.class);
    McpAuditLogger audit = mock(McpAuditLogger.class);
    Map<String, Document> persisted = new HashMap<>();
    JobMemoryMcpTool tool = new JobMemoryMcpTool(store, client, redactor, audit);

    @BeforeEach void setup() {
        McpClientContextHolder.set(new McpClientContext("A", "codex", "local", List.of("memory.read", "memory.write")));
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> { for (Document d : (List<Document>) i.getArgument(0)) persisted.put(d.getId(), d); return null; })
                .when(store).add(anyList());
        when(client.retrieveAsync(anyString(), any(Points.PointId.class), eq(true), eq(false), isNull()))
                .thenAnswer(i -> {
                    var id = (Points.PointId) i.getArgument(1);
                    Document d = persisted.get(id.getUuid());
                    if (d == null) return Futures.immediateFuture(List.of());
                    var point = Points.RetrievedPoint.newBuilder().setId(id);
                    d.getMetadata().forEach((key, value) -> point.putPayload(key, ValueFactory.value((String) value)));
                    return Futures.immediateFuture(List.of(point.build()));
                });
    }

    @AfterEach void clear() { McpClientContextHolder.clear(); }

    @Test void separateJobsAndExactUpdateSurviveNewInstanceAcrossProjects() {
        var first = tool.save("Redis gecikmesi", "Tracking GET beklemesi", "ilk tam not", null);
        var second = tool.save("Store yayını", "Android mağaza", "başka iş", null);
        assertThat(first.jobId()).isNotEqualTo(second.jobId());
        assertThat(tool.get(UUID.randomUUID().toString()).found()).isFalse();
        tool.save("Redis gecikmesi", "Tracking GET beklemesi", "güncel tam not", first.jobId());
        McpClientContextHolder.set(new McpClientContext("B", "claude", "local", List.of("memory.read")));
        var reader = new JobMemoryMcpTool(store, client, redactor, audit);
        assertThat(reader.get(first.jobId()).content()).isEqualTo("güncel tam not");
        assertThat(reader.get(second.jobId()).content()).isEqualTo("başka iş");
        assertThat(persisted).hasSize(2);
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test void searchReturnsOnlyShortMetadataAndRedactsQuery() {
        var saved = tool.save("Tracking", "Redis yavaşlama araştırması", "FULL_PRIVATE_HANDOFF", null);
        Document doc = Document.builder().id(saved.jobId()).text("Tracking Redis")
                .metadata(persisted.get(saved.jobId()).getMetadata()).score(0.8).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(redactor.redact("secret clue")).thenReturn("safe clue");
        var matches = tool.search("secret clue", null);
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().jobId()).isEqualTo(saved.jobId());
        assertThat(matches.toString()).doesNotContain("FULL_PRIVATE_HANDOFF");
        var query = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(query.capture());
        assertThat(query.getValue().getQuery()).isEqualTo("safe clue");
        assertThat(query.getValue().getTopK()).isEqualTo(3);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(tool.search("unrelated", 3)).isEmpty();
    }

    @Test void redactedContentIsStoredAndOnlyTitleAndSummaryBecomeEmbeddingText() {
        when(redactor.redact("sensitive body")).thenReturn("clean body");
        var saved = tool.save("title", "summary", "sensitive body", null);
        assertThat(saved.redacted()).isTrue();
        assertThat(persisted.get(saved.jobId()).getText()).isEqualTo("title\nsummary");
        assertThat(tool.get(saved.jobId()).content()).isEqualTo("clean body");
    }

    @Test void invalidInputPreservesSnapshotAndUsesUtf8ByteLimits() {
        var saved = tool.save("title", "summary", "keep", null);
        for (String value : List.of(" ", "é".repeat(131073))) {
            assertThatThrownBy(() -> tool.save("title", "summary", value, saved.jobId()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tool.save("é".repeat(257), "s", "b", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.save("t", "é".repeat(4097), "b", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.get("1-1-1-1-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.save("t", "s", "b", "bad-id")).isInstanceOf(IllegalArgumentException.class);
        for (int k : List.of(0, 11)) assertThatThrownBy(() -> tool.search("query", k)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.search("é".repeat(4097), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tool.get(saved.jobId()).content()).isEqualTo("keep");
        tool.save("title", "summary", "é".repeat(131072), saved.jobId());
        assertThat(tool.get(saved.jobId()).content()).hasSize(131072);
        when(redactor.redact("redacted-away")).thenReturn("");
        assertThatThrownBy(() -> tool.save("title", "summary", "redacted-away", saved.jobId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deniesAndAuditsBearerAndMissingScopes() {
        for (var ctx : List.of(
                new McpClientContext("A", "remote", "token", List.of("memory.read", "memory.write")),
                new McpClientContext("A", "local", "local", List.of()))) {
            McpClientContextHolder.set(ctx);
            assertThatThrownBy(() -> tool.save("title", "summary", "body", null)).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.search("query", null)).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.get(UUID.randomUUID().toString())).isInstanceOf(McpAccessException.class);
            for (String name : List.of("save", "search", "get")) {
                verify(audit).log(eq(ctx), eq("job_memory." + name), isNull(), eq(0), any(Instant.class), eq("denied_scope"));
            }
        }
        assertThat(persisted).isEmpty();
        verify(store, never()).similaritySearch(any(SearchRequest.class));
        verify(client, never()).retrieveAsync(anyString(), any(Points.PointId.class), anyBoolean(), anyBoolean(), any());
    }

    @Test void dependencyFailuresAreAuditedAndNeverReportedAsSuccess() {
        doThrow(new RuntimeException("private dependency detail")).when(store).add(anyList());
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("private dependency detail"));
        when(client.retrieveAsync(anyString(), any(Points.PointId.class), eq(true), eq(false), isNull()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("private dependency detail")));
        assertThatThrownBy(() -> tool.save("t", "s", "c", null)).hasMessage("Job memory save failed");
        assertThatThrownBy(() -> tool.search("q", null)).hasMessage("Job memory search failed");
        assertThatThrownBy(() -> tool.get(UUID.randomUUID().toString())).hasMessage("Job memory read failed");
        for (String name : List.of("save", "search", "get")) {
            verify(audit).log(any(), eq("job_memory." + name), isNull(), eq(0), any(Instant.class), eq("error"));
        }
    }

    @Test void configurationAndAnnotationsExposeSeparateBoundedTools() throws Exception {
        var defaults = new JobMemoryProperties(null, null, null);
        assertThat(defaults.collectionName()).isEqualTo("job_memory_bge-m3_1024");
        assertThatThrownBy(() -> new JobMemoryProperties("", 1024, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobMemoryProperties(null, 0, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobMemoryProperties(null, 1024, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(java.util.Arrays.stream(JobMemoryMcpTool.class.getMethods())
                .map(m -> m.getAnnotation(org.springaicommunity.mcp.annotation.McpTool.class))
                .filter(java.util.Objects::nonNull).map(org.springaicommunity.mcp.annotation.McpTool::name))
                .containsExactlyInAnyOrder("job_memory.save", "job_memory.get", "job_memory.search");
    }
}
