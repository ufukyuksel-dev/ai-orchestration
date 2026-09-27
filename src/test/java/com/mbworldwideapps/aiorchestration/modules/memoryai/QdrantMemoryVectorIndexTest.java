package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;

class QdrantMemoryVectorIndexTest {

    @Test
    void usesOneOversampleAndOneBatchHydrationQuery() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryItem first = item("PROJECT_A", "first");
        MemoryItem second = item("PROJECT_A", "second");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(first, 0.82), document(second, 0.71)));
        when(repository.findAllByIds(any())).thenReturn(List.of(first, second));
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(vectorStore, repository);

        List<ScoredMemoryRef> results = index.search("memory cost", 6,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null, 0.20));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(6);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.20);
        assertThat(request.getValue().hasFilterExpression()).isTrue();
        String qdrantFilter = convertWithSpringAiQdrantConverter(request.getValue().getFilterExpression())
                .toString();
        assertThat(qdrantFilter)
                .contains("status", "active", "stale")
                .contains("scope", "project", "global", "episodic")
                .contains("project_key", "PROJECT_A");
        assertThat(qdrantFilter).doesNotContain("rule_authority_linked");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findAllByIds(ids.capture());
        assertThat(ids.getValue()).containsExactly(first.id(), second.id());
        verify(repository, never()).findById(any());
        assertThat(results).extracting(ref -> ref.item().id()).containsExactly(first.id(), second.id());
    }

    @Test
    void promotedOriginPayloadAndAutomaticFilterExcludeAuthorityRowsBeforeTopK() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(vectorStore, repository);
        MemoryItem promoted = item("PROJECT_A", "promoted", Map.of("promotedRuleId", UUID.randomUUID().toString()));

        index.upsert(promoted);
        index.search("controller", 5,
                MemoryEligibilityFilter.forAutomaticInjection("PROJECT_A", null, 0.2, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document ->
                assertThat(document.getMetadata()).containsEntry("rule_authority_linked", "true"));
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        String qdrantFilter = convertWithSpringAiQdrantConverter(request.getValue().getFilterExpression())
                .toString();
        assertThat(qdrantFilter).contains("rule_authority_linked");
    }

    @Test
    void verifiedWritesCarryFingerprintAndSchemaMetadata() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        EmbeddingModel model = new HashingEmbeddingModel(64);
        MemoryEmbeddingProperties unverified = MemoryEmbeddingContractTest.properties(
                "", "legacy-v1", MemoryShadowMode.OFF, "");
        String expected = MemoryEmbeddingFingerprint.active(model, unverified).value();
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model,
                MemoryEmbeddingContractTest.properties(expected, "legacy-v1", MemoryShadowMode.OFF, ""));
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(vectorStore, null, repository, contract,
                new MemoryRetrievalTextBuilder(), MemoryShadowMode.OFF, null);
        MemoryItem item = item("PROJECT_A", "fingerprinted");

        index.upsert(item);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document -> assertThat(document.getMetadata())
                .containsEntry("embedding_fingerprint", expected)
                .containsEntry("retrieval_text_schema", "legacy-v1"));
    }

    @Test
    void mismatchFailsBeforeVectorReadOrWrite() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        EmbeddingModel model = new HashingEmbeddingModel(64);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model,
                MemoryEmbeddingContractTest.properties("wrong", "legacy-v1", MemoryShadowMode.OFF, ""));
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(vectorStore, null, repository, contract,
                new MemoryRetrievalTextBuilder(), MemoryShadowMode.OFF, null);

        assertThatThrownBy(() -> index.upsert(item("PROJECT_A", "blocked")))
                .isInstanceOf(MemoryEmbeddingMismatchException.class);
        assertThatThrownBy(() -> index.search("blocked", 3,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null)))
                .isInstanceOf(MemoryEmbeddingMismatchException.class);
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void dualReadWritesBothLanesAndFusesWithoutDuplicateMemoryIds() {
        QdrantVectorStore activeStore = mock(QdrantVectorStore.class);
        QdrantVectorStore shadowStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        EmbeddingModel model = new HashingEmbeddingModel(64);
        String activeExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "legacy-v1").value();
        String shadowExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "discovery-v2").value();
        MemoryEmbeddingProperties properties = MemoryEmbeddingContractTest.properties(activeExpected,
                "legacy-v1", MemoryShadowMode.DUAL_READ, shadowExpected);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model, properties);
        MemoryItem shared = item("PROJECT_A", "shared");
        MemoryItem activeOnly = item("PROJECT_A", "active-only");
        MemoryItem shadowOnly = item("PROJECT_A", "shadow-only");
        when(activeStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(activeOnly, 0.95), document(shared, 0.80)));
        when(shadowStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(shared, 0.93), document(shadowOnly, 0.72)));
        when(repository.findAllByIds(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<UUID> ids = invocation.getArgument(0);
            return List.of(shared, activeOnly, shadowOnly).stream().filter(item -> ids.contains(item.id())).toList();
        });
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(activeStore, shadowStore, repository, contract,
                new MemoryRetrievalTextBuilder(), MemoryShadowMode.DUAL_READ, model);

        index.upsert(shared);
        List<ScoredMemoryRef> results = index.search("shared", 5,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null));

        verify(activeStore).add(any());
        verify(shadowStore).add(any());
        assertThat(results).extracting(ref -> ref.item().id())
                .containsExactly(shared.id(), activeOnly.id(), shadowOnly.id())
                .doesNotHaveDuplicates();
        verify(repository, times(2)).findAllByIds(any());
    }

    @Test
    void shadowOnlyReadsShadowWithoutFallingBackToActive() {
        QdrantVectorStore activeStore = mock(QdrantVectorStore.class);
        QdrantVectorStore shadowStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        EmbeddingModel model = new HashingEmbeddingModel(64);
        String activeExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "legacy-v1").value();
        String shadowExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "discovery-v2").value();
        MemoryEmbeddingProperties properties = MemoryEmbeddingContractTest.properties(activeExpected,
                "legacy-v1", MemoryShadowMode.SHADOW_ONLY, shadowExpected);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model, properties);
        MemoryItem shadowItem = item("PROJECT_A", "shadow-authoritative");
        when(shadowStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(document(shadowItem, 0.91)));
        when(repository.findAllByIds(any())).thenReturn(List.of(shadowItem));
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(activeStore, shadowStore, repository, contract,
                new MemoryRetrievalTextBuilder(), MemoryShadowMode.SHADOW_ONLY, model);

        List<ScoredMemoryRef> results = index.search("shadow", 3,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null));

        assertThat(results).extracting(ref -> ref.item().id()).containsExactly(shadowItem.id());
        verify(shadowStore).similaritySearch(any(SearchRequest.class));
        verify(activeStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void rollbackToActiveModeDoesNotDeleteEitherCollection() {
        QdrantVectorStore activeStore = mock(QdrantVectorStore.class);
        QdrantVectorStore shadowStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryItem activeItem = item("PROJECT_A", "active");
        when(activeStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document(activeItem, 0.9)));
        when(repository.findAllByIds(any())).thenReturn(List.of(activeItem));

        QdrantMemoryVectorIndex rolledBack = new QdrantMemoryVectorIndex(activeStore, repository);
        List<ScoredMemoryRef> results = rolledBack.search("active", 3,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null));

        assertThat(results).extracting(ref -> ref.item().id()).containsExactly(activeItem.id());
        verify(activeStore).similaritySearch(any(SearchRequest.class));
        verify(shadowStore, never()).similaritySearch(any(SearchRequest.class));
        verify(activeStore, never()).delete(org.mockito.ArgumentMatchers.<List<String>>any());
        verify(shadowStore, never()).delete(org.mockito.ArgumentMatchers.<List<String>>any());
    }

    @Test
    void sqlHydrationRejectsForeignArchivedAndExpiredStalePayloads() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryItem eligible = item("PROJECT_A", "eligible");
        MemoryItem foreign = item("PROJECT_B", "foreign");
        MemoryItem archived = item("PROJECT_A", "archived", MemoryStatus.ARCHIVED, null);
        MemoryItem expired = item("PROJECT_A", "expired", MemoryStatus.ACTIVE, Instant.now().minusSeconds(1));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document(foreign, 0.99), document(archived, 0.98), document(expired, 0.97),
                document(eligible, 0.80)));
        when(repository.findAllByIds(any())).thenReturn(List.of(foreign, archived, expired, eligible));
        QdrantMemoryVectorIndex index = new QdrantMemoryVectorIndex(vectorStore, repository);

        List<ScoredMemoryRef> results = index.search("eligible", 10,
                MemoryEligibilityFilter.forRetrieve("PROJECT_A", null));

        assertThat(results).extracting(ref -> ref.item().id()).containsExactly(eligible.id());
    }

    private static io.qdrant.client.grpc.Points.Filter convertWithSpringAiQdrantConverter(
            Filter.Expression expression) {
        try {
            Class<?> converterType = Class.forName(
                    "org.springframework.ai.vectorstore.qdrant.QdrantFilterExpressionConverter");
            var constructor = converterType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object converter = constructor.newInstance();
            var convert = converterType.getDeclaredMethod("convertExpression", Filter.Expression.class);
            convert.setAccessible(true);
            return (io.qdrant.client.grpc.Points.Filter) convert.invoke(converter, expression);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke Spring AI's Qdrant filter converter", e);
        }
    }

    private static Document document(MemoryItem item, double score) {
        return Document.builder()
                .id(item.vectorId().toString())
                .text(item.text())
                .metadata("memory_id", item.id().toString())
                .score(score)
                .build();
    }

    private static MemoryItem item(String projectKey, String text) {
        return item(projectKey, text, Map.of());
    }

    private static MemoryItem item(String projectKey, String text, Map<String, Object> metadata) {
        return item(projectKey, text, MemoryStatus.ACTIVE, null, metadata);
    }

    private static MemoryItem item(String projectKey, String text, MemoryStatus status, Instant expiresAt) {
        return item(projectKey, text, status, expiresAt, Map.of());
    }

    private static MemoryItem item(String projectKey, String text, MemoryStatus status, Instant expiresAt,
            Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, projectKey, MemoryType.RULE,
                text, text, List.of(), 0.9, status, MemorySourceType.MANUAL,
                "test:" + id, "test", metadata, Instant.now(), Instant.now(), null, Instant.now(), expiresAt);
    }
}
