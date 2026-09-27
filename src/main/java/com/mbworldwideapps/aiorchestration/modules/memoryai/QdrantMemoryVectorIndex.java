package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import io.qdrant.client.QdrantClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class QdrantMemoryVectorIndex implements MemoryVectorIndex {

    private final QdrantVectorStore activeStore;
    private final QdrantVectorStore shadowStore;
    private final MemoryRepository repository;
    private final MemoryEmbeddingContract embeddingContract;
    private final MemoryRetrievalTextBuilder textBuilder;
    private final MemoryShadowMode shadowMode;
    private final EmbeddingModel shadowEmbeddingModel;

    @Autowired
    public QdrantMemoryVectorIndex(QdrantClient qdrantClient, EmbeddingModel embeddingModel,
            @Qualifier("memoryShadowEmbeddingModel") ObjectProvider<EmbeddingModel> shadowEmbeddingModels,
            MemoryRepository repository, AiOrchestrationProperties properties,
            MemoryEmbeddingContract embeddingContract, MemoryRetrievalTextBuilder textBuilder) {
        this.repository = repository;
        this.embeddingContract = embeddingContract;
        this.textBuilder = textBuilder;
        this.shadowMode = embeddingContract.properties().shadowMode();
        this.activeStore = createStore(qdrantClient, embeddingModel,
                properties.memory().episodicCollectionName(), "active");
        if (shadowMode == MemoryShadowMode.OFF) {
            this.shadowStore = null;
            this.shadowEmbeddingModel = null;
        } else {
            if (properties.memory().episodicCollectionName()
                    .equals(embeddingContract.properties().shadowCollectionName())) {
                throw new IllegalArgumentException("shadow collection must differ from active collection");
            }
            EmbeddingModel suppliedShadow = shadowEmbeddingModels.getIfAvailable();
            if (suppliedShadow == null && embeddingContract.properties().shadowUsesDifferentModel()) {
                throw new IllegalStateException(
                        "memoryShadowEmbeddingModel bean is required for a different shadow embedding model");
            }
            this.shadowEmbeddingModel = suppliedShadow == null ? embeddingModel : suppliedShadow;
            embeddingContract.assertShadowCompatible(this.shadowEmbeddingModel);
            this.shadowStore = createStore(qdrantClient, this.shadowEmbeddingModel,
                    embeddingContract.properties().shadowCollectionName(), "shadow");
        }
    }

    QdrantMemoryVectorIndex(QdrantVectorStore vectorStore, MemoryRepository repository) {
        this.activeStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.shadowStore = null;
        this.repository = Objects.requireNonNull(repository, "repository");
        this.embeddingContract = null;
        this.textBuilder = new MemoryRetrievalTextBuilder();
        this.shadowMode = MemoryShadowMode.OFF;
        this.shadowEmbeddingModel = null;
    }

    QdrantMemoryVectorIndex(QdrantVectorStore activeStore, QdrantVectorStore shadowStore,
            MemoryRepository repository, MemoryEmbeddingContract embeddingContract,
            MemoryRetrievalTextBuilder textBuilder, MemoryShadowMode shadowMode,
            EmbeddingModel shadowEmbeddingModel) {
        this.activeStore = Objects.requireNonNull(activeStore, "activeStore");
        this.shadowStore = shadowStore;
        this.repository = Objects.requireNonNull(repository, "repository");
        this.embeddingContract = Objects.requireNonNull(embeddingContract, "embeddingContract");
        this.textBuilder = Objects.requireNonNull(textBuilder, "textBuilder");
        this.shadowMode = Objects.requireNonNull(shadowMode, "shadowMode");
        this.shadowEmbeddingModel = shadowEmbeddingModel;
        if (shadowMode != MemoryShadowMode.OFF && (shadowStore == null || shadowEmbeddingModel == null)) {
            throw new IllegalArgumentException("enabled shadow mode requires a store and embedding model");
        }
    }

    @Override
    public void upsert(MemoryItem item) {
        assertActiveCompatible();
        activeStore.add(List.of(document(item, activeSchemaVersion(), activeFingerprint())));
        if (shadowMode.writesShadow()) {
            assertShadowCompatible();
            shadowStore.add(List.of(document(item, shadowSchemaVersion(), shadowFingerprint())));
        }
    }

    @Override
    public void delete(MemoryItem item) {
        assertActiveCompatible();
        activeStore.delete(List.of(item.vectorId().toString()));
        if (shadowMode.writesShadow()) {
            assertShadowCompatible();
            shadowStore.delete(List.of(item.vectorId().toString()));
        }
    }

    @Override
    public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
        if (topK <= 0) {
            return List.of();
        }
        if (shadowMode == MemoryShadowMode.SHADOW_ONLY) {
            assertShadowCompatible();
            return searchStore(shadowStore, query, topK, filter, shadowFingerprintFilter());
        }
        assertActiveCompatible();
        List<ScoredMemoryRef> active = searchStore(activeStore, query, topK, filter, activeFingerprintFilter());
        if (shadowMode != MemoryShadowMode.DUAL_READ) {
            return active;
        }
        assertShadowCompatible();
        List<ScoredMemoryRef> shadow = searchStore(shadowStore, query, topK, filter, shadowFingerprintFilter());
        return MemoryRankFusion.fuse(List.of(active, shadow), topK);
    }

    private List<ScoredMemoryRef> searchStore(QdrantVectorStore vectorStore, String query, int topK,
            MemoryEligibilityFilter filter, String fingerprintFilter) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query == null ? "" : query)
                .topK(topK)
                .similarityThreshold(filter == null ? 0.0 : filter.minSimilarity());
        Filter.Expression eligibilityFilter = buildEligibilityFilter(filter, fingerprintFilter);
        if (eligibilityFilter != null) {
            requestBuilder.filterExpression(eligibilityFilter);
        }
        List<Document> raw = vectorStore.similaritySearch(requestBuilder.build());
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = raw.stream()
                .map(QdrantMemoryVectorIndex::memoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, MemoryItem> itemsById = new HashMap<>();
        for (MemoryItem item : repository.findAllByIds(ids)) {
            itemsById.put(item.id(), item);
        }
        List<ScoredMemoryRef> resolved = new ArrayList<>(raw.size());
        for (Document document : raw) {
            ScoredMemoryRef ref = toRef(document, filter, itemsById);
            if (ref != null) {
                resolved.add(ref);
            }
        }
        resolved.sort(Comparator.comparingDouble(ScoredMemoryRef::similarityScore).reversed());
        return resolved.size() > topK ? List.copyOf(resolved.subList(0, topK)) : List.copyOf(resolved);
    }

    private ScoredMemoryRef toRef(Document document, MemoryEligibilityFilter filter,
            Map<UUID, MemoryItem> itemsById) {
        MemoryItem item = itemsById.get(memoryId(document));
        if (item == null || (filter != null && !filter.accepts(item))) {
            return null;
        }
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new ScoredMemoryRef(item, score);
    }

    private static UUID memoryId(Document document) {
        Object idValue = document.getMetadata().get("memory_id");
        if (idValue == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(idValue));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Filter.Expression buildEligibilityFilter(MemoryEligibilityFilter filter,
            String embeddingFingerprint) {
        if (filter == null || filter.statuses() == null || filter.statuses().isEmpty()) {
            return null;
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Object[] values = filter.statuses().stream()
                .map(MemoryStatus::value)
                .toArray();
        FilterExpressionBuilder.Op scope = null;
        if (filter.projectKey() != null && !filter.projectKey().isBlank()) {
            scope = builder.and(builder.eq("scope", MemoryScope.PROJECT.value()),
                    builder.eq("project_key", filter.projectKey()));
        }
        if (filter.userProjectKey() != null && !filter.userProjectKey().isBlank()) {
            FilterExpressionBuilder.Op user = builder.and(builder.eq("scope", MemoryScope.USER.value()),
                    builder.eq("project_key", filter.userProjectKey()));
            scope = scope == null ? user : builder.or(scope, user);
        }
        if (filter.includeGlobal()) {
            // Spring AI's Qdrant converter does not support IS NULL. Scope is
            // sufficient for the vector prefilter; the hydrated item is still
            // checked by MemoryEligibilityFilter, including the null project key.
            FilterExpressionBuilder.Op global = builder.eq("scope", MemoryScope.GLOBAL.value());
            scope = scope == null ? global : builder.or(scope, global);
        }
        if (filter.includeEpisodic()) {
            FilterExpressionBuilder.Op episodic = builder.eq("scope", MemoryScope.EPISODIC.value());
            scope = scope == null ? episodic : builder.or(scope, episodic);
        }
        FilterExpressionBuilder.Op status = builder.in("status", values);
        if (filter.excludePromotedRuleOrigins()) {
            // Spring AI's Qdrant converter (including current main) supports
            // string/number equality operands, not booleans. Store this
            // projection marker as a keyword so the filter is converted and
            // executed by Qdrant before topK is applied.
            status = builder.and(status, builder.ne("rule_authority_linked", "true"));
        }
        // Do not wrap the OR subtree in group(): QdrantFilterExpressionConverter
        // 1.1.x only handles Group under NOT and otherwise emits an empty filter.
        FilterExpressionBuilder.Op combined = scope == null ? status : builder.and(status, scope);
        if (embeddingFingerprint != null && !embeddingFingerprint.isBlank()) {
            combined = builder.and(combined, builder.eq("embedding_fingerprint", embeddingFingerprint));
        }
        return combined.build();
    }

    private Document document(MemoryItem item, String schemaVersion, String fingerprint) {
        return new Document(item.vectorId().toString(), textBuilder.build(item, schemaVersion),
                metadata(item, schemaVersion, fingerprint));
    }

    private static Map<String, Object> metadata(MemoryItem item, String schemaVersion, String fingerprint) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memory_id", item.id().toString());
        metadata.put("vector_id", item.vectorId().toString());
        metadata.put("scope", item.scope().value());
        if (item.projectKey() != null) {
            metadata.put("project_key", item.projectKey());
        }
        metadata.put("memory_type", item.memoryType().value());
        metadata.put("status", item.status().value());
        metadata.put("rule_authority_linked", Boolean.toString(item.memoryType() == MemoryType.RULE
                && item.metadata().containsKey("promotedRuleId")));
        metadata.put("confidence", item.confidence());
        metadata.put("source_type", item.sourceType().value());
        if (item.lastVerifiedAt() != null) {
            metadata.put("last_verified_at", item.lastVerifiedAt().toString());
        }
        if (fingerprint != null && !fingerprint.isBlank()) {
            metadata.put("embedding_fingerprint", fingerprint);
            metadata.put("retrieval_text_schema", schemaVersion);
        }
        metadata.put("indexed_at", Instant.now().toString());
        return metadata;
    }

    private static QdrantVectorStore createStore(QdrantClient qdrantClient, EmbeddingModel embeddingModel,
            String collectionName, String lane) {
        QdrantVectorStore store = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
        try {
            store.afterPropertiesSet();
            return store;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize " + lane + " memory vector collection", e);
        }
    }

    private void assertActiveCompatible() {
        if (embeddingContract != null) {
            embeddingContract.assertActiveCompatible();
        }
    }

    private void assertShadowCompatible() {
        embeddingContract.assertShadowCompatible(shadowEmbeddingModel);
    }

    private String activeSchemaVersion() {
        return embeddingContract == null ? "legacy-v1"
                : embeddingContract.activeFingerprint().retrievalTextSchemaVersion();
    }

    private String shadowSchemaVersion() {
        return embeddingContract.shadowFingerprint(shadowEmbeddingModel).retrievalTextSchemaVersion();
    }

    private String activeFingerprint() {
        return embeddingContract == null ? "" : embeddingContract.activeFingerprint().value();
    }

    private String shadowFingerprint() {
        return embeddingContract.shadowFingerprint(shadowEmbeddingModel).value();
    }

    private String activeFingerprintFilter() {
        return embeddingContract != null && embeddingContract.activeFingerprintVerified()
                ? activeFingerprint()
                : "";
    }

    private String shadowFingerprintFilter() {
        return shadowFingerprint();
    }
}
